package org.beFree.goal;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Money set aside for something ahead ("reserve"): a name, a target, and
 * optionally a date to reach it by.
 *
 * What is saved is the opening balance plus every contribution. The opening
 * balance is what was already in the jar when the goal was created; it never
 * appears in a month's flow, because no income went to it that month.
 */
@Entity
@Table(name = "goals")
public class Goal extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    public String description;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal target;

    @Column(name = "target_date")
    public LocalDate targetDate;

    /** Already in the jar on day one. Counts towards the goal, not towards any month. */
    @Column(name = "initial_amount", nullable = false, precision = 12, scale = 2)
    public BigDecimal initialAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    public String currency = "EUR";

    /** Row owner. One user today; see CurrentUser for why the column exists now. */
    @Column(nullable = false, length = 64)
    public String owner;

    public static Optional<Goal> findByName(String name) {
        return find("lower(name) = ?1", name.toLowerCase()).firstResultOptional();
    }
}
