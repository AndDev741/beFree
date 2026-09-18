package org.beFree.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * On a PATCH, a field left out keeps its value. Taking the category off a
 * movement needs its own flag, because an absent field and a JSON null are
 * the same thing by the time it gets here.
 */
public record TransactionRequest(
        BigDecimal amount,
        TransactionType type,
        String currency,
        LocalDate occurredOn,
        String description,
        Long categoryId,
        Boolean clearCategory,
        Long goalId,
        Boolean clearGoal) {
}
