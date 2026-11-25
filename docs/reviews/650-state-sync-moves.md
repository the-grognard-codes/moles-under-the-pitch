# Match state sync journal and move timings

The production baseline was `0277e63b3`, before the preview change in #652. The capture ran on AMD Ryzen 7 260, 16 logical processors, 31.3 GiB RAM, Windows 11 build 26300, Node 26.12.1 and Temurin 21.0.11+10. Timed move JVMs used G1, `-Xmx768m` and two active processors. The training host played synthetic Human and Orc rosters of 11 players each with Bogruk on both sides and a real `ServerCommunication`.

Seed 43 reached full time at revision 929. The corrected reference capture retained revisions 50 and 928, with 2,540,478 and 43,025,921 decoded bytes and 153 and 2,649 native commands and outcomes, respectively. The earlier replay used for the storage measurements captured the same seed and revisions. Its decoded envelopes measured 2,539,809 and 43,011,773 bytes. This training path omits the server's admission policy. The late artifacts exceed the production 40 MiB reserve threshold for ongoing play, so a persisted game can stop for size before reaching the same late revision.

## Journal cadence and first compaction

The reference capture ran the production journal policy on the fixture. It retained 850 appended entries and observed 78 compactions. The JDBC timings are reported separately in the [outcome report](../specs/match-state-sync-measurement-outcome.md).

| Measure | n | p50 | p90 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Append entry bytes | 850 | 184,567 | 213,971 | 232,656 | 241,628 |
| Revisions between compactions | 78 | 12 | 13 | | 14 |
| Appends before compaction | 78 | 11 | 12 | | 13 |
| Journal bytes before compaction | 78 | 1,993,951 | 2,074,106 | | 2,096,904 |
| First compaction build, ms | 3 | 126.38 | 237.39 | | 237.39 |

All three first compactions occurred at revision 11 after ten appends. The figures include journal path selection and checkpoint materialization, encoding and cursor construction. Each JVM had already built its initial base and applied the early decisions. Seed 43/44/45 checkpoint sizes were 777,444, 751,767 and 776,198 decoded bytes. Their builds after the journal decision took 129.84, 67.12 and 58.08 ms, respectively. The reference early first-entry sample was 196,150 bytes. The equivalent late terminal delta was 187,650 bytes under an empty journal cursor. Production persists the terminal action as a full checkpoint.

| Seed 43 build | Early n | Early p50 / p90 / max ms | Early p50 decoded bytes | Late n | Late p50 / p90 / max ms | Late p50 decoded bytes |
| --- | ---: | --- | ---: | ---: | --- | ---: |
| Journal append | 46 | 29.90 / 40.66 / 91.81 | 176,149 | 45 | 126.11 / 260.26 / 360.42 | 191,152 |
| Compaction | 4 | 119.32 / 163.36 / 163.36 | 2,700,705 | 5 | 2,556.86 / 2,750.67 / 2,750.67 | 41,905,170 |

The build thresholds are inconclusive at p99 for these windows. No seed 43 append build in either window exceeded 500 ms. Across the whole seed 43 match, 61 compactions took more than 500 ms, with a maximum of 3,816.01 ms. Checkpoint materialization, encoding and cursor construction accounted for 88.36% of their combined time. The fixture times end before JDBC.

For #611, entry size holds near 0.18 MiB in these samples. The 2 MiB cap forces compaction substantially sooner than the 31-entry bound. A comparison using the entry limit by itself would understate how often the baseline rewrites the accumulated history.

## Native capture and engine copies

Test-only advice measured `NativeOutcomeCapture.since` and each preview copy during the actual `SetupSession.apply` path and its acting-role reply. Early windows use 50 input revisions beginning at 25. Late windows use the last 50 nonterminal decisions for each match. Copy counts include zero-copy moves. The samples pool three fresh JVMs for seeds 43, 44 and 45, which reached full time at revisions 929, 876 and 1102.

| Measure | Early n | Early p50 / p90 / p99 / max | Late n | Late p50 / p90 / p99 / max |
| --- | ---: | --- | ---: | --- |
| Native capture per move, ms | 150 | 0.0814 / 0.1610 / 0.2941 / 0.4644 | 150 | 0.1010 / 0.1845 / 0.2369 / 0.2458 |
| Native capture allocation per call, bytes | 150 | 27,312 / 32,736 / 37,176 / 37,224 | 150 | 37,088 / 44,632 / 65,616 / 66,064 |
| Block copies per move | 150 | 1.5 / 4 / 8 / 10 | 150 | 2 / 3 / 4 / 5 |
| Block copy total per move, ms | 150 | 18.64 / 41.75 / 77.39 / 91.85 | 150 | 187.63 / 381.93 / 567.21 / 717.03 |
| Individual block copy, ms | 265 | 9.75 / 18.43 / 23.77 / 31.93 | 209 | 108.56 / 150.34 / 211.05 / 303.47 |
| Allocation per block copy, bytes | 265 | 5,330,000 / 6,208,872 / 6,554,080 / 6,730,256 | 209 | 64,282,560 / 66,448,536 / 66,962,912 / 67,583,896 |

Median native capture time was 1.24× early cost, with 1.36× median allocation. Median individual block cost was 11.13× early duration and 12.06× early allocation. Total copy time per move was 10.07× at the median. Cohort composition affects this comparison. Seed 44 had zero block copies in its late window, while median total copy time per move rose from 32.91 to 224.83 ms, or 6.83×, in seed 43 alone. These trials yielded zero `PlannedBallTransfer` samples. The harness bypasses browser transport and omits additional view construction during production checkpoint and publication.

The 151 fixture applies above 500 ms totaled 97,118.7 ms. Block preview copies accounted for 94,705.3 ms, or 97.51%. The slowest apply, at seed 45 revision 983, took 1,126.97 ms and spent 1,113.66 ms in eight block copies. The remaining part of every slow apply was below 500 ms. For #611, the copy measurements favor eliminating full-log work per preview. Native capture was small on this workload, although it still scans the growing log. These timings cover fixture applies and are separate from the historical full-time queue waits.

## Reproduction and retained evidence

Prepare the test-scope training classpath with `computer-player/training/prepare.ps1` or `prepare.sh`, then run the commands from the worktree root. Use the pinned JDK in `JAVA_HOME` or set `FFB_STATE_SYNC_JAVA` to its Java executable. The capture script writes only beneath a new ignored `.tools` directory. The outcome uses the seed 43 fixture above 700 revisions and collects independent samples from other seeds or repeats the reference to complete three fresh JVMs.

```powershell
node tools/diagnostics/capture-match-state-sync-fixtures.mjs --smoke .tools/state-sync-smoke
node tools/diagnostics/capture-match-state-sync-fixtures.mjs --measure .tools/state-sync-measure
node tools/diagnostics/analyze-state-sync-move-timing.mjs .tools/state-sync-measure
```

The corrected seed 43 and seed 45 captures saved complete JFR and GC logs. Seed 44 reached natural full time and wrote its whole move trace and terminal journal delta before a duplicate file write stopped terminal export. Its GC log and first 207 seconds of JFR were recovered in `.tools/state-sync-g1/seed-44`, including the measured move windows. The final capture code writes each artifact once and resolves the advice types before gameplay. The recorded move evidence above can be reprocessed directly. The fallback analyzer checks that each trial's retained terminal delta records natural full time.

```powershell
node tools/diagnostics/analyze-state-sync-move-timing.mjs .tools/state-sync-smoke-fixed
node tools/diagnostics/analyze-state-sync-move-timing.mjs --move-only .tools/state-sync-smoke-fixed/selected .tools/state-sync-g1/seed-44 .tools/state-sync-g1/selected
Get-Content .tools/state-sync-smoke-fixed/selected/first-compaction.json,.tools/state-sync-g1/seed-44/first-compaction.json,.tools/state-sync-g1/selected/first-compaction.json
```
