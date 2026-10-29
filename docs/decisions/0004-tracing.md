# 4. One trace per payment, across HTTP, the provider and the queue

**Status:** accepted

## Context

A payment crosses three boundaries: the merchant's HTTP request, a RabbitMQ message from the
outbox to the worker, and HTTP calls to the provider. When one is slow or fails, logs from three
places have to be matched by hand.

## Decision

- Micrometer Tracing with the OpenTelemetry bridge; spans exported over OTLP when
  `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set (compose sends them to Jaeger, `localhost:16686`).
- HTTP server spans come from Spring Boot. Provider calls use the Boot-provided `RestClient.Builder`,
  so each call is a span and sends `traceparent` to the provider. RabbitMQ observation is on for
  the template and the listener, so the trace continues through the queue.
- `X-Request-Id` on every response is the trace id: a merchant can quote one id that finds the
  request in Jaeger and in the logs.
- Sampling: 100% locally, 10% in Kubernetes (`TRACE_SAMPLING`).

## Consequences

- Without an endpoint nothing is exported and nothing breaks (the public demo runs that way).
