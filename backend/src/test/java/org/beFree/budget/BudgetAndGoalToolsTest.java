package org.beFree.budget;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.beFree.assistant.ConversationContext;
import org.beFree.goal.GoalContribution;
import org.beFree.assistant.FinanceTools;
import org.beFree.assistant.PlanningTools;
import org.beFree.transaction.Source;
import org.beFree.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BudgetAndGoalToolsTest {

    @Inject
    PlanningTools planning;

    @Inject
    FinanceTools finance;

    @Inject
    ConversationContext context;

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void budgetTracksSpendingRecordedAfterwards() {
        finance.createCategory("Mercado");
        assertTrue(planning.setBudget("Mercado", new BigDecimal("300"), "2026-10").contains("300.00"));

        String empty = planning.budgetStatus("2026-10");
        assertTrue(empty.contains("spent 0 of 300.00"), empty);

        context.open(Source.WHATSAPP, "wamid.BUD1", "compras");
        finance.recordTransaction(new BigDecimal("120.50"), TransactionType.EXPENSE, "Lidl", "Mercado", "2026-10-03");
        finance.recordTransaction(new BigDecimal("60"), TransactionType.EXPENSE, "Pingo Doce", "Mercado", "2026-10-09");

        String status = planning.budgetStatus("2026-10");
        assertTrue(status.contains("spent 180.50 of 300.00"), status);
        assertTrue(status.contains("left 119.50"), status);
        assertTrue(status.contains("(60%)"), status);
    }

    @Test
    void overspendingIsReportedAsOver() {
        finance.createCategory("Restaurantes");
        planning.setBudget("Restaurantes", new BigDecimal("50"), "2026-11");

        context.open(Source.WHATSAPP, "wamid.BUD2", "jantar");
        finance.recordTransaction(new BigDecimal("80"), TransactionType.EXPENSE, "sushi", "Restaurantes", "2026-11-04");

        String status = planning.budgetStatus("2026-11");
        assertTrue(status.contains("over by 30.00"), status);
    }

    @Test
    void budgetsAreOnePerCategoryAndMonth() {
        finance.createCategory("Transporte");
        planning.setBudget("Transporte", new BigDecimal("40"), "2026-12");
        planning.setBudget("Transporte", new BigDecimal("55"), "2026-12");

        assertEquals(1, Budget.count("period", java.time.YearMonth.of(2026, 12).atDay(1)));
        assertTrue(planning.budgetStatus("2026-12").contains("of 55.00"));

        assertTrue(planning.removeBudget("Transporte", "2026-12").startsWith("Removed"));
        assertTrue(planning.removeBudget("Transporte", "2026-12").startsWith("There was no budget"));
        assertTrue(planning.setBudget("Inexistente", new BigDecimal("10"), null).startsWith("ERROR"));
        assertTrue(planning.setBudget("Transporte", new BigDecimal("-5"), null).startsWith("ERROR"));
        assertTrue(planning.setBudget("Transporte", new BigDecimal("10"), "dezembro").startsWith("ERROR"));
    }

    @Test
    void goalAccumulatesContributionsAndPacesByDate() {
        assertTrue(planning.createGoal("Férias", new BigDecimal("1200"), "2027-03-01", "Viagem", null).startsWith("Created goal"));
        assertTrue(planning.createGoal("Férias", new BigDecimal("500"), null, null, null).contains("already exists"));

        assertTrue(planning.contributeToGoal("Férias", new BigDecimal("200"), "2026-09-15", "setembro").contains("Saved 200.00 of 1200.00"));
        assertTrue(planning.contributeToGoal("Férias", new BigDecimal("100"), "2026-10-15", null).contains("Saved 300.00"));
        assertTrue(planning.contributeToGoal("Férias", new BigDecimal("-50"), "2026-10-20", "imprevisto").contains("Saved 250.00"));

        String progress = planning.goalProgress();
        assertTrue(progress.contains("Férias: 250.00 of 1200.00"), progress);
        assertTrue(progress.contains("(21%)"), progress);
        assertTrue(progress.contains("950.00 left"), progress);
        assertTrue(progress.contains("per month"), progress);

        assertTrue(planning.contributeToGoal("Inexistente", BigDecimal.TEN, null, null).startsWith("ERROR"));
        assertTrue(planning.contributeToGoal("Férias", BigDecimal.ZERO, null, null).startsWith("ERROR"));
    }

    @Test
    void aGoalCanStartWithMoneyThatIsAlreadyPutBy() {
        assertTrue(planning.createGoal("Cofrinho", new BigDecimal("800"), null, null, new BigDecimal("300"))
                .contains("300"));
        assertTrue(planning.goalProgress().contains("- Cofrinho: 300.00 of 800.00"));

        // Nothing was set aside, so nothing may show up as a contribution
        assertEquals(0, GoalContribution.count("goal.name", "Cofrinho"));

        assertTrue(planning.createGoal("Impossível", new BigDecimal("100"), null, null, new BigDecimal("500"))
                .startsWith("ERROR"));
    }

    @Test
    void reachedGoalStopsAskingForMonthlyContributions() {
        planning.createGoal("Fundo", new BigDecimal("100"), "2026-12-01", null, null);
        planning.contributeToGoal("Fundo", new BigDecimal("100"), "2026-09-01", null);

        // Scoped to this goal's own line: the listing is global and other tests add goals.
        String fundoLine = planning.goalProgress().lines()
                .filter(l -> l.startsWith("- Fundo:"))
                .findFirst().orElseThrow();
        assertTrue(fundoLine.contains("100.00 of 100.00"), fundoLine);
        assertTrue(fundoLine.contains("reached"), fundoLine);
        assertFalse(fundoLine.contains("per month"), fundoLine);

        String deleted = planning.deleteGoal("Fundo");
        assertTrue(deleted.contains("1 contributions"), deleted);
        assertTrue(planning.deleteGoal("Fundo").startsWith("ERROR"));
    }

    @Test
    void goalContributionsAreNotExpenses() {
        planning.createGoal("Reserva", new BigDecimal("500"), null, null, null);
        planning.contributeToGoal("Reserva", new BigDecimal("150"), "2026-08-05", null);

        String summary = finance.monthlySummary("2026-08");
        assertFalse(summary.contains("150"), summary);
    }
}
