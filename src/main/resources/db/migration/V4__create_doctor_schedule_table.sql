CREATE TABLE doctor_schedule (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doctor_id   BIGINT      NOT NULL REFERENCES doctor (id),
    day_of_week VARCHAR(10) NOT NULL,
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    CONSTRAINT uq_doctor_schedule_day UNIQUE (doctor_id, day_of_week),
    CONSTRAINT chk_doctor_schedule_time_order CHECK (start_time < end_time)
);

CREATE INDEX idx_doctor_schedule_doctor_id ON doctor_schedule (doctor_id);
