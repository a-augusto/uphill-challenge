-- Hand-seeded for tests and manual demo use. A proper mock-data generator
-- (many more patients, realistic distribution) is a separate future task.
INSERT INTO patient (id, patient_id, name, email, phone, gender, date_of_birth, address, emergency_contact_phone) VALUES
    (1, 'PAT-0001', 'Maria Silva',   'maria.silva@example.com',   '+351912000001', 'FEMALE',      '1985-03-14', 'Rua das Flores 12, Lisboa',       '+351912900001'),
    (2, 'PAT-0002', 'Joao Pereira',  'joao.pereira@example.com',  '+351912000002', 'MALE',        '1990-07-22', 'Avenida da Liberdade 45, Lisboa', '+351912900002'),
    (3, 'PAT-0003', 'Ines Costa',    'ines.costa@example.com',    '+351912000003', 'FEMALE',      '1978-11-02', 'Rua do Comercio 8, Porto',        '+351912900003'),
    (4, 'PAT-0004', 'Bruno Alves',   'bruno.alves@example.com',   '+351912000004', 'MALE',        '2001-01-30', 'Praca da Republica 3, Braga',     '+351912900004'),
    (5, 'PAT-0005', 'Sofia Martins', 'sofia.martins@example.com', '+351912000005', 'UNSPECIFIED', '1995-09-17', 'Rua Nova 21, Coimbra',            '+351912900005');

INSERT INTO patient_language (patient_id, language) VALUES
    (1, 'pt'), (1, 'en'),
    (2, 'pt'),
    (3, 'pt'), (3, 'fr'),
    (4, 'pt'), (4, 'en'), (4, 'es'),
    (5, 'pt');

ALTER TABLE patient ALTER COLUMN id RESTART WITH 6;
