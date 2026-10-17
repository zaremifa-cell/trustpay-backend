# Verification — 17 October 2026

## Executed successfully

* Maven compilation and packaging targeting Java 21.
* 13 Spring Boot / PostgreSQL Testcontainers tests: zero failures, errors or skipped tests. Includes 20 concurrent idempotent requests, concurrent settlement, balanced ledger, identity rejection, request conflict, merchant ownership, role restrictions, JWT issuance/tampering/expiry and conflicting provider outcomes.
* Full Docker Compose stack: Java application, PostgreSQL, RabbitMQ and persistent Python provider simulator.
* Live REST demo: identity gating, 20 concurrent requests, one payment, changed payload 409, cross-merchant 404, anonymous 401, merchant/ops separation, bearer token use, callback authentication/deduplication, provider decline and timeout recovery.
* Fault injection: stopped RabbitMQ, verified pending outbox, restarted RabbitMQ and completed the original payment; replayed the actual broker event twice with one ledger posting; SIGKILLed the application during PROCESSING and recovered the same payment after restart.
* Generated OpenAPI exposes 11 paths and Basic/Bearer authorization schemes.

Raw outputs: [integration tests](../evidence/integration-tests.txt), [live demo](../evidence/live-demo.txt), [fault tests](../evidence/fault-tests.txt).

## SQL experiment

PostgreSQL 17 on Colima (4 vCPU, 6 GiB VM), Mac Apple M1 with 16 GiB host RAM. 100,000 synthetic FAILED payments for a benchmark merchant, no financial postings.

The same listing query returned 20 rows ordered by timestamp and ID. Within a transaction, the listing index was dropped, measured, recreated, measured, and the schema change rolled back:

| Plan | Execution time |
|---|---:|
| Parallel sequential scan plus sort | 43.697 ms |
| Merchant/timestamp index scan | 0.054 ms |

These are individual local observations, affected by cache warmth, not a general performance guarantee. Source and full plans: [benchmark SQL](../scripts/sql-benchmark.sql), [captured EXPLAIN ANALYZE](../evidence/sql-benchmark.txt).

## HTTP and JVM experiment

A local Java 25 process ran the Java-21-targeted artifact on port 8082 against the same PostgreSQL container. Background jobs were disabled in this extra profiling process; the main Docker application remained on 8080. The extra process was stopped after recording.

After 50 warm-up requests, 1,000 JWT-authenticated listing requests at concurrency 16 completed in 1.075 seconds. Observed p50 13.657 ms, p95 37.765 ms, p99 51.964 ms. This is a short read-only local workload using Python urllib with fresh connections. The authenticated merchant had a small history; the 100,000 benchmark records belonged to a separate tenant. It is not payment-processing throughput or a production capacity estimate. See [raw measurement](../evidence/http-benchmark.json).

A 25-second JFR recording spans token acquisition and this workload. It contains three G1 collection events and no recorded JavaMonitorEnter contention events. The first and longest observed GC pause was about 14.46 ms. BCrypt key expansion had the most top-frame execution samples (14), consistent with password verification during token acquisition; steady-state requests used JWTs. The short recording and small number of samples do not establish a steady-state CPU bottleneck or absence of contention in longer runs.

The raw JFR recording is retained locally. Public artifacts: [JFR summary](../evidence/jfr-summary.txt), [measurement script](../scripts/benchmark.py).

## Boundaries

Identity review and the payment provider are simulated. Authentication has local demo accounts and a configurable symmetric JWT key. No real financial credentials, identity documents or payments are used. Reconciliation report coverage is bounded to 1,000 recent provider records per run; production-scale pagination and external identity integrations remain outside this demo. The supplied GitHub Actions workflow has not been executed remotely.
