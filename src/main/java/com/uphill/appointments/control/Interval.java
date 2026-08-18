package com.uphill.appointments.control;

import java.time.OffsetDateTime;

/**
 * A half-open time range [start, end) — mirrors the database's tstzrange
 * semantics (#024): touching, non-overlapping ranges are adjacent, not
 * overlapping.
 */
record Interval(OffsetDateTime start, OffsetDateTime end) {
}
