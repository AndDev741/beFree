package org.beFree.loan;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beFree.auth.CurrentUser;
import org.beFree.category.Category;
import org.beFree.transaction.Transaction;
import org.beFree.transaction.TransactionType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Loans and what came back for them. Every write the API and the assistant
 * make goes through here, so both refuse the same things with the same words.
 * Refusals are IllegalArgumentException; callers turn them into a 400 or into
 * text the model can act on.
 */
@ApplicationScoped
public class LoanService {

    /** A personal loan of a million is a typo, in any currency. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    @Inject
    CurrentUser currentUser;

    @ConfigProperty(name = "befree.zone", defaultValue = "Europe/Lisbon")
    String zone;

    public enum Status {
        /** Still owed. */
        OPEN,
        /** Paid back in full, in the loan's own currency. */
        SETTLED,
        /** Called done by hand, with or without the numbers adding up. */
        CLOSED
    }

    /**
     * A loan with its repayments, oldest first.
     *
     * @param repaid          what came back in the loan's own currency
     * @param otherCurrencies what came back in any other currency, per currency, never converted
     * @param settledOn       the day the own-currency repayments first covered the amount
     */
    public record Progress(Loan loan, List<LoanRepayment> repayments, BigDecimal repaid,
                           Map<String, BigDecimal> otherCurrencies, LocalDate settledOn) {

        public BigDecimal outstanding() {
            return loan.amount.subtract(repaid).max(BigDecimal.ZERO);
        }

        public Status status() {
            if (loan.closedOn != null) {
                return Status.CLOSED;
            }
            return settledOn != null ? Status.SETTLED : Status.OPEN;
        }

        public boolean open() {
            return status() == Status.OPEN;
        }

        public Integer percent() {
            if (loan.amount.signum() <= 0) {
                return null;
            }
            return repaid.multiply(BigDecimal.valueOf(100))
                    .divide(loan.amount, 0, RoundingMode.HALF_UP)
                    .min(BigDecimal.valueOf(100))
                    .intValue();
        }
    }

    public LocalDate today() {
        return LocalDate.now(ZoneId.of(zone));
    }

    /** Three letters, upper case, EUR when left out. */
    public static String currency(String raw) {
        if (raw == null || raw.isBlank()) {
            return "EUR";
        }
        String clean = raw.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(clean).matches()) {
            throw new IllegalArgumentException("currency must be a three-letter code such as EUR or BRL");
        }
        return clean;
    }

    public static BigDecimal amount(BigDecimal raw) {
        if (raw == null || raw.signum() <= 0 || raw.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("amount must be a positive, plausible value");
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    public static String person(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("person is required");
        }
        String clean = raw.trim();
        if (clean.length() > 255) {
            throw new IllegalArgumentException("person is too long");
        }
        return clean;
    }

    /** Blank means none. */
    public static String text(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.trim();
        return clean.length() > 255 ? clean.substring(0, 255) : clean;
    }

    @Transactional
    public Loan lend(String person, BigDecimal amount, String currency, LocalDate lentOn,
                     String description, Long transactionId) {
        Loan loan = new Loan();
        loan.person = person(person);
        loan.amount = amount(amount);
        loan.currency = currency(currency);
        loan.lentOn = lentOn != null ? lentOn : today();
        loan.description = text(description);
        loan.transaction = transactionId == null ? null : linkable(transactionId, null);
        loan.owner = currentUser.name();
        loan.persist();
        return loan;
    }

    /**
     * The expense a loan points at must be money going out, and only one loan
     * can claim it, or the same euros would be owed twice.
     */
    public Transaction linkable(long transactionId, Loan self) {
        Transaction t = Transaction.findById(transactionId);
        if (t == null) {
            throw new IllegalArgumentException("unknown transaction: " + transactionId);
        }
        if (t.type != TransactionType.EXPENSE) {
            throw new IllegalArgumentException("a loan goes out as an expense; #" + transactionId + " is income");
        }
        Loan other = Loan.find("transaction", t).firstResult();
        if (other != null && (self == null || !other.id.equals(self.id))) {
            throw new IllegalArgumentException(
                    "#%d already belongs to the loan to %s".formatted(transactionId, other.person));
        }
        return t;
    }

    /**
     * Money back. A repayment in the loan's own currency may not exceed what
     * is still owed: more than that is a typo, or a different loan. Any other
     * currency is taken as given, since only the user knows the rate.
     */
    @Transactional
    public LoanRepayment repay(Loan loan, BigDecimal amount, String currency, LocalDate on, String note) {
        if (loan.closedOn != null) {
            throw new IllegalArgumentException(
                    "the loan to %s is closed; reopen it first to record money for it".formatted(loan.person));
        }
        BigDecimal value = amount(amount);
        String cur = currency == null || currency.isBlank() ? loan.currency : currency(currency);
        if (cur.equals(loan.currency)) {
            BigDecimal owed = progress(loan).outstanding();
            if (value.compareTo(owed) > 0) {
                throw new IllegalArgumentException(
                        "%s owes %s %s on this loan, which is less than %s"
                                .formatted(loan.person, owed, loan.currency, value));
            }
        }
        LoanRepayment r = new LoanRepayment();
        r.loan = loan;
        r.amount = value;
        r.currency = cur;
        r.occurredOn = on != null ? on : today();
        r.note = text(note);
        r.owner = currentUser.name();
        r.persist();
        r.flush();
        return r;
    }

    /** Closing twice keeps the first day, and the newer note when one is given. */
    @Transactional
    public void close(Loan loan, String note) {
        if (loan.closedOn == null) {
            loan.closedOn = today();
        }
        String clean = text(note);
        if (clean != null) {
            loan.closingNote = clean;
        }
    }

    /** Back to being owed. The closing note goes with the closing. */
    @Transactional
    public void reopen(Loan loan) {
        loan.closedOn = null;
        loan.closingNote = null;
    }

    @Transactional
    public void delete(Loan loan) {
        LoanRepayment.delete("loan", loan);
        loan.delete();
    }

    @Transactional
    public Progress progress(Loan loan) {
        return progressOf(List.of(loan)).getFirst();
    }

    /** Everything, open or not, in one read. */
    @Transactional
    public List<Progress> all() {
        return progressOf(Loan.listAll(Sort.by("lentOn").and("id")));
    }

    /** Still owed, the longest-standing first. */
    @Transactional
    public List<Progress> open() {
        return all().stream().filter(Progress::open).toList();
    }

    /** Paid back or closed by hand, the most recently finished first. */
    @Transactional
    public List<Progress> finished() {
        return all().stream()
                .filter(p -> !p.open())
                .sorted(Comparator.comparing(LoanService::finishedOn).reversed())
                .toList();
    }

    private static LocalDate finishedOn(Progress p) {
        return p.loan().closedOn != null ? p.loan().closedOn : p.settledOn();
    }

    private List<Progress> progressOf(List<Loan> loans) {
        if (loans.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LoanRepayment>> byLoan = new HashMap<>();
        List<LoanRepayment> rows = LoanRepayment.list("loan in ?1", Sort.by("occurredOn").and("id"), loans);
        for (LoanRepayment r : rows) {
            byLoan.computeIfAbsent(r.loan.id, k -> new ArrayList<>()).add(r);
        }
        List<Progress> out = new ArrayList<>(loans.size());
        for (Loan loan : loans) {
            List<LoanRepayment> repayments = byLoan.getOrDefault(loan.id, List.of());
            // Cents from the start, so nothing paid back reads 0.00 like every other amount
            BigDecimal repaid = BigDecimal.ZERO.setScale(2);
            LocalDate settledOn = null;
            Map<String, BigDecimal> other = new TreeMap<>();
            for (LoanRepayment r : repayments) {
                if (r.currency.equals(loan.currency)) {
                    repaid = repaid.add(r.amount);
                    if (settledOn == null && repaid.compareTo(loan.amount) >= 0) {
                        settledOn = r.occurredOn;
                    }
                } else {
                    other.merge(r.currency, r.amount, BigDecimal::add);
                }
            }
            out.add(new Progress(loan, repayments, repaid, other, settledOn));
        }
        return out;
    }

    /**
     * Expenses filed under a loan category ("Emprestimo", "Empréstimos") that
     * no loan points at yet: money that went out to someone and is not being
     * tracked. Newest first.
     */
    @Transactional
    public List<Transaction> untracked() {
        List<Category> loanCategories = Category.<Category>listAll().stream()
                .filter(c -> isLoanCategory(c.name))
                .toList();
        if (loanCategories.isEmpty()) {
            return List.of();
        }
        return Transaction.list(
                "type = ?1 and category in ?2 and id not in (select l.transaction.id from Loan l where l.transaction is not null)",
                Sort.descending("occurredOn").and("id", Sort.Direction.Descending),
                TransactionType.EXPENSE, loanCategories);
    }

    static boolean isLoanCategory(String name) {
        return name != null && name.trim().toLowerCase(Locale.ROOT).replace('é', 'e').startsWith("emprest");
    }
}
