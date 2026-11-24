# ADR request: match state, history and live sync

Issue: [#611](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/611)

Status: requested; resulting ADR not yet accepted.
Source: [#550](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/550), [Codex's recovery, live sync and replay notes](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/550#issuecomment-7086931992), and the maintainer decisions of 2026-11-22 to 2026-11-24 recorded below. The decisions move here from #550 so that #550 stays on engine structure and ruleset separation. The remaining work of [#548](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/548) is folded in (decision 13).
Reviewed: 2026-11-22, `main` at `5f6779969b644a406de379a36fbfe05f590ef037`. The evidence was rechecked against the same revision on 2026-11-24, and several statements below were corrected.
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

  Each revision therefore stores the board twice and its outcomes twice. That is about 56 KB per revision, or 39.12 MiB at about 713 revisions in the 2026-11-20 soak (#596). One full-state snapshot was about 16–20 KB in the 2026-11-15 soak (#548).
- **Capture uses only the new tail of the native game log, but walks all of it.** `NativeOutcomeCapture.since` and `DriveTrackerPresentation` use commands after `transcript.nativeCursor`, and scan the log from command 1 to find them (`NativeOutcomeCapture.java:21-29`). Capture therefore also grows with match length. Restore compares the whole native state, log included (`SetupSession.java:387-388`), and the journal counts log entries (`RecoveryJournal.java:132`).
- **Previews copy the whole engine, game log included.** `BlockPreviewPresentation.copy` (`:117-122`) and `PlannedBallTransfer` (`:43`) call `toJsonValue(true, 0)` and rebuild every logged command. The acting coach's view makes one copy for each player that can declare a block and has an adjacent target (`SetupSession.java:1275`). The cost has not been measured, and earlier harnesses had no opponents on the pitch, so they never reached this code.
- **Nothing else needs the full log.** Every user of `GameLog` on `main` at `eb5057d02` was read on 2026-11-24. Only capture, the two preview copies and recovery use it, and the previews don't read what they copy.
- **Other reads take in the whole match.**
  - Each replay step parses the whole completed artifact to return one event (`MatchResultJson.java:26-38`). Each log page of a completed match also parses it, and rebuilds the whole transcript (`SetupApplication.java:1265-1271`).
  - The lobby loads and parses the whole checkpoint of a match that isn't in memory, to show its phase, half, turn and score (`SetupApplication.java:1218-1246`).
- **Each move builds a view that it mostly throws away.** Recording a revision builds the home coach's view, then drops its actions, prompt, movement forecast and block preview (`SetupSession.java:1575-1580`).
- **`events` duplicates the transcript's `state`, and shipped features read it.** Each of these needs a replacement before `events` is removed:
  - the replay viewer loads each step by index (`play-entry.tsx:564`, `MatchResultJson.java:27-37`), and the result shows `eventCount`;
  - the view after full time is the last event's state (`SetupApplication.java:1120-1123`);
  - retained concessions (`RetainedConcession.java:41, 61`);
  - completed-artifact validation (`MatchJson.java:268`);
  - the decision-limit restore (`SetupSession.java:362`).
- **Restore reads the whole history in six places:**
  - journal assembly re-encodes the whole checkpoint when journal entries exist (`RecoveryJournal.java:67, 87`);
  - every event is parsed and frozen (`SetupSession.java:360-361`);
  - the decision limit is rebuilt from all events (`:362`);
  - every transcript record is parsed, checked and re-added (`:366`, `MatchTranscript.java:34-57`);
  - the native round trip includes the full log (`:387-388`);
  - the request map is rebuilt (`:339-358`).
- **Compaction rewrites the whole base at least every 31 journal entries**, and sooner once the journal passes 2 MiB (`RecoveryJournal.java:12-14, 41, 52-53`). Each entry also carries the current native state, both coach views and all chat (`SetupSession.java:497-517`), so the real interval needs measuring. Codex's isolated replay showed a one-square move triggering that rewrite, taking 3.7–4.9 s cold against the 2,000 ms budget.
- **Full time builds the whole match.** It assembles the completed match in memory and stores it (`SetupSession.java:1601-1611`). In the soak, finishing a match of about 38 MiB needed about 400 MiB of temporary heap (#594). Requests waited more than 5 s in 38 episodes, about 25 of which began within about 20 s of a full-time result (#593). **Where that blocked time goes has not been measured**, so its link to this path is a correlation. The measurement below attributes it.
- **Live updates send the full state twice** to connections with the live transcript on.
  - Each coach recipient gets a full view for its role, after a `matches.load` for that recipient (`SetupApplication.java:1167-1194`). Spectators get re-parsed copies of one shared public view (`ActiveMatchPublisher.java:187-217`).
  - Connections with the live transcript on also get the record, which repeats the state (`LiveMatchTranscript.java:28`).
  - The client replaces its state wholesale (`v2-client.ts:545-546`).
- **Chat** keeps up to 512 messages in every checkpoint (`MatchChat.java:15`).
- **A parked computer worker's fallback load reads the whole checkpoint** to find that the game is still waiting for the same human (`SetupApplication.java:344`, `RecoveryCheckpoint.java:45`). That was about 20 MB in the workload reported on #548 after #575.
- **`MatchTranscript` owns presentation state.** It holds a `DriveTrackerPresentation.History` and feeds it every record (`MatchTranscript.java:18, 124, 156`). That history covers only the current drive, but it reads each record's `state` and `native` (`DriveTrackerPresentation.java:87-100`), which format 4 changes, and restore rebuilds it by reading every record.
- **`COMPLETION_PENDING` is no longer sent.** #537 (merged 2026-11-15, `0261fd53e`) removed it from the server, and nothing under `ffb-server/src/main` mentions it. The browser (`setup-protocol.ts:15, 39`, `setup-recovery.ts:28`, `v2-client.ts:30`) and the computer player (`computer-player/src/main.mjs:150`) still handle it.
- **ADR 0007 is accepted but not built.** The code writes transcript format 2 (`MatchTranscript.java:15`), and the phase markers don't exist yet. No open ticket for them was found on 2026-11-24. #603 leaves the marker schema and the transcript readers and writers out of scope, and points here for the storage cutover.
- **Accepted ADRs involved:**
  - ADR 0001 (publication)
  - ADR 0002 (bounded transcript)
  - ADR 0004 (recovery journal)
  - ADR 0006 (single worker and request deadline)
  - ADR 0007 (presentation markers, transcript format 3; accepted, not built)
  - ADR 0008 (staged placements)
  - ADR 0012 (versioned JSON over WebSockets)
  - ADR 0013 (application seams). It put match storage behind a repository interface and anticipated "a future canonical event log".
- **Limits of the evidence:** the split of the 39 MiB by field is estimated, not measured. The size of the native snapshot alone, and its growth over a match, are not measured. Soak figures come from computer-driven matches of about 113 decisions each.

## Maintainer decisions

Decisions 1–6 are from 2026-11-22. Decisions 7–20 are from 2026-11-24. Decisions 14–20 accept the recommended answers to [Codex's review](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/651#issuecomment-7146170290) of the implementation plan.

1. **Scope.** This issue owns the storage and sync decision and its ADR. #550 keeps engine structure and the ruleset-boundary review, and treats this ADR as an input: it settles the persistence and presentation seams that #550's review maps.
2. **One record serves live play, catch-up and replay.** The transcript record evolves to format 4:
   - It keeps the decision, ordered outcomes (dice, movement, resolved effects) and the ADR 0007 presentation markers.
   - It replaces the full `state` with a **public patch**. An earlier version of this request also listed a public-state digest here, but the maintainer had not decided it. Decision 12 now rules it out for now.
   - **Keyframes** (a full public snapshot) are written periodically for seeking.
   - Live viewers receive the record, catch-up reads it, and replay plays it.
3. **Replay storage is written in the move's own transaction.** The transaction updates the restore snapshot, writes the exact-request receipt, and appends the record plus any keyframe. There is no background replay worker. Revisit only if storage cost requires it.
4. **Publish after commit**, as today.
5. **Old formats are retired.** All existing matches are cleared, active and completed. Older artifacts are refused with `RECOVERY_UNSUPPORTED`, as in ADRs 0007 and 0008.
6. **Required outcome: the cost of a move does not grow with match length.** It is held to ADR 0006's p99 ≤ 100 ms per task. The numbers are set after the measurement below.
7. **The new storage and the replay reader are built together.** Which part comes first within that work is open.
8. **Match log and chat.** The records are the durable log. The client caches and pages what it has received. Neither the game log nor chat is in the restore object.
9. **Full-time and load timing are part of this measurement**, replacing #593's instrumentation.
10. **#596's interim risk is accepted for now.** Until the new storage ships, a long match could stop at the size limit. This ADR and its resulting work are the next task on the schedule.
11. **Order of work.** #550 starts after this issue is settled. #548 was to be the first item completed after this review, until decision 13 folded it in here.
12. **No state digest for now.** Revisit if the server and client are found to disagree about the pitch state.
13. **#548 is folded into this issue and marked superseded.** With it gone, decision 10 makes this issue the next work.
14. **What the other coach and spectators receive.** Who must decide and what kind of decision it is, with movement, dice, pass ranges and Kick options. They don't receive the other coach's legal actions, prompts, forecasts or menus.
15. **Retries use private indexed receipts.** Each accepted gameplay request has one small entry, found by match, actor and request ID.
16. **Markers exist before matches are cleared.** ADR 0007's markers are emitted before the one-time clear and the switch to the new storage.
17. **Where history is checked.** Restore checks the newest and recent records. A page read fails on a gap. A separate audit verifies everything, against a running digest that is updated as each record is stored and sealed at full time. It covers stored history, not live state, so decision 12 stands.
18. **A parked computer coach is told when nothing changed**, with a reply that names the revision. Otherwise the computer player keeps receiving full snapshots.
19. **Drift is always checked in tests and soaks.** Live keyframe comparison and reports are included as ticket 17 of #651.
20. **Chat keeps its 512-message limit.**

## Scope and deliverables

1. **Request spec.** This file, `docs/specs/match-state-sync-adr.md`, linked to this issue.
2. **Measurement**, specified in [#650](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/650) (`docs/specs/match-state-sync-measurement.md`). Its outcome is `docs/specs/match-state-sync-measurement-outcome.md`, reproducible from a committed script:
   - a breakdown by field of a soak-sized match's restore object (native snapshot, game log, events, transcript `state` and `native`, request history, chat, views);
   - the size of a compact restore snapshot at about revision 50 and about revision 700;
   - the time to write that snapshot plus append one record in MariaDB, early and late in a match;
   - the time to restore from the compact snapshot;
   - the time full time spends in each phase on the serial worker: materializing events and transcript, building and validating the artifact, serializing, the store, and release, with the decoded size and the queue length at the start (from #593);
   - the time a `setup/load` restore spends in each phase (from #593);
   - checkpoint, `complete()` and JDBC store timing, with JFR and GC logging on any request over 500 ms, reported as p50, p90 and maximum (from #548);
   - the cost of a first compaction in a fresh JVM (from #548);
   - the journal entry size and the real compaction interval;
   - the cost of native capture, early and late in a match;
   - the cost of a parked computer worker's fallback load (from #548);
   - the engine copies made for previews: how many per move, and the cost of each;
   - the size of a public patch and of a keyframe, and the bytes sent per recipient per move today.

   Measure against decoded size (about 23–28 MB at 320–500 revisions), not the 0.7 MiB compressed size (#548). Prefer JFR or harness timing. If phases can't be separated without production instrumentation, that instrumentation goes in its own reviewed PR.

   These rules come from #548:
   - Measure the live server path, with real repositories and MariaDB, not mocks.
   - Use a separate compose project. Don't touch the shared soak stack or ADC while a soak runs.
   - Keep large raw evidence local and commit a summary.
   - The scripts share the statistics and file helpers that `tools/diagnostics/analyze-history-scaling.mjs` and `analyze-checkpoint-first-warm.mjs` now duplicate, and have a `*.test.mjs`. They normalize line endings before hashing, so that `analyzerSha256` matches on LF and CRLF checkouts.
3. **Proposed ADR**, `docs/adr/<slug>.md`, status `proposed`. It is numbered when the maintainer accepts it, before merge, under the rule set by #610. It states:
   - which records it **supersedes wholly**: ADR 0002 and ADR 0004. Their governing choice, where durable match state lives and what a stored record is, changes. A partial supersession of 0002, which 0004, 0007 and 0008 have already amended, would leave readers combining four or five records to find the rule in force. The ADR therefore restates every surviving guarantee itself (see "Carried-forward guarantees" below).
   - which it **amends**:
     - ADR 0001: publication sends records plus a per-role envelope.
     - ADR 0007: its format 3, which isn't built, becomes format 4, with the markers in the record.
     - ADR 0012: full-snapshot recovery and full resync for slow clients become a snapshot plus revisioned deltas. Its governing choice, versioned JSON over WebSockets, still holds, so under #610's template rule this is an amendment, not a supersession.
   - which it **extends with a dated history entry**:
     - ADR 0013: the new storage sits behind that record's repository boundary, and the format-4 record is the "future canonical event log" it anticipated.
     - ADR 0008: its alternatives leave per-event snapshots for other actions to #548, which this ADR now takes over.
   - that ADR 0006 is unchanged.
4. **Implementation plan**, in [#651](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/651) (`docs/specs/match-state-sync-tickets.md`): twenty-six tickets, revised after Codex's review of 2026-11-24. Tickets 1–3 don't depend on the ADR and are filed as #652, #653 and #654. The rest are filed when the ADR is accepted. They carry every item from #548.

**Sequencing:** #610 has merged (PR #612), so the measurement and the ADR draft are both unblocked. The measurement (#650) comes first.

**Excluded:**
- Production code changes. Measurement scripts are allowed.
- Engine structure, Step and SequenceGenerator restructuring, and the ruleset-boundary audit (#550).
- Parallel match execution (ADR 0006's revisit condition).
- Binary encoding and transport compression, unless the measurement shows that payload size still matters once deltas exist.

## Required decision content

- **Restore snapshot:**
  - What it contains: the native snapshot and continuation; dice and random-number state; clocks; pending decisions; save/resume state; computer memory; counters (storage generation, gameplay revision, record and chat sequences, decision limit); the absolute command number and the last captured command; the unfinished movement narrative; the phase, announcement, block and drive-tracker cursors.
  - Proof that its size does not grow with match length.
  - Restore reads no history.
- **Identity and order.** The storage generation (compare-and-swap), the gameplay revision (one record each) and the chat index are separate counters, and a staged placement's sequence is transient. The ADR defines which operations append a record, advance the generation, only acknowledge, or update a stage. Saves, resumes, proposal expiry, refused setups, clock maintenance and abandonment change stored data without a new revision. Live changes at one revision need their own order.
- **Exact-request receipts (decision 15):** the canonical fingerprint, accepted revision, code and any error details; a reused ID with different content; two devices on one seat; save/resume's limit of 256 requests, which is a hard limit; chat's own author-scoped uniqueness, including after the match ends.
- **Row summary, for the lobby and for computer probes (the probe part is from #548).** Each committed write stores a small summary in the current-state row, bound to the storage generation, with absolute expiry times and maintenance inputs: gameplay revision, actor, prompt actor, phase, half, turn, score and save/resume status. The lobby listing reads it instead of the snapshot.
  - An unchanged computer probe is answered from it with decision 18's reply, without reading or hashing the snapshot. The summary can't return the full view that the worker requires today (`computer-player/src/main.mjs:168`).
  - It accounts for everything `RecoveryCheckpoint.computerReply` checks today: failed and terminal state, save/resume state, a pending reactive decision, and the human-inactivity deadline. It also accounts for pending maintenance.
  - When it can't answer safely, the full restore path answers.
- **Format-4 record:**
  - fields, recording domain facts only: the accepted decision, dice, resolved effects, presentation markers, and patches to public entities. Never browser commands or diffs of the rendered view. The application-seams record says "Do not equate recorded UI commands with a stable domain-event schema";
  - the shape of the public patch: whole players and whole top-level public sections, with insertion, removal and a stable order for players, and explicit removal of a section, distinct from unchanged and from `null`;
  - no state digest (decision 12), and the running history digest of decision 17, with its canonical bytes and its treatment of keyframes and chat;
  - keyframe cadence.
- **Public and private (decision 14):** one public view, built once per revision from an explicit allowlist, with parity fixtures for both coaches, a spectator and replay. The allowlist also covers accepted decisions and native outcomes, because today the recorder copies the request and removes only `computerDecision`. Screens depend on the list of actions today: without it, a healthy spectator is told to reconnect (`GameView.tsx:1136`). Who must decide needs a source other than the first available action (`SetupSession.java:1458` on `main` at `eb5057d02`).
- **Live delivery and catch-up:** join and resync send a snapshot plus the latest page, with a stated base revision; the size of the bounded in-memory tail, in records and bytes; duplicate and gap detection; drift checks (decision 19); spectators. A replaced or dropped update always leads to a bounded, authorized snapshot, because a later delta can otherwise erase the only path to its base (`PublicationDelivery.java:50-57`). Every request keeps its own reply. This stays consistent with ADR 0001's best-effort delivery and ADR 0008's staged-placement messages.
- **Transaction:** what the move's transaction writes; behaviour on uncertain commit; exact retry; crash between commit and publish.
- **Match log and chat:** as decided (decision 8), settling the detail listed below.
- **Server memory:** no history stays resident beyond the tail. State the consequences for `MatchMemoryBudget`, for #543's frozen history, and for the drive tracker's current-drive summary, which restore now rebuilds from every record.
- **Ruleset neutrality:** record and patch shapes in shared protocol code carry no BB2025-specific types, consistent with #550's ruleset-separation principle.
- **Compatibility:** retire the old formats (decision 5); new version markers; refuse old data explicitly.
- **Carried-forward guarantees.** Because 0002 and 0004 are superseded wholly, the ADR classifies each of their rules, so that none is lost by accident.
  - **Keep, restated in the new ADR:**
    - One durable boundary per action. An action is accepted and published only after commit.
    - An exact retry appends nothing. The indexed receipt now provides this.
    - Storage admission rejects an action before native execution, never after.
    - Recovery never reruns commands, dice, clocks or optional decisions.
    - Log lines and replay derive from recorded native outcomes, in canonical home orientation, without running the engine again.
    - Records keep immutable account, match-team, player and side IDs, and names resolve on read. Time comes from the server's UTC epoch-millisecond clock.
    - Reads are paged. Live delivery may drop a frame, because authorized readers can catch up.
    - Spectator chat uses the same persisted sequence and access check, and never invokes a game command.
    - Staged placements create no record (ADR 0008's amendments to 0002 and 0004).
    - Missing durable records never silently roll a match back. This was the reason for 0004's separate head and base generations. Now, restore checks the storage generation, the newest record and the recent records in one consistent read, and treats a mismatch or gap as `RECOVERY_CORRUPT`. Older damage is found by page reads and the audit (decision 17).
    - Computer-coach availability (0004's schema marker 10, `computer_work_until_epoch_ms`) is committed in the same transaction as the move. It moves to the current-state row.
    - Restore validates the native state through the existing native restore path.
    - Every accepted revision is indexed.
    - A terminal digest covers the completed transcript. It becomes the running digest of decision 17, because hashing every record at full time would grow with the match.
    - Expiry and abandonment aren't full time. The match stays activated, the lobby derives its status from deadlines, and dismissal stays per account (#639, #643, #644).
    - The computer coach's cold load path keeps working. `RecoveryCheckpoint.computerReply` reads stored per-role views from the checkpoint, so the snapshot either keeps those views or the ADR says how they are rebuilt. Decision 18 covers the unchanged case.
  - **Replace:**
    - the single-blob artifact;
    - the base-plus-journal layout, with its separate generations and compaction;
    - the completed-result size limits;
    - recovery version 7.
  - **Retire:**
    - freezing each native log command's JSON for recovery, since the native log is no longer persisted;
    - readers for older formats (decision 5);
    - invalidating recovery cursors when a native log is reset or replaced;
    - classifying legacy recovery heads for computer availability, since existing matches are cleared;
    - the format-1 cleanup.
- **Budget:** targets for durable write, restore, publication and full time, set from the measurement, all flat in match length.
- **Revisit conditions:** for example, storage cost per match, row counts, or measured payload sizes that justify binary encoding.

## Risks and mitigation ownership

| Risk / trigger | Consequence | Required mitigation or decision | Owner / tracking | Remaining risk |
| --- | --- | --- | --- | --- |
| A client misses a record, or a later update replaces it | Pitch shown differs from the server | Gap detection, and a mandatory snapshot repair even when no further move follows | Ticket 13 | — |
| A client or server defect makes a patch wrong at the right revision | Pitch shown differs from the server; replay shows it too | Rebuild checks in tests and soaks; live keyframes and reports (decision 19) | Tickets 5, 13 and 17 | A wrong pitch can last until the next keyframe |
| A reader of `events` is missed | Replay, the full-time view or a concession breaks | Decision 7; the list of readers above | Tickets 4, 9a and 9b | — |
| A long match reaches the size limit before the new storage ships (#596) | The match stops early with `MATCH_SIZE_LIMIT` | Accepted for now (decision 10) | Maintainer | Until the new storage ships |
| The native snapshot hides data that grows | Cost grows with match length again | Measure at revisions 50 and 700; size regression test | This ADR; storage ticket | Rare paths not covered by the measured matches |
| Response lost after commit | Coach retries a move | Indexed receipt written in the same transaction (decision 15) | Ticket 7a | — |
| Crash between commit and publish | Viewers miss a record | Catch-up from the tail or the record table; snapshot on reconnect | ADR 0001 behaviour, this ADR | None beyond today's best effort |
| BB2025 shapes leak into the shared record | Breaks #550's ruleset separation | Neutral record schema; review checklist | This ADR; #550 | — |
| Old records are damaged or removed | A replay fails, or a digest doesn't match | Contiguous appends; page reads fail on a gap; the audit (decision 17) | Tickets 7a, 9a and 10 | Found when read or audited, not at restore |
| The cutover comes before the proof | A fault is found after matches are cleared | Markers, the acceptance gate and a rehearsal come first (decision 16) | Tickets 11a, 12 and 19a | — |
| Completed replays are lost | Earlier matches cannot be replayed | Accepted by decision 5 (build phase) | Maintainer | — |

## Alternatives and open decisions

**Considered:**
- **Keep base plus journal and tune the limits** (#596 option a). Rejected: cost still grows with match length.
- **Assemble replay in a background worker** (Codex's notes). Deferred by decision 3.
- **Publish before commit.** Rejected by decision 4.
- **Rebuild state by re-executing inputs deterministically.** Rejected: ADR 0004's rule that recovery never reruns commands stands.

**Open, for the maintainer:**
- **The plan's working assumptions**, listed in #651 and kept by the review: patch shape, one writer per match, the row summary and the order of test changes. They become decisions when the ADR is accepted. So does the table of what each operation writes.
- **Detail for decision 19.** A client can't send its whole state in a drift report, because inbound messages are capped at 16 KiB (`BrowserMatchSocket.java:15`) and one state is about 16–20 KB. Today's soak reads state straight from the wire (`overnight-soak.mjs:125-135`), so it has to use the client's applied state.
- **Detail for the ADR to settle under decision 8:**
  - The native engine log leaves the restore object and is never persisted. Only the tail not yet captured stays in memory.
  - The match log coaches read is derived from the records, so the record table is the replay storage.
  - Each chat message is an appended row with its own index. The ADR defines its order relative to records, and the restore object keeps only counters.
  - The client owns display, retention and pagination of what it has received, as a cache. The server serves older pages on reload, device change, spectator join, dropped frames and replay.
  - Caching across reloads in browser storage is optional and deferred.
- Keyframe cadence and tail size, after the measurement.
- The numeric targets under decision 6, after the measurement.

## Related tickets

- **#548, #593, #594 and #596 are superseded by this design.** The ADR PR references all four. Each is closed as *not planned* once that PR merges; GitHub closing keywords would close them as *completed*, so close them by hand. They are labelled `wontfix` until then.
- **Where #548's work went** (decision 13):

  | #548 item | Place here |
  | --- | --- |
  | Completion timing; first compaction in a fresh JVM | Measurement |
  | Serialization budget, and the action when a match reaches it | Decision 6's flat-cost targets |
  | Real-play memory (#524 AC3 and AC4) | Already met: #524 closed on 2026-11-21 with a three-hour run. The full-cache case left to #594 is in ticket 19a |
  | Per-commit turn summary (after #575) | Measurement; required decision content; ticket 7b, as part of the row summary |
  | Transcript decoupled from presentation | Ticket 3 (#654) |
  | Client `COMPLETION_PENDING` handling | Ticket 2 (#653), which now includes the computer player |
  | Analysis scripts | Measurement; ticket 11c |
- **#593, #594 and #596** keep their objectives in ticket 19a: the queue stall, the full cache and the long human match.
- **#550** consumes this ADR as an input, and starts after this issue is settled. #595 and #597 don't depend on this design.
- **#610** (PR #612, merged) promoted the founding records as ADRs 0010–0013 and numbers an ADR on acceptance, before merge.

## Acceptance criteria

### Draft ready for maintainer review

- The measurement outcome exists, is reproducible from a committed script, and contains no credentials or private data.
- The ADR is drafted as `proposed` and covers every item under "Required decision content".
- It names the records it supersedes, amends and extends, and states its consistency with ADRs 0006, 0007 and 0008 and with #550's ruleset principle.
- It contains the carried-forward classification of every rule in ADRs 0002 and 0004.
- The open decisions are either resolved with the maintainer's recorded approval, or listed as open.
- The candidate tickets are listed, and they carry every item that came from #548. Links are checked, and every cited symbol exists at the reviewed revision.

### Accepted

- The maintainer's approval is recorded with a date.
- In the same PR, under #610's template:
  - ADRs 0002 and 0004 are marked `superseded`, with links both ways;
  - ADRs 0001, 0007 and 0012 carry dated amendments;
  - ADRs 0008 and 0013 carry dated history entries;
  - the architecture map's invariants on durable boundaries, recovery and stored formats are updated.
- No accepted ADR gains a forward pointer to this decision before acceptance.
- The follow-up tickets are filed.
- #548, #593, #594 and #596 are closed as *not planned*, each with a link to the ADR.

## Validation and handoff

- **Now:** documentation and measurement scripts only. Check links, and confirm the cited symbols at the reviewed revision.
- **Branch and PR:** create a new branch. The PR says `Part of #<this issue>`. Do not merge, and do not mark the ADR accepted without the maintainer's approval.
- **Later:** the implementation tickets prove decision 6 with early- and late-match comparisons at the default heap, zero history reads on restore, and process-death tests around the commit. They also run the [retained-move acceptance case](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/611#issuecomment-7122189387): a legal move whose checkpoint write fails before commit, retried once through cold recovery, advances the durable revision exactly once within the request budget. The `codex/retained-move-retry` patch is on hold until that check runs.
- **Later, from #548:**
  - **Memory.** With the resident cache full, a large match finishes, both coaches read it, and another match is served meanwhile, at `-Xmx768m` with 32 sessions (`MatchMemoryBudget.java:10`). This replaces #548's real-play item, which #524 already met, and carries #594's full-cache case.
  - **Row summary.** Unchanged probes and the lobby listing neither read nor hash the snapshot, and a committed human move still wakes the computer.
  - **Full time.** With `COMPLETION_PENDING` handling removed, a completed match still shows full time in the browser, and the computer player still ends its work.
  - **Decoupling.** The transcript has no dependency on presentation classes, and the moved drive-tracker history has unit tests.
  - **Scripts.** `analyzerSha256` matches on LF and CRLF checkouts, and the shared helpers have tests.
