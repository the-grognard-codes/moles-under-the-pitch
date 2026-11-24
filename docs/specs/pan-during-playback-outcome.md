# Pitch panning during playback, slice 1

[Issue #638](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/638) tracks two slices. The hover change was built from `dc323dc95` and the production-code revision is `37bcc0dc7`. The final 50° thresholds and stationary-piece interpolation check follow [#635](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/635).

## Behavior

The document animation listeners return immediately during a drag or touch pan. With mouse or pen hover active, they skip the player and ball marker subtrees, including artwork and skill icons. Other elements that start animations enter a set. The next frame inspects each distinct element once and continues hit-testing while its finite animations run. Camera controls, the mutation observer, movement timing and replay use their existing paths.

## Live play

The production browser build ran on Windows in Chrome against the local Java server and computer player. In a fresh coach-versus-computer match at 50°, a right drag reversed direction during each side's movement. Frame samples captured 18 distinct camera positions for each successful drag. The moving marker occupied 46 distinct horizontal screen positions during the coach sample and 43 during the computer sample. Both markers ended on their authoritative squares. Two earlier computer samples registered no camera displacement and were excluded from this observation. This smoke check records movement and final squares. The matched benchmark measures latency.

Dev-local started with `--quick` and passed its native WebSocket authentication-gate and reconnect checks. Startup used the main checkout's local schema-10 index compatibility fix because the committed startup helper rejects the development database's version.

## Matched movement benchmark

The #637 fixture at `2ce20a25d` used a production build, 22 decoded player sprites, Windows Chrome 158.0.8127.36, a 1440 × 900 viewport and 160 requested camera inputs per trial. One warm-up was discarded and each condition was measured three times with alternating order. The normal-renderer comparisons ran without competing browser suites. The table gives medians of run-level statistics, baseline → candidate. Input latency includes only inputs matched to a camera commit.

| Venue and angle | Workload | Frame interval p95 (ms) | Camera commits / 160 | Input-to-commit p95 (ms) | Dropped-frame events |
| --- | --- | ---: | ---: | ---: | ---: |
| Old World Classic 50° | stationary | 6.2 → 6.2 | 150 → 142 | 16.2 → 17.7 | 2 → 1 |
| Old World Classic 50° | player | 90.8 → 24.3 | 25 → 85 | 27.1 → 30.9 | 249 → 150 |
| Old World Classic 50° | ball | 90.9 → 18.3 | 23 → 100 | 25.6 → 28.7 | 236 → 128 |
| Old World Classic 90° | stationary | 6.2 → 6.2 | 145 → 145 | 16.6 → 16.6 | 4 → 0 |
| Old World Classic 90° | player | 12.1 → 6.2 | 133 → 134 | 18.8 → 18.7 | 17 → 1 |
| Old World Classic 90° | ball | 6.2 → 6.2 | 136 → 138 | 19.3 → 17.4 | 12 → 1 |
| Badlands Brawl 90° | stationary | 6.2 → 6.2 | 147 → 141 | 17.8 → 15.5 | 5 → 0 |
| Badlands Brawl 90° | player | 12.1 → 6.2 | 135 → 139 | 19.4 → 18.9 | 21 → 1 |
| Badlands Brawl 90° | ball | 12.1 → 6.2 | 138 → 137 | 19.9 → 18.5 | 18 → 2 |

At 50°, the moving-player case committed 3.40× as many inputs and the ball case committed 4.35× as many. Both more than halved their p95 frame interval. Input-to-commit p95 increased for the larger set of committed inputs, a trade-off also seen when the baseline listener was blocked. The candidate's 90° player and ball frame-interval medians matched the stationary 6.2 ms in both venues. Individual commit counts varied, especially for Old World Classic players (132–144 during playback against 138–149 stationary). The residual 50° movement cost remains for the post-#635 measurement.

Of the 160 requested moves at 50°, the median count coalesced before reaching the input handler fell from 115 to 44 during player movement and from 118 to 39 during ball movement. Among received inputs, 20 → 27 for players and 18 → 21 for the ball had no matching camera commit. [Per-run measurements and bundle hashes](../reviews/638-hover-overlap.csv) include both normal and window-capture control runs. Each row records received, committed and frame sample counts.

Blocking the listener at window capture on the baseline produced 18.3 ms p95 frame intervals for both 50° workloads, with 97 player and 90 ball camera commits. Disabling stationary-marker transitions on the candidate produced 6.2 ms and 139 commits during a player move. This separates the measured hover improvement from the remaining cost assigned to #635.

The matrix showed eight fewer median commits when idle at 50°. A separate repeat using the same bundles measured 149 baseline and 150 candidate. Input-to-commit p95 varied across browser processes, so a final run alternated the baseline and candidate bundles in one Chrome process, reversing order each round. All three idle views measured 6.2 ms p95 frame intervals on both builds. The 50° baseline and candidate committed 149 and 147 inputs, with input-to-commit p95 of 20.3 and 18.6 ms. Both 90° views were within three commits and 0.5 ms input latency of baseline. The CSV retains both idle follow-ups.

## Regression coverage

The deterministic cadence test pans during player playback and samples animation API calls both during the drag and after hover returns. The baseline hook made 18 player-subtree, nine ball-subtree and three overlay reads during the drag. The candidate made zero. With hover restored, the candidate inspected the overlay once for three animation events and skipped both piece subtrees. The sliding-panel, pending-recovery, player-action-menu and projected-pitch browser suites also passed.

The playback, playback-burst and playback-motion suites passed. The motion check sampled 32 intermediate player frames and 39 ball frames. Cadence covered reduced motion and replay seek/skip. Two hosted result/replay checks passed. The idle pan test passed grid registration, cancellation, release and final camera alignment.

An additional camera-perspective suite failed its overhead scenery snapshot on both the candidate and the original hook. It expected `null` and received `undefined` after the element was removed. Grid and marker coordinates were identical. Local browser checks used Windows Chrome. Android and Linux device behavior was not directly measured here.
