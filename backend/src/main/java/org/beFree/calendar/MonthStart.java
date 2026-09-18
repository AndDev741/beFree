package org.beFree.calendar;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * One month that begins on a different day from the rest. Pay dates slip: the
 * 25th lands on a Saturday and that month alone starts on the 27th.
 */
@Entity
@Table(name = "month_starts")
public class MonthStart extends PanacheEntity {

    @Column(nullable = false, length = 64)
    public String owner;

    /** First day of the labelled month, the same key budgets use. */
    @Column(nullable = false)
    public LocalDate period;

    @Column(name = "start_day", nullable = false)
    public int startDay;

    public static Optional<MonthStart> of(String owner, YearMonth month) {
        return find("owner = ?1 and period = ?2", owner, month.atDay(1)).firstResultOptional();
    }
}
