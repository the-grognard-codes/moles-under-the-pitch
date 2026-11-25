# Feature: Turn the Gatehouse stadium with the pitch in the away overhead view

Issue: #618

## Abstract

From the away coach end at 90° overhead, show the Gatehouse turned with the pitch, so the gate is at the top of the screen. This replaces the D1 interim that shipped in PR #609, which keeps the architecture in the home orientation and swaps only the kits and regalia.

## Objective

Both coach ends see the same stadium from their own end of the pitch, as ADR 0003 requires. The raised architecture, crowds and stands face the correct way, with no change to browser code.

## Context / Existing Behavior

- **The interim is live.** In `assets/game/pitch/stadiums/gatehouse/views.json`, the `away` view reuses the `approved` layers and the `full` crowd, so the gate stays at the bottom. It rotates only the alignment (`worldOrigin [0, 15]`, `worldRight [0, -1]`, `worldDown [1, 0]`), swaps `kitByBay` between the west bays (4–7) and east bays (8–11), and uses the `away` regalia set. The decision is D1 in [`gatehouse-live-overhead.md`](gatehouse-live-overhead.md).
- **Why the stadium can't simply rotate 180°.** The north doorways, the south gate arch, the end-stand bench risers and the braziers would turn upside down. No art exists for the opposite viewing direction.
- **The seam is in place.** `browser-client/src/gatehouse-stadium.ts` reads the manifest. Each view carries its own alignment, layers, crowd, regalia, kit mapping and end zones, and the code has no per-end branches. Requirements 10–13 of `gatehouse-live-overhead.md` set this up, so this change only touches assets and the manifest.
- **Seats and placements.** The side stands each hold 57 supporters in 86 places (`assets/game/references/stadiums/scale-studies/README.md`). `assets/game/scripts/build-gatehouse-runtime.py` builds the runtime layers.
- **Approval.** The gallery has a home/away end toggle (requirement 13), so away art can be reviewed before it reaches live play.

## In Scope

- Raised art for the opposite viewing direction: the north doorways, the south gate arch, the end-stand bench risers and the braziers.
- Side-stand crowds re-seated against the rotated benches, using the shared supporter cast.
- End stands swapped to their front and rear poses.
- A new `away` layer set, crowd set and regalia set in `views.json`, plus the runtime build.
- Owner approval in the gallery before the manifest change goes live.

## Out of Scope

- Browser code changes. If the manifest seam can't express the true view, stop and report it rather than adding per-end branches.
- Other stadiums, and the stadium creation skill (`stadium-creation-skill.md`).
- Angled cameras, which keep turf.

## Functional Requirements

1. From the away overhead end, the gate is at the top of the screen and the north stand at the bottom. All raised architecture is drawn for that viewing direction.
2. From either end, figures, heraldry and end-zone lettering are upright, and the visiting supporters sit at screen-left from the away end.
3. Each end zone shows the team that defends that world end.
4. The stadium registers to the grid within half a screen pixel at the gallery's three zoom levels, from both ends.
5. Ground art is the home ground turned 180°. Only raised art is repainted.
6. The change is limited to the files under `assets/game/pitch/stadiums/gatehouse/`, the runtime build script, and their references and provenance.

## Acceptance Criteria

- The gallery's end toggle shows the true away view, and the owner approves it.
- A real dev-local kickoff captured from both coach ends shows the turned stadium from the away end, with no browser-code diff.
- The world-anchored tests from requirement 12 of `gatehouse-live-overhead.md` pass unchanged.
- `provenance.json` records the new sources and their hashes.

## Constraints

- Ensure the themes are all supported on Android, Windows, and Linux.
- MUTP colors, typography, style, and themes shall be preserved. Reuse CSS and other UI/UX scaffolding where appropriate.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.
- Keep runtime asset size close to the current total of 444,809 bytes, and keep composition time comparable to the current 27–33 ms.

## Implementation Guidance

- Paint the raised parts once for the opposite direction. Don't rotate or flip the existing raised art, because lighting and perspective would be wrong.
- If the stadium creation skill lands first, rebuild this view through it.

## Validation

- Create new or update existing regression tests as required.
- Run the gallery and projected-pitch browser suites for both ends, at three zoom levels.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
- In the PR, record both end captures and the owner's approval.
