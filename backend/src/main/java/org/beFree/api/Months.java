package org.beFree.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.beFree.calendar.MonthCycle;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** Parses the yyyy-MM query parameter every screen uses, or falls back to now. */
@ApplicationScoped
public class Months {

    @Inject
    MonthCycle cycle;

    public YearMonth parse(String month) {
        if (month == null || month.isBlank()) {
            // "now" is the period today falls in, which is not the calendar
            // month when the cycle starts later than the 1st
            return cycle.current();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month must look like 2026-09");
        }
    }

    public MonthCycle.Range range(YearMonth month) {
        return cycle.range(month);
    }
}
