# Feature: A repository skill for creating stadiums in batches

## Abstract

Capture stadium creation as a repeatable process in a repository skill under `.agents/skills/`, alongside `team-pixel-sprites`. 6–7 more stadiums are planned. The skill should let several of them be produced in one batch, and the Gatehouse should be rebuilt through it as the reference example.

## Objective

Any agent can produce a new stadium that registers to the pitch from both coach ends, reuses the shared supporter cast, generates its own view manifest and passes the same checks, without having to rediscover the Gatehouse method.

## Context / Existing Behavior

- **The Gatehouse was made by hand, across four rounds.** The round-four method, painting first and overhead only, is recorded in `docs/specs/old-world-classic-stadium-round-four.md` and its outcome. The scale studies are in `docs/specs/gatehouse-scale-studies.md` and `assets/game/references/stadiums/scale-studies/README.md`. They include `build-gatehouse.py`, which seats the crowd and exports the foreground and material planes.
- **Runtime format.** `assets/game/pitch/stadiums/gatehouse/views.json` (version 1) holds per-view `alignment`, `layers`, `crowd`, `regalia`, `kitByBay` and `endZones`. `assets/game/scripts/build-gatehouse-runtime.py` builds the runtime layers. `browser-client/src/gatehouse-stadium.ts` composes a stadium from the manifest alone. The spec behind this format is [`gatehouse-live-overhead.md`](gatehouse-live-overhead.md), requirements 10–13.
- **Material masks.** In the output mask, 0 keeps the pixel fixed, 1–32 are home cloth and 33–64 are away cloth. Live play recolours from the match teams' kits.
- **Existing skill pattern.** `.agents/skills/team-pixel-sprites/SKILL.md` and its standard (`.notes/art-preview/team-sprite-standard.md`) show how an art workflow is captured as a skill. `AGENTS.md` routes sprite work to that skill.
- **The away view.** The Gatehouse's away overhead end still uses the D1 interim (`gatehouse-true-away-view.md`). New stadiums should be designed to need little per-direction art.

## In Scope

- A skill, `.agents/skills/stadium-creation/` or similar, with a `SKILL.md` and its referenced standard. It defines:
  - a layout written once per stadium in pitch coordinates: the bowl outline, bays and seats, regalia anchors, stairwells, apron reservations and end zones;
  - ground art painted once and turned 180° for the away view, and raised art painted once per viewing direction, with designs that keep most of the architecture flat to limit that work;
  - crowds placed from the shared supporter cast in four facings, never repainted per stadium;
  - generation of the view manifest (`views.json`) from the layout;
  - layout checks showing that both views line up with the same layout;
  - the gallery review and owner approval steps.
- Batch production: one run can lay out and build several stadiums.
- Rebuilding the Gatehouse through the skill, as the reference example, with no visible change to live play.
- A routing line in `AGENTS.md`, like the one for team sprites.

## Out of Scope

- Painting the 6–7 new stadiums themselves. Each is its own work item once the skill exists.
- Venue selection rules for new leagues, beyond what the skill documents.
- Browser code changes, unless the version 1 manifest can't describe a stadium the skill must support. If so, propose a manifest version 2 with a migration.

## Functional Requirements

1. The skill gives an agent everything needed, from a layout to a reviewed stadium: inputs, steps, scripts, file locations and checks.
2. The layout is the single source for seat, regalia, stairwell and end-zone positions. The manifest and crowd placement are generated from it, never edited by hand.
3. The skill's checks fail when the views disagree with the layout, when an end zone is misplaced, when the registration error exceeds half a screen pixel, or when a mask value is out of range.
4. The rebuilt Gatehouse's runtime output matches today's assets, or any differences are listed and approved by the owner.
5. A batch of at least two stadium layouts can be built in one run.

## Acceptance Criteria

- `SKILL.md` and its standard exist, and `AGENTS.md` routes stadium work to them.
- The Gatehouse rebuilds through the skill, and the gallery and live 90° kickoff look unchanged, or the differences are approved.
- A second, placeholder layout builds in the same batch run and passes the checks, proving the batch path.

## Constraints

- Ensure the themes are all supported on Android, Windows, and Linux.
- MUTP colors, typography, style, and themes shall be preserved. Reuse CSS and other UI/UX scaffolding where appropriate.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.
- Keep reference material out of the production bundle. The asset sync skips the folder names `source` and `mockup`.

## Implementation Guidance

- Start from the Gatehouse scripts (`build-gatehouse.py`, `build-gatehouse-runtime.py`) and generalise them, rather than writing a new pipeline.
- Model the skill's structure on `team-pixel-sprites`.

## Validation

- Create new or update existing regression tests as required.
- Add asset tests for the layout and manifest checks, and run the gallery and projected-pitch suites on the rebuilt Gatehouse.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
- In the PR, record the rebuild comparison and the batch run.
