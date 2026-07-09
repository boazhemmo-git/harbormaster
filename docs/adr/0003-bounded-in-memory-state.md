# ADR-0003: Bounded in-memory state, no database

**Status**: accepted

## Context

Vessel tracks and alerts could be persisted (PostGIS is the natural fit and
would enable historical queries, replays and long-baseline analytics).

## Decision

Keep all state in bounded in-memory structures:

- `TrackStore`: `ConcurrentHashMap<MMSI, VesselTrack>` with per-track fix
  history capped at `trail-length` and eviction after `evict-after` silence.
- `AlertLog`: a 1000-entry ring buffer.

A national feed is a few thousand concurrent vessels × a few KB of track
state — single-digit megabytes. Eviction bounds growth regardless of uptime.

## Consequences

- Zero infrastructure, instant startup, trivially correct under the
  single-writer model.
- History does not survive restarts and long-baseline questions ("where was
  this vessel last Tuesday?") are out of scope. That is the honest cut line
  for a live-monitoring demo; the seam for persistence is the same
  `PositionUpdate`/`Alert` events the WebSocket broadcaster already consumes.
