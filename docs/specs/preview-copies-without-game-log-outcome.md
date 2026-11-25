# Preview copies without the native game log

[Issue #652](https://github.com/the-grognard-codes/moles-under-the-pitch)

Both preview copies now omit the native command log when they serialize the engine. Self-review traced the block, blitz, Jump Up and Multiple Block copies through `BlockPreviewPresentation`, and the planned pass and hand-off copies through `PlannedBallTransfer`. Both paths still initialize a detached engine and register its skill behaviours. The diff changes only the preview serializers in production code. Capture, recovery, replay and stored and wire formats are outside those call paths.

The regression compares all six preview payloads against their exact combined JSON digest from the parent commit. It uses a native BB2025 engine with two eleven-player rosters and a populated command log, and compares the live engine serialization before and after the preview reads. The copy regression found 40 copied commands on the parent. On this branch it requires zero copied commands and checks the live log size and recovery epoch. The transfer regression also checks that validation requests serialization without the log.

## One preview copy

Parent commit for #650: `0277e63b36a5177f3d1dae754363d8235997447d`.

| Checkpoint | Native commands | Median time, parent → change | Median allocation, parent → change |
| --- | ---: | ---: | ---: |
| Revision 50 | 143 | 9.91 → 4.78 ms | 4.97 → 2.07 MiB |
| Revision 378, one decision before full time | 918 | 35.41 → 4.72 ms | 22.90 → 2.06 MiB |

Both runs played the same seeded synthetic 22-player match to full time with real `ServerCommunication`. The probe reconstructs each snapshot with its native log, warms up the production block copy with 120 calls, then samples 120 single copies per snapshot using elapsed time and JVM thread allocation. The local Windows runs used Temurin 21.0.11. The parent run overlapped with the dev-local image build, so treat elapsed times as indicative. Thread allocation measures bytes allocated by the copy, including allocations later collected. These figures cover a single copy on the calling thread.

Run `PreviewCopyTest#measureCopyAtRevision50AndBeforeFullTime` with Maven's `-Dpreview.copy.probe=true` to reproduce the probe.

## Validation

- Focused Maven run passed 45 server tests and 21 state tests, including the opt-in copy probe. The affected tests cover ordinary and Multiple Block visibility, Jump Up, movement previews and ball transfers.
- The pinned Maven clean install passed with 1,269 tests passed and 27 skipped under the default configuration. The native shard validator accepted all 51 suites assigned to shard 2, including the preview regression and its opt-in probe.
- Browser unit run passed all 594 tests. Quick dev-local built the client, site and Java server and passed the browser-to-Java WebSocket smoke.
- For manual testing, a separate local acceptance instance runs at `http://localhost:5173/play` with its own fresh marker-11 database. The shared port 5000 instance was in use by another worktree. Chrome loaded the alternate local transport configuration and reached Java's authentication gate on initial connection and reconnect. The computer player daemon reached ready. Authenticated play is left for manual testing.
