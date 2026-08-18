package com.uphill.appointments.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class FreeWindowFinderTest {

    private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 8, 24, 9, 0, 0, 0, ZoneOffset.UTC);

    private static OffsetDateTime at(int hour, int minute) {
        return BASE.withHour(hour).withMinute(minute);
    }

    @Test
    void wholeBoundIsFreeWhenNoBusyIntervals() {
        Interval bound = new Interval(at(9, 0), at(18, 0));

        List<Interval> free = FreeWindowFinder.freeWindows(bound, List.of());

        assertThat(free).containsExactly(bound);
    }

    @Test
    void noFreeTimeWhenFullyBusy() {
        Interval bound = new Interval(at(9, 0), at(18, 0));
        Interval busy = new Interval(at(9, 0), at(18, 0));

        List<Interval> free = FreeWindowFinder.freeWindows(bound, List.of(busy));

        assertThat(free).isEmpty();
    }

    @Test
    void findsGapBetweenTwoBusyIntervals() {
        Interval bound = new Interval(at(9, 0), at(18, 0));
        Interval morning = new Interval(at(9, 0), at(11, 0));
        Interval afternoon = new Interval(at(14, 0), at(18, 0));

        List<Interval> free = FreeWindowFinder.freeWindows(bound, List.of(morning, afternoon));

        assertThat(free).containsExactly(new Interval(at(11, 0), at(14, 0)));
    }

    @Test
    void clipsBusyIntervalPartiallyOutsideBound() {
        Interval bound = new Interval(at(9, 0), at(18, 0));
        Interval busy = new Interval(at(7, 0), at(10, 0));

        List<Interval> free = FreeWindowFinder.freeWindows(bound, List.of(busy));

        assertThat(free).containsExactly(new Interval(at(10, 0), at(18, 0)));
    }

    @Test
    void backToBackBusyIntervalsLeaveNoGapBetweenThem() {
        Interval bound = new Interval(at(9, 0), at(18, 0));
        Interval first = new Interval(at(9, 0), at(11, 0));
        Interval second = new Interval(at(11, 0), at(13, 0));

        List<Interval> free = FreeWindowFinder.freeWindows(bound, List.of(second, first));

        assertThat(free).containsExactly(new Interval(at(13, 0), at(18, 0)));
    }

    @Test
    void intersectOfDisjointFreeListsIsEmpty() {
        List<Interval> a = List.of(new Interval(at(9, 0), at(11, 0)));
        List<Interval> b = List.of(new Interval(at(14, 0), at(18, 0)));

        assertThat(FreeWindowFinder.intersect(a, b)).isEmpty();
    }

    @Test
    void intersectProducesNarrowerOverlapThanEitherInput() {
        List<Interval> doctorFree = List.of(new Interval(at(9, 0), at(13, 0)));
        List<Interval> roomFree = List.of(new Interval(at(11, 0), at(18, 0)));

        List<Interval> overlap = FreeWindowFinder.intersect(doctorFree, roomFree);

        assertThat(overlap).containsExactly(new Interval(at(11, 0), at(13, 0)));
    }
}
