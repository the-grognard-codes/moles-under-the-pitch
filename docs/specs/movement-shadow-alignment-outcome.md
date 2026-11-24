# Movement shadow alignment outcome

Implemented [issue #635](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/635) against `64f9878bb`. Players, shadows, selection squares and the ball now move together during pitch playback. The camera can move independently at any point in the step.

## Reproduction and results

The original match filmstrip shows two stationary midfield players with shadows under their feet at 9.055 s and displaced below them at 9.352 s. A browser reproduction on the unchanged renderer confirmed the second reported mechanism as well. The sprite eased between screen coordinates while the shadow and selection moved to the destination immediately. The ball highlight also interpolated its screen transform during camera travel.

The table gives the largest deviation in CSS pixels in the matched 50° home-coach capture. The drag reverses direction and includes wheel zoom. The acceptance limit is one CSS pixel.

| Measurement | Before | After |
| --- | ---: | ---: |
| Moving shadow, still camera | 37.711 | 0.026 |
| Moving selection, still camera | 38.756 | 0.027 |
| Stationary shadow, moving camera | 121.647 | 0.030 |
| Moving shadow, moving camera | 147.210 | 0.028 |
| Ball ring projection, moving camera | 5.981 | 0.0025 |

The test measures rendered sprite anchors from the artwork and compares the shadow, selection and ring to their ground-plane projections on each sampled frame. The paired captures include [before](references/movement-shadow-alignment/before.png) and [after](references/movement-shadow-alignment/after.png) screenshots and per-frame geometry for the [original](references/movement-shadow-alignment/before-geometry.csv) and [corrected](references/movement-shadow-alignment/after-geometry.csv) renderers.

On the same Windows machine, headless Chrome 158.0.8127.36 performed six right drags during multi-square moves on a 22-player pitch at 50°. The first drag warmed up each build. The benchmark sampled only active playback between pointer-down and pointer-up, with geometry probes disabled.

| Build | Intervals | p50 | p95 | Maximum |
| --- | ---: | ---: | ---: | ---: |
| [Before](references/movement-shadow-alignment/before-frame-intervals.json) | 396 | 6.10 ms | 12.20 ms | 18.10 ms |
| [After](references/movement-shadow-alignment/after-frame-intervals.json) | 408 | 6.10 ms | 6.20 ms | 12.20 ms |

These are browser animation-frame intervals in the fixture. The live-match latency investigation continues in [#616](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/616).

## Behavior and verification

- A committed movement beat starts one clock for its moving pieces. Each participating layer samples the same fractional pitch position and projects it through the current camera. Pose-specific offsets and per-depth scaling apply there. Selection, accessible names and hit testing retain their authoritative square semantics. The marker button follows the displayed sprite for prompt placement.
- Player and ball step durations, replay rates and beat ordering use the existing playback scheduler. The clock stops on cancellation, finishes at the destination after a late frame, and lets an overlapping step continue from its displayed position. Reduced motion places pieces immediately.
- The new browser suite sampled intermediate player and loose-ball frames at 30°, 40°, 50° and 90° from both coach ends, including overhead turf and Gatehouse. It covered still cameras, pan reversal and release, wheel zoom, reveal and resize, idle pan, provisional cancellation, replay at 1× and 2×, seeking, skipping animations and reduced motion. Both full runs passed.
- All 587 browser unit tests passed. Projected pitch, ball, ball zoom, camera perspective, cadence, idle panning, movement flow/range/checks, provisional movement, playback, playback burst, playback motion and match announcements passed. The camera perspective test now waits for the Old World Classic Gatehouse composition before comparing its transform across flip and reload. TypeScript and the production browser build passed.
- The Maven reactor built the common and server modules. Of 981 tests, 962 passed and 19 were skipped. The PR contains browser presentation, instrumentation, regression coverage, and documentation changes.

The automated captures ran on Windows Chrome. The computer coach uses the tested transcript playback path. A signed-in computer-coach match, decision-triggered reveal, and physical Android and Linux devices were not separately exercised.

## Manual testing

The local review database already uses schema 11 from #639. The issue branch builds against current main, whose server expects schema 10. A separate preview worktree at `.worktrees/635-dev-local` runs the schema-11-compatible build `94eab7dd9` with all eight changed #635 runtime files verified byte-for-byte. The original #639 worktree and the existing database were preserved.

The preview started with `node tools/dev-local.mjs --start --quick`. The server and database passed health checks, the browser reached the Java authentication gate on initial connection and reconnect, and the computer coach reported ready. The served JavaScript and CSS matched the preview build. Open [dev-local play](http://localhost:5000/play) for manual testing.
