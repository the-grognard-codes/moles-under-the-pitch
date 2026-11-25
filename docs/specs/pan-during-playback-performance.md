# Improve pitch panning during player and ball playback

This is the original proposal for #638. See the [current spec](pan-during-playback.md) and [delivery outcome](pan-during-playback-outcome.md).

## Abstract

At 50°, a drag that is responsive when the pitch is stationary can stall during a player or loose-ball move. The benchmark in #637 isolates a substantial cost from applying movement transitions to stationary player markers as the camera pans. This child of #616 owns the production performance outcome. #635 owns visual alignment and may deliver the shared rendering change first.

## Objective

Keep the camera responsive throughout gameplay movement while preserving the existing square-to-square playback cadence. Establish the improvement in the normal renderer with every player visible.

## Context / Existing Behavior

The coach observed lag during both their own long moves and computer turns. The headless benchmark used 22 real sprites, a fixed drag requesting 160 updates, a discarded warm-up and three measured runs per condition. At Old World Classic 50°, median run-level p95 rAF interval was 8.8 ms stationary, 158.6 ms during a player move and 108.6 ms during ball movement. In the player case, disabling transitions only on stationary markers and their images yielded 12.2 ms and 131 matched camera updates, up from 18. Hiding the turf and grid still yielded 133.2 ms. The direct-transition experiment was run during player movement only. See the per-run CSV and methodology in #637.

## In Scope

- Measure the current renderer after integrating the relevant #635 work. If it already meets the criteria, deliver regression coverage and evidence. Implement only the remaining performance correction.
- Cover the shared live pitch during coach, opponent and computer playback, with separate player-move and loose-ball workloads. Use the normal 50° renderer as the primary case and both 90° venues as controls.

## Out of Scope

Shadow geometry, selection alignment and the shared interpolation design belong to #635. General idle or late-match degradation remains with #616 and #527. Stadium artwork, camera projection redesign and server-side changes are outside this slice.

## Functional Requirements

- Stationary players and their sprite images follow camera position and scale immediately during another piece’s movement. Movement timing applies only to pieces actually travelling between squares. A loose-ball step must not start transitions on surrounding players.
- Preserve smooth travel of the moving piece during a pan or reversal, correct final squares, playback speed, reduced-motion preferences and existing replay seek behavior. Keep every player, the ball, turf, grid and overlays visible as usual.

## Acceptance Criteria

- [ ] On the same machine, browser, viewport and build settings, compare the pre-change baseline with the candidate’s normal renderer using the #637 workload. Run one warm-up and at least three measured runs per condition. Keep per-run values and sample counts.
- [ ] Proposed review target for 50° Old World Classic: for both player and loose-ball playback, cut the median run-level p95 rAF interval by at least half versus the matched baseline and commit at least 100 of 160 requested drag updates in the median run. The player control achieved 131. Confirm the ball target against measured runs. These are reference-workload acceptance targets, not universal hardware limits.
- [ ] Report p95 input-to-camera-commit, p95 rAF interval, committed input count and Chrome dropped-frame events. Check 50° idle and both 90° venues against their matched baseline ranges. Investigate any repeatable regression before acceptance.
- [ ] A focused deterministic renderer test verifies that a stationary teammate does not acquire a screen-position transition from a player or ball step while panning. Reuse coverage from #635 where it protects the same behavior.
- [ ] In live play, pan during a coach move and a computer move at 50°. Verify responsiveness, continuous movement and correct final squares. Record the build and outcome.

## Constraints

Use the existing benchmark and playback paths. Preserve authoritative game state and camera controls. Keep timing thresholds out of machine-independent CI. Claude reviews this scope before implementation begins.

## Implementation Guidance

Review how GameView and HostedResult supply playback duration, how LivePitch projects each marker, and which live-pitch.css rules affect the marker and its image. Coordinate with #635 so one implementation owns movement interpolation. Check CSS precedence: a less-specific default marker rule also transitions left and top. If #635 eliminates the global transitions, rerun the normal renderer first. Use the stationary-transition control only to isolate residual cost.

## Validation

Run the #637 movement matrix, the existing idle-pan camera registration/release checks, and relevant playback/replay tests. Keep diagnostics, throttling and zoom identical between comparisons. Report coalesced input counts alongside latency because a low p95 based on very few camera commits can hide stalls.

## Completion Notes

Attach the baseline and candidate revisions, per-run measurements, and live-play observations. Document which gains came from #635 and any additional production change. Report residual idle lag to parent #616.

Tracking issue: https://github.com/the-grognard-codes/moles-under-the-pitch/issues/638
