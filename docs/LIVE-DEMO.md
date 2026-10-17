# Five-minute reviewer walkthrough

Open the live demo linked from the repository README. All identities, provider responses and money are simulated. No installation, registration or real payment information is required.

Use the persistent button at the top to move through the four highlighted steps. Each action displays a visible confirmation. Payment results update automatically; technical JSON is optional and collapsed.

1. **Start demo**, then **Create customer**. Each session has its own merchant identity and expires after 15 minutes.
2. **Test pending identity**: expect HTTP 422. **Verify identity** enables payment creation for your customer only.
3. **Pay €100**, then **Check status + ledger** if needed; the card updates automatically to SUCCEEDED. Inspect the audit trail and the single balanced debit/credit transaction.
4. **Repeat same request**: expect the same payment ID. **Repeat settlement twice**: expect one ledger transaction, demonstrating duplicate delivery safety.
5. **Test another merchant**: a separate JWT cannot read your payment (HTTP 404).
6. **Pay €13**: provider rejection ends FAILED with no ledger entry. **Test €99 timeout**: provider accepts durably but delays its response. Automatic status checks reconcile UNKNOWN/PROCESSING to SUCCEEDED without a second ledger entry.

You can copy your session JWT into Swagger's **Authorize** dialog to call the API directly. Each session permits 3 customers and 25 payments; per-instance request limits also apply. Demo data remains in the database after the session expires. First requests may be slower after idle periods.

# Cloud architecture

The `vercel` Spring profile runs the same payment/ledger core in a Java 21 container. Four Vercel Services provide:

- `java`: public Spring Boot API and static demo page.
- `publisher`: private Node adapter publishing to Vercel Queues.
- `queue`: private Node consumer receiving queue deliveries and forwarding them to Java.
- `provider`: private Python provider simulator, persisted in PostgreSQL rather than local disk.

Service bindings provide internal URLs. A generated internal secret additionally protects the Java consumer and queue publisher. Provider requests use a separate generated secret. Java private-service clients merge the platform-provided CA bundle with the JVM truststore, retaining certificate and hostname validation. PostgreSQL connections use TLS; Java uses a pooled runtime connection and a direct Flyway migration connection. Secrets are configured in Vercel and are not stored in source control.

Payment creation commits an outbox record before publication. An accepted queue response marks it published. Queue failures leave the outbox pending for a later status request to retry. Duplicate queue deliveries use the same database receipt and ledger constraints as RabbitMQ. For this interactive sandbox, status checks also recover stale/unknown operations by querying the provider. There is no always-running reconciliation daemon or scheduled outbox scan in this cloud profile. An unvisited pending payment can therefore remain pending until checked; local Compose has scheduled publisher/reconciliation jobs.

The original Docker Compose deployment uses RabbitMQ, publisher confirms and the scheduled reconciliation worker. Its production failure scenarios remain covered by the existing integration suite. Vercel Services, container Functions and Queues are platform beta features; cloud adapters are intentionally isolated from the domain logic.

## Deployment configuration

Root `vercel.json` defines the service graph, private bindings and queue trigger. Java and provider use `Dockerfile.vercel`. `cloud/queue/package-lock.json` pins the SDK. Provision PostgreSQL, then configure encrypted environment values:

`JWT_SECRET`, `PROVIDER_SECRET`, `CLOUD_INTERNAL_SECRET`, `MERCHANT_PASSWORD`, `OTHER_PASSWORD`, `OPS_PASSWORD`, `DATABASE_URL` (pooled JDBC), `MIGRATION_DATABASE_URL` (direct JDBC), `DATABASE_USER`, `DATABASE_PASSWORD`, `PROVIDER_DATABASE_URL` (Postgres URI), `PORT=8080`, `SPRING_PROFILES_ACTIVE=vercel`.

Basic authentication is disabled in the public demo. Generated demo JWTs have MERCHANT privileges, never OPS. Demo verification checks ownership and is only available in the public-demo profile. Request limits are per instance, not a distributed abuse-control system. This is a constrained portfolio demonstration, not a real payment gateway.

## Automated checks

`sh ./scripts/test.sh` runs 19 PostgreSQL-backed Java integration tests. `npm ci --prefix cloud/queue-publish && npm test --prefix cloud/queue-publish` runs four raw HTTP publisher adapter tests. `npm ci --prefix cloud/queue && npm test --prefix cloud/queue` checks the queue callback HTTP adapter. `python3 scripts/check-live.py https://trustpay-demo.vercel.app` checks the public deployment without printing session tokens. GitHub Actions runs the Java, Docker failure scenarios and Node adapter checks.
