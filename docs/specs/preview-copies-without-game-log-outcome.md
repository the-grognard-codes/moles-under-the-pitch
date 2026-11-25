# Preview copies without the native game log

[Issue #652](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/652) · [Local spec](preview-copies-without-game-log.md)

## Acceptance criteria

- [x] Both preview serializers omit the log. `BlockPreviewPresentation` covers block, blitz, Jump Up and Multiple Block. `PlannedBallTransfer` covers pass and hand-off.
- [x] `PreviewCopyTest` compares the four block payloads individually using the same native preview code with and without the log. The BB2025 fixture fields 22 players and exercises Jump Up, Multiple Block and an assist from a marked player with Guard. Pass and hand-off use the same comparison for native validation outcomes, including rejected transfers.
- [x] The block test inspects the detached copy for an empty log and registered Jump Up behaviour. The transfer test captures the JSON passed to the copy and verifies an absent `gameLog` field and zero restored commands. Both check the live log size and recovery epoch.
- [x] The single-copy benchmark reports time and allocation at revision 50 and one decision before full time, with a named parent for the measurement follow-up.

**Remaining verification** Authenticated browser gameplay is open for manual testing. The regressions cover preview calculation and validation in the native engine.

## One preview copy

Parent commit for [#650](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/650) is `0277e63b36a5177f3d1dae754363d8235997447d`.

| Checkpoint | Native commands | Parent time, median (range) | Change time, median (range) | Median allocation, parent → change |
| --- | ---: | ---: | ---: | ---: |
| Revision 50 | 143 | 8.25 (7.85–9.02) ms | 4.43 (4.12–5.19) ms | 4.95 → 1.96 MiB |
| Revision 378, one decision before full time | 918 | 28.79 (24.62–29.08) ms | 3.82 (3.64–4.50) ms | 22.78 → 1.95 MiB |

The table uses the median of three fresh JVM medians per side. Runs used pinned Temurin 21.0.11 on the same Windows host, one JVM at a time, with no concurrent build started for this work. Total host CPU utilization was 5.9–10.2% immediately before the runs. These runs replace the original comparison, whose parent overlapped with an image build. The parent was checked out separately and received only the test harness files (`BogrukEngineFixture`, `PreviewCopyFixture` and `PreviewCopyWorkloadTest`), so it measured the parent's production copy.

Each run starts in Nice weather, uses seeded dice for the committed decisions and completes a synthetic 22-player match with real `ServerCommunication`. The workload asserts the two checkpoint revisions and command counts. It restores each checkpoint with its log, warms up the production block copy with 120 calls, then samples 120 individual copies per checkpoint. Thread allocation measures bytes allocated on the calling thread, including allocations later collected.

Run the opt-in benchmark with the pinned toolchain after `tools/bootstrap.ps1`. Supply `-JavaHome` if the pinned JDK is installed outside the configured path.

```powershell
$env:PREVIEW_COPY_WORKLOAD = 'true'
try {
    ./tools/target-build.ps1 test -Module ffb-server -Test PreviewCopyWorkloadTest
} finally {
    Remove-Item Env:PREVIEW_COPY_WORKLOAD
}
```

## Validation

- The review follow-up ran the four revised regression tests on the parent. Both behavior comparisons passed, and both empty-log guards failed as expected. The block copy held 40 commands and the transfer used the log-including serializer.
- The pinned follow-up run passed 58 server tests and 21 state tests, including the shared fixture's memory isolation tests. The workload was skipped by default and passed in all six opt-in runs. The native shard validator accepted the 52 suite reports assigned to shard 2.
- The original production change passed pinned Maven clean install with 1,269 passing tests and 27 skips under the default configuration.
- Browser unit run passed all 594 tests. Quick dev-local built the client, site and Java server and passed the browser-to-Java WebSocket smoke.
- For manual testing, a separate local acceptance instance runs at `http://localhost:5173/play` with its own fresh marker-11 database. The shared port 5000 instance was in use by another worktree. Chrome loaded the alternate local transport configuration and reached Java's authentication gate on initial connection and reconnect. The computer player daemon reached ready. Authenticated play is left for manual testing.
