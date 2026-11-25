# Active match publication owns live viewers

Status: accepted on 2026-09-23. Refines [ADR 0013 on application seams](0013-application-seams.md).

Amended on 2026-11-25: what is delivered and how a missed update is repaired. See [amendments](#amendments).

The `/browser/v2` publication module owns enrollment and removal of live match viewers, rechecks each recipient's access, obtains that recipient's permitted view, and offers best-effort updates after match handling identifies a public-state change, including save/resume and full time. The acting player's request-correlated reply and pre-activation preparation notices remain outside this module. Match handling returns an explicit publication outcome instead of a consumable completion flag; the remaining offline `/browser/v1` caller uses that outcome until its separately tracked retirement. This keeps the access and delivery rules at one seam without changing the browser protocol, durable match state, single-worker mutation order, or delivery guarantees.

Verification of this decision uses current `/browser/v2` tests for player and spectator views, revoked recipients, ordinary updates, and terminal reconciliation after an uncertain commit with real database behavior. Historical `/browser/v1` process-kill evidence is not evidence for the current runtime. New publication counters and a new process-kill campaign are outside this workstream.

## Amendments

- 2026-11-25. [ADR 0014](0014-match-state-records-and-live-sync.md) ([approval](../specs/match-state-sync-adr.md#maintainer-acceptance)) changes what publication sends: a match record, whose public part is the same for every viewer, and a separate offer private to each coach. Delivery of each message stays best effort, but a missed update now always leads to a bounded repair. Enrollment, removal and the access recheck for each recipient are unchanged. Implementation is tracked in [#651](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/651).
