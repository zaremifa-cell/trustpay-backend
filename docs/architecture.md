# Architecture decisions

## One deployable, explicit boundaries

The API, payment domain and reconciliation share a database and deployable. This makes payment/ledger consistency a local transaction. Split services only when independent scaling, team ownership or isolation justifies distributed coordination. The provider is a separate process to make network failures real.

## Idempotency under concurrency

The key is scoped to the authenticated merchant. `pg_advisory_xact_lock(hashtextextended(merchant + ':' + key, 0))` serializes matching keys across application instances. Hash collisions can serialize unrelated work but do not violate correctness. A database unique constraint guards against alternative writers. Compare customer, amount and currency before returning an existing operation. Replays remain possible after identity changes because they retrieve the original operation rather than authorize new movement.

## Financial model

One row represents the complete balanced pair of entries for a successful payment: debit provider clearing, credit merchant payable. Database checks enforce positive, equal amounts; one currency belongs to the whole transaction. The unique payment reference prevents duplicate posting. This intentionally limited ledger does not claim a general-purpose accounting engine. Extending to fees/refunds requires immutable additional transaction types and conservation checks across all lines.

## Delivery and recovery

The outbox is selected using `FOR UPDATE SKIP LOCKED`. Publisher waits for broker confirmation before marking an event published. Holding up to 20 row locks during network calls favors simple correctness for this prototype; larger deployments need short lease-based batches and bounded publisher concurrency.

Consumer deduplication and PROCESSING commit before I/O. An exception or process crash after the commit leaves work recoverable by the reconciliation job. Reconciliation can overlap workers: both use the same provider payment ID, and settlement is serialized by the payment row lock. Provider lookup returning 404 permits resubmission with the original payment ID. A provider without reliable idempotency would require a different recovery policy.

## Security choices

Merchant identity comes from the authenticated principal, never a request merchant field. Operations endpoints require an independent role. Shared-secret provider callbacks have no financial effect when duplicated; mismatched values create review cases. JWTs carry subject and role claims, are validated for issuer and expiration, and expire after 15 minutes. The local symmetric signing key is configurable. Basic authentication supports the demo token exchange; both flows need TLS outside loopback. External OIDC, timestamped signatures and credential rotation are explicit production follow-ups.

## State transitions

CREATED is durably queued. PROCESSING means the worker claimed the operation. UNKNOWN means the response did not establish a final outcome. SUCCEEDED and FAILED are terminal. Both terminal states can also arrive via an authenticated provider callback. Contradictory terminal notifications are recorded for investigation; they never silently reverse an existing posting.

## Reconciliation bounds

Each run reads at most 1,000 recent provider report rows and 100 unresolved local operations. It also flags successful payments missing a ledger transaction. Published CREATED operations are eligible for recovery if a consumer failed before recording receipt. The report verifies terminal amount/currency and contradictory outcomes as well as unresolved results. A production integration needs paginated reports, a durable cursor, backoff/jitter and alert thresholds; this demo does not claim unbounded historical coverage.
