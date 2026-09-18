package org.beFree.calendar;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.Optional;

/** One row per owner. Created on first read, so there is no seeding step. */
@Entity
@Table(name = "app_settings")
public class AppSettings extends PanacheEntity {

    @Column(nullable = false, unique = true, length = 64)
    public String owner;

    @Column(name = "month_start_day", nullable = false)
    public int monthStartDay = 1;

    public static Optional<AppSettings> of(String owner) {
        return find("owner", owner).firstResultOptional();
    }
}
