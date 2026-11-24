# Reproducing idle pitch panning

Run with Node 26 from the checkout root:

```powershell
node browser-client/test/idle-panning-ui.mjs
```

This builds a production-mode fixture using the real match renderer, serves it on an ephemeral loopback port and closes the browser/server afterwards. It needs installed browser-client dependencies and Chrome or the configured Playwright Chromium executable. It makes no game-server connection and changes no matches.

For the hover-listener control, set `PAN_BLOCK_TRANSITIONRUN=1` and choose a separate `PAN_OUTPUT` directory. The runner stops `transitionrun` events at the window before they reach document listeners. CSS transitions still run. Unset the variable for the normal renderer comparison. When using `PAN_BASELINE=1`, set `PAN_BASELINE_REF` to the comparison revision so its pitch-hover hook is included in the baseline build.

The fixture contains 22 token players derived from existing public projections. It isolates camera behavior rather than native gameplay, sprite-heavy GPU cost or remote transport. Checks cover release stability, exact grid registration at 30/40/50/90 degrees, team-based card sides, and HUD measurement invalidation. Repeated drag/reversal measurements include dispatch, camera commit, rendering opportunities and frame intervals. The browser may coalesce input: counts of received and presented samples differ and must remain visible.

Results and a screenshot are written under `.tools/idle-panning/`. The default performs one measured-shape warm-up followed by three reported repetitions under fixed 4x CPU throttling. This is a stress workload, not a physical Android simulation. rAF and DOM observations do not measure physical display latency. The normal correctness test does not enforce a machine-independent timing ceiling. `--assert-latency` optionally enforces a 50 ms per-run p95 input-to-camera-commit ceiling for a declared reference workload; use it only with the relevant hardware and load recorded.

## Matched before/after measurements

Keep other builds/browser tests stopped while measuring. In PowerShell:

```powershell
$env:PAN_CPU_RATE = '1'
$env:PAN_REPEATS = '3'
$env:PAN_MODE = 'off'
$env:PAN_BASELINE = '1'
$env:PAN_BASELINE_REF = 'd430b9f36'
$env:PAN_OUTPUT = "$PWD/.tools/pan-before"
node browser-client/test/idle-panning-ui.mjs --report-only

Remove-Item Env:PAN_BASELINE
$env:PAN_OUTPUT = "$PWD/.tools/pan-after"
node browser-client/test/idle-panning-ui.mjs --report-only
```

Baseline mode builds the affected renderer/HUD modules from the named Git reference while keeping the same current fixture, measurement code, assets and diagnostics wrapper. It never changes source files or the working tree. It is a scoped comparison, not a claim that the entire artifact is the historical release. Each report records its bundle SHA-256, reference, browser, CPU, viewport, instrumentation mode and sample counts. `--report-only` retains baseline findings without stopping at expected correctness regressions; the normal command enforces those regressions.

Repeat after measurements with `PAN_MODE=counters` and `PAN_MODE=graph` when the diagnostics feature is available in the checkout. Its optional test adapter reports `unavailable` explicitly on a checkout without diagnostics. Repeat with `PAN_CPU_RATE=4` for the stress cohort; keep its statistics separate from the unthrottled cohort. `PAN_USE_BUNDLE` can reuse a previously built fixture bundle when a repeat must use identical code. Use a fresh output directory for each condition; builds do not recursively empty existing directories.

The outcome record is [the idle-panning implementation report](../specs/idle-panning-responsiveness-outcome.md). Retain original Chrome traces/video privately when following a real report. This harness complements those captures and does not replace manual validation on the affected device.

## Pan during movement

Run `node browser-client/test/idle-panning-ui.mjs --movement` from the checkout root. This runs the production LivePitch in a full-size match viewport with 22 real sprites. It requires the same Node, Chrome and browser dependencies as the idle test. It uses an ephemeral loopback server and headless Chrome without CPU throttling. The game server is unnecessary.

Each trial requests the same 160 right-drag updates at 10 ms intervals with two reversals in each direction. The player and loose-ball cases update eight successive squares across midfield at 200 ms per step. The stationary case pans over the same starting lineup. All three run at 50° and 90° for Old World Classic, plus 90° for Badlands Brawl. Playback inputs are simulated at the pitch boundary, so the test measures renderer cost shared by coach and computer actions. Server response handling, the surrounding HUD and the shadow alignment bug have separate coverage or investigations.

At 50° the runner also repeats the moving-player case with three independent controls in the fixture stylesheet:

- Hide the 21 stationary player markers, retaining the moving player and ground shadows.
- Hide the scenery and ordinary grid, retaining the players.
- Keep every player visible and disable CSS transitions only on stationary markers and their images.

The default discards one warm-up per condition and reports three repetitions, alternating condition order between rounds. Set `PAN_REPEATS` to change the measured repetition count or `PAN_OUTPUT` to choose the output directory. Keep other benchmarks stopped. Each trial checks the camera moved, the sprite artwork decoded, and the moving piece reached its final square.

The report at `.tools/idle-panning/movement.json` includes browser and CPU, bundle hash, input-to-camera-commit latency, requestAnimationFrame intervals, Chrome DrawFrame and DroppedFrame counts, and marker Paint durations. Chrome event counts and rAF timing describe different pipeline stages. Compare the same metric across conditions. With heavy input coalescing, check received and committed sample counts alongside percentiles.
