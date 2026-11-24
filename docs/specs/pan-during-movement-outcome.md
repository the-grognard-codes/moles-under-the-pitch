# Pitch pan during movement

The live renderer reproduces the reported slowdown without network traffic. During playback, every player marker and its image can transition left, top, width and height while the camera changes their projected geometry. Keeping the stationary players visible and removing just those transitions materially improves the 50° moving-player drag. Hiding the perspective scenery and ordinary grid still leaves heavy frame loss. The next implementation target for #616 is stationary marker transitions during camera movement. Shadow alignment is tracked separately in #635.

## Measurements

Three measured runs per condition after a discarded warm-up. Each cell is the median of its three run-level statistics. Timing columns are milliseconds. Commit samples count pointer updates that could be matched to a rendered camera position out of 160 requested moves. Slow cases coalesce more inputs, so read latency alongside frame intervals and sample counts.

| Home venue | Camera | Motion | Fixture control | Input-to-commit p95 | rAF interval p95 | DroppedFrame events | Commit samples |
|---|---:|---|---|---:|---:|---:|---:|
| Old World Classic | 50° | stationary | normal | 20.4 | 8.8 | 31 | 149 |
| Old World Classic | 50° | player | normal | 39.0 | 158.6 | 178 | 18 |
| Old World Classic | 50° | ball | normal | 95.8 | 108.6 | 254 | 19 |
| Old World Classic | 90° | stationary | normal | 19.1 | 6.2 | 7 | 146 |
| Old World Classic | 90° | player | normal | 25.7 | 12.2 | 76 | 122 |
| Old World Classic | 90° | ball | normal | 26.3 | 16.9 | 75 | 122 |
| Badlands Brawl | 90° | stationary | normal | 19.9 | 6.2 | 20 | 149 |
| Badlands Brawl | 90° | player | normal | 26.9 | 18.1 | 96 | 117 |
| Badlands Brawl | 90° | ball | normal | 26.8 | 18.2 | 97 | 120 |
| Old World Classic | 50° | player | hide-stationary | 23.6 | 12.2 | 57 | 137 |
| Old World Classic | 50° | player | hide-ground | 51.4 | 133.2 | 243 | 18 |
| Old World Classic | 50° | player | direct-stationary | 24.7 | 12.2 | 75 | 131 |

Chrome 158.0.8127.36, AMD Ryzen 7 260 w/ Radeon 780M Graphics, 1440 × 900, device scale 1, diagnostics off, no emulated CPU throttling. Benchmark built from the renderer at `d7232421a`. Fixture bundle SHA-256 `bcff68dd3999c6dc4233afb6af0a76acb54d7d599e7ce5ec92afd743ee99e22b`.

[Per-run measurements](../reviews/616-pan-during-movement.csv) · [Reproduction command and control definitions](../operations/idle-panning-review.md#pan-during-movement) · [Investigation #616](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/616)

## Interpretation and limits

The first archived Old World coach traces recorded 18 dropped-frame events in a 367 ms drag at 50° and one in a 511 ms drag at 90°. The additional 50° trace recorded 71 during a 1.22 s drag without a clear square crossing. These manual samples motivated the matched synthetic workloads; their durations and match states differ.

The benchmark drives the real player, ball, camera, and venue renderer with synthetic playback updates. Both coach and computer actions use that renderer. It isolates presentation from gameplay decisions, server timing, and match history growth. The ground control hides the ground visually while retaining camera projection calculations. The marker controls retain ground shadows. The direct-stationary control preserves every sprite, including normal animation on the moving player. It is a diagnostic experiment confined to the fixture.

All three paired rounds showed the same direction for the stationary-transition control. The baseline 50-degree player trial committed only 18 to 19 sampled inputs per run. The control committed 129 to 131. The slow runs actually accumulated less attributed marker Paint time because so few updates completed. The controlled improvement implicates transitioning marker geometry during camera travel; these traces alone do not identify a specific Chrome internal stage as the sole bottleneck.

Chrome's DroppedFrame events can include partially presented frames. Event counts are workload indicators, not a percentage of display refreshes. Input-to-commit ends at the observed DOM update. rAF intervals measure main-thread rendering opportunities. Neither measures physical display latency. Marker Paint durations cover attributed paint events only. Runtime acceptance should repeat the same comparison after changing the renderer and verify live gameplay on the affected device.

## Follow-up

Gate movement interpolation by the piece that changes square. Preserve camera responsiveness for other markers during both player and ball playback. Validate live movement, final positions, and both camera modes, then rerun this matrix. The remaining cost of stationary 50° panning should be measured separately after that change.
