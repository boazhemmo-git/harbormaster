# ADR-0005: Hand-rolled MCP server as a thin adapter over the REST API

**Status**: accepted

## Context

Exposing the live maritime picture to AI agents is a natural extension: an
analyst should be able to ask "which tankers went dark in the last hour?"
in natural language. The Model Context Protocol is the emerging standard for
that, with official SDKs for Java and TypeScript.

## Decision

Implement the MCP stdio transport (newline-delimited JSON-RPC 2.0) directly
from the specification in `mcp-server/` — plain Java 21, Jackson as the only
dependency, ~200 lines of protocol code. This mirrors ADR-0004: the protocol
surface a tools-only server needs ({@code initialize}, {@code ping},
{@code tools/list}, {@code tools/call}) is small enough that owning it beats
depending on an SDK plus its transitive graph — and protocol work from the
spec is this project's signature move.

Two further choices:

1. **Thin adapter, not a backdoor.** The MCP server consumes the same public
   REST API as the browser UI. No privileged access to internals: the API
   remains the single contract, and the MCP process can run anywhere the API
   is reachable (`HARBORMASTER_API` env var).
2. **Stdout discipline.** Stdout carries protocol frames exclusively; all
   diagnostics go to stderr, which MCP clients surface as server logs. This
   is also why the module is framework-free — a Spring banner on stdout
   corrupts the very first frame.

## Consequences

- Tests speak raw protocol bytes: JSON-RPC lines in, JSON-RPC lines out —
  the exact conversation a client has.
- Only the tools capability is implemented; resources/prompts/sampling are
  out of scope until a use case shows up.
- Protocol-revision negotiation is permissive (the server echoes the
  client's proposed version) — valid for a tools-only server whose behavior
  is identical across current revisions.
