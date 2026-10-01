-- =============================================================================
-- V4 - rotate the password of the four development accounts
-- =============================================================================
-- V2 seeded one account per role with a shared development password. V2 is applied
-- on Neon and cannot be edited (Flyway checksums), so the rotation is a forward
-- migration. The plaintext is documented in README.md, not here: a password in a
-- comment is a password in the schema. Only these four rows change; any other
-- account keeps its own password.
--
-- These accounts are for local development and demonstration only.
-- =============================================================================

UPDATE app_user SET password_hash = '$2a$10$GqFY9Z5rI0EeF8XW5/ChuOfb.bxZNa9YrEFUI2u8On4PLsIxL.iPu'
 WHERE email = 'customer@callverse.local';

UPDATE app_user SET password_hash = '$2a$10$N3tcWsNh3ucQfcRXpPrG4uLfAaGONcqM2RDk962Z8.KVv2sEvFjLe'
 WHERE email = 'advisor@callverse.local';

UPDATE app_user SET password_hash = '$2a$10$4rgnvMWi3KLSQ.X/j6HUWu0St6lVmGGFV5of06fCkC9gV2vicDi3q'
 WHERE email = 'supervisor@callverse.local';

UPDATE app_user SET password_hash = '$2a$10$7mgxuxWoo97ojfQmaQ75qOc3TPAIbhOo5/OAleXe2M83cW3Fu9DL2'
 WHERE email = 'admin@callverse.local';
