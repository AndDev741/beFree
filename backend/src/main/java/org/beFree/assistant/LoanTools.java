package org.beFree.assistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beFree.loan.Loan;
import org.beFree.loan.LoanRepayment;
import org.beFree.loan.LoanService;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Money the user lent out and what came back. Same contract as
 * {@link FinanceTools}: plain text back, errors as text the model can act on,
 * every tool transactional.
 */
@ApplicationScoped
public class LoanTools {

    private static final Logger LOG = Logger.getLogger(LoanTools.class);

    @Inject
    LoanService loans;

    @Tool("Start tracking money the user lent to someone. When the money left through a recorded expense, pass that expense's id so the loan points at it")
    @Transactional
    public String recordLoan(@P("Who borrowed it, as the user calls them, e.g. 'Pai' or 'Rafaela'") String person,
                             @P("Amount lent, positive") BigDecimal amount,
                             @P("Currency code: EUR, or BRL when the user says reais; null for EUR") String currency,
                             @P("Date lent as yyyy-MM-dd, or null for today") String date,
                             @P("What it was for, or null") String description,
                             @P("Id of the expense transaction that paid it out, or null") Long transactionId) {
        LOG.infof("tool recordLoan(%s, %s %s, %s, tx=%s)", person, amount, currency, date, transactionId);
        try {
            Loan loan = loans.lend(person, amount, currency, parseDate(date), description, transactionId);
            return "Loan #%d: %s %s lent to %s on %s%s.".formatted(
                    loan.id, loan.amount, loan.currency, loan.person, loan.lentOn,
                    loan.transaction != null ? ", paid out by transaction #" + loan.transaction.id : "");
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("Record money someone paid back on a loan. Call listLoans first to get the loan id. The currency may differ from the loan's (a euro loan repaid in reais); such money is listed beside the loan, not converted")
    @Transactional
    public String recordLoanRepayment(@P("Loan id from listLoans") long loanId,
                                      @P("Amount paid back, positive") BigDecimal amount,
                                      @P("Currency of the money that came back; null for the loan's own currency") String currency,
                                      @P("Date as yyyy-MM-dd, or null for today") String date,
                                      @P("Short note, or null") String note) {
        LOG.infof("tool recordLoanRepayment(#%d, %s %s, %s)", loanId, amount, currency, date);
        Loan loan = Loan.findById(loanId);
        if (loan == null) {
            return "ERROR: loan #" + loanId + " not found. Call listLoans to see the open loans.";
        }
        try {
            LoanRepayment r = loans.repay(loan, amount, currency, parseDate(date), note);
            return "Recorded %s %s back from %s on %s. %s".formatted(
                    r.amount, r.currency, loan.person, r.occurredOn, describe(loans.progress(loan)));
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("List loans still owed to the user, with what each person still owes. Pass includeClosed to also list loans that were paid back or closed")
    @Transactional
    public String listLoans(@P("true to also list paid back and closed loans; otherwise null") Boolean includeClosed) {
        LOG.infof("tool listLoans(includeClosed=%s)", includeClosed);
        List<LoanService.Progress> open = loans.open();
        List<LoanService.Progress> finished = Boolean.TRUE.equals(includeClosed) ? loans.finished() : List.of();
        if (open.isEmpty() && finished.isEmpty()) {
            return "Nobody owes the user anything. Use recordLoan when they lend money.";
        }
        StringBuilder out = new StringBuilder();
        if (open.isEmpty()) {
            out.append("No open loans.\n");
        } else {
            out.append("Open loans:\n");
            Map<String, BigDecimal> owed = new TreeMap<>();
            for (LoanService.Progress p : open) {
                out.append("- #").append(p.loan().id).append(' ').append(describe(p)).append('\n');
                owed.merge(p.loan().currency, p.outstanding(), BigDecimal::add);
            }
            out.append("Still owed in total: ");
            owed.forEach((cur, sum) -> out.append(sum).append(' ').append(cur).append("; "));
            out.setLength(out.length() - 2);
            out.append('\n');
        }
        if (!finished.isEmpty()) {
            out.append("Paid back or closed:\n");
            for (LoanService.Progress p : finished) {
                out.append("- #").append(p.loan().id).append(' ').append(describe(p)).append('\n');
            }
        }
        return out.toString().trim();
    }

    @Tool("Close a loan by hand: when it was paid back in another currency and the user says the conversion adds up, when they forgive it, or when it was settled some other way. The note says how")
    @Transactional
    public String closeLoan(@P("Loan id from listLoans") long loanId,
                            @P("How it was settled, or null") String note) {
        LOG.infof("tool closeLoan(#%d)", loanId);
        Loan loan = Loan.findById(loanId);
        if (loan == null) {
            return "ERROR: loan #" + loanId + " not found. Call listLoans to see the loans.";
        }
        loans.close(loan, note);
        return "Closed the loan to %s on %s%s.".formatted(
                loan.person, loan.closedOn, loan.closingNote != null ? " (" + loan.closingNote + ")" : "");
    }

    @Tool("Reopen a loan that was closed by mistake, so it counts as owed again")
    @Transactional
    public String reopenLoan(@P("Loan id from listLoans with includeClosed") long loanId) {
        LOG.infof("tool reopenLoan(#%d)", loanId);
        Loan loan = Loan.findById(loanId);
        if (loan == null) {
            return "ERROR: loan #" + loanId + " not found.";
        }
        if (loan.closedOn == null) {
            return "The loan to %s is not closed. %s".formatted(loan.person, describe(loans.progress(loan)));
        }
        loans.reopen(loan);
        return "Reopened. " + describe(loans.progress(loan));
    }

    @Tool("Delete a loan and its repayments, only when the user asks to delete one recorded by mistake. To finish a real loan use closeLoan")
    @Transactional
    public String deleteLoan(@P("Loan id from listLoans") long loanId) {
        LOG.infof("tool deleteLoan(#%d)", loanId);
        Loan loan = Loan.findById(loanId);
        if (loan == null) {
            return "ERROR: loan #" + loanId + " not found.";
        }
        String summary = "%s %s to %s on %s".formatted(loan.amount, loan.currency, loan.person, loan.lentOn);
        loans.delete(loan);
        return "Deleted the loan of " + summary + " and its repayments. Any expense it pointed at stays in the ledger.";
    }

    private static String describe(LoanService.Progress p) {
        Loan l = p.loan();
        StringBuilder s = new StringBuilder("%s: %s %s lent on %s".formatted(l.person, l.amount, l.currency, l.lentOn));
        if (l.description != null) {
            s.append(" (").append(l.description).append(')');
        }
        s.append(", %s paid back".formatted(p.repaid()));
        p.otherCurrencies().forEach((cur, sum) -> s.append(" plus %s %s".formatted(sum, cur)));
        switch (p.status()) {
            case OPEN -> s.append(", still owes %s %s".formatted(p.outstanding(), l.currency));
            case SETTLED -> s.append(", fully paid back on ").append(p.settledOn());
            case CLOSED -> {
                s.append(", closed on ").append(l.closedOn);
                if (l.closingNote != null) {
                    s.append(" (").append(l.closingNote).append(')');
                }
            }
        }
        return s.append('.').toString();
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be yyyy-MM-dd");
        }
    }
}
