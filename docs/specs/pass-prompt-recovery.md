# Bug: Recover a match paused during a pass

Issue: [#691](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/691)

Found in the [review of PR 688](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/688#pullrequestreview-6064617687). The bug is already on `main`. #662 needs the same fix for its compact snapshot.

Blocked by: nothing.

## Abstract

While a pass is waiting on a decision, such as the team re-roll after a missed throw, the server saves a checkpoint that it then refuses to restore. Anyone who needs that checkpoint gets `RECOVERY_CORRUPT`. Restore the step exactly as it was saved.

## Objective

A match interrupted at any decision in a pass restores and continues as an uninterrupted match would.

## Context / Existing Behavior

Line numbers are for `main` at `4db2c4aee`, under `ffb-server/src/main/java/com/fumbbl/ffb/server/`.

- Every BB2025 pass stacks `HAIL_MARY_PASS` behind the ordinary `PASS` step (`step/generator/bb2025/Pass.java:39`). Until that step runs, its `state.result` is null.
- `StepHailMaryPass.toJsonValue` saves that as `"passResult": null` (`step/bb2025/pass/StepHailMaryPass.java:97`).
- `initFrom` replaces a null result with `FUMBLE` or `INACCURATE`, using the older `passFumble` key (`:114-118`). Current checkpoints never have that key, so null always becomes `INACCURATE`.
- `SetupSession` compares the restored native state with the saved one (`match/SetupSession.java:391`). They differ, and the constructor reports `RECOVERY_CORRUPT` (`:403`).
- `SetupApplication.reconstruct` remembers the rejected generation in memory (`match/SetupApplication.java:422`), so later loads of the same checkpoint also fail. The stored checkpoint is intact, so a fixed server can read it.
- The fallback dates from `e1504d900` (2026-01-07).

Probe at `4db2c4aee`: unmodified Human and Orc teams, random legal play, real `ServerCommunication`. At every prompt below the session's own `recoveryArtifact()` was restored.

| Step / prompt | Restored | `RECOVERY_CORRUPT` |
| --- | --- | --- |
| `PASS` / team re-roll | 0 | 3 (seed 103 r246, seed 107 r9 and r284) |
| Other sampled prompts | 37 | 0 |

PR 688's compact snapshot failed the same way at that prompt, at On the Ball (`INIT_SELECTING` with the `PASS_BLOCK` dialog), and at `INIT_MOVING` while a pass was pending. The latter two weren't probed on the production checkpoint.

## In Scope

- Restoring `StepHailMaryPass` without changing a result that was never set.
- Regression tests for production checkpoints at decisions within a pass.

## Out of Scope

- The other restore defects in the PR 688 review (Steady Footing and `StepEndSelecting.moveStart`).
- The compact snapshot itself (#662).
- Changes to pass or Hail Mary rules.
- A general audit of step serialization.

## Functional Requirements

- A saved null result restores as null.
- A saved non-null result restores unchanged.
- The `passFumble` fallback applies only when that key is present.
- A checkpoint already stored at a pass prompt restores without migration.

## Acceptance Criteria

- [ ] A match at the team re-roll after a missed pass restores from its checkpoint, on both rosters.
- [ ] A match paused at On the Ball during a pass restores.
- [ ] After each restore, accepting and declining give the same dice, outcomes, choices and both coaches' views as a match that was never restored.
- [ ] A Hail Mary pass paused after its own roll still restores with its saved result.
- [ ] A step saved with the older `passFumble` key still restores as `FUMBLE` or `INACCURATE`.
- [ ] The first two tests fail on `main` before the fix.

## Constraints

- BB2025 is the only supported ruleset.
- Keep the checkpoint and journal formats unchanged (ADR 0004).
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- The likely change is in `StepHailMaryPass.initFrom`: use the fallback only when `IServerJsonOption.PASS_FUMBLE.isDefinedIn(jsonObject)`.
- `BogrukEngineFixture` with real communication reaches the prompt. Force the pass roll rather than searching seeds.
- Keep this separate from PR 688 so that it can ship first. PR 688 can then rebase on it and add the compact test.

## Validation

- Create new or update existing regression tests as required.
- Run the recovery tests, the Hail Mary state tests in `ffb-statetest`, and the pass tests.
- Record the fix under Unreleased in `CHANGELOG.md`.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
