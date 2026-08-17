-- Patient is now a real entity (see V4/V5) instead of info embedded per
-- booking. No existing appointment rows in this environment to backfill, so
-- the FK can go straight to NOT NULL.
ALTER TABLE appointment
    ADD COLUMN patient_id BIGINT REFERENCES patient (id);

ALTER TABLE appointment
    ALTER COLUMN patient_id SET NOT NULL;

ALTER TABLE appointment DROP COLUMN patient_name;
ALTER TABLE appointment DROP COLUMN patient_email;
ALTER TABLE appointment DROP COLUMN patient_phone;

CREATE INDEX idx_appointment_patient_id ON appointment (patient_id);
