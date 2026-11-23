# Feature: Use the Gatehouse stadium in playable matches at 90° overhead

Issue: #605

## Status

Implemented in [PR #609](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/609) (merge `6c049da0f`, 2026-11-22). This spec is kept as the record of the decisions behind it. The line references below describe `main` before that change.

- **The away overhead end (the 180° view) uses the D1 interim.** `assets/game/pitch/stadiums/gatehouse/views.json` has `home` and `away` entries. The `away` entry reuses the `approved` layers and the `full` crowd, so the gate stays at the bottom of the screen. It rotates the world alignment (`worldOrigin [0, 15]`, `worldRight [0, -1]`, `worldDown [1, 0]`) so the stadium still registers to the turned grid. It also swaps `kitByBay` between the west bays (4–7) and east bays (8–11), and switches to the `away` regalia set. The visitors therefore sit at screen-left, with upright lettering. The browser code reads this manifest (`browser-client/src/gatehouse-stadium.ts`) and has no per-end branches, as requirements 10–11 require.
- **The full away view is still open.** It replaces the interim by changing the `away` manifest entry and its art. See [`gatehouse-true-away-view.md`](gatehouse-true-away-view.md).
- **The stadium creation skill is still open.** 6–7 more stadiums are planned. See [`stadium-creation-skill.md`](stadium-creation-skill.md).

## Abstract

Show the approved Gatehouse stadium around the live pitch when a coach selects the 90° overhead camera in an Old World Classic match. Every other camera and venue keeps today's turf.

## Objective

Make the owner-approved Gatehouse ([#572](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/572), [PR #580](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/580)) part of real play, from setup through full time. It should show both match teams' names and kits, look right from either coach end, and never get in the way of play or input.

## Context / Existing Behavior

Line references are to `origin/main` at `5f6779969`.

- **Live scenery is turf only.** `PitchScenery` draws the registered grass from `assets/game/pitch/stadium-v1.png` and nothing else (`browser-client/src/PitchScenery.tsx:29-52`). `LivePitch` mounts it under every camera and replaces it with a plain field and the message "Pitch texture unavailable" if the image fails (`browser-client/src/LivePitch.tsx:201-202`, `:411`, `:559`).
- **The 90° camera is `top-down` mode.** Angle 90 selects `top-down` (`LivePitch.tsx:196`). The overhead view has its own end preference, `overheadEnd`, which can differ from the angled view's end and from the coach's role (`browser-client/src/camera-preferences.ts:39-40`). The away end rotates the world 180° (`browser-client/src/pitch-projection.ts:60`, `:224-226`). In the home view, canonical `x=0` is at the bottom and `y=0` is on the left, which matches the gallery's south and west.
- **The Gatehouse exists only in the development gallery.** `StadiumScaleStudies` builds the 790 × 1188 bowl (32 px per square, with the pitch at `bowl.pitchX/pitchY`) from Vite imports under `assets/game/references/stadiums/scale-studies/` (`browser-client/src/StadiumScaleStudies.tsx:2-15`, `:32-42`; `browser-client/src/stadium-bowl-study.ts:3-12`). That route only loads in development (`browser-client/src/dev-entry.tsx`). The gallery uses fixed sample teams and draws **only the home end** (`StadiumScaleStudies.tsx:211`, `mockupCamera('home')`). No away-end composition has been drawn or approved.
- **Reusable pieces already exist:** loading the material masks and recolouring cloth (`browser-client/src/gatehouse-artwork.ts:22-59`), end-zone painting (`browser-client/src/stadium-end-zones.ts:184`), regalia (`browser-client/src/stadium-regalia.ts`) and apron reservations (`browser-client/src/stadium-apron-reservations.ts`). Most of the composition logic still lives inside the 678-line gallery component.
- **Asset delivery leaves references out.** `sync-browser-assets.mjs` publishes `pitch/` but not `references/stadiums/`. It also skips every path segment named `source` or `mockup` (`assets/game/scripts/sync-browser-assets.mjs:90-109`, `:154-157`).
- **Venue lookup falls back to Old World Classic.** `stadiumPresentation` maps the home team's `league` to a venue and uses Old World Classic when the league is missing or unknown (`browser-client/src/stadium-presentation.ts:15`, `:24-27`). The map also lists Badlands Brawl, which has no overhead art (`browser-client/src/generated-stadium-modules.ts:354`).
- **Match identity is partly optional.** `MatchTeamArt.colors` and `league` can be absent or `null`, and so can `homeTeamName`/`awayTeamName` (`browser-client/src/setup-protocol.ts:22`, `:37`). Player sprites fall back to `defaultTeamColors(rosterId)` (`browser-client/src/team-colors.ts:11`).
- **Current tests require that no stadium is drawn.** `assertPitchWithoutStadium` fails if any `/pitch/stadiums/` image is mounted (`browser-client/test/pitch-appearance-helper.mjs:4-11`). `projected-pitch-ui.mjs` checks that no stadium request is made (`:27-32`, `:101`, `:149`, `:459`). Both checks date from [#340](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/340).
- **ADR 0003** requires that stands move with the active camera, because a backdrop that stays still under moving squares is not acceptable. It also requires the 90° view to keep the travelling width-fit camera (`docs/adr/0003-coach-oriented-angled-pitch.md:24-25`).

## In Scope

- Rendering the Gatehouse around the live pitch in `top-down` mode for Old World Classic home venues. This covers every phase from `SETUP` to `FULL_TIME`, for both coaches and spectators, plus replay and recovery, since they share `LivePitch`.
- Moving the runtime subset of the Gatehouse art from `references/` into canonical delivery assets, with provenance.
- Pulling the gallery's composition code out into a production module that the live pitch and the gallery both use.
- Composing the stadium for the away overhead end (D1).
- Narrowing the no-stadium tests to the cases that still show turf.
- A `CHANGELOG.md` entry under Unreleased.

## Out of Scope

- Other stadiums and venues, including Badlands Brawl.
- The stadium at 30°, 40° or 50°.
- Animation, ambient crowd motion, sound and sideline occupants (staff, reserves, cheerleaders).
- Showing fan factor, attendance or weather in the crowd.
- Changes to camera pan or zoom limits, the HUD layout, game rules or the server.
- New art, including the full away view in D1, which is a separate follow-up.
- A general stadium standard for future venues. This issue's manifest is the first instance of that standard, not its definition. See Follow-up work.

## Follow-up work

These are separate issues, listed so this implementation leaves room for them. Their specs are [`gatehouse-true-away-view.md`](gatehouse-true-away-view.md) and [`stadium-creation-skill.md`](stadium-creation-skill.md).

- **True away view for the Gatehouse.** This is the full fix from D1. Repaint the raised parts (north doorways, south gate arch, end-stand bench risers, braziers) for the opposite viewing direction. Re-seat the side-stand crowds against the rotated benches, swap the end stands to their front and rear poses, and get owner approval in the gallery. Requirements 10–13 make the live switch a change to the `away` manifest entry alone.
- **A stadium creation skill for batch production.** 6–7 more stadiums are planned, so stadium creation must be a repeatable process, captured as a repository skill in `.agents/skills/` alongside `team-pixel-sprites`. The skill should define:
  - a layout written once per stadium in pitch coordinates (bowl outline, bays and seats, regalia anchors, stairwells, apron reservations, end zones);
  - ground art painted once and turned 180° for the away view, and raised art painted once per viewing direction, with designs that keep most of the architecture flat to limit that work;
  - crowds placed from the shared supporter cast in four facings, never repainted per stadium;
  - generation of the view manifest from this issue (requirements 10–11);
  - layout checks showing both views line up with the same layout;
  - the gallery review and owner approval steps.

  The skill should let several stadiums be produced in one batch, and the Gatehouse should be rebuilt through it as the reference example.

## Decisions

The owner accepted D1 as an interim. D2–D5 are the recommended defaults: implement them unless the owner says otherwise in this issue. The requirements below assume all five.

- **D1: How the away overhead end looks (accepted as an interim).** Rotating the finished stadium 180° would turn the north doorways, south gate arch, end-stand bench risers and braziers upside down, and no rotated artwork exists. *Interim, accepted:* keep the architecture in the same screen orientation for both ends, with the gate at the bottom and the north stand at the top, as in the round-four gallery (`docs/specs/old-world-classic-stadium-round-four-outcome.md:70`). Tie team-owned content to the world instead. The stadium is 1½ squares of apron around a symmetric pitch opening, so it still registers to the rotated grid. When the camera looks from the away end, swap kits between the west bays (4–7) and east bays (8–11) and between the side regalia, so the visitors sit at screen-left. Paint each end zone for the team that defends that world end, with its lettering drawn upright for the screen. The stadium still moves with the camera, as ADR 0003 requires. *Full fix, separate follow-up:* a true away view in which the stadium turns with the world, so the gate is at the top. It needs those raised parts repainted for the opposite viewing direction, the side-stand crowds re-seated against the rotated benches, the end stands swapped to front and rear poses, and owner approval. This issue must not block that switch: see requirements 10–13.
- **D2: Where runtime assets live.** *Recommendation:* add `assets/game/pitch/stadiums/gatehouse/` with only the layers that live play needs, plus a `provenance.json` that points back to the reference sources and their hashes. Avoid the folder names `source` and `mockup`, which the sync skips. Leave the review material in `references/` and keep it out of the production bundle.
- **D3: Which venues get the Gatehouse.** *Recommendation:* show it only when the home team's `league` is exactly `"Old World Classic"`. A `null`, missing or unknown league, or any other mapped venue, shows turf. Change or bypass the Old World Classic fallback for this decision. Log a diagnostic, as `stadiumPresentation` already does.
- **D4: Missing identity.** *Recommendation:* when kits are missing, use `defaultTeamColors(rosterId)`, the same fallback the player sprites use. When a team name is missing, paint that end zone in the team's colour without lettering. When the two teams share a primary colour, change nothing beyond the end-zone contrast the gallery already proved.
- **D5: Approving the away end.** Only the home-end kickoff has been approved. *Recommendation:* the implementation PR adds an away-end kickoff capture, and the owner approves it before merge.

## Functional Requirements

1. In `top-down` mode with an Old World Classic home venue (D3), `LivePitch` shows the Gatehouse instead of turf-only scenery. Every other camera and venue keeps the current `PitchScenery` turf unchanged.
2. The stadium is static. It is composed once per match, team identity (names and kits) and overhead end, at the first overhead render or during setup. It is then reused across revisions, turns, player moves, pan, zoom and selection. Switching to an angled camera and back reuses the composition. Changing the overhead end reuses an existing composition for that end or builds a new one.
3. The composition uses the approved layers: turf, the bowl and its stands, the seated crowd with material-masked team colours (about one third of supporters in neutral clothing, as approved), regalia, painted and weathered end zones, and both locker-room stairwells. Gallery-only aids (sample players, seat markers, reservation outlines, inspection toggles) are not shipped. Apron reservation areas stay clear.
4. Cloth and heraldry use the frozen kits from the **match team** (`homeTeamArt.colors` / `awayTeamArt.colors`). End-zone lettering uses `homeTeamName` / `awayTeamName`. Each end zone belongs to its defending team: home at `x=0`, away at `x=25`.
5. From the home overhead end, the four visiting bays are on the east touchline (screen-right). From the away overhead end, the visiting supporters are at screen-left (D1). Figures, heraldry and lettering are upright from both ends.
6. The stadium is registered to the 26 × 15 grid. Native pitch pixel `(bowl.pitchX + 32·y, bowl.pitchY + 32·(25−x))` and its counterpart for the away end line up with canonical square `(x, y)` within 0.5 screen px at zoom 0.5, 1 and 3. Pixel art is drawn with nearest-neighbour sampling.
7. Players, ball, selection, legal targets, movement paths, dice and all overlays draw above the stadium and keep their current input geometry. The stadium layer takes no pointer events.
8. If loading or composing fails, the pitch falls back to the current turf, and to the plain field if the turf fails too. Play is unaffected and a diagnostic is logged. The match never waits for the stadium: until it is ready, turf is shown.
9. Angled cameras and turf-only venues make no request for Gatehouse assets.

The full away view (D1) must later be a change to assets and their list alone. Requirements 10–13 keep that change from touching code:

10. **One composition per view, driven by an asset list.** Each overhead view (`home`, `away`) has an entry in a stadium view manifest, a data file beside the runtime assets. The entry names its art layers, crowd placement set, regalia placement set, end-zone rectangles and kit mapping per bay. Code reads the manifest and has no per-end branches for Gatehouse content. Under D1, the `away` entry reuses the `home` layers and placements and swaps the kit mapping between the west and east bays and the side regalia.
11. **Alignment stored per view.** Each view entry carries its own source-to-world alignment (the pitch origin and axis directions in native pixels). Registration code computes the camera transform from that data and contains no assumption that the gate is at the bottom.
12. **Tests check where things are on the pitch, not whole-stadium images.** Automated tests assert world-anchored facts: which team owns each end zone, which side of the screen the visitors' supporters are on, grid alignment, upright lettering, and cache reuse. Whole-stadium pixel comparisons are allowed only in review captures, not as pass/fail gates, so new art doesn't fail the suite.
13. **Shared code with the gallery, plus an end toggle there.** The live pitch and the development gallery use the same composition module and manifest. The gallery gets a home/away end toggle, so away-view art can be reviewed before it reaches live play.

## Acceptance Criteria

- From both overhead ends, a playable Old World Classic match with distinctive team names and kits matches the approved kickoff composition at normal game scale (D5 for the away end). The visiting stand and both painted end zones are correct for each end.
- Both team names stay legible when the teams share a primary colour.
- From each overhead end: pan, zoom, select a player and target a square next to the sideline and the goal line. The highlighted square matches the submitted action.
- Switching 90° → 40° → 90° keeps the same players, ball, selection and highlights registered, and the turf returns at 40°.
- Angled cameras, a Badlands Brawl home team, a `null` league and a forced Gatehouse load failure all show today's turf behaviour and stay playable.
- Routine match updates do not recompose or reload the stadium.
- The development gallery shows both overhead ends using the same composition module and view manifest as live play.
- No live code branches on the overhead end for Gatehouse content. Replacing the `away` manifest entry is enough to change the away view.
- The production build contains the Gatehouse runtime assets and none of the `references/stadiums/` review files.

## Constraints

- Ensure the stadium renders correctly in the supported browsers on Android, Windows and Linux.
- MUTP colors, typography, style, and themes shall be preserved. Reuse CSS and other UI/UX scaffolding where appropriate.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.
- Keep the React DOM/SVG renderer and the single `PitchProjection` camera (ADR 0003). The stadium moves with the camera.
- Compose at the 790 × 1188 native size and scale through the camera transform, rather than composing at screen resolution. Keep at most one cached composition per overhead end. The project has no written memory budget, so record the composition's size and compose time in the PR.
- Do not change the shared setup/match data format. The feature uses only fields that already exist.
- Recording or replay: staged placements and stadium state are not part of the match checkpoint or transcript.

## Implementation Guidance

- Move the composition out of `StadiumScaleStudies.tsx` into a module, for example `gatehouse-stadium.ts`, that returns a cached canvas or object URL keyed by venue, end, kits and names. Make the gallery call it, so the gallery and live play cannot drift apart.
- Register the composed bitmap with `camera.planeImageTransform` using a source-to-world matrix derived from `bowl.pitchX/pitchY` and 32 px per square, in the same way `PitchScenery` registers its turf plates. Turn image smoothing off on the canvas and set `image-rendering: pixelated` on the element.
- Generate the view manifest from the existing scale-study layouts (`empty-seating.json`, `side-stands/`, `end-stands/`, `regalia/layout.json`, `locker-stairwell.json`), rather than copying their values into code.
- For D1, the side-stand kit swap only has to remap the material ranges per bay. Both sides hold 57 supporters in 86 places, as recorded in `scale-studies/README.md`.
- Choose the venue in `LivePitch` next to `mode`. Keep the existing turf path as the fallback branch.
- Update `assertPitchWithoutStadium` and the `stadiumRequests` check so they cover only the turf cases. Add a separate assertion for the Gatehouse.

## Validation

- Create new or update existing regression tests as required.
- Manifest tests: both view entries are complete, every layer and placement file exists, and the alignment of each view maps the pitch rectangle onto squares (0,0)–(25,14).
- Unit tests: the composition key and cache reuse; the D1 bay and regalia swap, driven by the manifest; end-zone ownership for each end; the D3 venue choice, including `null` and unknown leagues; the D4 fallbacks.
- `projected-pitch-ui.mjs`: the overhead Gatehouse from both ends (grid alignment within 0.5 px at three zoom levels, input on edge squares, the camera round trip, no recomposition across 10 or more revisions), the turf cases from the Acceptance Criteria, and a forced load failure.
- Asset checks: `sync-browser-assets` publishes the Gatehouse runtime set, and the production build leaves out `references/stadiums/`.
- Manual check: `node tools/dev-local.mjs --start --quick`, then play one drive from setup and kickoff through movement with an Old World Classic home team. Attach captures from both overhead ends.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
- In the implementation PR, record the drive result, the two end captures and the D5 approval, alongside the browser, asset and game-server results.
