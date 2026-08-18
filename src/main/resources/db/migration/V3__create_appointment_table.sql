CREATE TABLE appointment (
    id             UUID PRIMARY KEY,
    patient_id     BIGINT       NOT NULL REFERENCES patient (id),
    specialty_id   BIGINT       NOT NULL REFERENCES specialty (id),
    doctor_id      BIGINT       NOT NULL REFERENCES doctor (id),
    room_id        BIGINT       NOT NULL REFERENCES room (id),
    starts_at      TIMESTAMPTZ  NOT NULL,
    ends_at        TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'BOOKED',
    cancelled_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- No-overbooking guarantee: a doctor or room can only hold one *active*
-- appointment whose time range overlaps another. Appointments now have
-- variable duration, so an exact-match unique index on starts_at can't
-- express this — two appointments with different starts_at can still
-- overlap (9:00-9:45 and 9:30-10:00). A range-exclusion constraint over
-- tstzrange(starts_at, ends_at) is the general form of the same guarantee;
-- btree_gist lets GiST support equality on doctor_id/room_id alongside the
-- range overlap (&&) operator. tstzrange's default bounds are [) - inclusive
-- start, exclusive end - so back-to-back appointments in the same room
-- (one ending exactly when the next starts) are allowed, not excluded.
-- Filtered to BOOKED rows, same as the old partial indexes, so cancelling
-- an appointment frees its doctor/room/slot for someone else to book.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE appointment
    ADD CONSTRAINT excl_doctor_overlap
    EXCLUDE USING gist (doctor_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (status = 'BOOKED');

ALTER TABLE appointment
    ADD CONSTRAINT excl_room_overlap
    EXCLUDE USING gist (room_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (status = 'BOOKED');

CREATE INDEX idx_appointment_patient_id ON appointment (patient_id);
CREATE INDEX idx_appointment_specialty_id ON appointment (specialty_id);
CREATE INDEX idx_appointment_starts_at ON appointment (starts_at);
