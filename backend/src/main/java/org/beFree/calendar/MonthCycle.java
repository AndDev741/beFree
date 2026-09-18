package org.beFree.calendar;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.beFree.auth.CurrentUser;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Which days a month covers. Someone paid on the 25th lives a month that runs
 * from the 25th to the 24th, and calling that period "September" is what makes
 * the salary land in the month it is meant to pay for.
 *
 * A month is bounded by where it starts and where the next one starts, so
 * months stay back to back whatever days are chosen: no gap a transaction can
 * fall into, no overlap that counts one twice.
 *
 * Every screen and every tool asks here. A summary over one window and a
 * budget over another would be worse than no setting at all.
 */
@ApplicationScoped
public class MonthCycle {

    /** Above 28 a month would skip February, so the choice stops there. */
    public static final int MAX_START_DAY = 28;

    @Inject
    CurrentUser currentUser;

    @ConfigProperty(name = "befree.zone", defaultValue = "Europe/Lisbon")
    String zone;

    /** Half-open: `from` counts, `to` does not. */
    public record Range(LocalDate from, LocalDate to) {
    }

    /** What day a month begins on, and whether that is its own choice or the default. */
    public record Start(int day, boolean custom) {
    }

    public int defaultStartDay() {
        return read(() -> AppSettings.of(currentUser.name()).map(s -> s.monthStartDay).orElse(1));
    }

    public Start startOf(YearMonth month) {
        return read(() -> MonthStart.of(currentUser.name(), month)
                .map(m -> new Start(m.startDay, true))
                .orElseGet(() -> new Start(AppSettings.of(currentUser.name())
                        .map(s -> s.monthStartDay).orElse(1), false)));
    }

    @Transactional
    public void defaultStartDay(int day) {
        check(day);
        AppSettings settings = AppSettings.of(currentUser.name()).orElseGet(() -> {
            AppSettings fresh = new AppSettings();
            fresh.owner = currentUser.name();
            return fresh;
        });
        settings.monthStartDay = day;
        settings.persist();
    }

    @Transactional
    public void startOf(YearMonth month, int day) {
        check(day);
        MonthStart start = MonthStart.of(currentUser.name(), month).orElseGet(() -> {
            MonthStart fresh = new MonthStart();
            fresh.owner = currentUser.name();
            fresh.period = month.atDay(1);
            return fresh;
        });
        start.startDay = day;
        start.persist();
    }

    /** Drops the exception; the month goes back to the default. */
    @Transactional
    public boolean clearStartOf(YearMonth month) {
        return MonthStart.delete("owner = ?1 and period = ?2", currentUser.name(), month.atDay(1)) > 0;
    }

    public LocalDate today() {
        return LocalDate.now(ZoneId.of(zone));
    }

    /** The first day of a labelled month, which for a cycle past the 1st lies in the month before. */
    public LocalDate firstDayOf(YearMonth month) {
        int day = startOf(month).day();
        return day <= 1 ? month.atDay(1) : month.minusMonths(1).atDay(day);
    }

    /** A month runs until the next one starts, which is what keeps them contiguous. */
    public Range range(YearMonth month) {
        return new Range(firstDayOf(month), firstDayOf(month.plusMonths(1)));
    }

    /**
     * The period a date falls in. On the 26th with a cycle starting on the
     * 25th, you are already living next month.
     */
    public YearMonth monthOf(LocalDate date) {
        YearMonth calendar = YearMonth.from(date);
        YearMonth next = calendar.plusMonths(1);
        return !date.isBefore(firstDayOf(next)) ? next : calendar;
    }

    public YearMonth current() {
        return monthOf(today());
    }

    private static void check(int day) {
        if (day < 1 || day > MAX_START_DAY) {
            throw new IllegalArgumentException("the month can start on day 1 to " + MAX_START_DAY);
        }
    }

    /** Reads happen inside whatever transaction the caller already has, or a fresh one. */
    private static <T> T read(java.util.function.Supplier<T> query) {
        return QuarkusTransaction.joiningExisting().call(query::get);
    }
}
