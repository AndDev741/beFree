package org.beFree.calendar;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arithmetic of a month that does not start on the 1st. */
@QuarkusTest
@TestSecurity(user = "andre", roles = "owner")
class MonthCycleTest {

    @Inject
    MonthCycle cycle;

    @AfterEach
    void backToTheFirst() {
        for (int m = 1; m <= 12; m++) {
            cycle.clearStartOf(YearMonth.of(2026, m));
        }
        cycle.defaultStartDay(1);
    }

    @Test
    void byDefaultAMonthIsTheCalendarMonth() {
        assertEquals(1, cycle.defaultStartDay());
        var september = cycle.range(YearMonth.of(2026, 9));
        assertEquals(LocalDate.of(2026, 9, 1), september.from());
        assertEquals(LocalDate.of(2026, 10, 1), september.to());
    }

    @Test
    void payDayOnThe25thMakesSeptemberRunFromLateAugust() {
        cycle.defaultStartDay(25);

        var september = cycle.range(YearMonth.of(2026, 9));
        assertEquals(LocalDate.of(2026, 8, 25), september.from());
        assertEquals(LocalDate.of(2026, 9, 25), september.to());

        // The salary paid on 25 August is September's money, which is the point
        assertEquals(YearMonth.of(2026, 9), cycle.monthOf(LocalDate.of(2026, 8, 25)));
        assertEquals(YearMonth.of(2026, 8), cycle.monthOf(LocalDate.of(2026, 8, 24)));
        assertEquals(YearMonth.of(2026, 9), cycle.monthOf(LocalDate.of(2026, 9, 24)));
        assertEquals(YearMonth.of(2026, 10), cycle.monthOf(LocalDate.of(2026, 9, 25)));
    }

    @Test
    void aMonthCanStartOnItsOwnDayWithoutMovingTheOthers() {
        cycle.defaultStartDay(25);
        cycle.startOf(YearMonth.of(2026, 9), 26);

        assertTrue(cycle.startOf(YearMonth.of(2026, 9)).custom());
        assertFalse(cycle.startOf(YearMonth.of(2026, 10)).custom());

        // August keeps its own start and now ends one day later, where September begins
        var august = cycle.range(YearMonth.of(2026, 8));
        assertEquals(LocalDate.of(2026, 7, 25), august.from());
        assertEquals(LocalDate.of(2026, 8, 26), august.to());

        var september = cycle.range(YearMonth.of(2026, 9));
        assertEquals(LocalDate.of(2026, 8, 26), september.from());
        assertEquals(LocalDate.of(2026, 9, 25), september.to());

        // 25 August belongs to August now, where before it opened September
        assertEquals(YearMonth.of(2026, 8), cycle.monthOf(LocalDate.of(2026, 8, 25)));
        assertEquals(YearMonth.of(2026, 9), cycle.monthOf(LocalDate.of(2026, 8, 26)));

        cycle.clearStartOf(YearMonth.of(2026, 9));
        assertEquals(LocalDate.of(2026, 8, 25), cycle.range(YearMonth.of(2026, 9)).from());
    }

    /**
     * The property that matters: a month ends where the next one begins. Not a
     * rule applied twice, but one boundary owned by one month, so no date can
     * fall between two months or belong to both.
     */
    @Test
    void everyMonthEndsExactlyWhereTheNextOneBegins() {
        cycle.defaultStartDay(25);
        cycle.startOf(YearMonth.of(2026, 3), 28);
        cycle.startOf(YearMonth.of(2026, 9), 26);
        cycle.startOf(YearMonth.of(2026, 10), 2);

        for (int m = 1; m <= 11; m++) {
            var earlier = cycle.range(YearMonth.of(2026, m));
            var later = cycle.range(YearMonth.of(2026, m + 1));
            assertEquals(earlier.to(), later.from(), "month " + m + " must end where the next begins");
            assertTrue(earlier.from().isBefore(earlier.to()), "month " + m + " must not be empty");
        }

        // And every single day of the year lands in exactly one month
        for (LocalDate d = LocalDate.of(2026, 2, 1); d.isBefore(LocalDate.of(2026, 11, 1)); d = d.plusDays(1)) {
            var range = cycle.range(cycle.monthOf(d));
            assertFalse(d.isBefore(range.from()), d + " fell before its own month");
            assertTrue(d.isBefore(range.to()), d + " fell after its own month");
        }
    }

    @Test
    void theDayHasToBeOneThatEveryMonthHas() {
        assertThrows(IllegalArgumentException.class, () -> cycle.defaultStartDay(29));
        assertThrows(IllegalArgumentException.class, () -> cycle.defaultStartDay(0));
        assertThrows(IllegalArgumentException.class, () -> cycle.startOf(YearMonth.of(2026, 5), 31));
    }
}
