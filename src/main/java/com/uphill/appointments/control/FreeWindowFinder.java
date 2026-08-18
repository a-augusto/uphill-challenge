package com.uphill.appointments.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure interval arithmetic backing the day-only booking search (#023, #025):
 * given a bound and a set of busy intervals within it, find the free gaps;
 * given two free-interval lists (typically a doctor's and a room's), find
 * where they overlap. No I/O, no dependencies — deliberately kept separate
 * from {@link BookingService} so the interval math is testable on its own.
 */
final class FreeWindowFinder {

    private FreeWindowFinder() {
    }

    static List<Interval> freeWindows(Interval bound, List<Interval> busy) {
        List<Interval> clipped = busy.stream()
                .map(b -> new Interval(
                        b.start().isAfter(bound.start()) ? b.start() : bound.start(),
                        b.end().isBefore(bound.end()) ? b.end() : bound.end()))
                .filter(b -> b.start().isBefore(b.end()))
                .sorted(Comparator.comparing(Interval::start))
                .toList();

        List<Interval> free = new ArrayList<>();
        var cursor = bound.start();
        for (Interval b : clipped) {
            if (b.start().isAfter(cursor)) {
                free.add(new Interval(cursor, b.start()));
            }
            if (b.end().isAfter(cursor)) {
                cursor = b.end();
            }
        }
        if (cursor.isBefore(bound.end())) {
            free.add(new Interval(cursor, bound.end()));
        }
        return free;
    }

    static List<Interval> intersect(List<Interval> a, List<Interval> b) {
        List<Interval> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Interval x = a.get(i);
            Interval y = b.get(j);
            var start = x.start().isAfter(y.start()) ? x.start() : y.start();
            var end = x.end().isBefore(y.end()) ? x.end() : y.end();
            if (start.isBefore(end)) {
                result.add(new Interval(start, end));
            }
            if (x.end().isBefore(y.end())) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }
}
