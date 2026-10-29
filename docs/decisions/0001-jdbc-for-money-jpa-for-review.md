# 1. Plain JDBC for money, JPA for review cases

**Status:** accepted

## Context

TrustPay moves money. Creating a payment takes a transaction-scoped advisory lock, checks the
idempotency key and the customer's identity under `FOR SHARE`, and writes the payment, its outbox
event and an audit row together. Settlement locks the payment row `FOR UPDATE` and posts a
balanced ledger pair that database constraints check. Every statement and lock is deliberate.

Review cases are different: an operator loads a case, reads it, maybe asks the assistant, and
resolves it. That is ordinary CRUD where the risk is two operators deciding the same case at once.

## Decision

- The payment path stays on `JdbcTemplate`: explicit SQL, explicit locks, nothing generated.
- Review cases use JPA/Hibernate (`ReviewCase`, `ReviewCaseRepository`) with `@Version`
  optimistic locking. Detection still inserts cases with one SQL statement inside the payment
  transaction; JPA only reads and updates them.
- Flyway owns the schema. Hibernate runs with `ddl-auto: validate`, so a mapping that drifts from
  the migrations fails at startup, not in production traffic.
- Both run in one transaction manager (`JpaTransactionManager` also manages the JDBC connection),
  so resolving a case and writing its audit row commit together.

## Consequences

- A reader sees where each tool fits instead of one tool everywhere.
- Lost updates on review cases are impossible: the second operator gets `409 Conflict` and reloads
  (covered by `twoOperatorsResolvingAtOnceExactlyOneWins`).
- Two persistence styles to know. Acceptable: the boundary is one table, and the rule is simple:
  if it can move money, it is SQL.
