package org.beFree.api;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.beFree.budget.BudgetService;
import org.beFree.goal.GoalContribution;
import org.beFree.goal.GoalService;
import org.beFree.transaction.Transaction;
import org.beFree.transaction.TransactionType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything the dashboard needs in one request. The alternative is the SPA
 * firing five calls on load and stitching the totals itself, which puts the
 * same arithmetic in two places.
 */
@Path("/api/summary")
@Produces(MediaType.APPLICATION_JSON)
public class SummaryResource {

    @Inject
    BudgetService budgets;

    @Inject
    GoalService goals;

    @Inject
    Months months;

    @ConfigProperty(name = "befree.zone", defaultValue = "Europe/Lisbon")
    String zone;

    @GET
    @Transactional
    public Dto.Summary get(@QueryParam("month") String month) {
        YearMonth ym = months.parse(month);
        var range = months.range(ym);
        LocalDate from = range.from();
        LocalDate to = range.to();

        List<Object[]> rows = Transaction.getEntityManager().createQuery(
                        "select c.name, t.type, sum(t.amount) from Transaction t left join t.category c "
                                + "where t.occurredOn >= :from and t.occurredOn < :to group by c.name, t.type",
                        Object[].class)
                .setParameter("from", from).setParameter("to", to).getResultList();

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;
        List<Dto.CategorySpend> byCategory = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal sum = (BigDecimal) r[2];
            if (r[1] == TransactionType.INCOME) {
                income = income.add(sum);
            } else {
                spent = spent.add(sum);
                byCategory.add(new Dto.CategorySpend(r[0] == null ? "Sem categoria" : (String) r[0], sum));
            }
        }
        byCategory.sort(Comparator.comparing(Dto.CategorySpend::amount).reversed());

        BigDecimal reserved = GoalContribution.getEntityManager().createQuery(
                        "select coalesce(sum(c.amount), 0) from GoalContribution c "
                                + "where c.occurredOn >= :from and c.occurredOn < :to", BigDecimal.class)
                .setParameter("from", from).setParameter("to", to).getSingleResult();

        LocalDate today = LocalDate.now(ZoneId.of(zone));
        return new Dto.Summary(
                ym.toString(), income, spent, reserved,
                income.subtract(spent).subtract(reserved),
                byCategory,
                budgets.status(ym).stream().map(s -> new Dto.BudgetView(
                        s.category(), s.limitAmount(), s.spent(), s.remaining(), s.percentUsed(), s.overspent())).toList(),
                goals.progress().stream().map(p -> GoalResource.view(p, today)).toList());
    }
}
