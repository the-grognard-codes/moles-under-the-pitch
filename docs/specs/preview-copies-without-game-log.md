# Feature: Leave the game log out of block and ball-transfer preview copies

Issue: [#652](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/652)

Part of #651 (ticket 1).

## Abstract

To preview a block or a ball transfer, the server copies the whole engine through JSON, including every command in the game log, and rebuilds each command. The copy is made several times per move, and it grows with the match. Nothing reads the copied log. Leave it out.

## Objective

A preview costs the same late in a match as early, and shows the same result.

## Context / Existing Behavior

Line numbers are at `main` `eb5057d02`.

- `BlockPreviewPresentation.copy` (`:117-122`) and `PlannedBallTransfer.validate` (`:42-44`) call `GameState.toJsonValue(true, 0)` and then `initFrom`. The first argument includes the whole log, and `GameLog.initFrom` rebuilds every command.
- `BlockPreviewPresentation.project` runs for the coach whose turn it is, every time that coach's view is built in play (`SetupSession.java:1282`). It makes one copy for each player that can declare a block and has an adjacent target, and one more for each jump-up block and each multiple-block pair. `blitzPreview` (`:1775`) and planned transfers (`:1759`) make one each.
- A view is built more than once per move: for the checkpoint (`:502`), for the recorded event (`:1582`) and for each reply or publication.
- `SetupSession.recoveryNative` (`:569`) already uses `toJsonValue(false, 0)`, and `GameState.initFrom` accepts a missing log (`GameState.java:454-458`).
- Only capture, the two copies and recovery use `GameLog`. The previews don't read what they copy.
- The cost hasn't been measured. #650 measures it as part of the baseline.

## In Scope

- Both copies leave the game log out.
- Tests that the previews are unchanged and that a copy has an empty log.
- Before-and-after figures for one copy.

## Out of Scope

- Reducing how often views or previews are built, or sharing one copy between previews.
- Changing the game log, capture, recovery or any stored format.

## Functional Requirements

- Block, blitz, jump-up block, multiple block, pass and hand-off previews give the same result as before.
- A preview copy contains no logged command and leaves the live engine unchanged.

## Acceptance Criteria

- [ ] Both copy sites use the serializer without the log.
- [ ] Tests compare each kind of preview with its result before the change, on real 22-player states.
- [ ] A test asserts that the copied state's log is empty and that the live log keeps its size and its epoch.
- [ ] The PR reports the time and allocation of one copy at about revision 50 and late in a complete match, on the parent commit and on the change. The parent commit is named, so that #650 can pin its baseline to it.

## Constraints

- ADR 0006: one engine worker and the 2,000 ms request ceiling. No stored or wire format changes.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- The change is one argument at each of the two sites. Keep it that small.
- The training fixture mocks `ServerCommunication`, so its log is empty (`BogrukEngineFixture.java:69`). Measure with a real one, as `SetupSessionMovementTest.java:1385` does.

## Validation

- Create new or update existing regression tests as required.
- Run the focused server and state tests that cover previews (for example `BlockVisibilityTest`, `SetupSessionMovementTest` and `BallAndFoulActionsTest`), then `mvn clean install`.

## Completion Notes

- The [outcome report](preview-copies-without-game-log-outcome.md) records acceptance evidence and the benchmark used by #650.
- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
