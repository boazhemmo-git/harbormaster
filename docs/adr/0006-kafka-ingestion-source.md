# ADR-0006: Kafka as an ingestion source, not the internal transport

**Status**: accepted

## Context

[ADR-0001](0001-in-process-pipeline-no-kafka.md) kept the pipeline in-process
and named the seam for a broker: sources implement `AisSource`, and the shape
Kafka models well is the *upstream* one — many shore/edge receivers publishing
sentences that a fleet of consumers processes. A maritime platform is realistic
deployed that way, and "consumes from Kafka" is table stakes for a lot of
backend roles. What ADR-0001 deferred was making the broker the pipeline's
*internal* transport (per-partition decode workers sharded by MMSI); that
trade — losing single-writer simplicity for horizontal scale — still has no
requirement behind it.

## Decision

Add `KAFKA` as an `AisSource` (`KafkaSource`), consumed by the **unchanged**
in-process pipeline: Kafka lines land on the same bounded queue and the same
single decode worker as every other source. Kafka is where the data comes
*from*, not how it moves *inside* the process — so ADR-0001's single-writer
store and lock-free assembler still hold.

Concretely:

1. **Plain `kafka-clients`, not Spring Kafka.** A `KafkaConsumer` polled on a
   virtual thread matches the "each source owns its thread and lifecycle"
   contract the other sources follow, and keeps Spring off the ingest hot path
   and out of the framework-free reach of the protocol core.
2. **At-least-once, manual commit after hand-off.** Offsets commit only once a
   batch has reached the queue. The position store is idempotent — a redelivered
   fix overwrites to the same value — so at-least-once is the right cost/safety
   point; exactly-once would buy nothing here.
3. **`latest` offset reset.** A fresh instance tails current traffic rather than
   replaying history; a restart with a committed offset still resumes exactly.
4. **Self-contained demo.** `produce-simulation=true` runs the scripted generator
   as a *producer* into the topic, so `KAFKA` mode is runnable with just a broker
   (`docker compose --profile kafka up`) and no external feed.

## Consequences

- The default `docker compose up` is still broker-free (ADR-0001) — Kafka is one
  opt-in profile.
- Records are published round-robin (null key). Preserving per-vessel ordering
  would key by MMSI, but that key is only known after the six-bit payload is
  decoded downstream, so it belongs at the publishing edge — noted, not built.
- The per-partition, MMSI-sharded scale-out from ADR-0001 remains the next step
  if throughput ever demands it; this ADR does not take it.
- Verified with an in-JVM KRaft broker (`spring-kafka-test`) — the consumer
  group, the String serde round trip, and the producer bridge are covered
  without Docker.
