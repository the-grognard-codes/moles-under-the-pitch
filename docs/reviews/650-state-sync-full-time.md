# Full-time worker evidence for #650

Seed 43 played a complete 22-player match in the training engine. The early checkpoint at revision 50 had 153 native commands and outcomes. The late checkpoint at revision 928 had 2,649 of each. The terminal checkpoint at revision 929 had 2,668 of each. Production code was measured at `0277e63b3`, before #652. The runs used Temurin 21.0.11+10, G1 with `-Xmx768m` and one active processor, an AMD Ryzen 7 260 with 31.3 GiB of host memory, and isolated MariaDB 11.8.9 from the pinned [Compose file](../../tools/diagnostics/state-sync/compose.yaml).

The capture used the real browser protocol adapter, transport lanes, single engine worker, recovery and prepared-match JDBC repositories, and Connector/J deadline boundary. It used synthetic local authorization and in-process browser recipients. Payload bytes count UTF-8 messages passed to those recipients, including negotiated history frames when emitted. Socket framing, network delivery to a browser, provider latency, and a concurrent soak are outside this capture.

## Reproduction

Compile the server tests and prepare the training classpath using the [main outcome report](../specs/match-state-sync-measurement-outcome.md). Set `STATE_SYNC_JDBC_URL` and `STATE_SYNC_DB_PASSWORD` for the isolated schema. Point `FFB_STATE_SYNC_JAVA` at the pinned JDK executable if it is outside PATH. Generate the seed 43 fixture with `node tools/diagnostics/capture-match-state-sync-fixtures.mjs --smoke .tools/state-sync-seed43`.

```text
node tools/diagnostics/state-sync/run-full-time.mjs smoke .tools/full-time-smoke
node tools/diagnostics/state-sync/run-full-time.mjs measure .tools/full-time-baseline .tools/state-sync-seed43/selected
node tools/diagnostics/state-sync/run-full-time.mjs retained .tools/full-time-retained .tools/state-sync-seed43/selected
node tools/diagnostics/state-sync/estimate-full-time.mjs .tools/state-sync-seed43/selected
```

The smoke asserted real phase coverage, normal and injected-deferred MariaDB commits, a post-release live transcript read, and two queued controls with observed depths 1 and 0. Measurement modes write one JSON trace, JFR recording and GC log per fresh JVM. Summary keys include inclusive span durations, exclusive method rollups within and outside artifact construction, failed spans, allocation, queue wait and protocol payload bytes. Raw traces remain local.

## Worker tasks

Timing cells contain p50 / p90 / max in milliseconds. All p99s are inconclusive at these sample counts. The baseline sent one operation at a time and observed zero waiting foreground and advisory tasks at the measured starts.

| Cohort and task | n | Worker | Queue wait | Outcome |
| --- | ---: | ---: | ---: | --- |
| r50 cold load | 3 | 1,010 / 1,095 / 1,095 | 0.32 / 0.33 / 0.33 | Accepted |
| r50→51 concession | 3 | 1,251 / 1,379 / 1,379 | 0.23 / 0.36 / 0.36 | Completed and committed |
| r51 completed reload | 3 | 838 / 1,064 / 1,064 | 0.29 / 0.30 / 0.30 | Accepted, full time |
| r928 cold load | 3 | 7,559 / 9,607 / 9,607 | 0.31 / 0.35 / 0.35 | Accepted, task overran |
| r928 size-stop checkpoint attempt | 3 | 2,755 / 3,334 / 3,334 | 0.17 / 0.27 / 0.27 | The gate intercepted the request. Persistence failed |
| r928 follow-up load | 3 | 5,796 / 6,954 / 6,954 | 0.16 / 0.20 / 0.20 | Accepted, still in play |
| r929 retained terminal checkpoint load | 3 | 11,429 / 13,107 / 13,107 | 0.33 / 0.36 / 0.36 | Accepted, result write deferred |
| r929 resident completion retries that built an artifact | 6 | 4,048 / 5,633 / 5,633 | 0.45 / 0.90 / 0.90 | Deadline before the update |
| r929 follow-up load | 3 | 4,083 / 4,277 / 4,277 | 0.32 / 0.33 / 0.33 | Accepted, result write deferred |

The r928 checkpoint contained 43,011,773 decoded bytes, over the 40 MiB append limit. The production size gate started size termination before `SetupSession.apply`. The size-stop checkpoint exhausted the worker budget before SQL could save it and accounted for 96.3–97.3% of those tasks. The separately seeded r929 terminal checkpoint of 43,061,103 bytes exercised reconstruction and completion of an already terminal session. All late runs ended before prepared-match UPDATE, COMMIT, release and the resulting publication.

## Projected dataset at natural full time

The training engine played full 22-player matches past the production append limit. To estimate their size had production permitted natural full time, the estimator extends the observed byte growth per revision from r50 to the last active checkpoint by one revision. It projects checkpoint bytes and completed-match payload bytes separately in decoded UTF-8 and as gzip streams. It reconstructs the result fields from the checkpoint using the composition in `SetupSession.completedMatch`, including the parsed events array, transcript and chat. It compares the projections with the corresponding actual terminal training fixtures. The per-revision model averages across ordinary and large decisions while the terminal fixture includes the final decision and terminal state.

Each size cell gives decoded UTF-8 bytes / gzip bytes. Seed 43 projects r928 → r929. Seed 45 projects r1101 → r1102.

| Fixture and object | Projected at full time | From terminal training fixture | Terminal minus projected |
| --- | ---: | ---: | ---: |
| Seed 43 recovery checkpoint | 43,072,032 / 887,191 | 43,075,263 / 888,658 | 3,231 / 1,467 |
| Seed 43 completion payload | 36,090,727 / 668,813 | 36,105,754 / 672,137 | 15,027 / 3,324 |
| Seed 45 recovery checkpoint | 52,670,967 / 1,038,566 | 52,673,196 / 1,040,034 | 2,229 / 1,468 |
| Seed 45 completion payload | 43,997,889 / 775,996 | 44,010,518 / 779,070 | 12,629 / 3,074 |

For the seed 45 comparison, generate fixtures with `node tools/diagnostics/capture-match-state-sync-fixtures.mjs --measure .tools/state-sync-candidates` and pass the seed 45 artifact directory to the estimator. The figures above come from the two corrected terminal exports. The earlier r929 worker trace used a direct capture of the same match and decisions. Completion estimates reconstruct the payload shape and use Node's gzip. Differences in capture and JSON serialization affect compression. The estimates exclude the prepared-match document wrapper.

The seeded r929 worker trace supplies the production-code cross-check. In each of its three JVMs, `MatchJson.storageText` fed **36,130,595 decoded bytes** into gzip, which produced **705,298 bytes**. The versioned JSON wrapper and Base64 encoding would store **940,481 bytes** in the prepared-match row. This gives a reference case for the current design. The two synthetic matches suggest a planning scale of 43–53 MB decoded for recovery checkpoints and 36–44 MB decoded for completion payloads. Successful late persistence and its latency remain unmeasured. The two fixtures do not establish a population bound.

## Artifact build, write, release and publication

Build phase rows use exclusive elapsed time within `SetupSession.completedMatch`. Write and publication rows use elapsed time for the named operation. Medians describe each phase independently. A dash means the phase was never reached.

| Phase | r51 successful concession, n=3 | r929 retained load, n=3 | r929 resident retries, n=6 |
| --- | ---: | ---: | ---: |
| Events materialization and parse | 5.28 / 6.46 / 6.46 | 168.56 / 517.46 / 517.46 | 180.50 / 553.42 / 553.42 |
| Transcript materialization | 8.95 / 9.22 / 9.22 | 720.96 / 795.47 / 795.47 | 654.78 / 1,216.61 / 1,216.61 |
| Chat materialization | 0.02 / 0.03 / 0.03 | 0.05 / 0.06 / 0.06 | 0.04 / 0.07 / 0.07 |
| Other artifact assembly | 0.88 / 2.40 / 2.40 | 20.12 / 21.03 / 21.03 | 1.30 / 1.85 / 1.85 |
| Canonical serialization | 64.85 / 79.58 / 79.58 | 1,114.22 / 2,130.08 / 2,130.08 | 991.97 / 1,995.31 / 1,995.31 |
| Validation | 110.91 / 117.93 / 117.93 | 1,360.90 / 1,431.29 / 1,431.29 | 1,066.64 / 1,245.60 / 1,245.60 |
| Terminal recovery checkpoint | 306.29 / 340.32 / 340.32 | — | — |
| Prepare and scan result storage JSON | 22.25 / 34.95 / 34.95 | 120.37 / 141.76 / 141.76 | 163.00 / 213.73 / 213.73 |
| Result gzip compression | 16.32 / 17.84 / 17.84 | 370.35 / 416.16 / 416.16 | 389.55 / 451.29 / 451.29 |
| Result connection acquisition attempt | 8.07 / 8.97 / 8.97 | 1.28 / 1.62 / 1.62 | 0.05 / 0.07 / 0.07 |
| Result SQL update | 3.64 / 3.96 / 3.96 | — | — |
| Result SQL commit | 10.54 / 12.58 / 12.58 | — | — |
| Session release | 0.02 / 0.03 / 0.03 | — | — |
| Live transcript lookup | 243.78 / 296.30 / 296.30 | 0.12 / 0.17 / 0.17 | — |

The r51 result document medians were 2,072,086 decoded bytes and 47,165 gzip bytes before the storage envelope. The retained terminal load devoted 26–42% of worker time to completion artifact construction because it first had to reconstruct the native session. Once resident, artifact construction occupied 74–82% of the six retry tasks and took 1.951–4.629 s. Compression still ran before the deadline refused the connection. The failed SQL attempts leave late durable-write latency unresolved.

The r929 retained terminal load passed 13,956 bytes to the home recipient in each run. The follow-up loads passed 13,957 bytes each. Away, spectator and computer recipients received zero bytes in these single-client terminal fixtures. In the successful revision 1 smoke, the terminal action passed 31,508 / 31,504 / 31,509 bytes to home / away / spectator and zero to computer. The earlier r51 series predates per-role byte collection. Full-scale, successful post-release publication to several recipients remains unmeasured.

## Restore costs and pauses

These are selected exclusive restore costs in ms. Parse sums repeated calls in a task. Native initialization includes calls made by preview copies on the restore path. Each column has three JVM samples.

| Phase | r50 cold load | r51 completed reload | r928 cold load | r928 follow-up load | r929 terminal load |
| --- | ---: | ---: | ---: | ---: | ---: |
| JSON parse | 238 / 254 / 254 | 109 / 199 / 199 | 3,338 / 4,210 / 4,210 | 2,784 / 3,523 / 3,523 | 2,174 / 4,366 / 4,366 |
| Checkpoint validation excluding parse | 61 / 71 / 71 | 13 / 16 / 16 | 288 / 335 / 335 | 304 / 324 / 324 | 224 / 327 / 327 |
| Reconstruction excluding measured children | 186 / 198 / 198 | 120 / 134 / 134 | 1,563 / 1,569 / 1,569 | 1,108 / 1,215 / 1,215 | 1,095 / 1,561 / 1,561 |
| Native initialization | 132 / 133 / 133 | 23 / 25 / 25 | 641 / 981 / 981 | 338 / 396 / 396 | 226 / 443 / 443 |
| Recovery row and journal assembly excluding measured children | 185 / 213 / 213 | 33 / 36 / 36 | 655 / 897 / 897 | 379 / 448 / 448 | 600 / 615 / 615 |
| Inflate stored JSON | 4 / 5 / 5 | 7 / 7 / 7 | 55 / 57 / 57 | 124 / 147 / 147 | 64 / 81 / 81 |
| JDBC query execution | 8 / 8 / 8 | 7 / 8 / 8 | 20 / 33 / 33 | 25 / 39 / 39 | 30 / 34 / 34 |

JFR `jdk.GCPhasePause` overlap with the early cold load, concession and reload was 100–114, 156–177 and 101–236 ms respectively. Their remaining elapsed time was distributed over checkpointing, completion validation, live-history publication and restore. The r928 cold and follow-up loads overlapped 1.612–2.648 s and 1.869–2.594 s of GC pauses. Repeated parsing, native restoration and journal assembly explain their largest named spans. The failed r928 size-stop requests overlapped 1.322–2.348 s of GC pauses during checkpoint processing, 57.5–72.3% of task time.

The r929 terminal loads overlapped 1.784–3.653 s of GC pauses. They spent time in both recovery and result materialization. Resident retries overlapped 0.540–2.361 s of pauses and spent most of their elapsed time building and validating the result. Follow-up loads had the same artifact work and 0.900–1.149 s of pauses. These pauses are included within the phase durations. The largest individual pause across these late task windows was 821 ms.

The measurements reproduce tasks in the 5–14 s range on one local worker. They cannot assign the blocked-time shares of the November soak because this run had no competing clients in the queue. A bounded concurrent run on the isolated stack would need the maintainer's approval and would collect every worker task, matched queue wait, result retries, live transcript reads, and GC around the full-time window.
