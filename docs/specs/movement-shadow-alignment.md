# Bug: Player sprites separate from their shadows during movement playback

Implementation and acceptance evidence for [issue #635](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/635) are in the [outcome report](movement-shadow-alignment-outcome.md). The reproduction and code references below describe the original renderer at `64f9878bb`.

## Abstract

While a move plays back, every player sprite and the ball marker trail the camera by up to 200 ms. The ground shadows, turf and grid follow the camera at once. Panning, zooming or revealing during the move therefore pulls each sprite off its shadow and the ball off its square. The coach saw this at 50°: stationary teammates were affected as well as the moving player.

The browser reproduction confirmed a second symptom: with the camera still, the moving player's shadow and selection square jump to each new square at the start of a step while the sprite is still on its way there.

## Objective

During movement and ball playback, each player's sprite, its shadow and the ball share one projected ground position in every frame, whatever the camera does.

## Context on the original build

### Reproduction

1. In a live Old World Classic match, select the 50° camera and confirm a move.
2. Drag the camera while the player travels. Watch the feet of a stationary teammate as well as the moving player.
3. In the Chrome Performance filmstrip, two stationary red midfield players have shadows beneath their boots around 9.05 s. Around 9.35 s, during the drag, both shadows sit visibly below their boots. Times are relative to the first filmstrip frame.

Evidence (local archive, outside git): `archive/performance-reviews/616-pan-during-movement/old-world-50-shadows-one-square-start.json.gz`.

### Cause confirmed by the match filmstrip and browser reproduction

1. While playback is active, [GameView.tsx:1143](../../browser-client/src/GameView.tsx) and [HostedResult.tsx:140](../../browser-client/src/HostedResult.tsx) pass `movementDuration` to `LivePitch`, which sets `--pitch-move-duration` on the whole pitch section ([LivePitch.tsx:365](../../browser-client/src/LivePitch.tsx)). A player step sets it to `PLAYER_STEP_MS / rate`, which is 200 ms at normal speed; a ball step sets it to `BALL_STEP_MS / rate`, which is 250 ms ([use-pitch-playback.ts:365-367](../../browser-client/src/use-pitch-playback.ts), [presentation-timing.ts:3-4](../../browser-client/src/presentation-timing.ts)). It stays set between steps until a dice or block beat arrives or playback ends.
2. [live-pitch.css:501-505](../../browser-client/src/live-pitch.css) transitions `left`, `top`, `width` and `height` on every `.live-marker` and its `img` over that duration. It applies to every player, not only the moving one. Lines 506-508 transition the ball marker's `left` and `top` and the ball highlight's `transform`.
3. Each camera change gives every marker a new screen box. Camera changes include a drag pan, its release, wheel zoom, the Reveal buttons, the automatic reveal at a decision ([LivePitch.tsx:280-287](../../browser-client/src/LivePitch.tsx)) and a resize. During playback each sprite and the ball ease towards their new boxes.
4. Shadows are SVG polygons whose `points` are recomputed from the current camera at each render ([LivePitch.tsx:438-449](../../browser-client/src/LivePitch.tsx)). SVG `points` do not transition. Shadows, grid, turf and selection squares therefore move at once, and each sprite trails its shadow by the last 200 ms of camera travel.
5. The shadow and the selection square are placed from `player.x`/`player.y`. That position jumps to the next square when the step's view is committed, while the sprite takes the same 200 ms to ease there. So with a still camera, the moving player's shadow leads its sprite by up to one square on every step.
6. The ball highlight's `transform` is a `matrix3d` ground-plane homography ([BallMarker.tsx:35-39](../../browser-client/src/BallMarker.tsx)). The browser blends such matrices by decomposing them, not along the pitch, so the ring's shape mid-step is not a projected ground ring.

### Other affected modes

- The 90° top-down view, including Gatehouse, renders through the same `.projected-pitch` rules, so it should show the same defect and cannot serve as a control.
- Match replay (`HostedResult`) and computer-player moves use the same playback path. The coach's own moves go through the provisional path ([use-pitch-playback.ts:195-212](../../browser-client/src/use-pitch-playback.ts)), which sets the same duration.
- With `prefers-reduced-motion`, these transitions are off ([live-pitch.css:509-511](../../browser-client/src/live-pitch.css)), so the defect should not appear. That is a cheap check of the cause.

### Code that depended on the original animation

- [live-pitch.css:46](../../browser-client/src/live-pitch.css) gives every `.play-runtime .live-marker` a permanent 200 ms `left`/`top` transition. Only the more specific rule at line 501 overrides it. If that rule loses its `transition` declaration, every marker eases on every pan, including idle pans.
- [CircularSkillPrompt.tsx:49](../../browser-client/src/CircularSkillPrompt.tsx), [RectangularDecisionPrompt.tsx:63](../../browser-client/src/RectangularDecisionPrompt.tsx) and [MatchDecisionDialog.tsx:33-40](../../browser-client/src/MatchDecisionDialog.tsx) place prompts from the `.live-marker` bounding boxes.
- [gameplay-performance-observer.mjs:83 and 297-299](../../browser-client/test/gameplay-performance-observer.mjs) records the marker's CSS transition in its traces and describes movement in terms of it. This is the tool #616 measures with.
- [cadence-ui.mjs:252](../../browser-client/test/cadence-ui.mjs) checks that a replay seek moves a marker with no transition.

### Original coverage gap

[projected-pitch-ui.mjs:279-367](../../browser-client/test/projected-pitch-ui.mjs) checks shadow alignment only when the pitch is at rest. No test samples a frame during a step. [playback-ui-harness.tsx](../../browser-client/test/playback-ui-harness.tsx) already drives playback and is a likely base for one.

### Related

- #616 investigates pan stutter during movement at 50°. During a pan, these transitions restyle every marker on every frame. This fix lands first; the maintainer will take new captures for #616 afterwards if they are still needed.
- #131 introduced projected shadows and required them to stay attached during movement playback, pan and zoom.
- #191 corrected body and shadow centering.

## In Scope

- Sprite-to-shadow alignment for every player during playback, both the moving player and stationary players.
- The ball marker: its square-to-square steps (pass flight, scatter, bounce, throw-in), a carried ball's attachment to its carrier, and its highlight ring.
- The moving player's selection square.
- Every camera change during playback: drag pan, release, wheel zoom, the Reveal buttons, automatic reveal and viewport resize.
- Every camera angle (30°, 40° and 50° perspective, and 90° top-down with and without Gatehouse) from both coach ends.
- Live matches and match replay, for the coach's own, the opponent's and computer-controlled moves.

## Out of Scope

- General pan frame-rate work (#616), apart from removing the transitions this fix replaces.
- Ball, shadow and sprite artwork, and the highlight ring's rotation.
- Kick-off placement previews, which do not animate during playback.
- Movement and ball step durations, beat order and dice timing.

## Functional Requirements

- A sprite's ground anchor, its shadow and its selection square come from the same pitch-space position in every frame. A carried ball's highlight uses its carrier's position.
- A camera change applies to every piece in the same frame. No piece eases towards a new camera position.
- A stationary player does not move relative to its square while another piece moves.
- The moving player and the ball travel between squares over the existing step durations, scaled by the playback rate.
- Motion is timed by the clock, not by frames. Dropped frames or a hidden tab must not leave a piece between squares; each step ends exactly on its authoritative square.
- A step that starts before the previous one has finished continues from the piece's displayed position, as a CSS transition does now.
- Cancelling or resynchronising playback, a replay seek, and "Skip animations" put pieces on their authoritative squares at once.
- Accessible names, `data-x`/`data-y` and hit testing keep the authoritative square. The `.live-marker` box stays on the displayed sprite, because prompts are placed from it.
- Keep the pose-specific `anchor` and `shadowAnchor` from `playerArtPlacement`, per-depth scale, and the current depth order.
- With reduced motion, pieces still move without animation.

## Acceptance Criteria

- [x] In the 50° reproduction, a stationary teammate keeps its at-rest boot-to-shadow offset, within 1 CSS pixel, in every sampled frame during the drag and after release.
- [x] With the camera still, the moving player's shadow and selection square stay under its boots for every step of a multi-square move.
- [x] When the camera reverses direction mid-step, the moving player's shadow stays under its boots.
- [x] During a pan, a carried ball's highlight stays under its carrier, and a loose ball's scatter or bounce lands on its authoritative square. In every sampled frame, the highlight ring matches the ground-plane projection at the ball's displayed position.
- [x] The first four criteria also hold at 90° top-down and at 30°, from both coach ends.
- [x] Wheel zoom during a move keeps every sprite on its shadow.
- [x] After playback, every piece rests on its authoritative square with the at-rest offsets that `projected-pitch-ui.mjs` already checks.
- [x] A match replay passes the stationary-teammate check at 1× and 2× speed. Seek and "Skip animations" move pieces with no animation.
- [x] An idle pan, with no playback, moves every marker with the camera and no easing.
- [x] With reduced motion, pieces move without animation and players stay on their shadows.
- [x] At 50°, the frame intervals of a pan during a multi-square move are no worse than before the change (p50 and p95, same machine and capture method).

## Constraints

- Preserve authoritative positions, movement and ball timing, existing pitch projections and all artwork.
- Keep the change in presentation. Do not change the protocol, the server or the transcript.
- Do not reintroduce synchronous rendering on each pointer move. [The idle-panning outcome](idle-panning-responsiveness-outcome.md) rejected it because it starved painting.
- Ensure the fix works in supported browsers on Android, Windows and Linux.
- Preserve MUTP colors, typography, style and themes. Reuse CSS and other UI/UX scaffolding where appropriate.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- Animate in pitch space, not CSS screen space. Interpolate each moving piece's position as a fractional square, then project its sprite box, shadow, selection square and ball highlight from that point with the current camera on each frame. Stationary pieces then follow the camera immediately.
- A CSS-only fix cannot meet the criteria. SVG `points` do not transition, and at 50° neither a linear blend of two screen positions nor a blended `matrix3d` follows the projected ground.
- Remove the marker, image, ball and highlight transitions, and remove or neutralise the rule at `live-pitch.css:46` so that it cannot take over.
- Keep each frame's work to the moving pieces. Write their positions imperatively or through a narrow component, rather than rendering all of `LivePitch` each frame. Write a piece's sprite and shadow in the same frame.
- Depth order may keep switching at the start of a step, as it does now. Change it only if the moving sprite visibly overlaps a neighbour wrongly.
- Update `gameplay-performance-observer.mjs` so that its traces describe the new animation rather than the CSS transition, keeping its movement timings comparable for #616.
- For the regression test, start a move from `playback-ui-harness.tsx`, pan mid-step with the existing right-drag, and on several `requestAnimationFrame` ticks compare each marker's rendered foot anchor with its `[data-shadow-player]` centroid. Reuse the measurement at `projected-pitch-ui.mjs:358-367`.

## Validation

- Before changing code, capture the reproduction and confirm both mechanisms: stationary sprites trailing the camera, and the moving player's shadow leading its sprite. Record any that does not reproduce.
- Add browser regression tests that sample intermediate frames with the camera still, during a pan and just after release, at 50° and at 90°, for a player step and a ball step. A test that checks only the end position would miss this defect.
- Record pan frame intervals during a multi-square move at 50°, before and after, with the same capture method.
- Run the projected-pitch, ball, ball-zoom, cadence, idle-panning, camera-perspective and movement browser suites, the browser unit tests, the typecheck and the production build.

## Completion Notes

- In the closing PR, record the verified cause, the regression tests, before/after captures of the same pan-and-move sequence, and the frame-interval comparison.
- Note on #616 which build contains this fix, so its new captures can use it.
- Add a user-visible fix entry under Unreleased in `CHANGELOG.md`.
- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.

## Decisions

Settled by the maintainer on #635 on 2026-11-23:

1. The moving player's sprite is in scope.
2. The ball is in scope.
3. Fix this before #616; take new #616 captures afterwards if they are needed.

Assumed during review on 2026-11-23, open to the maintainer's veto:

- Because ball steps use the same transition, "the ball" covers its flight, scatter and bounce steps, not only camera following.
- The moving player's selection square travels with the sprite rather than marking the authoritative square ahead of it.
- Depth order keeps its current behaviour.
