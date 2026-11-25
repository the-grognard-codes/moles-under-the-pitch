# Compact match snapshot outcome

[Issue #662](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/662) ? [Ticket 6](match-state-sync-tickets.md#6-a-match-restores-from-a-compact-snapshot-alone) ? [ADR 0014](../adr/0014-match-state-records-and-live-sync.md#restore-snapshot)

The test-only compact session writes a versioned snapshot with the frozen match inputs, the log-free native state, both command cursors, dice, pending route and activation, reactive decisions, clocks, save/resume and computer memory. Decision-limit and drive-tracker summaries, unfinished movement, and the announcement and block cursors resume directly. The writer supplies the record count, chat watermark and digest. Restore needs the snapshot and a server with the native factories. It does not consult a repository, execute an engine command, or refresh a clock or reaction.

Native deserialization exposed a missing acting-player dodge flag. A pending dodge could advance as a successful move after a restore even when the same decline ended the turn in an uninterrupted match. The compact continuation now carries the omitted activation flags and any buffered native model changes. It also retains both teams' drive and single-use reroll counters and the Ball and Chain flag that distinguishes the two native rush steps, and the ordered moves in an unfinished chain push. The legacy checkpoint normalizes its complete native state, including the log. A regression covers a logged reroll source set and preserves the existing log epoch behavior.

Compact sessions freeze the captured model syncs as JSON. A confirmed write drops the captured commands. Failed and unknown writes call the owner's eviction callback while leaving those commands in the rejected session. Compact sessions keep staging as an overlay on committed positions. The current checkpoint and journal remain the production path. The database transaction, receipt lookup and bounded history check belong to ticket 7a. Chat rows belong to ticket 8.

## Acceptance evidence

Two full matches used real `ServerCommunication` and the Human and Orc rosters in both seat orders. The regression suite compares continuation, command numbers, revealed outcomes, legal choices and both coach views with a session that never restored. It also covers a routed dodge with a drive-limited reroll, a successful rush reroll with an unused die left in the queue, an activation queued behind Steady Footing, a chain push awaiting its second destination, halftime cleanup, staged placement loss, suspension and resume, and failed or unknown writes. The comparisons exclude wall-clock samples from separate executions and the order of consecutive removals from native movement-marker sets. The test verifies exact clock anchors and byte-identical export immediately after restore.

| Seed and seats | Revision 50 bytes | Last PLAY / REGULAR bytes | Full-time bytes |
| --- | ---: | ---: | ---: |
| 43, Human / Orc | 83,658 | 83,876 at r384 | 77,763 at r385 |
| 45, Orc / Human | 83,678 | 83,886 at r384 | 77,763 at r385 |

The test fails if the last matched phase or full-time snapshot exceeds 125% of the revision-50 size.

## Acting-coach timing

The test probe ran on Windows with pinned Temurin 21.0.11. It times the live acting-coach reply on revisions 51?384 and counts native block and transfer preview copies. A separate interval covers the accepted action, reply, snapshot encoding and an in-memory write acknowledgment. The earlier #650 figure was the median late acting-coach view on a restored checkpoint at r928, with preview copies still including the log. The revision, workload and sampling differ. [#650 measurement](match-state-sync-measurement-outcome.md#findings-for-611)

| Seed and operation | Samples | Median | p99 | Preview copies | Total time inside copies |
| --- | ---: | ---: | ---: | ---: | ---: |
| #650 late acting-coach view | 9 | 245.79 ms | ? | ? | ? |
| 43 acting-coach reply | 334 | 6.81 ms | 25.61 ms | 645 | 2,609.27 ms |
| 45 acting-coach reply | 334 | 5.97 ms | 23.67 ms | 645 | 2,199.69 ms |
| 43 apply and snapshot | 385 | 12.85 ms | 60.40 ms | 992 | 4,713.99 ms |
| 45 apply and snapshot | 385 | 12.32 ms | 32.94 ms | 993 | 3,361.38 ms |

The measured worker portion stayed within the 100 ms p99 budget. SQL, authorization and socket delivery remain to be measured on the integrated storage path.

```powershell
$env:COMPACT_SNAPSHOT_TIMING = 'true'
try {
    ./tools/target-build.ps1 test -Module ffb-server -Test CompactMatchSnapshotTest
} finally {
    Remove-Item Env:COMPACT_SNAPSHOT_TIMING
}
```
