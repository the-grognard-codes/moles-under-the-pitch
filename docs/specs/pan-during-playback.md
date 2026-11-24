# Feature: Keep pitch panning responsive during player and ball playback

[Issue #638](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/638) · [Slice 1 outcome](pan-during-playback-outcome.md)

## Abstract

At 50°, panning the pitch stalls while a player or a loose ball moves. The #637 benchmark shows that the stall depends on CSS transitions on the stationary player markers. A review rerun of that benchmark found where most of the cost is: our own code, not the browser's rendering. [`usePitchHover`](../../browser-client/src/use-pitch-hover.ts) listens for every `transitionrun` event in the document and calls `getAnimations()` on its target. A 50° pan during playback starts about 3,500 transitions.

Blocking only that listener cuts the 50° p95 frame interval from about 152 ms to 30 ms. Also removing the stationary transitions, as #635 will, brings it to about 12 ms. This issue fixes the listener now, then measures what remains after #635. It is a child of #616.

## Objective

Keep the camera responsive during gameplay movement while preserving the existing square-to-square playback cadence. Establish the improvement in the normal renderer with every player visible.

## Context / Existing Behavior

The coach observed lag during their own long moves and during computer turns.

### The #637 benchmark

The headless benchmark uses 22 real sprites and a fixed drag of 160 requested updates, discards one warm-up, then measures three runs per condition. At Old World Classic 50°, the median run-level p95 rAF interval was 8.8 ms when the pitch was stationary, 158.6 ms during a player move and 108.6 ms during ball movement. Disabling transitions only on the stationary markers and their images gave 12.2 ms and 131 matched camera updates, up from 18. Hiding the turf and grid still gave 133.2 ms. Per-run CSV and method: #637.

### Review rerun (2026-11-23)

The rerun used PR #637 head `2ce20a25d`, Chrome 158.0.8127.36 and the same runner, with one added control. That control adds a capture listener on `window` that stops `transitionrun` before it reaches the document, so `usePitchHover` never sees it. Each value is the median of three measured runs after a warm-up.

| Condition | rAF p95 (ms) | Camera updates of 160 | `transitionrun` dispatch per run |
|---|---:|---:|---:|
| 50° stationary | 6.2 | 143 | 0 |
| 50° player, normal | 151.6 | 21 | about 1,450 ms |
| 50° player, hover listener blocked | 30.2 | 70 | about 75 ms |
| 50° player, stationary transitions off | 12.2 | 138 | about 290 ms |
| 50° ball, normal | 115.2 | 20 | about 1,400 ms |
| 50° ball, hover listener blocked | 30.3 | 69 | about 75 ms |
| 90° player, normal | 12.2 | 134 | about 460 ms |
| 90° player, hover listener blocked | 6.2 | 137 | about 25 ms |

The baseline reproduced #637's figures, and every round showed the same direction.

### The live trace agrees

In the archived Old World 50° trace (`archive/performance-reviews/616-pan-during-movement/old-world-50-shadows-one-square-start.json.gz`, a local archive outside git), one 1.25 s window of panning during movement dispatched 2,832 `transitionrun` events. They took 800 ms of main-thread time, and 706 ms of that ran in a single handler whose call count matches the event count. Paint, style and layout together stayed under 15 ms per 250 ms. The pan received only 23 pointer moves in that window.

### Why it costs so much

1. During playback, every player marker and its images transition `left`, `top`, `width` and `height` ([live-pitch.css:501-505](../../browser-client/src/live-pitch.css); #635 has the details). Each camera update retargets those transitions. That starts about 170 transitions per update at 50°, where markers change size with depth, and about 25 at 90°, where only position changes. The `.live-marker img` selector also matches the skill-marker icons.
2. [use-pitch-hover.ts:44-53 and 72](../../browser-client/src/use-pitch-hover.ts) handles each `transitionrun` (and `animationstart`) in capture phase on the document. It calls `getAnimations()` and `getComputedTiming()` for its target. The handler runs even when no hover pointer is tracked, which includes every right-drag and every touch pan. Phones therefore pay the cost too.
3. Neither player markers nor the ball can change the hovered square. The square comes from the camera and the pointer position. The hit test accepts either the ground or a player marker, and the ball marker has `pointer-events: none`. The listener exists so that panels sliding over a stationary pointer refresh the hover; [cursor-square-ui.mjs:103-112](../../browser-client/test/cursor-square-ui.mjs) covers that case.
4. Once the listener is blocked, about 30 ms remains at 50°, compared with 12 ms when stationary transitions are also off. The rest of the cost is the transitions themselves. #635 removes them. The same hook's body-wide `MutationObserver` also watches `style` changes on every marker; measure that before changing it.

The 90° views are affected as well, not unaffected controls: 12.2 ms and 42–65 dropped frames against 6.2 ms and 4–9 with the listener blocked.

## In Scope

- **Slice 1 (now):** stop `usePitchHover` doing per-transition work for pitch pieces and when no hover pointer is tracked. Add the hover-listener control to the benchmark runner if #637 merges without it.
- **Slice 2 (after #635):** measure the normal renderer with #635 merged. If it meets the final targets, deliver the evidence. Otherwise implement only the remaining correction.
- The shared live pitch during coach, opponent and computer playback, with separate player-move and loose-ball workloads. 50° Old World Classic is the primary case. Both 90° venues are targets too.

## Out of Scope

- Shadow geometry, selection alignment and the shared interpolation design, which belong to #635.
- General idle or late-match degradation, which stays with #616 and #527.
- Stadium artwork, camera projection redesign and server-side changes.

## Functional Requirements

- `usePitchHover` does no animation bookkeeping while it tracks no hover pointer.
- It ignores transitions and animations inside `.live-marker` and `.live-ball-marker`.
- It calls `getAnimations()` at most once per frame for the remaining targets, not once per event.
- A panel that slides or resizes over a stationary pointer still refreshes the hovered square during its transition.
- After #635, stationary players and their images follow the camera's position and scale immediately while another piece moves. A loose-ball step must not start transitions on surrounding players.
- Preserve smooth travel of the moving piece during a pan or reversal, correct final squares, playback speed, reduced-motion preferences and existing replay seek behavior. Keep every player, the ball, turf, grid and overlays visible as usual.

## Acceptance Criteria

Slice 1:

- [ ] In the benchmark at 50° Old World Classic, the player and the loose-ball cases each at least halve the median run-level p95 rAF interval and at least triple the committed camera updates against the matched baseline. The review rerun with the listener blocked gave 30 ms and 70 updates, against 152 ms and 21.
- [ ] The player and ball cases at 90° in both venues fall within the matched stationary range.
- [ ] A deterministic browser test pans during player playback, with and without a hover pointer. It checks that `getAnimations()` is not called for marker or ball transitions.
- [ ] `cursor-square-ui.mjs` still passes, including the sliding-overlay case, along with the other hover suites.

Slice 2, after #635:

- [ ] At 50° Old World Classic, the player and loose-ball cases commit at least 120 of 160 camera updates, with a median p95 rAF interval of at most 20 ms. The stationary-transitions-off control reached 129–143 updates and 12.2 ms.
- [ ] A focused deterministic renderer test checks that a stationary teammate gets no screen-position transition from a player or ball step while panning. Reuse #635's coverage where it protects the same behavior.

Both slices:

- [ ] Compare against the matched baseline on the same machine, browser, viewport and build settings. Run one warm-up and at least three measured runs per condition, and keep per-run values and sample counts.
- [ ] Report p95 input-to-camera-commit, p95 rAF interval, committed input count and Chrome dropped-frame events. Check 50° idle and both 90° venues against their matched baselines, and investigate any repeatable regression before acceptance.
- [ ] In live play, pan during a coach move and a computer move at 50°. Verify responsiveness, continuous movement and correct final squares, and record the build and outcome.

## Constraints

- Use the existing benchmark and playback paths.
- Preserve authoritative game state, camera controls and hover behavior.
- Keep timing thresholds out of machine-independent CI.
- Ensure the change works in supported browsers on Android, Windows and Linux.
- Preserve MUTP colors, typography, style and themes.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- In `animate` ([use-pitch-hover.ts:44-53](../../browser-client/src/use-pitch-hover.ts)), return before touching the target when `pointer.current` is null or the target is inside `.live-marker` or `.live-ball-marker`. Queue the remaining targets and read their animations in the scheduled frame. Keep the finite-`endTime` filter that stops infinite decorative effects from looping.
- Use `closest()` on the target, which is cheap, rather than checking ancestry against React state.
- The benchmark control: `window.addEventListener('transitionrun', event => event.stopImmediatePropagation(), true)` before the pan. A capture listener on `window` runs before the document's.
- Slice 2: coordinate with #635 so that one implementation owns movement interpolation. Check CSS precedence: a less specific default rule ([live-pitch.css:46](../../browser-client/src/live-pitch.css)) also transitions `left` and `top`. Run the normal renderer first; use the stationary-transition control only to isolate any residual cost.

## Validation

- Run the #637 movement matrix with the hover-listener control, the existing idle-pan camera registration and release checks, and the relevant playback and replay tests.
- Run `cursor-square-ui.mjs`, `pending-recovery-ui.mjs`, `player-action-menu-ui.mjs` and `projected-pitch-ui.mjs`, which cover hover behavior.
- Keep diagnostics, throttling and zoom identical between comparisons.
- Report coalesced input counts alongside latency, because a low p95 based on very few camera commits can hide stalls.

## Completion Notes

- Attach the baseline and candidate revisions, per-run measurements and live-play observations for each slice.
- Document which gains came from slice 1, which from #635, and any further change.
- Report residual idle lag to parent #616.
- Add a user-visible entry under Unreleased in `CHANGELOG.md` for slice 1.

## Dependencies

- The benchmark runner arrives with #637 (open PR).
- Slice 2 is blocked by #635.

## Decisions

Settled by the maintainer on 2026-11-23 ("1A 2A"):

1. Slice 1, the hover-listener fix, lands now, ahead of #635.
2. The final 50° target after #635 is at least 120 of 160 camera updates and a median p95 rAF interval of at most 20 ms, for both player and ball.
