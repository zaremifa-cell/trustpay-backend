-- Reconciliation cases become reviewable work items: an operator resolves each one, optionally
-- after asking the AI assistant for a summary. Detection still inserts (payment_id, reason) with
-- plain SQL; everything below has defaults so that path is unchanged.
ALTER TABLE reconciliation_cases
  ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
  ADD COLUMN resolution VARCHAR(40) CHECK (resolution IN ('ACCEPT_PROVIDER_RESULT', 'KEEP_LEDGER', 'ESCALATE')),
  ADD COLUMN resolution_note VARCHAR(500),
  ADD COLUMN resolved_by VARCHAR(100),
  ADD COLUMN resolved_at TIMESTAMPTZ,
  ADD COLUMN assistant_summary VARCHAR(1000),
  ADD COLUMN assistant_suggestion VARCHAR(40),
  ADD COLUMN assistant_source VARCHAR(60),
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
  -- A resolved case always records what was decided, by whom and when.
  ADD CONSTRAINT resolved_cases_are_complete CHECK (
    status = 'OPEN' OR (resolution IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL));

CREATE INDEX reconciliation_cases_open_idx ON reconciliation_cases (created_at) WHERE status = 'OPEN';
