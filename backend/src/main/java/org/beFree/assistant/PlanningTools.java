package org.beFree.assistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.auth.CurrentUser;
import jakarta.transaction.Transactional;
import org.beFree.budget.BudgetService;
import org.beFree.category.Category;
import org.beFree.goal.Goal;
import org.beFree.goal.GoalService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Planning half of the assistant: monthly budgets per category and savings
 * goals. Same contract as {@link FinanceTools}: plain text back, errors as
 * text the model can act on, every tool transactional.
 */
@ApplicationScoped
public class PlanningTools {

    private static final Logger LOG = Logger.getLogger(PlanningTools.class);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    @Inject
    BudgetService budgets;

    @Inject
    GoalService goals;

    @Inject
    CurrentUser currentUser;

    @ConfigProperty(name = "befree.zone", defaultValue = "Europe/Lisbon")
    String zone;

    @Tool("Set or change the monthly spending limit for a category")
    @Transactional
    public String setBudget(@P("Existing category name") String categoryName,
                            @P("Limit in EUR, positive") BigDecimal limitAmount,
                            @P("Month as yyyy-MM, or null for the current month") String month) {
        LOG.infof("tool setBudget(%s, %s, %s)", categoryName, limitAmount, month);
        var category = Category.findByName(categoryName == null ? "" : categoryName.trim());
        if (category.isEmpty()) {
            return "ERROR: category '" + categoryName + "' does not exist. Call listCategories, or createCategory first.";
        }
        if (limitAmount == null || limitAmount.signum() <= 0 || limitAmount.compareTo(MAX_AMOUNT) > 0) {
            return "ERROR: limit must be a positive, plausible amount";
        }
        YearMonth ym;
        try {
            ym = parseMonth(month);
        } catch (DateTimeParseException e) {
            return "ERROR: month must be yyyy-MM";
        }
        var budget = budgets.set(category.get(), ym, limitAmount.setScale(2, java.math.RoundingMode.HALF_UP));
        return "Budget for %s in %s set to %s %s.".formatted(
                category.get().name, ym, budget.limitAmount, budget.currency);
    }

    @Tool("Remove a category's budget for a month")
    @Transactional
    public String removeBudget(@P("Existing category name") String categoryName,
                               @P("Month as yyyy-MM, or null for the current month") String month) {
        LOG.infof("tool removeBudget(%s, %s)", categoryName, month);
        var category = Category.findByName(categoryName == null ? "" : categoryName.trim());
        if (category.isEmpty()) {
            return "ERROR: category '" + categoryName + "' does not exist";
        }
        YearMonth ym;
        try {
            ym = parseMonth(month);
        } catch (DateTimeParseException e) {
            return "ERROR: month must be yyyy-MM";
        }
        return budgets.remove(category.get(), ym)
                ? "Removed the %s budget for %s.".formatted(category.get().name, ym)
                : "There was no budget for %s in %s.".formatted(category.get().name, ym);
    }

    @Tool("Budget status for a month: limit, spent and remaining per category")
    @Transactional
    public String budgetStatus(@P("Month as yyyy-MM, or null for the current month") String month) {
        LOG.infof("tool budgetStatus(%s)", month);
        YearMonth ym;
        try {
            ym = parseMonth(month);
        } catch (DateTimeParseException e) {
            return "ERROR: month must be yyyy-MM";
        }
        List<BudgetService.Status> statuses = budgets.status(ym);
        if (statuses.isEmpty()) {
            return "No budgets set for " + ym + ". Use setBudget to create one.";
        }
        StringBuilder out = new StringBuilder("Budgets for ").append(ym).append(" (EUR):\n");
        BigDecimal totalLimit = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;
        for (BudgetService.Status s : statuses) {
            totalLimit = totalLimit.add(s.limitAmount());
            totalSpent = totalSpent.add(s.spent());
            out.append("- %s: spent %s of %s, %s %s".formatted(
                    s.category(), s.spent(), s.limitAmount(),
                    s.overspent() ? "over by" : "left",
                    s.remaining().abs()));
            Integer percent = s.percentUsed();
            if (percent != null) {
                out.append(" (").append(percent).append("%)");
            }
            out.append('\n');
        }
        out.append("Total: spent %s of %s, %s %s".formatted(
                totalSpent, totalLimit,
                totalSpent.compareTo(totalLimit) > 0 ? "over by" : "left",
                totalLimit.subtract(totalSpent).abs()));
        return out.toString();
    }

    @Tool("Create a savings goal (money set aside for the future, not spending)")
    @Transactional
    public String createGoal(@P("Goal name, e.g. 'Férias'") String name,
                             @P("Target amount in EUR") BigDecimal target,
                             @P("Target date as yyyy-MM-dd, or null") String targetDate,
                             @P("Short description, or null") String description) {
        LOG.infof("tool createGoal(%s, %s, %s)", name, target, targetDate);
        if (name == null || name.isBlank()) {
            return "ERROR: name is required";
        }
        if (target == null || target.signum() <= 0 || target.compareTo(MAX_AMOUNT) > 0) {
            return "ERROR: target must be a positive, plausible amount";
        }
        String clean = name.trim();
        if (Goal.findByName(clean).isPresent()) {
            return "A goal named '" + clean + "' already exists.";
        }
        Goal goal = new Goal();
        goal.name = clean;
        goal.target = target.setScale(2, java.math.RoundingMode.HALF_UP);
        goal.description = description == null || description.isBlank() ? null : description.trim();
        if (targetDate != null && !targetDate.isBlank()) {
            try {
                goal.targetDate = LocalDate.parse(targetDate.trim());
            } catch (DateTimeParseException e) {
                return "ERROR: target date must be yyyy-MM-dd";
            }
        }
        goal.owner = currentUser.name();
        goal.persist();
        return "Created goal '%s': target %s EUR%s.".formatted(
                goal.name, goal.target, goal.targetDate == null ? "" : " by " + goal.targetDate);
    }

    @Tool("Put money into a savings goal, or take it back out with a negative amount")
    @Transactional
    public String contributeToGoal(@P("Existing goal name") String goalName,
                                   @P("Amount in EUR; negative withdraws") BigDecimal amount,
                                   @P("Date as yyyy-MM-dd, or null for today") String date,
                                   @P("Short note, or null") String note) {
        LOG.infof("tool contributeToGoal(%s, %s, %s)", goalName, amount, date);
        var goal = Goal.findByName(goalName == null ? "" : goalName.trim());
        if (goal.isEmpty()) {
            return "ERROR: goal '" + goalName + "' does not exist. Call goalProgress to see the goals, or createGoal.";
        }
        if (amount == null || amount.signum() == 0 || amount.abs().compareTo(MAX_AMOUNT) > 0) {
            return "ERROR: amount must be a non-zero, plausible value";
        }
        LocalDate when;
        try {
            when = (date == null || date.isBlank()) ? LocalDate.now(ZoneId.of(zone)) : LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return "ERROR: date must be yyyy-MM-dd";
        }
        goals.contribute(goal.get(), amount.setScale(2, java.math.RoundingMode.HALF_UP), when,
                note == null || note.isBlank() ? null : note.trim());
        BigDecimal saved = goals.saved(goal.get());
        var progress = new GoalService.Progress(goal.get(), saved);
        return "%s %s EUR %s '%s'. Saved %s of %s%s.".formatted(
                amount.signum() > 0 ? "Added" : "Took", amount.abs(),
                amount.signum() > 0 ? "to" : "from", goal.get().name,
                saved, goal.get().target,
                progress.percent() == null ? "" : " (" + progress.percent() + "%)");
    }

    @Tool("Progress of every savings goal: saved, target, and what to put aside per month")
    @Transactional
    public String goalProgress() {
        LOG.info("tool goalProgress()");
        List<GoalService.Progress> all = goals.progress();
        if (all.isEmpty()) {
            return "No goals yet. Use createGoal to start one.";
        }
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        StringBuilder out = new StringBuilder("Goals (EUR):\n");
        for (GoalService.Progress p : all) {
            out.append("- %s: %s of %s".formatted(p.goal().name, p.saved(), p.goal().target));
            if (p.percent() != null) {
                out.append(" (").append(p.percent()).append("%)");
            }
            if (p.reached()) {
                out.append(", reached");
            } else if (p.goal().targetDate != null) {
                out.append(", %s left, %s months to go, put aside %s per month".formatted(
                        p.remaining(), p.monthsLeft(today), p.perMonth(today)));
            } else {
                out.append(", ").append(p.remaining()).append(" left");
            }
            out.append('\n');
        }
        return out.toString().trim();
    }

    @Tool("Delete a savings goal and its contributions")
    @Transactional
    public String deleteGoal(@P("Existing goal name") String goalName) {
        LOG.infof("tool deleteGoal(%s)", goalName);
        var goal = Goal.findByName(goalName == null ? "" : goalName.trim());
        if (goal.isEmpty()) {
            return "ERROR: goal '" + goalName + "' does not exist";
        }
        BigDecimal saved = goals.saved(goal.get());
        long removed = org.beFree.goal.GoalContribution.delete("goal", goal.get());
        String name = goal.get().name;
        goal.get().delete();
        return "Deleted goal '%s' and %d contributions (%s EUR were set aside).".formatted(name, removed, saved);
    }

    private YearMonth parseMonth(String month) {
        return (month == null || month.isBlank())
                ? YearMonth.now(ZoneId.of(zone))
                : YearMonth.parse(month.trim());
    }
}
