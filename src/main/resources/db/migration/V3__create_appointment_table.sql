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
-- appointment per exact slot start. Partial (not plain) unique indexes, filtered
-- to BOOKED rows, so cancelling an appointment frees its doctor/room/slot for
-- someone else to book — a plain UNIQUE constraint can't express that.
CREATE UNIQUE INDEX uq_doctor_slot ON appointment (doctor_id, starts_at) WHERE status = 'BOOKED';
CREATE UNIQUE INDEX uq_room_slot ON appointment (room_id, starts_at) WHERE status = 'BOOKED';

CREATE INDEX idx_appointment_patient_id ON appointment (patient_id);
CREATE INDEX idx_appointment_specialty_id ON appointment (specialty_id);
CREATE INDEX idx_appointment_starts_at ON appointment (starts_at);
