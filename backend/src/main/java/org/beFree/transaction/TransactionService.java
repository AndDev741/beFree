package org.beFree.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.beFree.category.Category;

import java.math.BigDecimal;
import java.time.LocalDate;

@ApplicationScoped
public class TransactionService {

    @Transactional
    public Transaction record(NewTransaction in) {
        if (in.amount() == null || in.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount is required and must be positive");
        }
        if (in.source() == null) {
            throw new IllegalArgumentException("source is required");
        }

        if (in.externalId() != null) {
            var existing = Transaction.bySourceRef(in.source(), in.externalId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Transaction t = new Transaction();
        t.amount = in.amount();
        t.type = in.type() != null ? in.type() : TransactionType.EXPENSE;
        t.currency = in.currency() != null ? in.currency() : "EUR";
        t.occurredOn = in.occurredOn() != null ? in.occurredOn() : LocalDate.now();
        t.description = in.description();
        t.source = in.source();
        t.externalId = in.externalId();
        t.rawInput = in.rawInput();

        if (in.categoryId() != null) {
            Category category = Category.findById(in.categoryId());
            if (category == null) {
                throw new IllegalArgumentException("unknown category: " + in.categoryId());
            }
            t.category = category;
        }

        // Flush so @CreationTimestamp is populated before callers read it
        t.persistAndFlush();
        return t;
    }
}
