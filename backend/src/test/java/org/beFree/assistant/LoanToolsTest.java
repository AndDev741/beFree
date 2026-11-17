package org.beFree.assistant;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.beFree.loan.Loan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LoanToolsTest {

    @Inject
    LoanTools tools;

    private static long idOf(String reply) {
        // "Loan #123: ..."
        return Long.parseLong(reply.substring(reply.indexOf('#') + 1, reply.indexOf(':')));
    }

    @Test
    void lendRepayAndListWhatIsOwed() {
        String lent = tools.recordLoan("Richelle Tool", new BigDecimal("1100"), null, "2026-08-01", "passagem", null);
        assertTrue(lent.startsWith("Loan #"), lent);
        long id = idOf(lent);

        String back = tools.recordLoanRepayment(id, new BigDecimal("500"), null, "2026-08-03", null);
        assertTrue(back.contains("still owes 600.00 EUR"), back);

        String list = tools.listLoans(null);
        assertTrue(list.contains("#" + id + " Richelle Tool"), list);
        assertTrue(list.contains("still owes 600.00 EUR"), list);

        String tooMuch = tools.recordLoanRepayment(id, new BigDecimal("700"), "EUR", null, null);
        assertTrue(tooMuch.startsWith("ERROR:"), tooMuch);
    }

    @Test
    void otherCurrencyIsListedNotConvertedAndClosingIsByHand() {
        long id = idOf(tools.recordLoan("Pai Tool", new BigDecimal("652.54"), "EUR", "2026-02-15", null, null));

        String back = tools.recordLoanRepayment(id, new BigDecimal("1100"), "BRL", "2026-08-01", "cofrinho");
        assertTrue(back.contains("0.00 paid back plus 1100.00 BRL"), back);
        assertTrue(back.contains("still owes 652.54 EUR"), back);

        String closed = tools.closeLoan(id, "conversão bateu");
        assertTrue(closed.contains("Closed the loan to Pai Tool"), closed);
        assertTrue(!tools.listLoans(null).contains("#" + id + " "), "closed loans leave the open list");
        assertTrue(tools.listLoans(true).contains("closed on"), "and come back with includeClosed");

        String reopened = tools.reopenLoan(id);
        assertTrue(reopened.contains("still owes 652.54 EUR"), reopened);

        String deleted = tools.deleteLoan(id);
        assertTrue(deleted.startsWith("Deleted"), deleted);
        assertTrue(Loan.findByIdOptional(id).isEmpty());
    }

    @Test
    void badInputComesBackAsText() {
        assertTrue(tools.recordLoan(" ", BigDecimal.TEN, null, null, null, null).startsWith("ERROR:"));
        assertTrue(tools.recordLoan("X", BigDecimal.TEN, "reais", null, null, null).startsWith("ERROR:"));
        assertTrue(tools.recordLoan("X", BigDecimal.TEN, null, "14/10", null, null).startsWith("ERROR:"));
        assertTrue(tools.recordLoanRepayment(999999L, BigDecimal.ONE, null, null, null).startsWith("ERROR:"));
    }
}
