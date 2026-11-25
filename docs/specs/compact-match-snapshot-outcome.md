# Compact match snapshot outcome

[Issue #662](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/662) | [Ticket 6](match-state-sync-tickets.md#6-a-match-restores-from-a-compact-snapshot-alone) | [ADR 0014](../adr/0014-match-state-records-and-live-sync.md#restore-snapshot)

The test-only compact session writes a versioned snapshot with the frozen match inputs, the log-free native state, both command cursors, dice, pending route and activation, reactive decisions, clocks, save/resume and computer memory. Decision-limit and drive-tracker summaries, unfinished movement, and the announcement and block cursors resume directly. The writer supplies the record count, chat watermark and digest. Restore needs the snapshot and a server with the native factories. It does not consult a repository, execute an engine command, or refresh a clock or reaction.

Native deserialization exposed a missing acting-player dodge flag. A pending dodge could advance as a successful move after a restore even when the same decline ended the turn in an uninterrupted match. The compact continuation now carries the omitted activation flags and any buffered native model changes. Steady Footing now retains contextual injuries and complete deferred drop commands. The selection step preserves the movement origin used to calculate jump tackle zones. The compact continuation also retains both teams' drive and single-use reroll counters and the Ball and Chain flag that distinguishes the two native rush steps, and the ordered moves in an unfinished chain push. The legacy checkpoint normalizes its complete native state, including the log. A regression covers a logged reroll source set and preserves the existing log epoch behavior.

Compact sessions freeze the captured model syncs as JSON. A confirmed write drops the captured commands. Failed, unknown, and rejected writes release the session and invoke the owner's eviction callback. They leave the captured commands unpruned. Compact sessions keep staging as an overlay on committed positions. The current checkpoint and journal remain the production path. The database transaction, receipt lookup, and verification of the snapshot envelope's history digest against committed records belong to ticket 7a. Chat rows belong to ticket 8.

## Acceptance evidence

Two seeded Human-Orc matches in opposite seat orders use real `ServerCommunication` and legal coach actions. The stress test checks blocks, passes, injuries, and reactions. It compares the next reply, native command numbers and outcomes, the resulting continuation, legal choices, and both coach views with an uninterrupted session. It verifies exact clock anchors and byte-identical export immediately after each successful restore. Comparisons between separate executions omit wall-clock samples and normalize ordering within native sets of movement markers and injury modifiers.

Focused regressions cover routed dodges, drive-limited rerolls, a rush reroll with unused dice, a queued activation, pending Steady Footing injuries and deferred drops, the jump origin, a pending chain push, Secret Weapon cleanup, opposing-reaction expiry and the next action, live suspension and resume, pending drive-tracker reports, staged placements, malformed snapshots, and failed or unknown writes.

[Issue #691](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/691) tracks a shared native pass decoder defect. The stress test checks for the specific round-trip rejection when an unrun Hail Mary Pass step is present and the shared decoder changes its null result. When the decoder preserves that result, those boundaries run the same restore and comparison as all other revisions.

| Seed and seats | Revision 50 bytes | Last PLAY / REGULAR bytes | Full-time bytes | Native syncs | Block rolls | Pass rolls | Injury reports | Reaction boundaries | #691 boundaries |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 103, Human / Orc | 81,888 | 92,461 at r394 | 77,976 at r395 | 1,292 | 15 | 3 | 42 | 8 | 2 |
| 107, Orc / Human | 92,139 | 83,996 at r423 | 77,940 at r425 | 1,337 | 12 | 2 | 38 | 9 | 1 |

The test fails if the last matched phase or full-time snapshot exceeds 125% of the revision-50 size. The pinned Java recovery suite passed 86 tests, including 15 compact snapshot tests.

## Acting-coach timing

The test probe ran on Windows with pinned Temurin 21.0.11 at `63e7e8d42`. It times the live acting-coach reply on late revisions after r50 and counts native block and transfer preview copies. A separate interval covers the accepted action, reply, snapshot encoding and an in-memory write acknowledgment. The earlier #650 figure was the median late acting-coach view on a restored checkpoint at r928, with preview copies still including the log. The revision, workload and sampling differ. [#650 measurement](match-state-sync-measurement-outcome.md#findings-for-611)

| Seed and operation | Samples | Median | p99 | Preview copies | Total time inside copies |
| --- | ---: | ---: | ---: | ---: | ---: |
| #650 late acting-coach view | 9 | 245.79 ms | n/a | n/a | n/a |
| 103 acting-coach reply | 344 | 4.82 ms | 25.75 ms | 371 | 1,685.67 ms |
| 107 acting-coach reply | 374 | 3.64 ms | 25.37 ms | 449 | 1,649.85 ms |
| 103 apply and snapshot | 395 | 11.12 ms | 57.69 ms | 624 | 3,067.17 ms |
| 107 apply and snapshot | 425 | 11.26 ms | 45.51 ms | 799 | 3,156.16 ms |

The measured worker portion stayed within the 100 ms p99 budget. SQL, authorization and socket delivery remain to be measured on the integrated storage path.

```powershell
$env:COMPACT_SNAPSHOT_TIMING = 'true'
try {
    ./tools/target-build.ps1 test -Module ffb-server -Test CompactMatchSnapshotTest
} finally {
    Remove-Item Env:COMPACT_SNAPSHOT_TIMING
}
```
