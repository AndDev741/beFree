package org.beFree.assistant;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.beFree.transaction.Source;
import org.beFree.transaction.Transaction;
import org.beFree.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FinanceToolsTest {

    @Inject
    FinanceTools tools;

    @Inject
    ConversationContext context;

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void recordsUnderTheConversationIdentity() {
        context.open(Source.WHATSAPP, "wamid.TOOLS1", "12,50 almoço");

        String out = tools.recordTransaction(new BigDecimal("12.50"), TransactionType.EXPENSE, "almoço", null, "2026-07-03");

        assertTrue(out.startsWith("Recorded #"), out);
        var stored = Transaction.bySourceRef(Source.WHATSAPP, "wamid.TOOLS1#1").orElseThrow();
        assertEquals("12,50 almoço", stored.rawInput);
        assertEquals("almoço", stored.description);
    }

    @Test
    void secondItemInSameMessageGetsNextSuffix() {
        context.open(Source.WHATSAPP, "wamid.TOOLS2", "50 lidl e 12 gasolina");

        tools.recordTransaction(new BigDecimal("50"), TransactionType.EXPENSE, "lidl", null, "2026-07-04");
        tools.recordTransaction(new BigDecimal("12"), TransactionType.EXPENSE, "gasolina", null, "2026-07-04");

        assertTrue(Transaction.bySourceRef(Source.WHATSAPP, "wamid.TOOLS2#1").isPresent());
        assertTrue(Transaction.bySourceRef(Source.WHATSAPP, "wamid.TOOLS2#2").isPresent());
    }

    @Test
    void unknownCategoryIsReportedNotInvented() {
        String out = tools.recordTransaction(new BigDecimal("9"), TransactionType.EXPENSE, "x", "Nope", "2026-07-05");
        assertTrue(out.startsWith("ERROR"), out);
    }

    @Test
    void categoriesSummaryAndCorrections() {
        assertTrue(tools.createCategory("Food").startsWith("Created"));
        assertTrue(tools.createCategory("food").contains("already exists"));
        assertTrue(tools.listCategories().contains("Food"));

        context.open(Source.WHATSAPP, "wamid.TOOLS3", "test");
        String a = tools.recordTransaction(new BigDecimal("20"), TransactionType.EXPENSE, "lunch", "Food", "2026-06-10");
        tools.recordTransaction(new BigDecimal("5"), TransactionType.EXPENSE, "bus", null, "2026-06-11");
        tools.recordTransaction(new BigDecimal("1500"), TransactionType.INCOME, "salary", null, "2026-06-01");

        String summary = tools.monthlySummary("2026-06");
        assertTrue(summary.contains("Food: 20"), summary);
        assertTrue(summary.contains("Total expenses: 25"), summary);
        assertTrue(summary.contains("Total income: 1500"), summary);
        assertTrue(summary.contains("Balance: 1475"), summary);

        long id = Long.parseLong(a.substring(a.indexOf('#') + 1, a.indexOf(':')));
        assertTrue(tools.setCategory(id, "Food").contains("Food"));
        assertTrue(tools.listTransactions("2026-06", 5).contains("#" + id));
        assertTrue(tools.deleteTransaction(id).startsWith("Deleted"));
        assertTrue(tools.deleteTransaction(id).startsWith("ERROR"));
    }

    @Test
    void categoriesCanBeRenamedMergedAndDeleted() {
        tools.createCategory("Transporte");
        tools.createCategory("Uber");
        context.open(Source.WHATSAPP, "wamid.TOOLS4", "test");
        String a = tools.recordTransaction(new BigDecimal("9"), TransactionType.EXPENSE, "uber", "Uber", "2026-05-02");
        long id = Long.parseLong(a.substring(a.indexOf('#') + 1, a.indexOf(':')));

        assertTrue(tools.renameCategory("Uber", "Boleias").startsWith("Renamed"));
        assertTrue(tools.renameCategory("Boleias", "Transporte").startsWith("ERROR"));
        assertTrue(tools.listCategories().contains("Boleias"));

        String merged = tools.deleteCategory("Boleias", "Transporte");
        assertTrue(merged.contains("1 transactions moved to 'Transporte'"), merged);
        assertTrue(tools.listTransactions("2026-05", 10).contains("[Transporte]"));
        assertTrue(tools.deleteCategory("Boleias", null).startsWith("ERROR"));

        assertTrue(tools.updateTransaction(id, new BigDecimal("9.50"), "uber para casa", "2026-05-03").startsWith("Updated #" + id));
        String after = tools.listTransactions("2026-05", 10);
        assertTrue(after.contains("2026-05-03 EXPENSE 9.50 uber para casa"), after);
        assertTrue(tools.updateTransaction(id, new BigDecimal("-1"), null, null).startsWith("ERROR"));
    }
}
