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
 * The saved amount is the sum of its contributions, never a stored field.
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

    @Column(nullable = false, length = 3)
    public String currency = "EUR";

    public static Optional<Goal> findByName(String name) {
        return find("lower(name) = ?1", name.toLowerCase()).firstResultOptional();
    }
}
