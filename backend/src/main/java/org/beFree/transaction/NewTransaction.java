package org.beFree.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input for {@link TransactionService#record}. Null type/currency/occurredOn
 * take defaults; externalId enables idempotent writes per source.
 */
public record NewTransaction(
        BigDecimal amount,
        TransactionType type,
        String currency,
        LocalDate occurredOn,
        String description,
        Long categoryId,
        Source source,
        String externalId,
        String rawInput) {
}
