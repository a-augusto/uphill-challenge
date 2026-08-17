CREATE TABLE appointment (
    id             UUID PRIMARY KEY,
    patient_id     BIGINT       NOT NULL REFERENCES patient (id),
    specialty_id   BIGINT       NOT NULL REFERENCES specialty (id),
    doctor_id      BIGINT       NOT NULL REFERENCES doctor (id),
    room_id        BIGINT       NOT NULL REFERENCES room (id),
    starts_at      TIMESTAMPTZ  NOT NULL,
    ends_at        TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'BOOKED',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- No-overbooking guarantee: a doctor or room can only hold one appointment
    -- per exact slot start. Enforced here, not just in application code, so it
    -- holds under concurrent requests racing for the same doctor/room/slot.
    CONSTRAINT uq_doctor_slot UNIQUE (doctor_id, starts_at),
    CONSTRAINT uq_room_slot UNIQUE (room_id, starts_at)
);

CREATE INDEX idx_appointment_patient_id ON appointment (patient_id);
CREATE INDEX idx_appointment_specialty_id ON appointment (specialty_id);
CREATE INDEX idx_appointment_starts_at ON appointment (starts_at);
