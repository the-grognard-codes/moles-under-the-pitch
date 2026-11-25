# Bug: Credit the Star of the Show re-roll to the scorer's team

Issue: [#690](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/690)

Found in the [review of PR 687](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/687#pullrequestreview-6064437590). The bug is already on `main`; that PR preserves it as #603 requires.

Blocked by: #603 (PR 687 changes the same lines)

## Abstract

When a player scores during the opponent's turn and the scorer's coach accepts Star of the Show, the server credits the re-roll to the opponent. If a Secret Weapon send-off pauses the same cleanup, the re-roll goes to the scorer's team instead. Credit the scorer's team in every case.

## Objective

An accepted Star of the Show re-roll always goes to the team of the player who scored, whichever team's turn ended and whether or not cleanup paused.

## Context / Existing Behavior

Line numbers are for `main` at `4db2c4aee`, in `ffb-server/src/main/java/com/fumbbl/ffb/server/step/bb2025/StepEndTurn.java`.

- `:274` sets `isHomeTurnEnding` from `game.isHomePlaying()` each time `executeStep` runs.
- `:354` then sets the playing team to the scorer's. For an off-turn touchdown that changes it, but `isHomeTurnEnding` still names the team whose turn ended.
- `:560-564` credits the re-roll using `isHomeTurnEnding`, so the opponent gets it.
- A send-off choice returns before `:560`. On the next entry `:274` reads the changed playing team, so the scorer's team gets it.
- `:564` reports the skill for `touchdownPlayerId`, so the report names the scorer even when the opponent received the re-roll.
- On PR 687 (`3cedba68a`) the credit is at `:546`, through `endingTurnData`, with the same result.

Probe at `3cedba68a`, home scoring on away's turn and accepting the skill:

| Case | Home re-roll | Away re-roll |
| --- | --- | --- |
| No send-off | 0 | 1 (wrong) |
| No send-off, last turn of the half | 0 | 1 (wrong) |
| Secret Weapon send-off | 1 | 0 |
| Secret Weapon send-off, last turn of the half | 1 | 0 |

The two send-off cases gave the same result on `4db2c4aee`. The wrong recipient without a send-off on `4db2c4aee` is from reading the code above. `offTurnScoreCanEndTheHalfBeforeStar`, added in PR 687, accepts the skill off-turn but checks only that the report exists.

The expected recipient, the scorer's own team, has not been checked against the rulebook text. Confirm it at triage.

## In Scope
- Choose the Star of the Show recipient from the recorded scorer.
- Regression tests that assert the recipient.

## Out of Scope
- When Star of the Show is offered, and its position relative to the touchdown boundary (#603).
- HALFTIME ordering (#685).
- Turn-clock and reserve accounting (#588).
- Other uses of `isHomeTurnEnding` after a send-off pause, such as effect and prayer expiry (`:532-551`).
- `TurnData.setReRollShowStarOneDrive` (`ffb-common/src/main/java/com/fumbbl/ffb/model/TurnData.java:170-176`), which notifies observers with the Pump up the Crowd change ID. Its impact has not been assessed.

## Functional Requirements
- When Star of the Show is accepted, add one re-roll and one `reRollShowStarOneDrive` to the team of `touchdownPlayerId`.
- Apply this to own-turn and off-turn touchdowns, at mid-half and half end, with and without a cleanup pause.
- Declining adds no re-roll.
- Keep the credit where it is now in cleanup, after drive re-roll removal and any half start.
- Own-turn results don't change.

## Acceptance Criteria
- An accepted off-turn Star of the Show gives the scorer's team exactly one additional re-roll and the opponent none.
- The same holds on the last turn of a half, and with a Secret Weapon send-off between the response and the credit.
- Tests cover home and away scorers.
- Recovering during the send-off pause and retrying the accepted response add no second re-roll.
- A checkpoint written before this change, with the offer or a later send-off open, still restores and credits the scorer's team once.
- Own-turn and declined cases keep their current results.

## Constraints
- No browser UI change is expected. If one becomes necessary, preserve MUTP colors, typography, style and themes, reuse existing CSS and UI scaffolding, and support Android, Windows and Linux.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions (`AGENTS.md`).
- Do not commit or push unless explicitly requested. An implementing agent picking up this issue as `ready-for-agent` branches, commits, pushes and opens a PR that references it. It never merges.
- Add no recovery-format field; `touchdownPlayerId` is already serialized.

## Implementation Guidance
- Resolve the recipient at the credit site from `touchdownPlayerId`, for example by checking `game.getTeamHome().hasPlayer(...)`, in place of the ending team.
- Extend the Star of the Show cases in `MatchAnnouncementPresentationTest`, including a case that continues into a send-off.

## Validation
- Create new or update existing regression tests as required.
- Run `mvn test` for `ffb-server` and `ffb-statetest`.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
