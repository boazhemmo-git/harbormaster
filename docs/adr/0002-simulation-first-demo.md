# ADR-0002: Simulation-first demo, real data as alternate sources

**Status**: accepted

## Context

The original plan bundled a recorded live capture as the default source. Two
problems surfaced:

1. The open Norwegian TCP feed, consumed from outside Norway, trickles
   (~10 sentences/minute in a 15-minute capture) — too sparse for a
   compelling live picture.
2. Real traffic is well-behaved most of the time. A reviewer would need to
   watch for a long time before any detector fires, and some alerts
   (a genuine mid-sea transfer) might never occur during a demo.

## Decision

Default to a deterministic scenario simulator: ~120 vessels off the Dutch
coast with realistic kinematics, plus four scripted actors that exercise
every detector on a known timeline (spoof jump at T+3:00, rendezvous alert
around T+4:00, dark ship around T+7:00, loitering at T+10:00).

Crucially, the simulator emits **real AIVDM sentences** through the encoder —
checksums, six-bit armoring, two-fragment type 5 messages — so the demo
exercises the identical wire path as live data, not a shortcut into the
domain model. Simulated vessels carry a `SIM` name prefix; there is no
pretense of real traffic.

The real capture ships in the repo and drives both the `REPLAY` mode and an
integration test asserting decoded positions land inside the feed's true
geographic footprint. `LIVE_TCP` remains one flag away.

## Consequences

- A reviewer sees a living picture immediately and every alert type within
  ten minutes, reproducibly (fixed seed).
- The encoder is production code with tests, not test scaffolding — and the
  simulator doubles as a load generator (`speed`/`vesselCount` knobs).
- The demo's realism ceiling is the simulator's kinematic model; the README
  says so plainly.
