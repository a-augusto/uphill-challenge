CREATE TABLE specialty (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(50)  NOT NULL,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT uq_specialty_code UNIQUE (code)
);

CREATE TABLE doctor (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    specialty_id BIGINT       NOT NULL REFERENCES specialty (id),
    active       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_doctor_specialty_id ON doctor (specialty_id);

CREATE TABLE room (
    id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name   VARCHAR(100) NOT NULL,
    active BOOLEAN      NOT NULL DEFAULT TRUE
);
