CREATE TABLE patient (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id              VARCHAR(50)  NOT NULL,
    name                    VARCHAR(200) NOT NULL,
    email                   VARCHAR(200) NOT NULL,
    phone                   VARCHAR(50),
    gender                  VARCHAR(20)  NOT NULL,
    date_of_birth           DATE         NOT NULL,
    address                 VARCHAR(500),
    emergency_contact_phone VARCHAR(50),

    CONSTRAINT uq_patient_patient_id UNIQUE (patient_id)
);

CREATE TABLE patient_language (
    patient_id BIGINT      NOT NULL REFERENCES patient (id),
    language   VARCHAR(50) NOT NULL
);

CREATE INDEX idx_patient_language_patient_id ON patient_language (patient_id);
