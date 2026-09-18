package org.beFree.api;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.beFree.calendar.MonthCycle;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * The cycle: one default, and an exception for any month whose pay date
 * slipped. It answers with the days the month covers as well as the number,
 * because "the month starts on the 25th" is easy to agree to and hard to
 * picture until you see 25 Aug to 24 Sep written down.
 */
@Path("/api/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {

    @Inject
    MonthCycle cycle;

    @Inject
    Months months;

    @GET
    public Dto.Settings get(@QueryParam("month") String month) {
        return describe(months.parse(month));
    }

    /** Without a month this is the default; with one it is that month's exception. */
    @PUT
    @Transactional
    public Dto.Settings set(Dto.SettingsRequest req, @QueryParam("month") String month) {
        int day = day(req);
        try {
            if (month == null || month.isBlank()) {
                cycle.defaultStartDay(day);
            } else {
                cycle.startOf(parse(month), day);
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        return describe(months.parse(month));
    }

    @DELETE
    @jakarta.ws.rs.Path("/months/{month}")
    @Transactional
    public Dto.Settings clear(@PathParam("month") String month) {
        YearMonth ym = parse(month);
        cycle.clearStartOf(ym);
        return describe(ym);
    }

    private static int day(Dto.SettingsRequest req) {
        if (req == null || req.monthStartDay() == null) {
            throw new BadRequestException("monthStartDay is required");
        }
        return req.monthStartDay();
    }

    private static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month must look like 2026-09");
        }
    }

    private Dto.Settings describe(YearMonth ym) {
        MonthCycle.Range range = cycle.range(ym);
        MonthCycle.Start start = cycle.startOf(ym);
        return new Dto.Settings(cycle.defaultStartDay(), start.day(), start.custom(),
                ym.toString(), range.from(), range.to().minusDays(1));
    }
}
