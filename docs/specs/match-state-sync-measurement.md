# Feature: Measure match storage, restore and full-time cost for the state-sync ADR

Issue: [#650](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/650)

Part of #611. Extended on 2026-11-24 after [Codex's review](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/651#issuecomment-7146170290) of the implementation plan, with the maintainer's approval.

## Abstract

#611 proposes a compact current-state row, append-only revision records and small live patches, so that the cost of a move doesn't grow with match length. Several of its claims are estimated, not measured. Measure today's costs and a test-only prototype of the proposed storage on a full 22-player match, and report what each number means for the ADR.

## Objective

The maintainer can set #611's numeric targets, and can see whether any of its choices has to be reopened. Every figure can be reproduced from a committed script.

## Context / Existing Behavior

Line numbers are at `main` `eb5057d02`.

- **The checkpoint grows all match.** It carries the native game log, `eventsJson`, the transcript and the request history (`SetupSession.java:402-520`). The split of the 39 MiB by field is estimated. The size of the native snapshot alone, and whether it grows, has never been measured.
- **Full time.** `SetupSession.completedMatch` (`:1608`) builds the whole match. `SetupApplication.java:730`, `:829` and `:1061` store it. In the 2026-11-20 soak, requests waited more than 5 s in 38 episodes, and about 25 of them began within about 20 s of a full-time result (#593). Where that time goes hasn't been measured.
- **Compaction** rewrites the base at least every 31 entries, or after 2 MiB (`RecoveryJournal.java:12-14, 41`). The cost of a first compaction in a fresh JVM is unknown.
- **Native capture** scans the game log from command 1 (`NativeOutcomeCapture.java:17-29`).
- **Previews copy the whole engine, game log included.** `BlockPreviewPresentation.copy` (`:117-122`) and `PlannedBallTransfer` (`:43`) call `toJsonValue(true, 0)` and rebuild every logged command. The acting coach's view makes one copy for each player that can declare a block and has an adjacent target (`SetupSession.java:1282`). The cost hasn't been measured.
- **A parked computer worker's fallback load** validates the whole checkpoint to find that the human still owes the decision (`SetupApplication.java:334-349`, `RecoveryCheckpoint.java:45`).
- **Earlier evidence is limited.** `HistoryScalingWorkloadTest` uses repeated setup placements, a pitch with two players and an in-memory repository. It excludes SQL and full time, and with no opponents on its pitch it never reaches the block previews.
- **Available harnesses:**
  - `TrainingEngineHost` and `computer-player/training` play whole Human and Orc matches in one JVM, with synthetic teams and no accounts.
  - `RecoveryJournalMariaDbTest` and `OperatingEnvelopeTest` use the real JDBC repositories on an isolated schema.
  - `BrowserRequestTiming` already names the checkpoint, serialize, SQL, restore and publication phases (`:30-31`).

## In Scope

Measure each item early (about revision 50) and late (the last revision before full time, about 700 in the November soak). Today's code is the baseline, at a named commit before #652's change. Items 2–5 and 9–11 also measure a test-only prototype.

| # | Measure | What it decides |
| --- | --- | --- |
| 1 | Decoded and compressed size of the restore object by field: native state without the log, game log, events, transcript `state`, transcript `native`, request history, chat, both views, the rest | Whether the four growing arrays account for all the growth |
| 2 | Size of a compact snapshot: the fields listed under "Restore snapshot" in #611, with no history | Whether the snapshot is flat |
| 3 | Time to update that snapshot, append one record, write one indexed receipt and advance the running digest in one MariaDB transaction, next to today's journal append and compaction. Include finding a receipt among the 700 of a late match | Decision 3 (same transaction); decisions 15 and 17; the write target |
| 4 | Time to restore: today's path by phase, and decoding plus native restore from the compact snapshot | The restore target |
| 5 | Full time by phase on the serial worker: materializing events and transcript, building and validating, serializing, storing, releasing. Include decoded size, and the queue length at the start. Also the prototype of the new full-time write (final state, sealed digest, result), and a `setup/load` restore by phase | Whether #611's design removes the full-time stalls; the full-time target |
| 6 | Checkpoint, `complete()` and JDBC store timing; the first compaction in a fresh JVM; journal entry size and the real compaction interval | The baseline |
| 7 | Native capture time per move | Whether per-move work outside storage grows |
| 8 | Engine copies made for previews: how many per move, and the time and allocation of each | Same as 7 |
| 9 | Size of a public patch per move, by whole-entity replacement between successive transcript states; size of a keyframe; bytes sent per recipient per move today | Keyframe cadence, tail size, and whether binary encoding is worth revisiting |
| 10 | The parked computer worker's fallback load: today, reading the compact snapshot, and the "nothing changed since revision N" reply from a row summary | The cost of decision 18's reply |
| 11 | Building and sending one move: the public projection, the patch, the keyframe, the commit, each role's envelope, and delivery to several recipients. Time, allocation and serialized bytes of each, and the whole worker task | Whether the design fits ADR 0006's p99 of 100 ms |

Also in scope, from #548: the scripts share the statistics and file helpers that `tools/diagnostics/analyze-history-scaling.mjs` and `analyze-checkpoint-first-warm.mjs` duplicate. They have a `*.test.mjs`, and normalize line endings before hashing.

## Out of Scope

- Production code changes, schema migrations and stored-format changes. Prototype tables live in test code and an isolated schema.
- Fixing anything the measurement finds, including the preview copies.
- Choosing the targets. The outcome note proposes numbers, and the maintainer sets them.
- The memory run with a full resident cache. Ticket 19a of #651 runs it on the new storage.
- Hosted Development, and any external service.

## Functional Requirements

- Inputs come from complete 22-player matches played by the real rules engine, not from the two-player fixture.
- Database figures use the real JDBC repositories and MariaDB, not mocks or in-memory repositories.
- Size and time are reported against decoded size, next to compressed size.
- Each timed figure reports sample count, p50, p90 and maximum. It reports p99 only with at least 100 samples in that group. Where a threshold is tested without enough samples, the result is marked inconclusive. Use at least three fresh JVMs at `-Xmx768m`.
- Ordinary and keyframe moves are reported separately, early and late. Queue wait is measured apart from the task itself.
- The runs include quiet and active computer probes, several recipients, and a rare decision with a large output.
- JFR and GC logs are recorded, and the note explains any request or phase over 500 ms.
- The outcome note names the measured revision, the JDK, the hardware and the database image, and states each prototype's omissions.
- Committed files contain no credentials, account identifiers or private match data.

## Acceptance Criteria

- `docs/specs/match-state-sync-measurement-outcome.md` reports all eleven items, early and late, with the command that reproduces each one.
- Each item ends with what it means for #611, using these rules:
  - **Snapshot:** if the compact snapshot is more than 25% larger late than early, name the fields that grew.
  - **Write:** if updating and appending has a p99 above 100 ms on a healthy database, say that decision 3 needs the maintainer.
  - **Full time:** state the share of the blocked time that the named phases explain. If most of it lies elsewhere, name where, so that it can have its own ticket.
  - **Per-move work:** for items 7, 8 and 11, state the late cost as a multiple of the early cost.
  - **Worker task:** for item 11, give the whole-task p99 for ordinary and keyframe moves against 100 ms, or mark it inconclusive. The 2,000 ms request ceiling is a different test.
- The note proposes targets for durable write, restore, publication and full time, each with the figures behind it.
- The harness checks that its input match has a non-empty game log and non-empty native outcomes, and fails otherwise.
- `analyzerSha256` matches on LF and CRLF checkouts, and the shared helpers have tests.
- A summary is posted on #611.

## Constraints

- Use a separate Compose project. Don't touch the shared soak stack or ADC while a soak runs.
- Ask the maintainer before any run that needs a signed-in browser profile.
- Keep large raw evidence local and commit a summary.
- If phases can't be separated without production instrumentation, stop and propose that instrumentation as its own PR.
- Preserve ADR 0006: one engine worker, and the 2,000 ms request ceiling.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- **Input match.** Play a full Bogruk match through the training harness, and save `recoveryArtifact()` at about revision 50 and just before full time. If the match is shorter than 700 revisions, use the longest of several seeds and report its length.
- **Trap:** `BogrukEngineFixture.java:69` mocks `ServerCommunication`, and only the real one appends to the game log (`ServerCommunication.java:120`). With the mock, the log and the native outcomes stay empty. Use a real `ServerCommunication`, as `SetupSessionMovementTest.java:1385` does.
- **Location.** Test classes in `com.fumbbl.ffb.server.match` can reach the package-private session and repository code, as the existing workload tests do. Keep them opt-in by environment variable.
- **Restore prototype.** No production code restores from a compact snapshot. Measure decoding, native `initFrom` without the log, and building both views, and list what that leaves out.
- **Full-time stalls.** One match finishing alone can't show queue effects. If the phase times don't account for waits of 5–14 s, propose a bounded concurrent run with timing (`-Dffb.browser.timing=true`), JFR and GC logging, and ask before starting it.
- **Previews.** #652 removes the log from the preview copies. Measure item 8 on a commit before it, and report #652's own before-and-after figures beside it.
- **Public view.** The prototype projects the public fields named in decision 14 of #611, and lists what it leaves out. It computes no actions, forecasts or previews.

## Validation

- Create new or update existing regression tests as required: tests for the shared analysis helpers, and a smoke mode for the harness.
- Run the new harness's smoke mode and the analysis tests (`node --test`), plus the focused server tests for any test class the work touches.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
