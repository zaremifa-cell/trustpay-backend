# 3. The AI assistant suggests; it never acts

**Status:** accepted

## Context

Operators resolving reconciliation cases spend most of their time reading: payment status, the
provider's record, the audit trail. A language model can summarise that well. It can also be
wrong, slow, unavailable, or answer in a shape we did not ask for, and in payments a confident
wrong answer is expensive.

## Decision

- `POST /api/ops/review-cases/{id}/assist` stores a summary and a suggested resolution on the case.
  Nothing else changes; an operator must still resolve the case, and that decision is audited.
- Spring AI with structured output (`ModelAnswer`): Ollama by default, so it runs locally and for
  free; any Spring AI `ChatModel` can replace it.
- **Data minimisation:** the model receives payment facts only (reason, amount, currency, status,
  ledger state, provider record, audit actions). No customer identifiers, no merchant credentials.
- **Validation before display:** the resolution must be one of three known values, the summary
  non-empty and at most 600 characters, and a confidence below 0.6 is turned into `ESCALATE` by
  policy, not by the model.
- **Bounded:** the call runs on a virtual thread with a timeout (10 s) and outside any database
  transaction, so a slow model never holds a connection.
- **Always answers:** disabled, timeout, network error, malformed or rejected output all fall
  back to deterministic rules, and the source (`model`, `rules`, `rules (model timed out)`…) is
  stored with the suggestion and counted in `trustpay.review.assistant{outcome}`.

## Consequences

- The feature is safe to switch on without trusting the model.
- Every behaviour above is tested without a network (`ReviewAssistantTest`).
