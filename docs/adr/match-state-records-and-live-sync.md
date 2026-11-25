# Store a match as its current state and append-only records, and send live changes from the same records

Status: proposed
Issue: [#611](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/611)
Request: [match state, history and live sync](../specs/match-state-sync-adr.md)
Plan: [#651](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/651) ([tickets](../specs/match-state-sync-tickets.md)), with [Codex's review](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/651#issuecomment-7146170290)
Measurement: [#650](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/650), [reported](../specs/match-state-sync-measurement-outcome.md), with evidence on [storage](../reviews/650-state-sync-storage.md), [moves](../reviews/650-state-sync-moves.md) and [full time](../reviews/650-state-sync-full-time.md)
Evidence revision: `main` at `eb5057d022865f255005c0cd86f5101d503b37a3`. Line numbers are at that revision.
On acceptance, supersedes: [ADR 0002](0002-bounded-match-transcript.md), [ADR 0004](0004-durable-recovery-journal.md)
On acceptance, amends: [ADR 0001](0001-active-match-publication.md), [ADR 0007](0007-server-owned-turn-sequence-presentation.md), [ADR 0008](0008-staged-placements.md), [ADR 0012](0012-versioned-browser-protocol.md). Extends [ADR 0013](0013-application-seams.md).

Today a match's whole history is part of its recovery document. Compaction, restore, full time and replay all read or write it, and a move that triggers compaction gets slower as the match goes on. Each browser also receives the full state on every move. This ADR stores a compact current state and one append-only record per accepted decision, in one transaction, and sends browsers the same record. **The cost of a move then doesn't grow with match length.**

This is a draft. **D1–D29** mark the maintainer decisions recorded in #611. Everything else is proposed and becomes a decision only when this ADR is accepted.

## Decision and boundaries

### What is stored

For each match, under one match ID:

| Part | Content | Grows with the match |
| --- | --- | --- |
| Current-state row | Restore snapshot, storage generation, row summary, terminal state, sealed digest | No |
| Records | One per gameplay revision, in format 4, append-only (D2) | Rows only |
| Keyframes | The complete public view at chosen revisions, stored with their record, with one at revision zero (D2) | Rows only |
| Receipts | One small private entry per accepted gameplay request (D15) | Rows only |
| Chat | One row per message (D8), with the 512-message limit (D20) | Rows only, up to the limit |

The native engine log is never persisted. Only commands that haven't been captured yet stay in memory.

### Identity and order

Three counters stay separate:
- The **storage generation** is the compare-and-swap value for the current-state row. Every durable write to the match state advances it.
- The **gameplay revision** identifies a record. One accepted decision makes one revision and one record.
- The **chat index** orders chat messages within the match. Each message also names the gameplay revision it was sent at, which places it among the records.

The table below is D24 and D26. A staged placement's sequence stays transient (ADR 0008). Live changes at one gameplay revision carry the storage generation, so that a browser orders them and doesn't discard a valid save update.

| Operation | Record | Generation | Other |
| --- | --- | --- | --- |
| Activation | Revision 0, with a keyframe | First | The storage version is stored with the unpublished seed |
| Accepted decision, confirmed stage, declined or timed-out reaction, concession, full time | Yes | Advances | Receipt, except for a timed reaction |
| Refused setup outside staging | No | Advances | Receipt with the errors |
| Save and resume steps, proposal expiry, abandonment, decision-clock maintenance | No | Advances | Save/resume keeps its own request limit of 256, which is a hard limit |
| Chat message | No | No | A chat row, checked against the match's terminal state in the same transaction |
| Staged placement, staged refusal, load, exact retry | No | No | In-memory deduplication of staged requests, as in ADR 0008 |

### Restore snapshot

The snapshot holds everything needed to continue, and no history:
- the native engine state without its log: the game, the step stack, the current step, and pass, blitz-turn, prayer and effect state;
- the absolute command number and the last captured command number;
- dice and random-number state, clocks, pending decisions and reactions, pending routes and activations;
- save/resume state with its bounded request window, and computer memory;
- decision-limit and drive-tracker counters, the unfinished movement narrative, and the phase, announcement and block cursors;
- counters for records and chat, and the running history digest.

Restore validates the native state through the existing native restore path, and reads no event, record, receipt, chat or log history. It never reruns commands, dice, clocks or optional decisions.

A command leaves memory only after the write that captured it is known to be durable. Captured outcomes are frozen as JSON at capture, because native commands can hold references to mutable engine values. A failed or unknown write releases the session.

### Records, patches and keyframes

A format-4 record holds domain facts only:
- its revision, kind, actor, server time and stable account, match-team, player and side IDs;
- the accepted decision, reduced to an allowlist;
- the ordered native outcomes (dice, movement, resolved effects), reduced to an allowlist and in canonical home orientation;
- ADR 0007's ordered `presentation` markers;
- a public patch.

It never holds browser commands, rendered views, private data or BB2025-specific types in the shared record shape.

A **public patch** carries each changed player and each changed top-level public section whole (D28). Players are keyed by ID, can be added and removed, and keep a stable order. A section that ends is removed explicitly, which is different from unchanged and from `null`. A keyframe is the complete public view at its record's revision.

The public projection is built once per revision, on the engine worker, after native resolution and capture and before the transaction. It computes no actions, forecasts or previews. The patch is the difference from the previous committed projection, and a keyframe revision reuses the same projection.

Decoding is strict and versioned, and refuses unknown versions.

### Public view and coach offers

There is one public view (D14). Its outline here is accepted, and ticket 20 proposes the exact field list for the maintainer's approval (D27). It holds player, team and ball facts, score, phase, weather, revealed outcomes, phase gates, save status, who must decide and what kind of decision it is, and the pass range and Kick options shown on the pitch. Every viewer gets it: both coaches, spectators and replay.

A **coach's offer** is private to that coach: legal actions, prompt identity and options, movement forecast, player action menu, block preview, threats and saved formations. The computer player's data stays on its authenticated channel. Offers never enter a record, keyframe or public message.

Who must decide comes from an authoritative source. Today it can come from the first available action (`SetupSession.java:1458`), and screens read the action list to choose their text (`GameView.tsx:1136`).

Clocks and reaction deadlines are sent as anchors. Staged and provisional moves are overlays on the committed state.

### The transaction

One database transaction per accepted decision (D3):
1. checks and advances the storage generation;
2. replaces the snapshot, the row summary and the computer's availability;
3. appends the record, any keyframe and the receipt, as the next contiguous revision;
4. advances the running digest.

- **Acceptance** waits for the durable commit. An action is accepted and published only after it (D4).
- **Admission.** An action is refused before native execution when the store can't reserve room for its record, snapshot and receipt.
- **Unknown commit.** The session is released, and the next load reconciles from the stored generation and the receipt.
- **Crash before commit.** Nothing happened. **Crash after commit and before publication.** Viewers repair themselves, as below.
- **No worker.** There is no background replay worker (D3). The maintainer kept D3 after #650's results, and can revisit a worker thread for the SQL writes if performance on the GCP hosting is significantly worse ([2026-11-25](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/656#issuecomment-7153291066)).

### Retries

A receipt holds the match, actor and request ID, the canonical fingerprint, the accepted revision, the code and any setup errors. It is found by an indexed lookup, at any age (D15).
- The same ID and content returns the recorded outcome and appends nothing.
- The same ID with different content is refused with `REQUEST_ID_REUSED`.
- An unknown ID with an old expected revision is refused as stale, so a request can't run twice even without its receipt.
- Chat has its own uniqueness by author and request ID, including after the match ends.

### Live delivery, gaps and repair

- Publication follows the commit, rechecks each recipient's access when an update is queued and again after it was deferred, and makes no remote call per recipient (ADRs 0001 and 0006).
- A browser that negotiates it receives the record and its own offer. The public part is serialized once per revision.
- Every request keeps its own correlated reply, even when the record is over the live frame limit or can't be queued.
- The browser checks the match and the base revision, ignores duplicates and detects gaps. A snapshot or a page states the revision it starts from, so nothing is applied twice.
- **A missed update always leads to a repair.** Today a newer state replaces a deferred one (`PublicationDelivery.java:50-57`), which is safe only for complete states. A replaced, dropped or oversized update makes the recipient due a bounded, authorized snapshot, including when no further move follows and when a repair read fails.
- Joining and reloading get a snapshot and the latest page. Short gaps can be filled from a tail that is bounded in records and bytes (see Budgets), or from stored records. The oldest records leave the tail at either limit, and a browser whose base has left it gets an authorized snapshot. Offers never enter the tail. Neither source restores the match.
- Staged placements are delivered as in ADR 0008.
- The computer player keeps receiving full snapshots, and a parked one is told when nothing changed since a named revision (D18).

### Match log, chat and replay

The records are the durable log (D8). Pages are read by range, start from a keyframe, and fail on a gap. The browser caches and pages what it has received, and rebuilds each state with one shared reducer. Neither the log nor chat is in the restore snapshot or in a live state message.

Log lines and replay derive from recorded outcomes without running the engine. Names resolve on read. A finished match is readable by its participants only, as today.

### Full time, concession, expiry and abandonment

Full time and concession store the final state, seal the digest and record the result, without rebuilding the match. The result, the prepared match's lifecycle and the terminal state change together, or every intermediate state is recoverable to the same result after a restart, with no second record or concession.

**Expiry and abandonment aren't full time.** The match stays activated, the lobby derives its status from deadlines, and dismissal stays per account (#639, #643, #644).

A finished match keeps all its records (D21), and so does an abandoned or expired one, with no automatic deletion (D23). The limit on matches held for recovery, which today counts every match ever activated, counts only matches that can still be played (D22). Expiry is recorded only when a request or timer touches the match, so the count applies the lobby's expiry rule to the row summary's absolute deadlines. A deadline that moves later can take the count slightly over the limit.

The out-of-memory quarantine journal, its cooldown and the admission guard are separate and stay.

### History integrity

- **On restore:** the snapshot carries a checksum that is verified, as today's checkpoint does (`RecoveryCheckpoint.java:24`). One consistent read checks the storage generation, the newest record and the recent records. All records missing, the last one missing, or a mismatch is `RECOVERY_CORRUPT`. Missing records never silently roll a match back.
- **On a page read:** a gap fails.
- **On audit:** a separate check verifies every record against the running digest (D17). It detects a deleted, changed or reordered record.

The digest is an ordered commitment that starts at revision zero, advances with each record, and is sealed with its count at full time. It covers record bytes in their stored canonical form, and nothing else (D25). Keyframes can be rebuilt from records, so they aren't covered, and the audit checks them by rebuilding. Chat isn't covered. It is archival integrity, not a live-state digest, which stays ruled out (D12).

ADR 0002 requires a terminal digest, but none was built: the completed result has no digest today (`SetupSession.java:1608-1619`). Decision 17 therefore adds one.

### Row summary, lobby and computer coach

Each durable write stores a small summary (D28) with the storage generation: gameplay revision, actor, prompt actor, phase, half, turn, score, save/resume status, absolute expiry times and maintenance inputs. The lobby reads it, and it answers an unchanged computer probe. The snapshot stays authoritative. When the summary can't answer safely, the full restore path does.

### Drift checks

Tests and the soak check the browser's applied state against the server's at every revision (D19). Keyframes are also sent live. The browser compares its committed state with each one, reports a difference in bounded field paths with no private values, and adopts the keyframe. Reports are rate-limited and logged as one local line, under ADR 0006's logging allowance.

### Server memory

No history stays resident beyond the tail. `MatchMemoryBudget` and the frozen history from #543 are restated for the snapshot and the tail. One record, one page, the tail and the resident cache each have a stated limit.

### Budgets

ADR 0006 is unchanged: p99 ≤ 100 ms per healthy worker task, and 2,000 ms per failed request. The targets below are #650's proposals, accepted as D29. Each must hold early and late in a match (D6), on the real implementation.

| Measure | Target | Measured in #650 |
| --- | --- | --- |
| Durable write of one decision | p99 ≤ 60 ms | p99 in five rounds. Ordinary: 30.7–56.9 ms early, 31.7–98.5 ms late. Keyframe, late: 30.2–80.4 ms. Rare, late: 34.2–118.7 ms |
| Whole worker task, ordinary move and keyframe move | p99 ≤ 100 ms (ADR 0006) | p99 in five rounds. Ordinary: 33.5–57.5 ms early, 32.0–99.2 ms late. Keyframe: 31.0–45.5 ms early, 31.3–81.3 ms late. Rare, late: 35.0–120.1 ms |
| Restore of an active match, through native reconstruction | p90 ≤ 60 ms | Late: p50 35.4 ms, p90 50.1 ms, from nine samples. The current restore took 3,605 ms at p50 |
| Projection and delivery to five recipients, without the commit | p99 ≤ 5 ms | Ordinary: 3.06 ms early, 0.65 ms late. Keyframe: 1.88 ms early, 1.54 ms late |
| Full time | p99 ≤ 100 ms | p99 in five rounds. Ordinary: 30.4–161.5 ms. Keyframe: 48.5–164.7 ms. One round of the five exceeded 100 ms |
| Snapshot size | Late no more than 25% larger than early | 91,336 bytes early, 86,294 late (gzip 9,647 and 9,746) |
| Keyframe cadence | Every 32 revisions | A keyframe is 13,511–13,616 bytes |
| Catch-up tail, per match | 32 records or 64 KiB of encoded public data, whichever comes first. The bytes include the keyframe and the record envelopes | A keyframe and 32 ordinary patches: 17,127–29,872 bytes before envelopes. A late rare patch alone: 11,664 bytes |

- **What was measured.** One machine, an isolated MariaDB, and a prototype that leaves out rules execution, authorization and sockets. The measurements weren't run on the GCP hosting.
- **The write p99 didn't converge.** Late ordinary writes missed 60 ms in two of five rounds, and five full rounds didn't explain why.
- **Restore has a p90 target, not a p99,** because nine samples can't support a p99. D6 asks for p99, so the final proof (ticket 19b) takes at least 100 samples and sets it. ADR 0006's 100 ms still applies to the whole load task. A finished match's restore had a p90 of 73.9 ms, and the target covers active matches only.
- **The role view has no target.** Building the acting coach's view after a late restore took 245.8 ms at p50 (nine samples), which is over 100 ms alone. The other coach's took 4.7 ms. The acting coach's view allocated 114 MB, about twice one log-inclusive preview copy (56.6 MB), so two preview copies probably explain most of it. #652 takes the log out of those copies. The view is measured again after #652, and before the restore work is accepted.
- **The snapshot threshold** is the one #650 was asked to test against. #650 proposed no other size target.

### Formats, compatibility and cutover

- The storage and the replay reader are built together (D7).
- Each match has one storage version (D28), chosen at activation and stored with its unpublished seed. Nothing is written in both formats.
- The new storage is used only in tests until replay, chat, expiry and full time work on it.
- ADR 0007's markers are emitted before any match is cleared (D16). A complete correctness, latency and memory gate and a rehearsal of the schema change, restore and rollback also come first.
- At the cutover, all existing matches are cleared: active, saved and completed (D5). Older data is refused with `RECOVERY_UNSUPPORTED`.
- The journal, compaction, `events`, the request history, the game-log freezing and the completed-result size limits are then deleted.

## What happens to each rule of ADRs 0002 and 0004

| Rule | Outcome |
| --- | --- |
| **0002** A successful checkpoint is the one durable boundary for an action and its outcomes | Keep: the transaction |
| Transcript and native checkpoint share one compare-and-swap artifact | Replace: separate rows in one transaction, guarded by the storage generation |
| Records hold accepted inputs, native reports and model changes, snapshots and chat | Replace: allowlisted decisions and outcomes, markers, patches and keyframes; chat rows |
| A terminal digest | Keep, and build it: the running digest, because none exists today |
| A schema marker identifies format 2 | Replace: a storage version per match, and format 4 |
| Exact retries read the checkpoint and append nothing | Keep: indexed receipts |
| Admission rejects before native execution | Keep |
| The completed result keeps the transcript and digest within 16 MiB | Replace: records stay where they are, and there is no single-result size limit |
| Format-1 matches and replays are removed | Retire: done |
| Native model syncs are the source for reports, dice and movement | Keep |
| Canonical home orientation, with the originating revision | Keep |
| Log lines and replay derive from records without running the engine | Keep |
| Immutable account, match-team, player and side IDs; names resolve on read | Keep |
| Server UTC epoch-millisecond clock | Keep |
| Every accepted revision is indexed | Keep |
| The server pages reads and enforces artifact limits | Keep paging. Replace the limits with limits per record, page and tail |
| Live publications may be lost because readers can catch up | Keep, with a mandatory repair |
| Spectator chat uses the same sequence and access check, and never invokes a game command | Keep the access check and the rule. Replace the shared sequence with the chat index |
| Separate tables would need a multi-row transaction; the layout can change later without changing format 2 | Replace: this is that change, and the format changes too |
| **0004** Base checkpoint, bounded continuations and append-only tails | Replace: current state and records |
| One generation compare-and-swap in one transaction | Keep |
| Recovery assembles the recorded state and validates it through the native restore path | Keep the validation. Nothing is assembled |
| Recovery never reruns commands, dice, clocks or optional-skill decisions | Keep |
| Separate head and base generations, so that missing records can't roll a match back | Keep the guarantee, by the restore check above |
| Periodic compaction | Retire |
| Legacy full checkpoints stay readable | Retire (D5) |
| Acceptance waits for the durable commit | Keep |
| Each native log command's JSON is frozen once for recovery | Retire for storage. Keep freezing outcomes at capture, for the same reason |
| Live command objects and their default serialization don't change | Keep |
| Resetting or replacing a native log invalidates its recovery cursor | Retire with the positional cursor. Absolute command numbers stay. The old rule stays while the old storage exists |
| Computer availability is committed with the same generation (marker 10) | Keep: it moves to the current-state row |
| The recovery artifact is authoritative | Keep: the snapshot is, and the summary is derived |
| Legacy heads are classified for availability | Retire |
| Migration preserves the checkpoint's format version | Replace: cleared at the cutover |
| Native restore and the computer's stored-view path require recovery version 7 | Replace: a new version. The stored per-role view is rebuilt, or answered as unchanged (D18) |
| **0008** (its amendments to both) Staged placements create no record, and recovery returns to the stage's base positions | Keep |

## Effect on other records

Applied only on acceptance, in the accepting revision:

| Record | Change |
| --- | --- |
| ADR 0001 | Amend. Publication sends a record and a per-role offer, and a missed update always leads to a repair. Enrollment, access and best-effort delivery of each message stand |
| ADR 0006 | Unchanged |
| ADR 0007 | Amend. Format 3, which was never built, becomes format 4. `presentation` is part of the record. Its durability, ordering and cursor rules stand, read against the transaction here |
| ADR 0008 | Amend. A confirm makes one record, one current-state write and a receipt, instead of a state snapshot, a checkpoint and a request-history entry. Its rules on staging, delivery and limits stand |
| ADR 0012 | Amend. Full-snapshot recovery and full resync for slow clients become a snapshot plus revisioned changes, with snapshot repair. Versioned JSON over WebSockets stands |
| ADR 0013 | Add a history entry. Storage stays behind the repository interface, and the records are the "future canonical event log" it anticipated, holding domain facts and not recorded UI commands |
| Architecture map | Update the invariants on the durable boundary, recovery, stored formats and format 3 |
| `CONTEXT.md` | Apply the glossary below |

### Glossary on acceptance

> **Match checkpoint**:
> The durable state from which an unfinished match can resume at its last accepted decision. It carries no history. Staged placements are not part of it.

> **Match record**:
> One accepted decision with its revealed outcomes, phase markers and public changes. Live play, catch-up and replay all use it.

> **Keyframe**:
> The complete public view at one point in a match, from which a reader can start instead of at the beginning.

> **Match transcript**:
> The ordered, durable match records and keyframes of one match, with its chat. A confirmed setup or kick-off event appears as its final positions.

> **Match replay**:
> A read-only view reconstructed from stored match records and keyframes after a match finishes.

> **Public view**:
> What every viewer of a match may know at one point in it.

> **Coach's offer**:
> The choices the server currently offers one coach, private to that coach.

## Alternatives considered

| Alternative | Reason not chosen |
| --- | --- |
| Keep the base and journal, and tune the limits (#596) | Cost still grows with the match |
| A background worker that assembles replay | Deferred (D3). It adds ordering and recovery work that the transaction avoids |
| Publish before commit | Rejected (D4). It acknowledges volatile actions |
| Rebuild by re-executing recorded inputs | Rejected. Recovery never reruns commands, and deterministic replay would change native mechanics |
| Write both formats during the change | Doubles the cost of each move and needs two authorities to agree |
| One long integration branch | Larger merges and late test failures. One writer per match keeps each slice green |
| A receipt window of one request per role | Safe, but a second device gets "stale" instead of the recorded outcome |
| A digest of the live public state in each record | Ruled out for now (D12). Drift is checked by comparison |
| Hash the whole history at full time | Grows with the match |
| Verify the whole history at restore | Restore would read history again |
| Field-level patches | Smaller, with more ways to be wrong. Revisit with measured sizes |
| Binary encoding or compression on the wire | Deferred (ADR 0012). #650's sizes support JSON for the first implementation. Revisit if reconnect gaps, snapshot fallbacks or traffic exceed the production budget |
| Store offers with records | They are private, large and never replayed |

## Consequences and known gaps

**Benefits.** A move, a restore and full time stop paying for the match's history. Live updates shrink from full states to changes. Replay seeks without loading a whole match. One record serves live play, catch-up and replay, so they can't disagree about what happened.

**Costs.** There are more rows, and finished matches keep their records. Browsers must rebuild state correctly, and the server must repair them when they can't. Two storages exist side by side until the cutover. Every existing match is cleared.

**Known gaps.** None of the controls here exists yet.
- The budgets were measured on a prototype on one machine, not on the real implementation. The write target isn't met consistently, and full-time tasks exceeded 100 ms in one of five rounds. The maintainer kept D3 on this evidence. The compact snapshot was flat in the one match measured.
- ADR 0007's markers aren't built (ticket 12; #603 settles the touchdown order first).
- The public view is not yet defined field by field (D27, ticket 20). Today's views send every role the actions, prompts and forecasts.
- Old history is checked when it is read or audited, not at restore.
- Records of finished, abandoned and expired matches accumulate (D21, D23), so storage grows with the number of matches activated. Today activation stops when `ffb_match_recovery` holds 1,024 matches (`JdbcRecoveryRepository.java:31`), the server never deletes those rows, and a finished match is kept inside its prepared match.
- `docs/specs/match-state-sync-tickets.md` lists the twenty-six tickets. #652, #653 and #654 are filed. The rest are filed on acceptance.

## Settled on 2026-11-25

| Question | Answer |
| --- | --- |
| Which matches count toward the 1,024 limit | Only those that can still be played (D22) |
| How long abandoned and expired matches are kept | Kept, with no automatic deletion (D23) |
| Whether chat advances the storage generation | No (D24) |
| What the digest covers | Match records only (D25) |
| What each operation writes | The table above (D26) |
| The public view | The outline above. Ticket 20's exact list needs the maintainer's approval (D27) |
| Patch shape, one storage version per match, row summary, tests in batches | Accepted (D28) |
| Budgets, keyframe cadence, tail size | #650's proposals (D29) |

Nothing is left open. The ADR itself isn't accepted yet.

## Revisit conditions

- The measured cost of a write, restore, publication or full time grows with match length again.
- The GCP hosting is significantly slower than #650's measurements, which would reopen having no worker thread for the SQL writes (D3).
- Storage per match or the number of rows becomes a problem, which would reopen keeping every record (D21, D23).
- Measured patch sizes justify field-level patches or a binary encoding.
- Drift reports show browsers disagreeing with the server, which would reopen the state digest (D12).
- Interior damage to history has to be found at restore rather than on read or audit.
- Matches run in parallel, which is ADR 0006's own condition.
- The project leaves build status, after which clearing matches is no longer available.
