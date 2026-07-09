# ADR-0001: In-process pipeline, no message broker (yet)

**Status**: accepted

## Context

The pipeline shape — sources fanning into a decode stage feeding a store and
detectors — is exactly what Kafka topics model well, and a "streaming"
project invites reaching for one.

Measured reality: national-scale AIS feeds peak around a few thousand
sentences/second. A single virtual-thread worker running this decoder
processes hundreds of thousands of lines/second on laptop hardware — three
orders of magnitude of headroom — and the whole working set (every vessel in
a national feed) fits comfortably in memory.

## Decision

Run the pipeline in-process: bounded `ArrayBlockingQueue`, one decode worker
on a virtual thread, direct method calls into the store and detection engine.

Keep the seam explicit: sources implement `AisSource` and the worker consumes
`TimestampedLine`s from a queue. Swapping the queue for a Kafka consumer (and
running decode workers per partition, sharded by MMSI so per-vessel ordering
holds) touches only `PipelineService`.

## Consequences

- `docker compose up` needs no broker, no ZooKeeper/KRaft, no warm-up.
- Single-writer semantics keep the track store and fragment assembler free of
  locks and race conditions by construction.
- Horizontal scale-out and durable replay are deferred until a real
  requirement shows up; the cost of adding them later is localized.
