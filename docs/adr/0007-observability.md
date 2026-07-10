# ADR-0007: Observability — Prometheus always, tracing opt-in

**Status**: accepted

## Context

`PipelineStats` already computes the numbers an operator wants — throughput,
drop counts, decode errors, receive→applied latency — and streams them to the
dashboard over WebSocket. Its own comment named the production upgrade: export
Micrometer histograms through the actuator endpoint that was already wired.
Observability platforms (metrics + traces, Prometheus/Grafana/OpenTelemetry)
are also exactly the systems a chunk of backend roles are hiring for, so it is
worth doing properly rather than sketching.

## Decision

**Metrics — always on.** A `PipelineMetrics` component bridges the existing
lock-free `PipelineStats` counters onto Micrometer as `FunctionCounter`s and
`Gauge`s: thin read-only views over the same adders (no double counting, hot
path untouched), plus tagged gauges for vessels-by-state and alerts-by-type.
Exposed at `/actuator/prometheus`. `PipelineStats` stays the source of truth;
the cheap in-memory ring still backs the live dashboard, Micrometer serves
Grafana.

**Tracing — opt-in.** Each decoded message is wrapped in a Micrometer
`Observation` (`ais.process`). The OTel bridge turns it into a span; Micrometer
turns it into a timer histogram — the same instrument feeds both. Message type
is a low-cardinality tag (a handful of values → safe as a metric dimension);
MMSI is attached as a high-cardinality *span attribute only*, never a tag, so
per-vessel debugging is possible in a trace without exploding metric series.
Span export (`management.tracing.enabled`, OTLP) is off by default so the
zero-config demo stays quiet and dependency-free; the `observability` compose
profile flips it on and points it at a collector.

## Consequences

- `docker compose --profile observability up` brings Prometheus, an OTel
  collector, Tempo and a pre-provisioned Grafana dashboard; the backend needs
  no rebuild, only environment.
- Per-message observations cost a little on the hot path; at real AIS rates
  (thousands/sec against a decoder that does hundreds of thousands/sec) it is
  negligible, and trace sampling is configurable.
- The metric-bridge is unit-tested against a real `PrometheusMeterRegistry`;
  the endpoint wiring and span export are framework plumbing, verified against a
  running instance rather than in a slow full-context test.
