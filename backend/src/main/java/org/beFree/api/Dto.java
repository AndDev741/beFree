package org.beFree.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request and response shapes the SPA speaks. Kept together: they are one contract. */
public final class Dto {

    private Dto() {
    }

    public record Session(String username, boolean assistantEnabled) {
    }

    public record BudgetRequest(String category, BigDecimal limitAmount, String month) {
    }

    public record BudgetView(String category, BigDecimal limitAmount, BigDecimal spent,
                             BigDecimal remaining, Integer percentUsed, boolean overspent) {
    }

    /**
     * A field left out keeps its value. Clearing the date needs its own flag:
     * an absent field and a JSON null look the same once deserialised.
     */
    public record GoalRequest(String name, BigDecimal target, LocalDate targetDate, String description,
                              Boolean clearTargetDate, BigDecimal initial) {
    }

    public record ContributionRequest(BigDecimal amount, LocalDate occurredOn, String note) {
    }

    public record GoalView(Long id, String name, String description, BigDecimal target, LocalDate targetDate,
                           BigDecimal initial, BigDecimal saved, BigDecimal remaining, Integer percent,
                           boolean reached, Long monthsLeft, BigDecimal perMonth) {
    }

    public record CategorySpend(String category, BigDecimal amount) {
    }

    /**
     * One call for the whole dashboard, so the SPA does not fan out on load.
     * `carried` is what was still in the pot when the month began; `remaining`
     * already includes it.
     */
    public record Summary(String month, BigDecimal carried, BigDecimal income, BigDecimal spent,
                          BigDecimal reserved, BigDecimal remaining, List<CategorySpend> byCategory,
                          List<BudgetView> budgets, List<GoalView> goals) {
    }

    public record ChatRequest(String message) {
    }

    public record ChatReply(String reply) {
    }
}
