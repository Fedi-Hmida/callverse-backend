-- Forward-only extension: a customer removes a conversation from their own space.
-- The bank keeps the conversation, its messages and its metrics; only the customer stops seeing it.
ALTER TABLE conversation ADD COLUMN customer_deleted_at TIMESTAMPTZ;
