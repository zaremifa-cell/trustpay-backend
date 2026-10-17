-- Synthetic, non-financial fixtures. Re-running is safe.
INSERT INTO customers(id,merchant,identity_status) VALUES ('00000000-0000-0000-0000-000000000001','benchmark','VERIFIED') ON CONFLICT DO NOTHING;
INSERT INTO payments(id,merchant,customer_id,idempotency_key,amount,currency,status,created_at)
SELECT gen_random_uuid(),'benchmark','00000000-0000-0000-0000-000000000001','bench-'||n,100,'EUR','FAILED',now()-n*interval '1 second' FROM generate_series(1,100000) n ON CONFLICT DO NOTHING;
ANALYZE payments;
-- Compare the same data with/without the listing index. ROLLBACK restores it.
BEGIN;
DROP INDEX payments_merchant_created_idx;
EXPLAIN (ANALYZE,BUFFERS) SELECT id,amount,currency,status FROM payments WHERE merchant='benchmark' ORDER BY created_at DESC,id LIMIT 20;
CREATE INDEX payments_merchant_created_idx ON payments(merchant,created_at DESC,id);
EXPLAIN (ANALYZE,BUFFERS) SELECT id,amount,currency,status FROM payments WHERE merchant='benchmark' ORDER BY created_at DESC,id LIMIT 20;
ROLLBACK;
