package org.beFree.goal;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One deposit into a goal. Kept out of `transactions` on purpose: moving
 * money into savings is not spending, and counting it as an expense would
 * inflate every monthly total.
 */
@Entity
@Table(name = "goal_contributions")
public class GoalContribution extends PanacheEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    public Goal goal;

    /** Positive adds to the goal, negative takes money back out. */
    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal amount;

    @Column(name = "occurred_on", nullable = false)
    public LocalDate occurredOn;

    public String note;
}
