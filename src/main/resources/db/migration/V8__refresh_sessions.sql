-- Opaque refresh tokens rotate on use. Retain consumed hashes to detect replay.
-- The fixed family expiry bounds token history and does not extend on rotation.
CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    issued_role VARCHAR(20) NOT NULL CHECK (issued_role IN ('CUSTOMER','ADVISOR','SUPERVISOR','ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_auth_session_user ON auth_session(user_id);
CREATE TABLE auth_refresh_token (
    token_hash CHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_session(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_auth_refresh_session ON auth_refresh_token(session_id);
