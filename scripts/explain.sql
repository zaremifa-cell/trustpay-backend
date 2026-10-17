EXPLAIN (ANALYZE, BUFFERS) SELECT id,amount,currency,status FROM payments WHERE merchant='merchant' ORDER BY created_at DESC,id LIMIT 20;
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM payments WHERE status IN ('PROCESSING','UNKNOWN') AND updated_at < now()-interval '5 seconds' ORDER BY updated_at LIMIT 100;
EXPLAIN (ANALYZE, BUFFERS) SELECT id,payment_id FROM outbox WHERE published=false ORDER BY created_at LIMIT 20;
