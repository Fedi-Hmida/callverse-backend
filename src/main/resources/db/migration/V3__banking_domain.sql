-- =============================================================================
-- V3 - the platform becomes a retail bank
-- =============================================================================
-- Decision of 2026-09-30: CallVerse is dedicated to retail banking and no longer
-- models a telecom operator. The customer-relation core (conversations, advisors,
-- skills, tickets, escalations, knowledge base, SLA, experiments, quality) is
-- domain-neutral and stays. What only made sense for a telecom operator leaves:
-- tariff plans, subscriptions, monthly invoices and network incidents.
--
-- Forward-only, on purpose. V1 and V2 are already applied on Neon; editing them
-- changes their checksum and Flyway then refuses to start.
--
-- DATA LOSS. Dropping plan, contract, invoice and network_incident destroys every
-- row in them. Only test data is known to exist; the schema owner confirms that
-- before applying this migration to Neon.
--
-- Safe to run against a hand-provisioned database: IF EXISTS / IF NOT EXISTS where
-- PostgreSQL allows it, drops ordered by foreign key with no CASCADE (CASCADE can
-- silently drop constraints this file does not name), idempotent seeds.
-- =============================================================================


-- --- 1. Telecom-only tables ---------------------------------------------------
-- Order follows the foreign keys: invoice -> contract -> plan.

DROP TABLE IF EXISTS invoice;
DROP TABLE IF EXISTS contract;
DROP TABLE IF EXISTS plan;
DROP TABLE IF EXISTS network_incident;


-- --- 2. Customer ---------------------------------------------------------------
-- The network zone becomes the customer's region: banking outages and branch
-- networks are regional too, and the column keeps its NOT NULL and its index.
-- segment is the "client value" factor of the queue priority score.

-- Guarded, because PostgreSQL has no RENAME COLUMN IF EXISTS: a schema owner who
-- applied this by hand before Flyway runs it must not see the second run fail.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'customer' AND column_name = 'zone') THEN
        ALTER TABLE customer RENAME COLUMN zone TO region;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_customer_zone' AND relkind = 'i')
       AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_customer_region') THEN
        ALTER INDEX idx_customer_zone RENAME TO idx_customer_region;
    END IF;
END $$;

ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS segment VARCHAR(20) NOT NULL DEFAULT 'MASS'
        CHECK (segment IN ('MASS','AFFLUENT','PRIVATE','PROFESSIONAL'));


-- --- 3. Product catalogue --------------------------------------------------------
-- Reference data. Withdrawn products stay: open accounts still reference them.

CREATE TABLE IF NOT EXISTS banking_product (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code           VARCHAR(40)  NOT NULL UNIQUE,
    name           VARCHAR(120) NOT NULL,
    category       VARCHAR(20)  NOT NULL
                   CHECK (category IN ('CURRENT_ACCOUNT','SAVINGS','CONSUMER_LOAN','MORTGAGE')),
    monthly_fee    NUMERIC(8,2) NOT NULL DEFAULT 0 CHECK (monthly_fee >= 0),
    interest_rate  NUMERIC(6,4) CHECK (interest_rate >= 0),   -- annual, as a fraction; NULL = none
    active         BOOLEAN      NOT NULL DEFAULT TRUE
);


-- --- 4. Accounts ---------------------------------------------------------------
-- A loan is an account too, on a loan product, carrying its outstanding balance.

CREATE TABLE IF NOT EXISTS account (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id      UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    product_id       UUID NOT NULL REFERENCES banking_product(id),
    iban             VARCHAR(34)   NOT NULL UNIQUE,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
    balance          NUMERIC(14,2) NOT NULL DEFAULT 0,
    overdraft_limit  NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (overdraft_limit >= 0),
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    opened_at        DATE NOT NULL,
    closed_at        DATE,
    CONSTRAINT chk_account_dates CHECK (closed_at IS NULL OR closed_at >= opened_at)
);
CREATE INDEX IF NOT EXISTS idx_account_customer ON account(customer_id);


-- --- 5. Cards ------------------------------------------------------------------
-- PCI-DSS: the full card number (PAN), the CVV and the PIN are never stored.
-- pan_last4 is all a customer or an advisor needs to identify a card.

CREATE TABLE IF NOT EXISTS card (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    pan_last4     VARCHAR(4)    NOT NULL CHECK (pan_last4 ~ '^[0-9]{4}$'),
    network       VARCHAR(20)   NOT NULL CHECK (network IN ('VISA','MASTERCARD')),
    type          VARCHAR(10)   NOT NULL CHECK (type IN ('DEBIT','CREDIT')),
    status        VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE','BLOCKED','EXPIRED','CANCELLED')),
    expires_on    DATE          NOT NULL,
    daily_limit   NUMERIC(10,2) NOT NULL CHECK (daily_limit > 0),
    blocked_at    TIMESTAMPTZ,
    block_reason  VARCHAR(30)
                  CHECK (block_reason IN ('LOST','STOLEN','FRAUD_SUSPECTED','CUSTOMER_REQUEST')),
    -- A blocked card always says when and why: that is what a fraud or loss call is about.
    CONSTRAINT chk_card_blocked
        CHECK (status <> 'BLOCKED' OR (blocked_at IS NOT NULL AND block_reason IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_card_account ON card(account_id);


-- --- 6. Transactions -----------------------------------------------------------
-- amount is signed: a negative value debits the account.

CREATE TABLE IF NOT EXISTS bank_transaction (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    card_id       UUID REFERENCES card(id) ON DELETE SET NULL,
    type          VARCHAR(20)   NOT NULL
                  CHECK (type IN ('CARD_PAYMENT','ATM_WITHDRAWAL','TRANSFER_IN','TRANSFER_OUT',
                                  'DIRECT_DEBIT','FEE','INTEREST','REFUND')),
    amount        NUMERIC(14,2) NOT NULL CHECK (amount <> 0),
    currency      VARCHAR(3)    NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
    label         VARCHAR(140)  NOT NULL,
    counterparty  VARCHAR(140),
    status        VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING','BOOKED','REJECTED','DISPUTED')),
    booked_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_txn_account_booked ON bank_transaction(account_id, booked_at DESC);


-- --- 7. Service incidents --------------------------------------------------------
-- Replaces network_incident: an outage of a banking service, optionally regional.
-- run_id mirrors conversation.run_id: NULL in live mode, no foreign key.

CREATE TABLE IF NOT EXISTS service_incident (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service         VARCHAR(30) NOT NULL
                    CHECK (service IN ('CARD_PAYMENTS','ONLINE_BANKING','MOBILE_APP',
                                       'ATM_NETWORK','TRANSFERS')),
    region          VARCHAR(40),                   -- NULL: affects every region
    severity        SMALLINT NOT NULL CHECK (severity BETWEEN 1 AND 5),
    description     VARCHAR(255),
    started_at      TIMESTAMPTZ NOT NULL,
    estimated_end   TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    run_id          UUID,
    affected_count  INT CHECK (affected_count >= 0),
    CONSTRAINT chk_incident_dates CHECK (resolved_at IS NULL OR resolved_at >= started_at)
);
-- Every lookup filters unresolved incidents by region (or asks for all of them);
-- none filters by service, so the partial index leads with region.
CREATE INDEX IF NOT EXISTS idx_service_incident_active
    ON service_incident(region) WHERE resolved_at IS NULL;


-- --- 8. Reference data -----------------------------------------------------------
-- Skills are re-coded in place rather than deleted, so every foreign key that
-- already points at them (advisor_skill, sla_policy, routing_rule, conversation,
-- metric_sample) keeps pointing at the right row.

-- Each re-code is skipped if its target code already exists, so a database where
-- someone already added the banking skill does not hit the UNIQUE constraint.
UPDATE skill SET code = 'ACCOUNTS', label = 'Comptes et paiements'
 WHERE code = 'BILLING'    AND NOT EXISTS (SELECT 1 FROM skill WHERE code = 'ACCOUNTS');
UPDATE skill SET code = 'CARDS',    label = 'Cartes'
 WHERE code = 'TECHNICAL'  AND NOT EXISTS (SELECT 1 FROM skill WHERE code = 'CARDS');
UPDATE skill SET code = 'CREDIT',   label = 'Credit'
 WHERE code = 'COMMERCIAL' AND NOT EXISTS (SELECT 1 FROM skill WHERE code = 'CREDIT');

INSERT INTO skill (code, label) VALUES ('FRAUD', 'Fraude et securite')
ON CONFLICT (code) DO NOTHING;

-- Fraud is time-critical: a compromised card keeps being used while the customer
-- waits. Its target is stricter than the 60 s / 80 % the other skills carry.
INSERT INTO sla_policy (skill_id, target_seconds, target_ratio, active)
SELECT s.id, 30, 0.900, TRUE
FROM skill s
WHERE s.code = 'FRAUD'
  AND NOT EXISTS (SELECT 1 FROM sla_policy p WHERE p.skill_id = s.id);

-- Intents carry no CHECK constraint, so old telecom values would survive and then
-- fail to load into the banking Intent enum. They become OTHER.
UPDATE conversation SET intent = 'OTHER'
 WHERE intent IN ('BILLING','TECHNICAL','COMMERCIAL','CHURN');
UPDATE routing_rule SET intent = 'OTHER'
 WHERE intent IN ('BILLING','TECHNICAL','COMMERCIAL','CHURN');

INSERT INTO banking_product (code, name, category, monthly_fee, interest_rate, active) VALUES
    ('CUR_ESSENTIAL', 'Compte courant Essentiel', 'CURRENT_ACCOUNT', 2.00, NULL,   TRUE),
    ('CUR_PREMIUM',   'Compte courant Premium',   'CURRENT_ACCOUNT', 9.90, NULL,   TRUE),
    ('SAV_LIVRET',    'Livret epargne',           'SAVINGS',         0.00, 0.0300, TRUE),
    ('LOAN_PERSONAL', 'Pret personnel',           'CONSUMER_LOAN',   0.00, 0.0590, TRUE),
    ('MORTGAGE_20Y',  'Pret immobilier 20 ans',   'MORTGAGE',        0.00, 0.0350, TRUE)
ON CONFLICT (code) DO NOTHING;
