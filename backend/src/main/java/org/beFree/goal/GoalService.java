package org.beFree.goal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.auth.CurrentUser;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads goals together with what has been put aside for them. */
@ApplicationScoped
public class GoalService {

    @Inject
    CurrentUser currentUser;

    /** `spent` is what has already been paid out of the jar. `saved` is what is left in it. */
    public record Progress(Goal goal, BigDecimal saved, BigDecimal spent) {

        public Progress(Goal goal, BigDecimal saved) {
            this(goal, saved, BigDecimal.ZERO);
        }


        public BigDecimal remaining() {
            return goal.target.subtract(saved).max(BigDecimal.ZERO);
        }

        public boolean reached() {
            return saved.compareTo(goal.target) >= 0;
        }

        public Integer percent() {
            if (goal.target.signum() <= 0) {
                return null;
            }
            return saved.multiply(BigDecimal.valueOf(100))
                    .divide(goal.target, 0, RoundingMode.HALF_UP)
                    .intValue();
        }

        /** Whole months left until the target date, 0 when it is due this month or past. */
        public Long monthsLeft(LocalDate today) {
            if (goal.targetDate == null) {
                return null;
            }
            return Math.max(0, ChronoUnit.MONTHS.between(
                    today.withDayOfMonth(1), goal.targetDate.withDayOfMonth(1)));
        }

        /** What to put aside per month to arrive on time; null without a date, and once reached. */
        public BigDecimal perMonth(LocalDate today) {
            Long months = monthsLeft(today);
            if (months == null || reached()) {
                return null;
            }
            // Due this month (or overdue): the whole remainder is needed now
            return remaining().divide(BigDecimal.valueOf(Math.max(1, months)), 2, RoundingMode.HALF_UP);
        }
    }

    @Transactional
    public GoalContribution contribute(Goal goal, BigDecimal amount, LocalDate occurredOn, String note) {
        GoalContribution c = new GoalContribution();
        c.goal = goal;
        c.amount = amount;
        c.occurredOn = occurredOn;
        c.note = note;
        c.owner = currentUser.name();
        c.persist();
        return c;
    }

    /** The opening balance, plus every contribution, minus what has been spent out of it. */
    @Transactional
    public BigDecimal saved(Goal goal) {
        BigDecimal sum = GoalContribution.getEntityManager()
                .createQuery("select coalesce(sum(c.amount), 0) from GoalContribution c where c.goal = :goal", BigDecimal.class)
                .setParameter("goal", goal)
                .getSingleResult();
        return opening(goal).add(sum == null ? BigDecimal.ZERO : sum).subtract(spent(goal));
    }

    /** Expenses paid out of this goal. They never touched a month's income. */
    @Transactional
    public BigDecimal spent(Goal goal) {
        BigDecimal sum = GoalContribution.getEntityManager()
                .createQuery("select coalesce(sum(t.amount), 0) from Transaction t where t.goal = :goal", BigDecimal.class)
                .setParameter("goal", goal)
                .getSingleResult();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private static BigDecimal opening(Goal goal) {
        return goal.initialAmount == null ? BigDecimal.ZERO : goal.initialAmount;
    }

    @Transactional
    public List<Progress> progress() {
        List<Goal> goals = Goal.listAll(io.quarkus.panache.common.Sort.by("name"));
        if (goals.isEmpty()) {
            return List.of();
        }
        List<Object[]> rows = GoalContribution.getEntityManager()
                .createQuery("select c.goal.id, sum(c.amount) from GoalContribution c group by c.goal.id", Object[].class)
                .getResultList();
        Map<Long, BigDecimal> putInById = new HashMap<>();
        for (Object[] row : rows) {
            putInById.put((Long) row[0], (BigDecimal) row[1]);
        }
        List<Object[]> paid = GoalContribution.getEntityManager()
                .createQuery("select t.goal.id, sum(t.amount) from Transaction t where t.goal is not null group by t.goal.id",
                        Object[].class)
                .getResultList();
        Map<Long, BigDecimal> spentById = new HashMap<>();
        for (Object[] row : paid) {
            spentById.put((Long) row[0], (BigDecimal) row[1]);
        }
        List<Progress> out = new ArrayList<>(goals.size());
        for (Goal g : goals) {
            BigDecimal spent = spentById.getOrDefault(g.id, BigDecimal.ZERO);
            BigDecimal saved = opening(g).add(putInById.getOrDefault(g.id, BigDecimal.ZERO)).subtract(spent);
            out.add(new Progress(g, saved, spent));
        }
        return out;
    }
}
