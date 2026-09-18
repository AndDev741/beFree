package org.beFree.transaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.auth.CurrentUser;
import jakarta.transaction.Transactional;
import org.beFree.category.Category;
import org.beFree.goal.Goal;
import org.beFree.goal.GoalService;

import java.math.BigDecimal;
import java.time.LocalDate;

@ApplicationScoped
public class TransactionService {

    @Inject
    CurrentUser currentUser;

    @Inject
    GoalService goals;

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
        t.owner = currentUser.name();

        if (in.categoryId() != null) {
            Category category = Category.findById(in.categoryId());
            if (category == null) {
                throw new IllegalArgumentException("unknown category: " + in.categoryId());
            }
            t.category = category;
        }

        if (in.goalId() != null) {
            t.goal = payingFrom(in.goalId(), t.type, t.amount);
        }

        // Flush so @CreationTimestamp is populated before callers read it
        t.persistAndFlush();
        return t;
    }

    /**
     * A jar can only pay out what it holds. Spending more than is in it is
     * really two things, part from the jar and part from the month, so say so
     * rather than quietly letting the goal go negative.
     */
    public Goal payingFrom(Long goalId, TransactionType type, BigDecimal amount) {
        Goal goal = Goal.findById(goalId);
        if (goal == null) {
            throw new IllegalArgumentException("unknown goal: " + goalId);
        }
        if (type == TransactionType.INCOME) {
            throw new IllegalArgumentException("income cannot come out of a goal; put it in with a contribution");
        }
        BigDecimal available = goals.saved(goal);
        if (amount.compareTo(available) > 0) {
            throw new IllegalArgumentException(
                    "'%s' holds %s EUR, which is less than %s. Record %s from the goal and the rest as a normal expense."
                            .formatted(goal.name, available, amount, available));
        }
        return goal;
    }
}
