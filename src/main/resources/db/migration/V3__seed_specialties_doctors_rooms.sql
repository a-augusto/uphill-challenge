INSERT INTO specialty (id, code, name) VALUES
    (1, 'CARDIOLOGY', 'Cardiology'),
    (2, 'DERMATOLOGY', 'Dermatology'),
    (3, 'GENERAL_PRACTICE', 'General Practice'),
    (4, 'PEDIATRICS', 'Pediatrics');

INSERT INTO doctor (id, name, specialty_id, active) VALUES
    (1, 'Dr. Ana Ferreira',   1, TRUE),
    (2, 'Dr. Bruno Costa',    1, TRUE),
    (3, 'Dr. Carla Mendes',   2, TRUE),
    (4, 'Dr. Diogo Pinto',    2, TRUE),
    (5, 'Dr. Elisa Rocha',    3, TRUE),
    (6, 'Dr. Filipe Nunes',   3, TRUE),
    (7, 'Dr. Ines Carvalho',  3, TRUE),
    (8, 'Dr. Joao Silva',     4, TRUE),
    (9, 'Dr. Marta Oliveira', 4, TRUE);

INSERT INTO room (id, name, active) VALUES
    (1, 'Room 1', TRUE),
    (2, 'Room 2', TRUE),
    (3, 'Room 3', TRUE),
    (4, 'Room 4', TRUE),
    (5, 'Room 5', TRUE);

-- Keep identity sequences ahead of the explicit ids inserted above, so any
-- future runtime insert doesn't collide with seeded rows.
ALTER TABLE specialty ALTER COLUMN id RESTART WITH 5;
ALTER TABLE doctor ALTER COLUMN id RESTART WITH 10;
ALTER TABLE room ALTER COLUMN id RESTART WITH 6;
