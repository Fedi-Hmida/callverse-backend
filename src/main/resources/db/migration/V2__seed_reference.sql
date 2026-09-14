-- =============================================================================
-- CallVerse — reference seed data
--
-- Reference rows the application cannot function without: the skills routing
-- resolves against, the plans contracts point at, the SLA targets the engine
-- measures against, the quality grid, the control strategies an experiment
-- compares, and one test account per role.
--
-- This is reference data, not sample data. No customers, conversations or runs
-- are seeded here: those belong to a scenario fixture, not to the schema.
--
-- Every insert is guarded (ON CONFLICT DO NOTHING, or NOT EXISTS where the
-- table has no unique key). The application sets flyway.baseline-on-migrate,
-- so this migration can meet a Neon database that already holds reference rows,
-- and a duplicate-key failure there would abort the whole deployment.
--
-- Display labels are French because the product is French-facing, following the
-- schema document's own "TECHNICAL / Technique" convention. Codes, and every
-- identifier, stay English.
-- =============================================================================


-- Block 3 — skills --------------------------------------------------------------
INSERT INTO skill (code, label) VALUES
    ('TECHNICAL',  'Technique'),
    ('BILLING',    'Facturation'),
    ('COMMERCIAL', 'Commercial')
ON CONFLICT (code) DO NOTHING;


-- Block 2 — plans ---------------------------------------------------------------
-- One per category, so every PlanCategory value has at least one real row.
INSERT INTO plan (code, name, category, monthly_price, data_gb, speed_mbps, active) VALUES
    ('MOB_ESSENTIAL', 'Mobile Essentiel 20 Go',     'MOBILE', 19.99,   20, NULL,  TRUE),
    ('MOB_PREMIUM',   'Mobile Premium 150 Go',      'MOBILE', 39.99,  150, NULL,  TRUE),
    ('FIB_1G',        'Fibre 1 Gb/s',               'FIBER',  44.99, NULL, 1000,  TRUE),
    ('ADSL_START',    'ADSL Debut',                 'ADSL',   29.99, NULL,   20,  TRUE),
    ('BND_FAMILY',    'Pack Famille Fibre + Mobile','BUNDLE',  59.99,  100, 1000, TRUE)
ON CONFLICT (code) DO NOTHING;


-- Block 6 — SLA policies --------------------------------------------------------
-- One per skill: 80% of conversations answered within 60 seconds.
-- sla_policy has no unique key, so guard with NOT EXISTS rather than ON CONFLICT.
INSERT INTO sla_policy (skill_id, target_seconds, target_ratio, active)
SELECT s.id, 60, 0.800, TRUE
FROM skill s
WHERE NOT EXISTS (
    SELECT 1 FROM sla_policy p WHERE p.skill_id = s.id
);


-- Block 8 — quality criteria ----------------------------------------------------
-- Weights sum to exactly 1.000, which the persistence test asserts. The column is
-- NUMERIC(4,3), so three decimal places is the full available precision.
INSERT INTO quality_criterion (code, label, weight, active) VALUES
    ('RELEVANCE',     'Pertinence de la reponse',        0.200, TRUE),
    ('ACCURACY',      'Exactitude factuelle',            0.250, TRUE),
    ('COMPLIANCE',    'Respect des procedures',          0.150, TRUE),
    ('COMMUNICATION', 'Clarte de la communication',      0.150, TRUE),
    ('EMPATHY',       'Empathie',                        0.100, TRUE),
    ('RESOLUTION',    'Resolution effective du probleme',0.150, TRUE)
ON CONFLICT (code) DO NOTHING;


-- Block 7 — control strategies --------------------------------------------------
-- STATIC_FIFO is the baseline the reinforcement-learning policy must beat. Without
-- a measured baseline in the database, an RL result is a number with nothing to be
-- compared against.
INSERT INTO control_strategy (code, name, kind, params) VALUES
    ('STATIC_FIFO', 'Static FIFO baseline',        'BASELINE',
     '{"description":"First in, first out. No skill preference, no re-balancing."}'),
    ('THRESHOLD',   'Threshold heuristic',         'HEURISTIC',
     '{"queue_length_threshold":10,"wait_threshold_seconds":120}'),
    ('RL_PPO_V1',   'PPO workforce manager v1',    'RL',
     '{"algorithm":"PPO","policy_version":"v1"}')
ON CONFLICT (code) DO NOTHING;


-- Block 1 — test accounts -------------------------------------------------------
-- One per role. BCrypt hashes of the documented development password, which is
-- recorded in the README and deliberately NOT written here: a password in a SQL
-- comment is a password in the repository.
--
-- These accounts exist for local development and demonstration only. Do not seed
-- this migration into an environment reachable from outside.
INSERT INTO app_user (email, password_hash, first_name, last_name, role, active) VALUES
    ('customer@callverse.local',
     '$2a$10$JbvSigp4g8JBL8uAf8TphemdKYTTKGg4te0iaxu8nTRILoumJ7zWe',
     'Test', 'Customer',   'CUSTOMER',   TRUE),
    ('advisor@callverse.local',
     '$2a$10$O4PMyWbBGy1hg0rljmTGSuPZhArtP37.EpTqQtH.D4xufCnGFrdTe',
     'Test', 'Advisor',    'ADVISOR',    TRUE),
    ('supervisor@callverse.local',
     '$2a$10$gy58SD/pxIJZbrxtGb8SDOclMDL/hVNUd/./2AKolX9CKKyAP7BUC',
     'Test', 'Supervisor', 'SUPERVISOR', TRUE),
    ('admin@callverse.local',
     '$2a$10$7G95q20c4zcKpQ4DvMtcC.R83wQDzUtLYpRDJLMwhWasFItCPNiva',
     'Test', 'Admin',      'ADMIN',      TRUE)
ON CONFLICT (email) DO NOTHING;
