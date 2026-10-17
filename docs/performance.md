# Reproducible SQL and JVM investigation

No unmeasured throughput claims are made. Record CPU, RAM, Java version, PostgreSQL version, row count, workload distribution and concurrent clients with every result.

## SQL

Run `scripts/explain.sql` with psql against the demo database. The merchant listing has an index aligned with its merchant filter and timestamp ordering. Unresolved operations and pending outbox use partial indexes. Small datasets may correctly choose sequential scans; compare actual plans on representative data before attributing gains to an index.

```sh
docker compose exec -T postgres psql -U trustpay -d trustpay < scripts/explain.sql
```

## JVM

Run the API locally with the infrastructure containers, then start a 60-second recording:

```sh
jcmd -l
jcmd PID JFR.start name=trustpay settings=profile duration=60s filename=trustpay.jfr
```

Exercise concurrent payment submissions during the recording. Inspect allocation hot spots, blocked threads, database pool contention and GC pauses using JDK Mission Control or `jfr summary trustpay.jfr`. Compare p50/p95/p99 latencies under the same workload before and after any change. A recording and diagnosis are evidence only after the commands have actually run.

The current design uses bounded JDBC pooling and a blocking HTTP adapter with connect/read timeouts. Do not hold database transactions around provider I/O. Outbox publication does hold row locks while waiting for confirms, a documented scaling tradeoff.
