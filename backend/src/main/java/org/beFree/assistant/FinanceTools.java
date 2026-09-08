package org.beFree.assistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beFree.category.Category;
import org.beFree.transaction.NewTransaction;
import org.beFree.transaction.Source;
import org.beFree.transaction.Transaction;
import org.beFree.transaction.TransactionService;
import org.beFree.transaction.TransactionType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the assistant can do. Every method returns plain text the model reads
 * back; errors are returned as text too so the model can recover or ask.
 */
@ApplicationScoped
public class FinanceTools {

    private static final Logger LOG = Logger.getLogger(FinanceTools.class);
    /** Sanity ceiling: a personal ledger has no single 1,000,000 EUR line; anything above is a mis-parse. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    @Inject
    TransactionService transactions;

    @Inject
    ConversationContext context;

    @ConfigProperty(name = "befree.zone", defaultValue = "Europe/Lisbon")
    String zone;

    @Tool("Record one expense or income. Call it once per item: '50 lidl e 12 gasolina' means two calls.")
    @Transactional
    public String recordTransaction(
            @P("Positive amount in EUR, e.g. 12.50") BigDecimal amount,
            @P("EXPENSE or INCOME") TransactionType type,
            @P("Short description; null if the user gave none") String description,
            @P("Name of an existing category, or null to leave uncategorised") String categoryName,
            @P("Date as yyyy-MM-dd, or null for today") String date) {

        LOG.infof("tool recordTransaction(amount=%s, type=%s, description=%s, category=%s, date=%s)",
                amount, type, description, categoryName, date);
        if (amount == null || amount.signum() <= 0) {
            return "ERROR: amount must be a positive number";
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            return "ERROR: amount " + amount + " is implausibly large for one transaction; re-read the message and ask the user if unsure";
        }
        if (amount.scale() > 2) {
            amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        }

        Long categoryId = null;
        if (categoryName != null && !categoryName.isBlank()) {
            var category = Category.findByName(categoryName.trim());
            if (category.isEmpty()) {
                return "ERROR: category '" + categoryName + "' does not exist. Call listCategories, or createCategory if the user wants a new one.";
            }
            categoryId = category.get().id;
        }

        LocalDate occurredOn;
        try {
            occurredOn = (date == null || date.isBlank()) ? LocalDate.now(ZoneId.of(zone)) : LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return "ERROR: date must be yyyy-MM-dd";
        }

        var scope = context.current();
        Transaction t;
        try {
            t = transactions.record(new NewTransaction(
                    amount, type, null, occurredOn, description, categoryId,
                    scope.map(ConversationContext.Scope::source).orElse(Source.MANUAL),
                    context.nextExternalId(),
                    scope.map(ConversationContext.Scope::rawInput).orElse(null)));
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            // Never let a raw persistence error reach the model; it can only act on text
            LOG.warnf(e, "recordTransaction failed");
            return "ERROR: could not save the transaction (" + e.getClass().getSimpleName() + ")";
        }
        LOG.infof("Recorded transaction #%d via assistant (%s %s %s)", t.id, t.type, t.amount, t.currency);
        return "Recorded #%d: %s %s %s on %s%s".formatted(
                t.id, t.type, t.amount, t.currency, t.occurredOn,
                t.category != null ? " in category " + t.category.name : ", uncategorised");
    }

    @Tool("List every existing category name")
    public String listCategories() {
        LOG.info("tool listCategories()");
        List<Category> all = Category.listAll(Sort.by("name"));
        if (all.isEmpty()) {
            return "No categories exist yet.";
        }
        return String.join(", ", all.stream().map(c -> c.name).toList());
    }

    @Tool("Create a new category")
    @Transactional
    public String createCategory(@P("Category name") String name) {
        LOG.infof("tool createCategory(%s)", name);
        if (name == null || name.isBlank()) {
            return "ERROR: name is required";
        }
        String clean = name.trim();
        if (Category.findByName(clean).isPresent()) {
            return "Category '" + clean + "' already exists.";
        }
        Category c = new Category();
        c.name = clean;
        c.persist();
        return "Created category '" + clean + "'.";
    }

    @Tool("Monthly summary: expenses by category, total income, total expenses and balance")
    public String monthlySummary(@P("Month as yyyy-MM, or null for the current month") String month) {
        LOG.infof("tool monthlySummary(%s)", month);
        YearMonth ym;
        try {
            ym = (month == null || month.isBlank()) ? YearMonth.now(ZoneId.of(zone)) : YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            return "ERROR: month must be yyyy-MM";
        }
        List<Object[]> rows = Transaction.getEntityManager().createQuery(
                        "select c.name, t.type, sum(t.amount) from Transaction t left join t.category c "
                                + "where t.occurredOn >= :from and t.occurredOn < :to group by c.name, t.type",
                        Object[].class)
                .setParameter("from", ym.atDay(1))
                .setParameter("to", ym.plusMonths(1).atDay(1))
                .getResultList();
        if (rows.isEmpty()) {
            return "No transactions in " + ym + ".";
        }

        Map<String, BigDecimal> expenses = new LinkedHashMap<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;
        for (Object[] r : rows) {
            String category = r[0] == null ? "(uncategorised)" : (String) r[0];
            BigDecimal sum = (BigDecimal) r[2];
            if (r[1] == TransactionType.INCOME) {
                income = income.add(sum);
            } else {
                spent = spent.add(sum);
                expenses.merge(category, sum, BigDecimal::add);
            }
        }
        StringBuilder out = new StringBuilder("Summary for ").append(ym).append(" (EUR):\n");
        expenses.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> out.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
        out.append("Total expenses: ").append(spent).append('\n');
        out.append("Total income: ").append(income).append('\n');
        out.append("Balance: ").append(income.subtract(spent));
        return out.toString();
    }

    @Tool("List a month's transactions, newest first")
    public String listTransactions(
            @P("Month as yyyy-MM, or null for the current month") String month,
            @P("Maximum number of rows, or null for 10") Integer limit) {
        LOG.infof("tool listTransactions(%s, %s)", month, limit);
        YearMonth ym;
        try {
            ym = (month == null || month.isBlank()) ? YearMonth.now(ZoneId.of(zone)) : YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            return "ERROR: month must be yyyy-MM";
        }
        int max = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);
        List<Transaction> list = Transaction.inMonth(ym);
        if (list.isEmpty()) {
            return "No transactions in " + ym + ".";
        }
        StringBuilder out = new StringBuilder();
        list.stream().limit(max).forEach(t -> out.append("#%d %s %s %s %s%s%n".formatted(
                t.id, t.occurredOn, t.type, t.amount,
                t.description != null ? t.description : "(no description)",
                t.category != null ? " [" + t.category.name + "]" : "")));
        return out.toString().trim();
    }

    @Tool("Change the category of an existing transaction")
    @Transactional
    public String setCategory(@P("Transaction id") long transactionId, @P("Existing category name") String categoryName) {
        LOG.infof("tool setCategory(#%d, %s)", transactionId, categoryName);
        Transaction t = Transaction.findById(transactionId);
        if (t == null) {
            return "ERROR: transaction #" + transactionId + " not found";
        }
        var category = Category.findByName(categoryName == null ? "" : categoryName.trim());
        if (category.isEmpty()) {
            return "ERROR: category '" + categoryName + "' does not exist";
        }
        t.category = category.get();
        return "Transaction #%d is now in category %s.".formatted(t.id, t.category.name);
    }

    @Tool("Delete a transaction, for example to undo a mistake")
    @Transactional
    public String deleteTransaction(@P("Transaction id") long transactionId) {
        LOG.infof("tool deleteTransaction(#%d)", transactionId);
        Transaction t = Transaction.findById(transactionId);
        if (t == null) {
            return "ERROR: transaction #" + transactionId + " not found";
        }
        String summary = "#%d %s %s %s".formatted(t.id, t.type, t.amount, t.description != null ? t.description : "");
        t.delete();
        return "Deleted " + summary;
    }
}
