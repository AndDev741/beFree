package org.beFree.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request and response shapes the SPA speaks. Kept together: they are one contract. */
public final class Dto {

    private Dto() {
    }

    public record Session(String username, boolean assistantEnabled, int monthStartDay) {
    }

    public record SettingsRequest(Integer monthStartDay) {
    }

    /**
     * The cycle as it applies to one month: the day it starts, whether that is
     * this month's own exception or the default, and the days it covers.
     */
    public record Settings(int defaultStartDay, int startDay, boolean custom,
                           String month, LocalDate from, LocalDate to) {
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
                              Boolean clearTargetDate, BigDecimal initial, Boolean achieved) {
    }

    public record ContributionRequest(BigDecimal amount, LocalDate occurredOn, String note) {
    }

    public record GoalView(Long id, String name, String description, BigDecimal target, LocalDate targetDate,
                           BigDecimal initial, BigDecimal saved, BigDecimal spent, BigDecimal remaining,
                           Integer percent, boolean reached, LocalDate achievedOn, LocalDate archivedOn,
                           Long monthsLeft, BigDecimal perMonth) {
    }

    /**
     * A field left out keeps its value on a PATCH. Unlinking the movement needs
     * its own flag, for the same reason clearing a goal's date does.
     */
    public record LoanRequest(String person, String description, BigDecimal amount, String currency,
                              LocalDate lentOn, Long transactionId, Boolean clearTransaction) {
    }

    /** Currency left out means the loan's own. */
    public record RepaymentRequest(BigDecimal amount, String currency, LocalDate occurredOn, String note) {
    }

    public record CloseRequest(String note) {
    }

    public record RepaymentView(Long id, BigDecimal amount, String currency, LocalDate occurredOn, String note) {
    }

    /**
     * `repaid` and `outstanding` are in the loan's currency. Money that came
     * back in any other currency is in `otherCurrencies`, unconverted.
     */
    public record LoanView(Long id, String person, String description, BigDecimal amount, String currency,
                           LocalDate lentOn, Long transactionId, String transactionDescription,
                           BigDecimal repaid, BigDecimal outstanding, Integer percent,
                           Map<String, BigDecimal> otherCurrencies, String status,
                           LocalDate settledOn, LocalDate closedOn, String closingNote,
                           List<RepaymentView> repayments) {
    }

    public record CategorySpend(String category, BigDecimal amount) {
    }

    /** One call for the whole dashboard, so the SPA does not fan out on load. */
    public record Summary(String month, BigDecimal income, BigDecimal spent, BigDecimal spentFromGoals,
                          BigDecimal reserved, BigDecimal remaining, List<CategorySpend> byCategory,
                          List<BudgetView> budgets, List<GoalView> goals) {
    }

    public record ChatRequest(String message) {
    }

    public record ChatReply(String reply) {
    }
}
