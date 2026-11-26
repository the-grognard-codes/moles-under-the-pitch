# Format-4 match record outcome

[Issue #663](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/663) implements ticket 5 of the [match state work](match-state-sync-tickets.md#5-format-4-records-rebuild-a-match-exactly). [ADR 0014](../adr/0014-match-state-records-and-live-sync.md#records-patches-and-keyframes) defines the format and its ownership.

- The compact engine worker builds a record after native resolution. It projects the committed board once, diffs whole players and sections, and reuses the view for keyframes at revision zero and every 32 revisions. The compact snapshot carries one bounded patch baseline so a restored session produces the same patch. The session promotes that baseline once a write succeeds.
- The shared actor uses the match's team ID and side. Team and player display names resolve from public identity data on read. The codecs reject unknown formats, unknown fields, repeated keys, misplaced controls and invalid outcome order. Format 4 reserves the ordered presentation field and derives marker identity from the match, revision, command and ordinal.
- The browser reduces records from an empty view or a keyframe. It reports bounded paths for drift and preserves the latest generation of save, clock and reaction status when applying a keyframe. Staged moves remain live overlays on the committed board. The status encoder accepts the write's generation. [Ticket 7a](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/664) must supply the committed compare-and-swap generation, including for writes at the same gameplay revision.
- This slice provides an opt-in compact engine handoff, a browser reducer and a parity runner. Record storage, production publication and marker emission are tracked by later tickets. The experimental compact snapshot format advances to version 2 to carry its recorded view baseline.

## Validation

Native shard 1 runs a short seeded match through the real browser reducer and checks the independently projected spectator board at every revision. The shared wire fixtures verify malformed JSON, keyframes and status ordering. A fingerprint of the 139 legacy report field sets guards the format-2 allowlist through cutover. The focused Java run passed 47 tests, including compact restores, staged placements and legacy transcript compatibility. The browser suite passed 610 tests. The browser production build and game-server package also passed.

The full-match harness compared the browser's reduced view with the server's independently emitted spectator board at all 3,022 revisions across three matches, including with the Human and Orc sides swapped. It checked 8,514 ordered native outcomes and 96 keyframes. These matches included a player leaving the pitch and the removal of `ball`, `passing`, `kickoff`, `activePlayerAction` and `blockContext`. A separate fixture covers an off-pitch Kick alternative and a save and resume at a keyframe revision.

| Seed | Revisions | Worker p99 without database I/O |
| --- | ---: | ---: |
| 43 | 990 | 40.1 ms |
| 44 | 929 | 42.9 ms |
| 45 | 1,103 | 39.0 ms |

Run the harness with `node tools/validation/format-4-parity.mjs` after building the training classpath with `computer-player/training/prepare.ps1` or `computer-player/training/prepare.sh`. The production worker p99 with durable database writes remains unmeasured.
