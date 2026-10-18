-- Forward-only extension: existing ticket and credit rows are preserved.
CREATE TABLE ticket_comment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    author_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    author_role VARCHAR(20) NOT NULL CHECK (author_role IN ('CUSTOMER','ADVISOR','SUPERVISOR','ADMIN')),
    content VARCHAR(4000) NOT NULL CHECK (length(trim(content)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ticket_comment_ticket ON ticket_comment(ticket_id, created_at, id);

ALTER TABLE commercial_credit ADD COLUMN account_id UUID REFERENCES account(id);
ALTER TABLE commercial_credit ADD COLUMN transaction_id UUID REFERENCES bank_transaction(id);
ALTER TABLE commercial_credit ADD COLUMN request_id UUID;
CREATE UNIQUE INDEX uq_commercial_credit_request ON commercial_credit(conversation_id, request_id)
    WHERE request_id IS NOT NULL;
