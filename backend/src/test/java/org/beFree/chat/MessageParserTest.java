package org.beFree.chat;

import org.beFree.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageParserTest {

    @Test
    void amountFirstWithComma() {
        var p = MessageParser.parse("12,50 almoço").orElseThrow();
        assertEquals(new BigDecimal("12.50"), p.amount());
        assertEquals(TransactionType.EXPENSE, p.type());
        assertEquals("almoço", p.description());
        assertNull(p.categoryName());
    }

    @Test
    void amountLastWithDotAndEuroSign() {
        var p = MessageParser.parse("café da manhã 3.2€").orElseThrow();
        assertEquals(new BigDecimal("3.2"), p.amount());
        assertEquals("café da manhã", p.description());
    }

    @Test
    void hashtagBecomesCategory() {
        var p = MessageParser.parse("50 lidl #groceries").orElseThrow();
        assertEquals("groceries", p.categoryName());
        assertEquals("lidl", p.description());
    }

    @Test
    void plusPrefixMeansIncome() {
        var p = MessageParser.parse("+1500 salário").orElseThrow();
        assertEquals(TransactionType.INCOME, p.type());
        assertEquals(new BigDecimal("1500"), p.amount());
    }

    @Test
    void amountOnlyHasNoDescription() {
        var p = MessageParser.parse("7").orElseThrow();
        assertNull(p.description());
    }

    @Test
    void noAmountIsRejected() {
        assertTrue(MessageParser.parse("almoço com a equipa").isEmpty());
        assertTrue(MessageParser.parse("").isEmpty());
        assertTrue(MessageParser.parse(null).isEmpty());
    }
}
