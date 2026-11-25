# State sync storage measurements

Each size is decoded JSON bytes / gzip bytes. Individual fields were compressed independently. JSON wrappers and cross-field compression affect the total.

| Field | Early | Late | Full time |
| --- | ---: | ---: | ---: |
| total | 2540478 / 69596 | 43025921 / 886261 | 43075263 / 888658 |
| compact | 91336 / 9647 | 86294 / 9746 | 78053 / 8593 |
| engine | 53061 / 5188 | 47854 / 5225 | 39612 / 4051 |
| gameLog | 207211 / 8540 | 3767792 / 143793 | 3792638 / 145464 |
| eventsJson | 1026389 / 14539 | 18416681 / 221850 | 18432888 / 222111 |
| transcriptState | 877498 / 12163 | 15733394 / 179822 | 15747075 / 180122 |
| transcriptNative | 220180 / 9562 | 4029759 / 163233 | 4055556 / 165214 |
| history | 28563 / 1878 | 423246 / 20477 | 423904 / 20503 |
| chat | 47 / 63 | 47 / 63 | 47 / 63 |
| homeView | 31485 / 2989 | 25263 / 2737 | 13680 / 1826 |
| awayView | 31088 / 2921 | 26306 / 2853 | 13680 / 1826 |
| rest | 64010 / 7309 | 539707 / 42082 | 540294 / 42160 |

## Repeated storage rounds

Each round uses three fresh 768 MiB JVMs and has 102 samples per stage and move shape. The first two rounds show the observed spread before the compact predecessor was reset to a distinct blob for each repetition. The computer-player suite overlapped the first round. Browser validation overlapped the slowest JVM of the second. The three quiet rounds use the same final probe and seed-43 fixture, with the actual penultimate blob before the terminal write. The isolated database was restarted before the two repeats.

Commit and whole-task cells list ordinary / keyframe / rare-decision p99, in milliseconds. The DB health probe opens a connection and runs `SELECT 1`. It has nine samples per stage and round, so its p90 equals the maximum under nearest-rank sampling.

| Run | Early commit p99 (ms) | Late commit p99 (ms) | Terminal commit p99 (ms) |
| --- | ---: | ---: | ---: |
| state-sync-storage-43 | 37.98 / 29.73 / 28.54 | 31.70 / 40.40 / 44.96 | 29.91 / 52.95 / 30.44 |
| state-sync-storage-final | 56.85 / 42.38 / 51.22 | 32.54 / 37.40 / 34.25 | 160.66 / 163.33 / 117.02 |
| state-sync-storage-quiet | 30.74 / 40.52 / 53.81 | 32.74 / 30.19 / 34.24 | 29.35 / 47.66 / 29.28 |
| state-sync-storage-quiet-repeat1 | 43.27 / 38.09 / 40.42 | 98.49 / 80.41 / 118.73 | 95.25 / 77.60 / 65.32 |
| state-sync-storage-quiet-repeat2 | 40.61 / 44.44 / 36.00 | 61.85 / 59.27 / 51.55 | 42.07 / 48.88 / 41.23 |

| Run | Early whole-task p99 (ms) | Late whole-task p99 (ms) | Terminal whole-task p99 (ms) |
| --- | ---: | ---: | ---: |
| state-sync-storage-43 | 38.66 / 30.95 / 28.92 | 32.02 / 41.13 / 45.55 | 30.41 / 53.79 / 31.09 |
| state-sync-storage-final | 57.54 / 43.56 / 51.58 | 33.83 / 38.23 / 35.03 | 161.47 / 164.68 / 117.73 |
| state-sync-storage-quiet | 33.50 / 41.42 / 54.19 | 33.15 / 31.33 / 35.37 | 30.51 / 48.54 / 30.36 |
| state-sync-storage-quiet-repeat1 | 43.83 / 39.15 / 40.84 | 99.23 / 81.25 / 120.12 | 96.02 / 79.36 / 65.80 |
| state-sync-storage-quiet-repeat2 | 41.53 / 45.52 / 36.42 | 62.24 / 60.25 / 52.30 | 42.82 / 49.79 / 41.84 |

| Run | Early DB round trip p50 / p90 / max (ms) | Late DB round trip p50 / p90 / max (ms) | Terminal DB round trip p50 / p90 / max (ms) |
| --- | ---: | ---: | ---: |
| state-sync-storage-43 | 9.26 / 10.34 / 10.34 | 8.84 / 15.93 / 15.93 | 7.92 / 10.52 / 10.52 |
| state-sync-storage-final | 10.37 / 72.36 / 72.36 | 9.88 / 15.18 / 15.18 | 9.85 / 15.52 / 15.52 |
| state-sync-storage-quiet | 11.46 / 14.13 / 14.13 | 10.19 / 12.14 / 12.14 | 10.18 / 12.80 / 12.80 |
| state-sync-storage-quiet-repeat1 | 10.02 / 19.78 / 19.78 | 8.26 / 48.99 / 48.99 | 9.98 / 56.56 / 56.56 |
| state-sync-storage-quiet-repeat2 | 7.91 / 11.12 / 11.12 | 7.75 / 18.04 / 18.04 | 7.14 / 19.70 / 19.70 |

Late commit p99 still varied after five rounds. Across the three matched quiet rounds, ordinary write p99 ranged from 32.74 to 98.49 ms, keyframe from 30.19 to 80.41 ms, and rare-decision from 34.24 to 118.73 ms. Whole-task p99 ranged from 33.15 to 99.23 ms, 31.33 to 81.25 ms, and 35.37 to 120.12 ms respectively. Matched terminal ordinary and keyframe whole-task p99 ranged from 30.51 to 96.02 ms and 48.54 to 79.36 ms.

Late DB round-trip medians stayed between 7.75 and 10.19 ms in the matched quiet rounds. The round with the highest late write p99 also had a 48.99 ms maximum DB round trip. The health probe is too small and separate from the commits to attribute the cause. The late rare-decision p99 crossed 100 ms in one quiet repeat, and ordinary and keyframe p99 sometimes exceeded the proposed 60 ms write target.

Run the following commands from the repository root with the pinned JDK, training classpath and isolated database configured as in the outcome report. Use fresh output directories. The first two argument directories refer to retained evidence for the earlier probe versions.

```powershell
$fixture = '.tools/state-sync-smoke-fixed/selected'
node tools/diagnostics/state-sync/run-storage.mjs $fixture .tools/state-sync-storage-quiet measure
node tools/diagnostics/state-sync/run-storage.mjs $fixture .tools/state-sync-storage-quiet-repeat1 measure
node tools/diagnostics/state-sync/run-storage.mjs $fixture .tools/state-sync-storage-quiet-repeat2 measure
$rounds = @('43','final','quiet','quiet-repeat1','quiet-repeat2') | ForEach-Object { ".tools/state-sync-storage-$_" }
node tools/diagnostics/state-sync/storage-rounds-report.mjs compare $rounds
```

## Reference run phase distributions

The detailed distributions below use `state-sync-storage-quiet`, the first round using the distinct compact predecessor.

Each distribution gives sample count / p50 / p90 / p99 / maximum. A dash means fewer than 100 samples for p99, or a metric that does not apply. A parent duration includes its children.

| Cohort / operation | Milliseconds | Allocated bytes | Output bytes |
| --- | ---: | ---: | ---: |
| early/baseline/journalAppend | 9 / 33.406 / 80.625 / — / 80.625 | 9 / 1330608 / 1341992 / — / 1341992 | — |
| early/baseline/checkpointStore | 9 / 77.892 / 99.511 / — / 99.511 | 9 / 8004264 / 8004696 / — / 8004696 | — |
| early/baseline/jdbcRecoveryLoad | 9 / 94.728 / 591.481 / — / 591.481 | 9 / 47630112 / 52117296 / — / 52117296 | — |
| early/baseline/validateCheckpoint | 9 / 66.671 / 351.624 / — / 351.624 | 9 / 23065752 / 23505488 / — / 23505488 | — |
| early/baseline/computerFallback | 9 / 0.095 / 0.606 / — / 0.606 | 9 / 2144 / 3848 / — / 3848 | — |
| early/baseline/restoreSession | 9 / 136.236 / 436.042 / — / 436.042 | 9 / 42737144 / 46272896 / — / 46272896 | — |
| early/baseline/checkpointBuild | 9 / 145.173 / 261.383 / — / 261.383 | 9 / 104300384 / 104429696 / — / 104429696 | — |
| early/baseline/compactRead | 9 / 13.53 / 18.987 / — / 18.987 | 9 / 223336 / 236568 / — / 236568 | 9 / 9702 / 9702 / — / 9702 |
| early/baseline/compactDecode | 9 / 1.383 / 4.188 / — / 4.188 | 9 / 1025856 / 1032192 / — / 1032192 | — |
| early/baseline/compactNativeRestore | 9 / 13.153 / 20.483 / — / 20.483 | 9 / 1534392 / 1622784 / — / 1622784 | — |
| early/baseline/copyIncludingLog | 9 / 29.314 / 44.493 / — / 44.493 | 9 / 5410544 / 5422936 / — / 5422936 | — |
| early/baseline/copyOmittingLog | 9 / 13.208 / 17.63 / — / 17.63 | 9 / 2284584 / 2284592 / — / 2284592 | — |
| early/baseline/view-home | 9 / 31.123 / 42.336 / — / 42.336 | 9 / 6911984 / 6925856 / — / 6925856 | 9 / 31583 / 31583 / — / 31583 |
| early/baseline/view-away | 9 / 5.796 / 7.849 / — / 7.849 | 9 / 1452496 / 1458256 / — / 1458256 | 9 / 31186 / 31186 / — / 31186 |
| early/baseline/view-spectator | 9 / 4.825 / 6.11 / — / 6.11 | 9 / 1508208 / 1513976 / — / 1513976 | 9 / 31093 / 31093 / — / 31093 |
| early/baseline/quietProbe | 9 / 11.653 / 12.816 / — / 12.816 | 9 / 206624 / 206776 / — / 206776 | 9 / 32 / 32 / — / 32 |
| early/baseline/activeProbe | 9 / 24.129 / 26.277 / — / 26.277 | 9 / 428976 / 428976 / — / 428976 | 9 / 9702 / 9702 / — / 9702 |
| early/baseline/receiptLookup | 9 / 11.596 / 13.04 / — / 13.04 | 9 / 204024 / 206096 / — / 206096 | — |
| early/baseline/dbRoundTrip | 9 / 11.458 / 14.13 / — / 14.13 | 9 / 203472 / 204376 / — / 204376 | — |
| early/ordinary/queueWait | 102 / 0.097 / 0.12 / 2.922 / 3.777 | — | — |
| early/ordinary/publicProjection | 102 / 0.015 / 0.021 / 0.043 / 0.053 | 102 / 920 / 920 / 920 / 920 | — |
| early/ordinary/patch | 102 / 0.208 / 0.308 / 0.799 / 1.374 | 102 / 5160 / 5160 / 31088 / 31088 | — |
| early/ordinary/patchEncode | 102 / 0.017 / 0.022 / 0.054 / 0.072 | 102 / 3768 / 3768 / 3768 / 3768 | 102 / 508 / 508 / 508 / 508 |
| early/ordinary/commit | 102 / 24.153 / 27.79 / 30.741 / 33.383 | 102 / 653816 / 653816 / 655096 / 656704 | — |
| early/ordinary/envelope-home | 102 / 0.128 / 0.188 / 0.266 / 0.345 | 102 / 61560 / 61560 / 61560 / 61624 | 102 / 11472 / 11472 / 11472 / 11472 |
| early/ordinary/delivery-home | 102 / 0.007 / 0.036 / 0.067 / 0.13 | 102 / 23000 / 23000 / 23000 / 23000 | 102 / 11472 / 11472 / 11472 / 11472 |
| early/ordinary/envelope-away | 102 / 0.007 / 0.011 / 0.038 / 0.041 | 102 / 3912 / 3912 / 3912 / 3912 | 102 / 546 / 546 / 546 / 546 |
| early/ordinary/delivery-away | 102 / 0.001 / 0.002 / 0.005 / 0.014 | 102 / 1160 / 1160 / 1160 / 1160 | 102 / 546 / 546 / 546 / 546 |
| early/ordinary/envelope-spectator | 102 / 0.005 / 0.019 / 0.047 / 0.053 | 102 / 3952 / 3952 / 3952 / 3952 | 102 / 551 / 551 / 551 / 551 |
| early/ordinary/delivery-spectator | 102 / 0.001 / 0.002 / 0.031 / 0.053 | 102 / 3368 / 3368 / 3368 / 3368 | 102 / 1653 / 1653 / 1653 / 1653 |
| early/ordinary/prototypeTask | 102 / 24.755 / 28.383 / 33.498 / 33.987 | 102 / 768408 / 768464 / 872912 / 873408 | 102 / 13671 / 13671 / 13671 / 13671 |
| early/keyframe/queueWait | 102 / 0.098 / 0.123 / 0.193 / 0.553 | — | — |
| early/keyframe/publicProjection | 102 / 0.015 / 0.023 / 0.086 / 0.101 | 102 / 920 / 920 / 920 / 920 | — |
| early/keyframe/patch | 102 / 0.211 / 0.301 / 0.547 / 0.584 | 102 / 5160 / 5160 / 5160 / 5160 | — |
| early/keyframe/patchEncode | 102 / 0.017 / 0.024 / 0.073 / 0.089 | 102 / 3768 / 3768 / 3768 / 3768 | 102 / 508 / 508 / 508 / 508 |
| early/keyframe/keyframeEncode | 102 / 0.102 / 0.153 / 0.225 / 0.291 | 102 / 67176 / 67176 / 67176 / 67176 | 102 / 13511 / 13511 / 13511 / 13511 |
| early/keyframe/commit | 102 / 24.587 / 30.343 / 40.518 / 42.146 | 102 / 737096 / 737528 / 1398992 / 1400952 | — |
| early/keyframe/envelope-home | 102 / 0.269 / 0.373 / 0.591 / 1.201 | 102 / 128312 / 128312 / 128312 / 128312 | 102 / 24995 / 24995 / 24995 / 24995 |
| early/keyframe/delivery-home | 102 / 0.01 / 0.021 / 0.034 / 0.034 | 102 / 50056 / 50056 / 50056 / 50056 | 102 / 24995 / 24995 / 24995 / 24995 |
| early/keyframe/envelope-away | 102 / 0.108 / 0.173 / 0.324 / 0.624 | 102 / 68680 / 68680 / 68680 / 68680 | 102 / 14069 / 14069 / 14069 / 14069 |
| early/keyframe/delivery-away | 102 / 0.003 / 0.007 / 0.046 / 0.053 | 102 / 28200 / 28200 / 28200 / 28200 | 102 / 14069 / 14069 / 14069 / 14069 |
| early/keyframe/envelope-spectator | 102 / 0.085 / 0.131 / 0.218 / 0.244 | 102 / 69456 / 69456 / 69456 / 69456 | 102 / 14074 / 14074 / 14074 / 14074 |
| early/keyframe/delivery-spectator | 102 / 0.007 / 0.023 / 0.051 / 0.051 | 102 / 84504 / 84504 / 84504 / 84504 | 102 / 42222 / 42222 / 42222 / 42222 |
| early/keyframe/prototypeTask | 102 / 25.613 / 31.493 / 41.421 / 43.344 | 102 / 1251768 / 1253984 / 1913664 / 1915624 | 102 / 81286 / 81286 / 81286 / 81286 |
| early/rare/queueWait | 102 / 0.095 / 0.119 / 0.169 / 0.211 | — | — |
| early/rare/publicProjection | 102 / 0.013 / 0.016 / 0.063 / 0.066 | 102 / 920 / 920 / 920 / 920 | — |
| early/rare/patch | 102 / 0.206 / 0.275 / 0.563 / 0.616 | 102 / 6264 / 6264 / 6320 / 6320 | — |
| early/rare/patchEncode | 102 / 0.028 / 0.041 / 0.068 / 0.08 | 102 / 8336 / 8336 / 8336 / 8336 | 102 / 1441 / 1441 / 1441 / 1441 |
| early/rare/commit | 102 / 24.412 / 29.451 / 53.812 / 63.309 | 102 / 685344 / 685416 / 1660904 / 1661768 | — |
| early/rare/envelope-home | 102 / 0.037 / 0.068 / 0.105 / 0.116 | 102 / 8528 / 8528 / 8528 / 8528 | 102 / 1492 / 1492 / 1492 / 1492 |
| early/rare/delivery-home | 102 / 0.005 / 0.013 / 0.059 / 0.083 | 102 / 3048 / 3048 / 3048 / 3048 | 102 / 1492 / 1492 / 1492 / 1492 |
| early/rare/envelope-away | 102 / 0.014 / 0.041 / 0.084 / 0.087 | 102 / 8496 / 8496 / 8496 / 8496 | 102 / 1479 / 1479 / 1479 / 1479 |
| early/rare/delivery-away | 102 / 0.001 / 0.002 / 0.027 / 0.032 | 102 / 3016 / 3016 / 3016 / 3016 | 102 / 1479 / 1479 / 1479 / 1479 |
| early/rare/envelope-spectator | 102 / 0.013 / 0.02 / 0.044 / 0.047 | 102 / 8512 / 8512 / 8512 / 8512 | 102 / 1484 / 1484 / 1484 / 1484 |
| early/rare/delivery-spectator | 102 / 0.002 / 0.005 / 0.05 / 0.058 | 102 / 8968 / 8968 / 8968 / 8968 | 102 / 4452 / 4452 / 4452 / 4452 |
| early/rare/prototypeTask | 102 / 24.917 / 29.922 / 54.19 / 63.879 | 102 / 749176 / 752064 / 1724736 / 1725600 | 102 / 7423 / 7423 / 7423 / 7423 |
| late/baseline/journalAppend | 9 / 36.931 / 47.424 / — / 47.424 | 9 / 1845424 / 1845856 / — / 1845856 | — |
| late/baseline/checkpointStore | 9 / 843.433 / 938.632 / — / 938.632 | 9 / 124220112 / 124220576 / — / 124220576 | — |
| late/baseline/jdbcRecoveryLoad | 9 / 1255.898 / 1350.795 / — / 1350.795 | 9 / 803184936 / 805173200 / — / 805173200 | — |
| late/baseline/validateCheckpoint | 9 / 966.855 / 1132.579 / — / 1132.579 | 9 / 391422688 / 393409664 / — / 393409664 | — |
| late/baseline/computerFallback | 9 / 0.187 / 0.362 / — / 0.362 | 9 / 2176 / 2176 / — / 2176 | — |
| late/baseline/restoreSession | 9 / 1439.924 / 2080.036 / — / 2080.036 | 9 / 662014096 / 668905848 / — / 668905848 | — |
| late/baseline/checkpointBuild | 9 / 2130.27 / 2618.504 / — / 2618.504 | 9 / 1680986240 / 1690608552 / — / 1690608552 | — |
| late/baseline/compactRead | 9 / 18.105 / 20.901 / — / 20.901 | 9 / 594624 / 596336 / — / 596336 | 9 / 9789 / 9789 / — / 9789 |
| late/baseline/compactDecode | 9 / 1.085 / 1.245 / — / 1.245 | 9 / 981000 / 981000 / — / 981000 | — |
| late/baseline/compactNativeRestore | 9 / 16.27 / 27.944 / — / 27.944 | 9 / 2308680 / 2360760 / — / 2360760 | — |
| late/baseline/copyIncludingLog | 9 / 108.931 / 260.011 / — / 260.011 | 9 / 56625360 / 57786432 / — / 57786432 | — |
| late/baseline/copyOmittingLog | 9 / 9.527 / 11.373 / — / 11.373 | 9 / 2147856 / 2223312 / — / 2223312 | — |
| late/baseline/view-home | 9 / 4.65 / 6.054 / — / 6.054 | 9 / 1390400 / 1424432 / — / 1424432 | 9 / 25361 / 25361 / — / 25361 |
| late/baseline/view-away | 9 / 245.785 / 351.644 / — / 351.644 | 9 / 114223448 / 116070096 / — / 116070096 | 9 / 26404 / 26404 / — / 26404 |
| late/baseline/view-spectator | 9 / 3.643 / 6 / — / 6 | 9 / 1385168 / 1419200 / — / 1419200 | 9 / 25268 / 25268 / — / 25268 |
| late/baseline/quietProbe | 9 / 12.725 / 19.466 / — / 19.466 | 9 / 206640 / 206640 / — / 206640 | 9 / 33 / 33 / — / 33 |
| late/baseline/activeProbe | 9 / 22.584 / 24.991 / — / 24.991 | 9 / 429144 / 429144 / — / 429144 | 9 / 9789 / 9789 / — / 9789 |
| late/baseline/receiptLookup | 9 / 11.106 / 11.69 / — / 11.69 | 9 / 204040 / 204040 / — / 204040 | — |
| late/baseline/dbRoundTrip | 9 / 10.187 / 12.136 / — / 12.136 | 9 / 203472 / 203472 / — / 203472 | — |
| late/ordinary/queueWait | 102 / 0.098 / 0.125 / 0.184 / 0.191 | — | — |
| late/ordinary/publicProjection | 102 / 0.012 / 0.017 / 0.022 / 0.03 | 102 / 920 / 920 / 920 / 920 | — |
| late/ordinary/patch | 102 / 0.15 / 0.227 / 0.364 / 0.374 | 102 / 5536 / 5536 / 5856 / 5856 | — |
| late/ordinary/patchEncode | 102 / 0.007 / 0.013 / 0.033 / 0.055 | 102 / 968 / 968 / 968 / 968 | 102 / 113 / 113 / 113 / 113 |
| late/ordinary/commit | 102 / 23.306 / 27.067 / 32.739 / 40.607 | 102 / 497312 / 498520 / 1159208 / 1159800 | — |
| late/ordinary/envelope-home | 102 / 0.012 / 0.019 / 0.05 / 0.06 | 102 / 1360 / 1360 / 1360 / 1360 | 102 / 152 / 152 / 152 / 152 |
| late/ordinary/delivery-home | 102 / 0.002 / 0.005 / 0.008 / 0.031 | 102 / 360 / 360 / 360 / 360 | 102 / 152 / 152 / 152 / 152 |
| late/ordinary/envelope-away | 102 / 0.071 / 0.111 / 0.332 / 0.53 | 102 / 34480 / 34480 / 34480 / 34480 | 102 / 7126 / 7126 / 7126 / 7126 |
| late/ordinary/delivery-away | 102 / 0.002 / 0.031 / 0.04 / 0.056 | 102 / 14312 / 14312 / 14312 / 14312 | 102 / 7126 / 7126 / 7126 / 7126 |
| late/ordinary/envelope-spectator | 102 / 0.002 / 0.004 / 0.005 / 0.005 | 102 / 1392 / 1392 / 1392 / 1392 | 102 / 157 / 157 / 157 / 157 |
| late/ordinary/delivery-spectator | 102 / 0.001 / 0.001 / 0.002 / 0.003 | 102 / 1000 / 1000 / 1000 / 1000 | 102 / 471 / 471 / 471 / 471 |
| late/ordinary/prototypeTask | 102 / 23.636 / 27.51 / 33.145 / 41.184 | 102 / 565432 / 566640 / 1227328 / 1227920 | 102 / 7749 / 7749 / 7749 / 7749 |
| late/keyframe/queueWait | 102 / 0.1 / 0.149 / 0.225 / 0.539 | — | — |
| late/keyframe/publicProjection | 102 / 0.012 / 0.016 / 0.052 / 0.053 | 102 / 920 / 920 / 920 / 920 | — |
| late/keyframe/patch | 102 / 0.14 / 0.19 / 0.322 / 0.348 | 102 / 5536 / 5536 / 5536 / 5536 | — |
| late/keyframe/patchEncode | 102 / 0.008 / 0.011 / 0.026 / 0.028 | 102 / 968 / 968 / 968 / 968 | 102 / 113 / 113 / 113 / 113 |
| late/keyframe/keyframeEncode | 102 / 0.114 / 0.179 / 0.478 / 0.5 | 102 / 67640 / 67640 / 67640 / 67640 | 102 / 13616 / 13616 / 13616 / 13616 |
| late/keyframe/commit | 102 / 24.055 / 28.305 / 30.186 / 33.318 | 102 / 726880 / 728088 / 908968 / 908968 | — |
| late/keyframe/envelope-home | 102 / 0.176 / 0.241 / 0.348 / 0.37 | 102 / 67144 / 67144 / 67144 / 67144 | 102 / 13780 / 13780 / 13780 / 13780 |
| late/keyframe/delivery-home | 102 / 0.006 / 0.034 / 0.049 / 0.065 | 102 / 27624 / 27624 / 27624 / 27624 | 102 / 13780 / 13780 / 13780 / 13780 |
| late/keyframe/envelope-away | 102 / 0.157 / 0.232 / 0.341 / 0.398 | 102 / 116184 / 116184 / 116184 / 116184 | 102 / 20754 / 20754 / 20754 / 20754 |
| late/keyframe/delivery-away | 102 / 0.004 / 0.027 / 0.047 / 0.051 | 102 / 41576 / 41576 / 41576 / 41576 | 102 / 20754 / 20754 / 20754 / 20754 |
| late/keyframe/envelope-spectator | 102 / 0.086 / 0.151 / 0.187 / 0.301 | 102 / 68184 / 68184 / 68184 / 68184 | 102 / 13785 / 13785 / 13785 / 13785 |
| late/keyframe/delivery-spectator | 102 / 0.007 / 0.03 / 0.054 / 0.074 | 102 / 82776 / 82776 / 82776 / 82776 | 102 / 41355 / 41355 / 41355 / 41355 |
| late/keyframe/prototypeTask | 102 / 24.992 / 29.442 / 31.332 / 34.931 | 102 / 1213872 / 1215080 / 1395960 / 1395960 | 102 / 75889 / 75889 / 75889 / 75889 |
| late/rare/queueWait | 102 / 0.098 / 0.121 / 0.154 / 0.187 | — | — |
| late/rare/publicProjection | 102 / 0.009 / 0.012 / 0.017 / 0.057 | 102 / 920 / 920 / 920 / 920 | — |
| late/rare/patch | 102 / 0.147 / 0.226 / 0.445 / 0.619 | 102 / 8920 / 8920 / 8920 / 8920 | — |
| late/rare/patchEncode | 102 / 0.096 / 0.157 / 0.189 / 0.221 | 102 / 63472 / 63472 / 63472 / 63472 | 102 / 11664 / 11664 / 11664 / 11664 |
| late/rare/commit | 102 / 24.706 / 29.991 / 34.245 / 58.169 | 102 / 1103696 / 1104904 / 1107552 / 1108560 | — |
| late/rare/envelope-home | 102 / 0.146 / 0.202 / 0.259 / 0.409 | 102 / 62600 / 62600 / 62600 / 62600 | 102 / 11703 / 11703 / 11703 / 11703 |
| late/rare/delivery-home | 102 / 0.005 / 0.039 / 0.053 / 0.076 | 102 / 23464 / 23464 / 23464 / 23464 | 102 / 11703 / 11703 / 11703 / 11703 |
| late/rare/envelope-away | 102 / 0.083 / 0.137 / 0.19 / 0.21 | 102 / 62632 / 62632 / 62632 / 62632 | 102 / 11716 / 11716 / 11716 / 11716 |
| late/rare/delivery-away | 102 / 0.002 / 0.005 / 0.031 / 0.063 | 102 / 23496 / 23496 / 23496 / 23496 | 102 / 11716 / 11716 / 11716 / 11716 |
| late/rare/envelope-spectator | 102 / 0.076 / 0.132 / 0.174 / 0.189 | 102 / 63376 / 63376 / 63376 / 63376 | 102 / 11708 / 11708 / 11708 / 11708 |
| late/rare/delivery-spectator | 102 / 0.005 / 0.013 / 0.047 / 0.057 | 102 / 70312 / 70312 / 70312 / 70312 | 102 / 35124 / 35124 / 35124 / 35124 |
| late/rare/prototypeTask | 102 / 25.41 / 30.711 / 35.369 / 59.26 | 102 / 1490632 / 1491840 / 1501888 / 1502368 | 102 / 58543 / 58543 / 58543 / 58543 |
| terminal/baseline/checkpointStore | 9 / 878.78 / 995.678 / — / 995.678 | 9 / 124353464 / 124353600 / — / 124353600 | — |
| terminal/baseline/jdbcRecoveryLoad | 9 / 1252.957 / 1595.786 / — / 1595.786 | 9 / 792365464 / 794354864 / — / 794354864 | — |
| terminal/baseline/validateCheckpoint | 9 / 974.017 / 1037.654 / — / 1037.654 | 9 / 380282848 / 382272248 / — / 382272248 | — |
| terminal/baseline/computerFallback | 9 / 0.015 / 0.016 / — / 0.016 | 9 / 0 / 0 / — / 0 | — |
| terminal/baseline/restoreSession | 9 / 1028.519 / 1408.763 / — / 1408.763 | 9 / 549208776 / 551149592 / — / 551149592 | — |
| terminal/baseline/checkpointBuild | 9 / 2114.924 / 2705.327 / — / 2705.327 | 9 / 1566678472 / 1575897176 / — / 1575897176 | — |
| terminal/baseline/compactRead | 9 / 17.581 / 47.242 / — / 47.242 | 9 / 591040 / 593744 / — / 593744 | 9 / 8626 / 8626 / — / 8626 |
| terminal/baseline/compactDecode | 9 / 1.128 / 1.609 / — / 1.609 | 9 / 902392 / 902520 / — / 902520 | — |
| terminal/baseline/compactNativeRestore | 9 / 14.412 / 25.209 / — / 25.209 | 9 / 2208080 / 2231904 / — / 2231904 | — |
| terminal/baseline/copyIncludingLog | 9 / 98.59 / 202.35 / — / 202.35 | 9 / 56816688 / 56830000 / — / 56830000 | — |
| terminal/baseline/copyOmittingLog | 9 / 7.949 / 9.65 / — / 9.65 | 9 / 2047560 / 2061040 / — / 2061040 | — |
| terminal/baseline/view-home | 9 / 1.992 / 6.254 / — / 6.254 | 9 / 778728 / 778728 / — / 778728 | 9 / 13778 / 13778 / — / 13778 |
| terminal/baseline/view-away | 9 / 1.183 / 2.301 / — / 2.301 | 9 / 778728 / 778728 / — / 778728 | 9 / 13778 / 13778 / — / 13778 |
| terminal/baseline/view-spectator | 9 / 1.559 / 2.222 / — / 2.222 | 9 / 778096 / 778096 / — / 778096 | 9 / 13685 / 13685 / — / 13685 |
| terminal/baseline/quietProbe | 9 / 10.388 / 12.543 / — / 12.543 | 9 / 205480 / 205480 / — / 205480 | 9 / 33 / 33 / — / 33 |
| terminal/baseline/activeProbe | 9 / 21.159 / 25.121 / — / 25.121 | 9 / 424456 / 424456 / — / 424456 | 9 / 8626 / 8626 / — / 8626 |
| terminal/baseline/receiptLookup | 9 / 11.023 / 11.788 / — / 11.788 | 9 / 202832 / 202832 / — / 202832 | — |
| terminal/baseline/dbRoundTrip | 9 / 10.181 / 12.796 / — / 12.796 | 9 / 202264 / 202264 / — / 202264 | — |
| terminal/ordinary/queueWait | 102 / 0.09 / 0.133 / 0.179 / 0.184 | — | — |
| terminal/ordinary/publicProjection | 102 / 0.011 / 0.015 / 0.027 / 0.094 | 102 / 920 / 920 / 920 / 920 | — |
| terminal/ordinary/patch | 102 / 0.128 / 0.197 / 0.412 / 0.646 | 102 / 11464 / 11464 / 11464 / 11464 | — |
| terminal/ordinary/patchEncode | 102 / 0.102 / 0.152 / 0.265 / 0.392 | 102 / 61520 / 61520 / 61520 / 61520 | 102 / 10746 / 10746 / 10746 / 10746 |
| terminal/ordinary/commit | 102 / 23.027 / 26.443 / 29.352 / 30.689 | 102 / 1101832 / 1101832 / 1763728 / 1763728 | — |
| terminal/ordinary/envelope-home | 102 / 0.14 / 0.197 / 0.279 / 0.283 | 102 / 58352 / 58352 / 58352 / 58352 | 102 / 10785 / 10785 / 10785 / 10785 |
| terminal/ordinary/delivery-home | 102 / 0.008 / 0.039 / 0.061 / 0.193 | 102 / 21640 / 21640 / 21640 / 21640 | 102 / 10785 / 10785 / 10785 / 10785 |
| terminal/ordinary/envelope-away | 102 / 0.086 / 0.13 / 0.162 / 0.463 | 102 / 58368 / 58368 / 58368 / 58368 | 102 / 10798 / 10798 / 10798 / 10798 |
| terminal/ordinary/delivery-away | 102 / 0.002 / 0.006 / 0.018 / 0.023 | 102 / 21656 / 21656 / 21656 / 21656 | 102 / 10798 / 10798 / 10798 / 10798 |
| terminal/ordinary/envelope-spectator | 102 / 0.063 / 0.099 / 0.132 / 0.178 | 102 / 59624 / 59624 / 59624 / 59624 | 102 / 10790 / 10790 / 10790 / 10790 |
| terminal/ordinary/delivery-spectator | 102 / 0.005 / 0.015 / 0.037 / 0.047 | 102 / 64808 / 64808 / 64808 / 64808 | 102 / 32370 / 32370 / 32370 / 32370 |
| terminal/ordinary/prototypeTask | 102 / 23.834 / 27.046 / 30.509 / 31.337 | 102 / 1467976 / 1467976 / 2129872 / 2129872 | 102 / 53953 / 53953 / 53953 / 53953 |
| terminal/keyframe/queueWait | 102 / 0.09 / 0.128 / 0.181 / 0.185 | — | — |
| terminal/keyframe/publicProjection | 102 / 0.011 / 0.015 / 0.02 / 0.033 | 102 / 920 / 920 / 920 / 920 | — |
| terminal/keyframe/patch | 102 / 0.127 / 0.179 / 0.376 / 0.403 | 102 / 11464 / 11464 / 11464 / 11464 | — |
| terminal/keyframe/patchEncode | 102 / 0.093 / 0.147 / 0.248 / 0.653 | 102 / 61520 / 61520 / 61520 / 61520 | 102 / 10746 / 10746 / 10746 / 10746 |
| terminal/keyframe/keyframeEncode | 102 / 0.083 / 0.144 / 0.247 / 0.305 | 102 / 68400 / 68400 / 68400 / 68400 | 102 / 13611 / 13611 / 13611 / 13611 |
| terminal/keyframe/commit | 102 / 23.514 / 28.358 / 47.659 / 51.6 | 102 / 1191000 / 1191392 / 1371880 / 1371880 | — |
| terminal/keyframe/envelope-home | 102 / 0.243 / 0.365 / 0.457 / 0.679 | 102 / 122976 / 122976 / 122976 / 122976 | 102 / 24408 / 24408 / 24408 / 24408 |
| terminal/keyframe/delivery-home | 102 / 0.014 / 0.023 / 0.047 / 0.075 | 102 / 48872 / 48872 / 48872 / 48872 | 102 / 24408 / 24408 / 24408 / 24408 |
| terminal/keyframe/envelope-away | 102 / 0.155 / 0.227 / 0.294 / 0.3 | 102 / 123008 / 123008 / 123008 / 123008 | 102 / 24421 / 24421 / 24421 / 24421 |
| terminal/keyframe/delivery-away | 102 / 0.004 / 0.012 / 0.028 / 0.029 | 102 / 48904 / 48904 / 48904 / 48904 | 102 / 24421 / 24421 / 24421 / 24421 |
| terminal/keyframe/envelope-spectator | 102 / 0.134 / 0.203 / 0.252 / 0.631 | 102 / 125544 / 125544 / 125544 / 125544 | 102 / 24413 / 24413 / 24413 / 24413 |
| terminal/keyframe/delivery-spectator | 102 / 0.012 / 0.025 / 0.046 / 0.056 | 102 / 146536 / 146536 / 146536 / 146536 | 102 / 73239 / 73239 / 73239 / 73239 |
| terminal/keyframe/prototypeTask | 102 / 24.458 / 29.391 / 48.536 / 53.095 | 102 / 1957584 / 1958016 / 2138464 / 2138464 | 102 / 122068 / 122068 / 122068 / 122068 |
| terminal/rare/queueWait | 102 / 0.089 / 0.137 / 0.233 / 0.478 | — | — |
| terminal/rare/publicProjection | 102 / 0.009 / 0.013 / 0.057 / 0.057 | 102 / 920 / 920 / 920 / 920 | — |
| terminal/rare/patch | 102 / 0.128 / 0.19 / 0.528 / 0.658 | 102 / 11464 / 11464 / 11464 / 11464 | — |
| terminal/rare/patchEncode | 102 / 0.115 / 0.162 / 0.296 / 0.521 | 102 / 61520 / 61520 / 61520 / 61520 | 102 / 10746 / 10746 / 10746 / 10746 |
| terminal/rare/commit | 102 / 23.235 / 25.886 / 29.279 / 33.863 | 102 / 1101832 / 1101832 / 1102336 / 1102800 | — |
| terminal/rare/envelope-home | 102 / 0.132 / 0.198 / 0.295 / 0.307 | 102 / 58352 / 58352 / 58352 / 58352 | 102 / 10785 / 10785 / 10785 / 10785 |
| terminal/rare/delivery-home | 102 / 0.006 / 0.042 / 0.132 / 0.154 | 102 / 21640 / 21640 / 21640 / 21640 | 102 / 10785 / 10785 / 10785 / 10785 |
| terminal/rare/envelope-away | 102 / 0.086 / 0.134 / 0.283 / 7.399 | 102 / 58368 / 58368 / 58368 / 58368 | 102 / 10798 / 10798 / 10798 / 10798 |
| terminal/rare/delivery-away | 102 / 0.002 / 0.009 / 0.016 / 0.02 | 102 / 21656 / 21656 / 21656 / 21656 | 102 / 10798 / 10798 / 10798 / 10798 |
| terminal/rare/envelope-spectator | 102 / 0.065 / 0.108 / 0.219 / 0.242 | 102 / 59624 / 59624 / 59624 / 59624 | 102 / 10790 / 10790 / 10790 / 10790 |
| terminal/rare/delivery-spectator | 102 / 0.006 / 0.012 / 0.037 / 0.042 | 102 / 64808 / 64808 / 64808 / 64808 | 102 / 32370 / 32370 / 32370 / 32370 |
| terminal/rare/prototypeTask | 102 / 24.048 / 26.817 / 30.361 / 34.529 | 102 / 1467928 / 1467928 / 1468432 / 1468896 | 102 / 53953 / 53953 / 53953 / 53953 |

### GC during samples above 500 ms

The table intersects each slow sample with JFR stop-the-world GC pause intervals. The rest of the elapsed interval includes CPU work, I/O and scheduling. Wall-clock alignment is approximate to one millisecond.

| Cohort / operation | Pause milliseconds within slow samples (n / p50 / p90 / p99 / max) |
| --- | ---: |
| early/baseline/jdbcRecoveryLoad | 3 / 50.678 / 55.533 / — / 55.533 |
| late/baseline/checkpointStore | 9 / 0 / 11.086 / — / 11.086 |
| late/baseline/jdbcRecoveryLoad | 9 / 254.804 / 275.415 / — / 275.415 |
| late/baseline/validateCheckpoint | 9 / 219.477 / 284.27 / — / 284.27 |
| late/baseline/restoreSession | 9 / 263.966 / 621.654 / — / 621.654 |
| late/baseline/checkpointBuild | 9 / 1047.742 / 1472.351 / — / 1472.351 |
| terminal/baseline/checkpointStore | 9 / 12.832 / 16.06 / — / 16.06 |
| terminal/baseline/jdbcRecoveryLoad | 9 / 259.479 / 362.039 / — / 362.039 |
| terminal/baseline/validateCheckpoint | 9 / 236.052 / 247.379 / — / 247.379 |
| terminal/baseline/restoreSession | 9 / 197.128 / 531.267 / — / 531.267 |
| terminal/baseline/checkpointBuild | 9 / 1356.299 / 1709.38 / — / 1709.38 |

### Projection and delivery

Each sample subtracts the commit inside that same worker task. It includes public projection, patch generation, serialization, copying five payloads and measurement overhead.

| Cohort | Milliseconds (n / p50 / p90 / p99 / max) |
| --- | ---: |
| early/ordinary | 102 / 0.554 / 0.762 / 3.057 / 3.935 |
| early/keyframe | 102 / 1.037 / 1.324 / 1.879 / 2.568 |
| early/rare | 102 / 0.457 / 0.64 / 0.905 / 0.924 |
| late/ordinary | 102 / 0.383 / 0.54 / 0.648 / 0.852 |
| late/keyframe | 102 / 0.863 / 1.174 / 1.543 / 1.614 |
| late/rare | 102 / 0.717 / 1.052 / 1.228 / 1.283 |
| terminal/ordinary | 102 / 0.671 / 0.904 / 1.169 / 1.297 |
| terminal/keyframe | 102 / 1.059 / 1.364 / 1.703 / 1.847 |
| terminal/rare | 102 / 0.686 / 0.973 / 1.47 / 8.139 |

### Restore and fallback components within each repetition

These totals sum the named JDBC read, decode or validation, and native restore or computer check from each individual repetition. Role-view construction is separate.

| Cohort | Milliseconds (n / p50 / p90 / p99 / max) |
| --- | ---: |
| early/compactRestore | 9 / 28.349 / 43.658 / — / 43.658 |
| early/currentRestore | 9 / 294.333 / 1335.725 / — / 1335.725 |
| early/currentComputerFallback | 9 / 154.262 / 914.459 / — / 914.459 |
| late/compactRestore | 9 / 35.441 / 50.054 / — / 50.054 |
| late/currentRestore | 9 / 3604.57 / 4438.6 / — / 4438.6 |
| late/currentComputerFallback | 9 / 2233.883 / 2483.736 / — / 2483.736 |
| terminal/compactRestore | 9 / 32.315 / 73.924 / — / 73.924 |
| terminal/currentRestore | 9 / 3317.292 / 3630.105 / — / 3630.105 |
| terminal/currentComputerFallback | 9 / 2219.599 / 2593.993 / — / 2593.993 |

## Late recovery checkpoint store attribution

The reference round's late checkpoint store took 843.43 ms at p50. JFR pause overlap was zero at p50 and 11.09 ms at maximum. A separate replay ran the same production repository replacement after appending one journal entry, using the same fixture and three fresh JVMs. It observed production request timing spans around the store and recorded new JFR and GC files. It calls the repository directly with the late training artifact, outside the browser admission path. The sequence isolates stored-value preparation and JDBC from the prototype and restore workloads.

| Phase | Early (n / p50 / p90 / max ms) | Late (n / p50 / p90 / max ms) |
| --- | ---: | ---: |
| checkpointStore | 9 / 55.37 / 172.56 / 172.56 | 9 / 584.39 / 706.98 / 706.98 |
| STORAGE_ENCODE | 9 / 31.90 / 36.94 / 36.94 | 9 / 525.74 / 614.29 / 614.29 |
| STORAGE_COMPRESSION | 9 / 17.17 / 18.00 / 18.00 | 9 / 270.06 / 355.40 / 355.40 |
| CHECKPOINT_SQL | 9 / 21.96 / 135.51 / 135.51 | 9 / 71.82 / 94.32 / 94.32 |
| DB_CONNECT | 9 / 9.06 / 13.14 / 13.14 | 9 / 7.77 / 8.95 / 8.95 |
| DB_WRITE | 9 / 5.58 / 120.26 / 120.26 | 9 / 59.72 / 80.37 / 80.37 |
| DB_COMMIT | 9 / 3.10 / 6.76 / 6.76 | 9 / 2.59 / 3.04 / 3.04 |

The replay's late storage encode took 525.74 ms at p50 and accounted for 85.53% to 89.96% of each measured late save. Gzip took 270.06 ms at p50 within encode. The rest of the encode span covers strict UTF-8 conversion and constructing and checking the encoded envelope. The JDBC section took 71.82 ms at p50 and includes both the checkpoint update and clearing the journal. No JFR stop-the-world GC pause overlapped the nine late saves in this traced replay. Its median was lower than the reference round's median, so the breakdown identifies where the time accumulates on this code path without assigning exact stage durations to the original samples. Each timing sample with a child span includes that child.

```powershell
$fixture = '.tools/state-sync-smoke-fixed/selected'
node tools/diagnostics/state-sync/run-storage.mjs $fixture .tools/state-sync-storage-checkpoint-trace trace-checkpoints
node tools/diagnostics/state-sync/storage-rounds-report.mjs trace-checkpoints .tools/state-sync-storage-checkpoint-trace
```
