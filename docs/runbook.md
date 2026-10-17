# Operations runbook

## Find an unresolved payment

1. Query `GET /api/payments/{id}` with the owning merchant.
2. Read `/api/payments/{id}/audit` and `/ledger`.
3. Query the provider using the same payment ID. Never create a new payment ID to work around a timeout.
4. Trigger `POST /api/ops/reconcile` as ops, or wait for the 15-second scheduled reconciliation.
5. Review `/api/ops/reconciliation-cases`. Amount mismatches require investigation; do not automatically change the ledger to match an unverified report.

## Broker unavailable

`docker compose stop rabbit`, create a payment, then inspect the outbox in PostgreSQL. The payment and outbox remain committed. `docker compose start rabbit` resumes delivery. Publisher failures are logged; scheduled attempts continue. The queue is durable and publication uses confirms. In local development, the application must remain running through this demonstration.

## Worker crashes

Restart the app with `docker compose restart app`. CREATED operations retain their outbox event or broker message. PROCESSING/UNKNOWN operations are recovered by provider lookup. Duplicate broker events are harmless because receipt IDs are unique; ledger posting also has its own unique payment reference.

## Database inspection

```sh
docker compose exec -T postgres psql -U trustpay -d trustpay -c "SELECT status,count(*) FROM payments GROUP BY status"
docker compose exec -T postgres psql -U trustpay -d trustpay -c "SELECT count(*) FROM outbox WHERE NOT published"
docker compose logs --tail=100 app
curl -u ops:ops-demo localhost:8080/actuator/prometheus
```

Health and JVM metrics come from Actuator/Micrometer. Application logs avoid request bodies, identity documents and credentials. Business audit records correlate actions by payment UUID. Custom business metrics, distributed tracing and paging rules are future work.

## Backup and recovery

Use `pg_dump` for demo backups and test restoring into a separate database. Docker named volumes survive `down`; they do not substitute for backups. Real operations require tested point-in-time recovery and agreed recovery objectives.

## Poison messages

Listener failures retry three times with exponential backoff, then route to durable `trustpay.payments.dead`. Inspect the queue in RabbitMQ management and investigate the failure before republishing the original event. Published CREATED operations older than five seconds also enter reconciliation, covering a failed consumer before its receipt transaction committed. Outbox records remain an independent publication audit.

Run `python3 scripts/faults.py` from the repository root to stop/restart RabbitMQ, replay the actual broker event and SIGKILL/restart the application during PROCESSING. This script intentionally interrupts this demo stack.
