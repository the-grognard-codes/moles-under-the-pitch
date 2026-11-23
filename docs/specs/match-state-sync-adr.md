# ADR request: match state, history and live sync

Issue: [#611](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/611)

Status: requested; resulting ADR not yet accepted.
Source: [#550](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/550), [Codex's recovery, live sync and replay notes](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/550#issuecomment-7086931992), and the maintainer decisions of 2026-11-22 recorded below. The decisions move here from #550 so that #550 stays on engine structure and ruleset separation.
Reviewed: 2026-11-22, `main` at `5f6779969b644a406de379a36fbfe05f590ef037`.
Template: [ADR request template](../agents/adr-template.md).

## Objective

Decide how a match's current state is stored and restored, how its history is recorded for replay, and how live updates reach browsers. The required outcome is that **the cost of a move does not grow with match length**: durable write, restore, publication and full time each cost the same early and late in a match.

This sets the server-to-client model for the rest of the project. The target is that MUTP behaves like a modern game: low latency, small deltas to clients, and performance that meets current expectations.

## Context and evidence

- **The checkpoint grows all match.** Every checkpoint carries four arrays that grow for the whole match (`SetupSession.java:497-509`):
  - the native `gameLog`, from command 1;
  - `eventsJson`, a full board snapshot per revision (`:1575-1582`);
  - transcript records, each holding the same board snapshot again plus the native outcomes (`MatchTranscript.java:85-86`);
  - request history, up to 8,192 entries (`SetupSession.java:339-358`).

  Each revision therefore stores the board twice and its outcomes twice. That is about 56 KB per revision, or 39.12 MiB at about 713 revisions in the 2026-11-20 soak (#596).
- **The native game log is not needed after capture.** Live play reads it only after `transcript.nativeCursor`, in `NativeOutcomeCapture.since` (`SetupSession.java:1585`) and `DriveTrackerPresentation`. The full log is kept so that restore's round-trip equality check passes (`:387-388`).
- **`events` is a leftover copy.** It is read only for the completed result's `eventCount` and by-index replay (`MatchResultJson.java:27-37`) and for the decision-limit restore (`SetupSession.java:362`).
- **Restore reads the whole history in five places:**
  - journal assembly re-encodes the whole checkpoint (`RecoveryJournal.java:87`);
  - every event is parsed and frozen (`SetupSession.java:360-361`);
  - the decision limit is rebuilt from all events (`:362`);
  - the native round trip includes the full log (`:387-388`);
  - the request map is rebuilt (`:339-358`).
- **Compaction rewrites everything about every 31 moves.** The journal compacts at 32 entries or 2 MiB, rewriting the whole base (`RecoveryJournal.java:12-14, 41`). Codex's isolated replay showed a one-square move triggering that rewrite, taking 3.7–4.9 s cold against the 2,000 ms budget.
- **Full time builds the whole match.** It assembles the completed match in memory and stores it (`SetupSession.java:1601-1611`). In the soak this froze the server for 5–14 s and needed about 400 MiB of temporary heap (#593, #594).
- **Live updates send the full board twice.**
  - Each recipient gets a full view built for that recipient alone (`ActiveMatchPublisher.java:209-213`).
  - Coaches with the live transcript on also get the record, which repeats the board (`LiveMatchTranscript.java:28`).
  - The client replaces its state wholesale (`v2-client.ts:545-546`).
- **Chat** keeps up to 512 messages in every checkpoint (`MatchChat.java:15`).
- **Accepted ADRs involved:**
  - ADR 0001 (publication)
  - ADR 0002 (bounded transcript)
  - ADR 0004 (recovery journal)
  - ADR 0006 (single worker and request deadline)
  - ADR 0007 (presentation markers, transcript format 3)
  - ADR 0008 (staged placements)
  - the founding versioned browser protocol decision (notes ADR-003, promoted to `docs/adr` by #610 in PR #612)
  - the founding application-seams decision (notes ADR-004, promoted the same way). It put match storage behind a repository interface and anticipated "a future canonical event log".
- **Limits of the evidence:** the split of the 39 MiB by field is estimated, not measured. The size of the native snapshot alone, and its growth over a match, are not measured. Soak figures come from computer-driven matches of about 113 decisions each.

## Maintainer decisions (2026-11-22)

1. **Scope.** This issue owns the storage and sync decision and its ADR. #550 keeps engine structure and the ruleset-boundary review, and treats this ADR as an input: it settles the persistence and presentation seams that #550's review maps.
2. **One record serves live play, catch-up and replay.** The transcript record evolves to format 4:
   - It keeps the decision, ordered outcomes (dice, movement, resolved effects) and the ADR 0007 presentation markers.
   - It replaces the full `state` with a **public patch**, plus a **public-state digest**. A client whose state diverges detects the mismatch and requests a fresh snapshot.
   - **Keyframes** (a full public snapshot) are written periodically for seeking.
   - Live viewers receive the record, catch-up reads it, and replay plays it.
3. **Replay storage is written in the move's own transaction.** The transaction updates the restore snapshot, writes the exact-request receipt, and appends the record plus any keyframe. There is no background replay worker. Revisit only if storage cost requires it.
4. **Publish after commit**, as today.
5. **Old formats are retired.** All existing matches are cleared, active and completed. Older artifacts are refused with `RECOVERY_UNSUPPORTED`, as in ADRs 0007 and 0008.
6. **Required outcome: the cost of a move does not grow with match length.** It is held to ADR 0006's p99 ≤ 100 ms per task. The numbers are set after the measurement below.

## Scope and deliverables

1. **Request spec.** This file, `docs/specs/match-state-sync-adr.md`, linked to this issue.
2. **Measurement outcome**, `docs/specs/match-state-sync-measurement.md`, reproducible from a committed script:
   - a breakdown by field of a soak-sized match's restore object (native snapshot, game log, events, transcript `state` and `native`, request history, chat, views);
   - the size of a compact restore snapshot at about revision 50 and about revision 700;
   - the time to write that snapshot plus append one record in MariaDB, early and late in a match;
   - the time to restore from the compact snapshot.
3. **Proposed ADR**, `docs/adr/<slug>.md`, status `proposed`. It is numbered when the maintainer accepts it, before merge, under the rule set by #610. It states:
   - which records it **supersedes wholly**: ADR 0002 and ADR 0004. Their governing choice, where durable match state lives and what a stored record is, changes. A partial supersession of 0002, which 0004, 0007 and 0008 have already amended, would leave readers combining four or five records to find the rule in force. The ADR therefore restates every surviving guarantee itself (see "Carried-forward guarantees" below).
   - which it **amends**:
     - ADR 0001: publication sends records plus a per-role envelope.
     - ADR 0007: format 3 moves to 4, with the markers in the record.
     - The promoted versioned browser protocol record (0012 in #612): full-snapshot recovery and full resync for slow clients become a snapshot plus revisioned deltas. Its governing choice, versioned JSON over WebSockets, still holds, so under #610's template rule this is an amendment, not a supersession.
   - which it **extends with a dated history entry**: the promoted application-seams record (0013 in #612). The new storage sits behind that record's repository boundary, and the format-4 record is the "future canonical event log" it anticipated.
   - that ADR 0006 is unchanged.
4. **Candidate tickets** in the ADR or an outcome note, not filed until acceptance:
   - storage: the current-state row, revision records, the receipt and chat rows; full time marks the match finished; the journal and compaction are deleted;
   - replay: format-4 patches and keyframes;
   - live protocol: deltas, a per-role envelope, the digest, a catch-up tail, and a snapshot on join or resync.

**Sequencing:** the measurement can start at once. Draft the ADR after #610 merges, so that it can cite the promoted records by number.

**Excluded:**
- Production code changes. Measurement scripts are allowed.
- Engine structure, Step and SequenceGenerator restructuring, and the ruleset-boundary audit (#550).
- Parallel match execution (ADR 0006's revisit condition).
- Binary encoding and transport compression, unless the measurement shows that payload size still matters once deltas exist.

## Required decision content

- **Restore snapshot:**
  - What it contains: the native snapshot and continuation; dice and random-number state; clocks; pending decisions; save/resume state; computer memory; counters (revision, record and chat sequences, decision limit); the receipt; presentation cursors.
  - Proof that its size does not grow with match length.
  - Restore reads no history.
- **Exact-request receipt:** its window (for example, the last accepted request per role), and how a retry from outside the window is rejected.
- **Format-4 record:**
  - fields, recording domain facts only: the accepted decision, dice, resolved effects, presentation markers, and patches to public entities. Never browser commands or diffs of the rendered view. The application-seams record says "Do not equate recorded UI commands with a stable domain-event schema";
  - the shape of the public patch (for example, whole-entity replacement keyed by player, ball and team ID);
  - the digest's definition;
  - keyframe cadence.
- **Public and private:** records and patches carry public facts only. The per-role envelope carries prompt, actions, forecasts and other private data, and never enters the record. The public projection is built once per revision.
- **Live delivery and catch-up:** join and resync send a snapshot plus the latest page; the size of the bounded in-memory tail; gap detection; behaviour when the digest mismatches; spectators. This stays consistent with ADR 0001's best-effort delivery and ADR 0008's staged-placement messages.
- **Transaction:** what the move's transaction writes; behaviour on uncertain commit; exact retry; crash between commit and publish.
- **Match log and chat:** see the open decisions below.
- **Server memory:** no history stays resident beyond the tail. State the consequences for `MatchMemoryBudget` and for #543's frozen history.
- **Ruleset neutrality:** record and patch shapes in shared protocol code carry no BB2025-specific types, consistent with #550's ruleset-separation principle.
- **Compatibility:** retire the old formats (decision 5); new version markers; refuse old data explicitly.
- **Carried-forward guarantees.** Because 0002 and 0004 are superseded wholly, the ADR classifies each of their rules, so that none is lost by accident.
  - **Keep, restated in the new ADR:**
    - One durable boundary per action. An action is accepted and published only after commit.
    - An exact retry appends nothing. The bounded receipt now provides this.
    - Storage admission rejects an action before native execution, never after.
    - Recovery never reruns commands, dice, clocks or optional decisions.
    - Log lines and replay derive from recorded native outcomes, in canonical home orientation, without running the engine again.
    - Records keep immutable account, match-team, player and side IDs, and names resolve on read. Time comes from the server's UTC epoch-millisecond clock.
    - Reads are paged. Live delivery may drop a frame, because authorized readers can catch up.
    - Spectator chat uses the same persisted sequence and access check, and never invokes a game command.
    - Staged placements create no record (ADR 0008's amendments to 0002 and 0004).
    - Missing durable records never silently roll a match back. This was the reason for 0004's separate head and base generations. Now, restore checks that the snapshot's revision matches the newest stored record, and treats a gap as `RECOVERY_CORRUPT`.
    - Computer-coach availability (0004's schema marker 10, `computer_work_until_epoch_ms`) is committed in the same transaction as the move. It moves to the current-state row.
    - The computer coach's cold load path keeps working. `RecoveryCheckpoint.computerReply` reads stored per-role views from the checkpoint, so the snapshot either keeps those views or the ADR says how they are rebuilt.
  - **Replace:**
    - the single-blob artifact;
    - the base-plus-journal layout, with its separate generations and compaction;
    - the completed-result size limits;
    - recovery version 7.
  - **Retire:**
    - freezing each native log command's JSON for recovery, since the native log is no longer persisted;
    - readers for older formats (decision 5);
    - the format-1 cleanup.
- **Budget:** targets for durable write, restore, publication and full time, set from the measurement, all flat in match length.
- **Revisit conditions:** for example, storage cost per match, row counts, or measured payload sizes that justify binary encoding.

## Risks and mitigation ownership

| Risk / trigger | Consequence | Required mitigation or decision | Owner / tracking | Remaining risk |
| --- | --- | --- | --- | --- |
| A client applies a patch wrongly or misses one | Board shown differs from the server | Digest per record; resync on mismatch or gap | This ADR; live protocol ticket | Silent divergence between digests, if the digest omits a field |
| The native snapshot hides data that grows | Cost grows with match length again | Measure at revisions 50 and 700; size regression test | This ADR; storage ticket | Rare paths not covered by the measured matches |
| Response lost after commit | Coach retries a move | Bounded receipt written in the same transaction | Storage ticket | Retries older than the window are rejected as stale |
| Crash between commit and publish | Viewers miss a record | Catch-up from the tail or the record table; snapshot on reconnect | ADR 0001 behaviour, this ADR | None beyond today's best effort |
| BB2025 shapes leak into the shared record | Breaks #550's ruleset separation | Neutral record schema; review checklist | This ADR; #550 | — |
| Completed replays are lost | Earlier matches cannot be replayed | Accepted by decision 5 (build phase) | Maintainer | — |

## Alternatives and open decisions

**Considered:**
- **Keep base plus journal and tune the limits** (#596 option a). Rejected: cost still grows with match length.
- **Assemble replay in a background worker** (Codex's notes). Deferred by decision 3.
- **Publish before commit.** Rejected by decision 4.
- **Rebuild state by re-executing inputs deterministically.** Rejected: ADR 0004's rule that recovery never reruns commands stands.

**Open, for the maintainer:**
- **Match log and chat placement (proposed 2026-11-22, under review):**
  - The native engine log leaves the restore object and is never persisted. Only the tail not yet captured stays in memory.
  - The match log coaches read is derived from the records, so the record table is the replay storage.
  - Each chat message is an appended row in the same ordered sequence, and the restore object keeps only counters and the deduplication window.
  - The client owns display, retention and pagination of what it has received, as a cache. The server serves older pages on reload, device change, spectator join, dropped frames and replay.
  - Caching across reloads in browser storage is optional and deferred.
- Patch shape, keyframe cadence, receipt window and tail size.
- The numeric targets under decision 6, after the measurement.

## Related tickets

- **#593, #594 and #596 are superseded by this design.** The ADR PR references all three. Each is closed as *not planned* once that PR merges; GitHub closing keywords would close them as *completed*, so close them by hand. They are labelled `wontfix` until then.
- **#548 (proposed, not yet decided).** Its full-time timing and compaction budget are superseded too. Its other items stand on their own: retiring the browser's `COMPLETION_PENDING` handling, decoupling `MatchTranscript` from presentation, and tidying the analysis scripts. Splitting them out is proposed.
- **#550** consumes this ADR as an input. #595 and #597 are unaffected.
- **#610** (PR #612) promotes the founding records and sets numbering on acceptance, before merge.

## Acceptance criteria

### Draft ready for maintainer review

- The measurement outcome exists, is reproducible from a committed script, and contains no credentials or private data.
- The ADR is drafted as `proposed` and covers every item under "Required decision content".
- It names the records it supersedes, amends and extends, and states its consistency with ADRs 0006, 0007 and 0008 and with #550's ruleset principle.
- It contains the carried-forward classification of every rule in ADRs 0002 and 0004.
- The open decisions are either resolved with the maintainer's recorded approval, or listed as open.
- The candidate tickets are listed. Links are checked, and every cited symbol exists at the reviewed revision.

### Accepted

- The maintainer's approval is recorded with a date.
- In the same PR, under #610's template:
  - ADRs 0002 and 0004 are marked `superseded`, with links both ways;
  - ADRs 0001 and 0007 and the versioned browser protocol record carry dated amendments;
  - the application-seams record carries a dated history entry;
  - the architecture map's invariants on durable boundaries, recovery and stored formats are updated.
- No accepted ADR gains a forward pointer to this decision before acceptance.
- The follow-up tickets are filed.
- #593, #594 and #596 are closed as *not planned*, each with a link to the ADR.

## Validation and handoff

- **Now:** documentation and measurement scripts only. Check links, and confirm the cited symbols at the reviewed revision.
- **Branch and PR:** create a new branch. The PR says `Part of #<this issue>`. Do not merge, and do not mark the ADR accepted without the maintainer's approval.
- **Later:** the implementation tickets prove decision 6 with early- and late-match comparisons at the default heap, zero history reads on restore, and process-death tests around the commit.
