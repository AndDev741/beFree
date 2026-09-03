package org.beFree.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
        BigDecimal amount,
        TransactionType type,
        String currency,
        LocalDate occurredOn,
        String description,
        Long categoryId) {
}
