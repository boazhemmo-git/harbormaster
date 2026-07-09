# ADR-0004: From-scratch AIS decoder instead of a library

**Status**: accepted

## Context

Mature AIS decoding libraries exist for the JVM. Using one would be the
right call in a product team — less surface, maintained edge cases.

## Decision

Implement NMEA parsing, fragment reassembly and ITU-R M.1371 six-bit
decoding from the specification, in `io.harbormaster.nmea` and
`io.harbormaster.ais`.

This project exists to demonstrate bit-level protocol engineering — the same
class of work as radar/maritime protocol adapters in defense C4I systems,
which cannot be shown publicly. Delegating the interesting part to a library
would defeat the point.

Correctness is anchored three ways rather than by trust in a reference
implementation:

1. Published golden vectors from the AIVDM/AIVDO protocol documentation.
2. Round-trip tests against an independently written bit-packer (write-path
   vs read-path, so symmetric offset errors cannot cancel).
3. An integration test decoding a real recorded capture and asserting every
   position falls inside the source network's geographic footprint — a sign
   flip or offset error scatters vessels across the globe and fails loudly.

## Consequences

- Message-type coverage is deliberately narrow: 1/2/3/5/18/19/24 — the types
  the tracker consumes. Everything else decodes to `UnsupportedMessage` and
  is counted, not dropped silently.
- The decoder is the project's centerpiece for code review; it carries the
  densest tests in the repository.
