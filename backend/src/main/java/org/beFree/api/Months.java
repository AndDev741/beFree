package org.beFree.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Parses the yyyy-MM query parameter every screen uses, or falls back to now. */
@ApplicationScoped
public class Months {

    @ConfigProperty(name = "befree.zone", defaultValue = "Europe/Lisbon")
    String zone;

    public YearMonth parse(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(ZoneId.of(zone));
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month must look like 2026-09");
        }
    }
}
