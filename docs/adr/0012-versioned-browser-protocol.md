# Use versioned JSON over browser-native WebSockets

Status: accepted by the owner on 2026-09-06. [Acceptance and source](../../.notes/overhaul-analysis/04-technology-and-decisions.md#adr-003-versioned-json-over-browser-native-websockets) · [September 6 revision](https://github.com/the-grognard-codes/moles-under-the-pitch/blob/120dd9953a71d7b0bff031c751feffae7f55b5f8/.notes/overhaul-analysis/04-technology-and-decisions.md#adr-003-versioned-json-over-browser-native-websockets)

## Amendments and follow-up

- 2026-11-03. V1 retirement left V2 as the sole browser runtime and retained shared formats needed for V2 replay and recovery. [V1 retirement outcome](../specs/v1-retirement-outcome.md)

## Original decision, 2026-09-06

Decision status: **accepted**.

Retain the existing transport category. First prove a browser can encode representative legacy commands and decode responses in an isolated environment. Prefer uncompressed text JSON in the diagnostic slice; test existing LZString framing separately if required. The existing 64 KiB ingress setting and server-side decompression require real payload measurements, including decoded-size limits. [CommandSocket](https://github.com/the-grognard-codes/moles-under-the-pitch/blob/120dd9953a71d7b0bff031c751feffae7f55b5f8/ffb-server/src/main/java/com/fumbbl/ffb/server/net/CommandSocket.java#L25).

For the product interface, introduce a versioned adapter rather than exporting the entire mutable Java object model. Document protocol version, request correlation, ordered state revision, caller role, accepted/rejected actions, full snapshot recovery, and audience projection. Client types are generated or checked against shared protocol fixtures; TypeScript types alone do not validate incoming JSON.

JSON is readable and aligns with the current implementation. Protobuf could improve size and explicit schema evolution, but introduces schema/compiler/client integration work; defer until profiling demonstrates a meaningful benefit. A binary format does not solve authorization, ordering, or replay compatibility. [Protocol Buffers language guide](https://protobuf.dev/programming-guides/proto3/).

Use TLS and an explicitly validated origin/authentication policy for public connections. Browser WebSocket does not provide automatic backpressure; bound outbound queues and use a full resync for slow clients instead of unbounded buffering. [Browser WebSocket API](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket); [WebSocket server guidance](https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_WebSocket_servers).
