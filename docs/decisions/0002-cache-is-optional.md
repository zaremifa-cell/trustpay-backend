# 2. The cache is an optimisation, never the source of truth

**Status:** accepted

## Context

Two reads are worth caching: a merchant's balance (an aggregate over the ledger) and payment
retries, which today take an advisory lock just to find out the payment already exists. A retry
storm after a network blip would serialise on that lock.

## Decision

- Redis (`app.cache=redis`) in compose and Kubernetes; an in-memory map for the public demo and tests.
- `balances`: cached per merchant for 5 minutes, evicted whenever one of its payments settles.
- `idempotency`: (merchant, key) of created payments for 24 hours. A retry with the *same* payload
  returns the stored payment without the lock; a different payload, a miss or a cache error falls
  through to the locked database path, which still decides.
- Every cache is **transaction-aware**: puts and evictions run after commit. Otherwise a reader
  could re-cache the old balance between the eviction and the commit, or cache a payment whose
  transaction then rolled back.
- Every cache is **fail-safe**: a Redis error is logged and read as a miss. Because writes happen
  after commit, an unguarded error there would fail a request whose payment already committed.
- Redis runs without persistence, with `maxmemory` and LRU eviction: losing it costs round trips,
  never data.

## Consequences

- Redis down means slower, not wrong (`CacheOutageTest` runs payments against a dead Redis).
- A balance can be up to 5 minutes stale only if an eviction itself fails; that is logged.
