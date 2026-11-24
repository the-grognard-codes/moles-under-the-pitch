# Movement shadow alignment outcome

Implemented [issue #635](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/635) against `64f9878bb`. Players, shadows, selection squares and the ball now move together during pitch playback. The camera can move independently at any point in the step.

## Reproduction and results

The original match filmstrip shows two stationary midfield players with shadows under their feet at 9.055 s and displaced below them at 9.352 s. A browser reproduction on the unchanged renderer confirmed the second reported mechanism as well. The sprite eased between screen coordinates while the shadow and selection moved to the destination immediately. The ball highlight also interpolated its screen transform during camera travel.

The table gives the largest deviation in CSS pixels in the matched 50° home-coach capture. The drag reverses direction and includes wheel zoom. The acceptance limit is one CSS pixel.

| Measurement | Before | After |
| --- | ---: | ---: |
| Moving shadow, still camera | 37.711 | 0.026 |
| Moving selection, still camera | 38.756 | 0.027 |
| Stationary shadow, moving camera | 127.206 | 0.030 |
| Moving shadow, moving camera | 163.708 | 0.027 |
| Ball ring projection, moving camera | 5.189 | 0.0026 |

The test measures rendered sprite anchors from the artwork and compares the shadow, selection and ring to their ground-plane projections on each sampled frame. The paired captures include [before](references/movement-shadow-alignment/before.png) and [after](references/movement-shadow-alignment/after.png) screenshots and per-frame geometry for the [original](references/movement-shadow-alignment/before-geometry.csv) and [corrected](references/movement-shadow-alignment/after-geometry.csv) renderers.

On the same Windows machine, headless Chrome 158.0.8127.36 performed six right drags during multi-square moves on a 22-player pitch at 50°. The first drag warmed up each build. The benchmark sampled only active playback between pointer-down and pointer-up, with geometry probes disabled.

| Build | Intervals | p50 | p95 | Maximum |
| --- | ---: | ---: | ---: | ---: |
| [Before](references/movement-shadow-alignment/before-frame-intervals.json) | 395 | 6.10 ms | 12.20 ms | 12.30 ms |
| [After](references/movement-shadow-alignment/after-frame-intervals.json) | 406 | 6.10 ms | 6.20 ms | 12.20 ms |

These are browser animation-frame intervals in the fixture. The live-match latency investigation continues in [#616](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/616).

### Reproduce the captures

From the issue checkout, run the following in PowerShell with the project's browser dependencies installed. The capture writes a screenshot, per-frame geometry in JSON and CSV, and frame intervals. Running the suite without the flag exercises the correctness assertions.

```powershell
$env:MOVEMENT_ALIGNMENT_EVIDENCE = '.tools/635-capture'
node browser-client/test/movement-alignment-ui.mjs --capture-only
```

For a matched baseline, run the same command in a disposable checkout of `64f9878bb` with the current `movement-alignment-ui.mjs` copied into `browser-client/test/`. In that checkout run `git restore --source=359957ca7 --worktree -- browser-client/test/playback-ui-harness.tsx` to enable selection in the fixture and supply the original renderer's CSS step duration. Use the same machine, Chrome binary and viewport for both captures.

## Acceptance evidence

| Requirement | Check |
| --- | --- |
| Stationary teammate stays within 1 CSS pixel at 50° during a drag and after release | The frame sampler compares the rendered art anchor and shadow at rest, through the drag and after pointer-up. The paired geometry captures give the individual samples. |
| Moving shadow and selection follow the boots with a still camera and during a reversal | The same test samples both the initial steps before panning and the two directions of a held right drag. |
| Carried ball follows the player and a loose ball lands on its square | The browser test checks the carrier offset, independently projects five points of the ground ring, and checks the final player and ball against known destination squares. |
| Landing in possession, steals and pickups | A ball entering possession has its own path until it arrives. Browser frames check that its image travels with the ring before a catch or steal and that a stationary loose football stays on the pickup square until the player arrives. At rest the ring identifies the carrier. |
| Angle, coach end, zoom and idle pan | The matrix covers both ends at 30°, 40°, 50° and 90°, with turf and Gatehouse overhead. Dedicated cases exercise wheel zoom during travel and pan at rest. |
| Reveal and resize | The test puts the destination in the Reveal margin, verifies the camera focus changes, and samples subsequent frames. It exercises both Reveal buttons during playback and the automatic decision reveal when playback ends. A separate case resizes the viewport during motion. |
| Resting geometry and depth order | The projected-pitch suite checks canonical hits, pose anchors, shadow offsets and missing-art fallback. The renderer continues sorting by authoritative player position when a step starts. |
| Replay at 1× and 2×, seek, skip and reduced motion | The alignment and cadence suites exercise replay at both rates, resynchronisation and immediate placement at the requested square. |
| Interruptions and clock timing | The tests retarget a partial step, skip frames, suppress rAF through the step timer, simulate hiding the tab and toggle reduced motion mid-step. Each path checks the displayed position. |
| Canonical hits and prompt anchors | A diagonal push checks the intermediate sprite and its button against the destination label and integer coordinates. A pointer click during playback sends no action. Clicking the settled marker selects the pushed player. |
| 50° pan frame intervals | Compare the warm, matched p50 and p95 measurements above. |

The existing step scheduler still owns player, ball, dice and block beat order and timing. Integer coordinates continue to identify squares in labels, inputs, transcripts and the protocol. Fractional coordinates exist only in presentation. Camera drag retains its asynchronous updates.

## Scope and exclusions

The patch keeps the projection model, the depth-order rule, the artwork and the ring rotation. Static kickoff placement previews keep their existing placement path. The change covers visible ball travel during kickoff. Server rules, saved games and the wire protocol are unchanged. Wider live-match pan performance remains tracked under #616.

## Validation

The review found two cases the original validation missed. A ball beat that also assigned possession jumped to the carrier and hid the football at the start of the step. The revised path keeps the football visible until landing. Separately, piece style mutations triggered replay hover hit tests on animation frames. The cadence suite now checks that a resting pointer retains its hover square with zero extra hit tests during the sampled portion of a step.

- All 40 hosted browser tests passed across the 13 registered hosted suites, including live play, results and replay. The original observer test timed out on the reviewed head when a marker lacked a projected-pitch ancestor. It now continues sampling and reports an absent step duration as null. Observer format 2 labels displayed-coordinate samples and records pitch-space motion. Request, response and first-movement timing boundaries keep their original definitions.
- The projected pitch, ball, ball zoom, camera perspective, cadence, provisional movement, playback motion, playback burst and idle-pan suites exercise the revised presentation. The original implementation also passed movement flow/range/checks, playback and match announcements. The old camera-perspective test failed on the untouched base because Gatehouse loaded between its samples. The corrected test waits for the composed stadium and compares its transform across flip and reload.
- All 589 browser unit tests, TypeScript and the production browser build passed. On the original implementation, Maven built the common and server modules with 962 tests passing and 19 skipped. This review changed browser code, test instrumentation and documentation.

Chrome on Windows ran the automated captures. The fixture exercises native-shaped playback records shared by human opponents and computer coaches. A signed-in computer-coach match and physical Android and Linux devices remain outside the direct evidence.

## Manual testing

The local review database already uses schema 11 from #639. The issue branch builds against current main, whose server expects schema 10. A separate preview worktree at `.worktrees/635-dev-local` runs the schema-11-compatible build `94eab7dd9` with the changed #635 runtime files verified byte-for-byte. The original #639 worktree and the existing database were preserved.

The original preview passed server, database, browser reconnect and computer-coach checks. The review build also produced the local Hosting artifact. Run `node tools/dev-local.mjs --start --quick` in the preview worktree and open [dev-local play](http://localhost:5000/play) after startup reports ready. The startup script requests interactive Google sign-in if the local Application Default Credentials need renewal.
