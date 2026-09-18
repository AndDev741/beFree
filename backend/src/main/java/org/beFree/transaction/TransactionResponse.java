package org.beFree.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TransactionResponse(
        Long id,
        BigDecimal amount,
        TransactionType type,
        String currency,
        LocalDate occurredOn,
        String description,
        String category,
        /** The goal this came out of, when it was not paid from the month. */
        String goal,
        Long goalId,
        Source source,
        Instant createdAt) {

    // Built inside the transaction, before `category` detaches (no OSIV in Quarkus)
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.id, t.amount, t.type, t.currency, t.occurredOn, t.description,
                t.category != null ? t.category.name : null,
                t.goal != null ? t.goal.name : null,
                t.goal != null ? t.goal.id : null,
                t.source, t.createdAt);
    }
}
