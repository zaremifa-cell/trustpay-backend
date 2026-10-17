CREATE TABLE provider_records(payment_id UUID PRIMARY KEY,payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX provider_records_created_idx ON provider_records(created_at);
