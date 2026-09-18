package org.beFree.budget;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.auth.CurrentUser;
import org.beFree.calendar.MonthCycle;
import jakarta.transaction.Transactional;
import org.beFree.category.Category;
import org.beFree.transaction.Transaction;
import org.beFree.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads budgets together with what was actually spent against them. */
@ApplicationScoped
public class BudgetService {

    @Inject
    CurrentUser currentUser;

    @Inject
    MonthCycle cycle;

    /**
     * @param spent     expenses recorded in the category that month
     * @param remaining limit minus spent; negative means over budget
     */
    public record Status(String category, BigDecimal limitAmount, BigDecimal spent, BigDecimal remaining, String currency) {

        public boolean overspent() {
            return remaining.signum() < 0;
        }

        /** Share of the limit already used, 0-100+, or null when the limit is zero. */
        public Integer percentUsed() {
            if (limitAmount.signum() <= 0) {
                return null;
            }
            return spent.multiply(BigDecimal.valueOf(100))
                    .divide(limitAmount, 0, java.math.RoundingMode.HALF_UP)
                    .intValue();
        }
    }

    @Transactional
    public Budget set(Category category, YearMonth month, BigDecimal limitAmount) {
        Optional<Budget> existing = Budget.find(category, month);
        if (existing.isPresent()) {
            existing.get().limitAmount = limitAmount;
            return existing.get();
        }
        // Fill every non-null column before persisting: persist can flush immediately
        Budget budget = new Budget();
        budget.category = category;
        budget.period = month.atDay(1);
        budget.limitAmount = limitAmount;
        budget.owner = currentUser.name();
        budget.persist();
        return budget;
    }

    @Transactional
    public boolean remove(Category category, YearMonth month) {
        return Budget.find(category, month).map(b -> {
            b.delete();
            return true;
        }).orElse(false);
    }

    /** Every budget for the month, each with its spend. */
    @Transactional
    public List<Status> status(YearMonth month) {
        List<Budget> budgets = Budget.inMonth(month);
        if (budgets.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> spentByCategory = spentByCategory(month);
        List<Status> out = new ArrayList<>(budgets.size());
        for (Budget b : budgets) {
            BigDecimal spent = spentByCategory.getOrDefault(b.category.id, BigDecimal.ZERO);
            out.add(new Status(b.category.name, b.limitAmount, spent, b.limitAmount.subtract(spent), b.currency));
        }
        return out;
    }

    private Map<Long, BigDecimal> spentByCategory(YearMonth month) {
        var range = cycle.range(month);
        LocalDate from = range.from();
        LocalDate to = range.to();
        List<Object[]> rows = Transaction.getEntityManager().createQuery(
                        "select t.category.id, sum(t.amount) from Transaction t "
                                + "where t.type = :type and t.category is not null "
                                + "and t.occurredOn >= :from and t.occurredOn < :to group by t.category.id",
                        Object[].class)
                .setParameter("type", TransactionType.EXPENSE)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        Map<Long, BigDecimal> spent = new HashMap<>();
        for (Object[] row : rows) {
            spent.put((Long) row[0], (BigDecimal) row[1]);
        }
        return spent;
    }
}
