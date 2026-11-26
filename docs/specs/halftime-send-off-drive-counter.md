# Bug: Advance the drive counter once when a half-ending touchdown pauses for a send-off

Issue: [#697](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/697)

Found in the [review of PR 693](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/693#discussion_r4704052864). The maintainer assigned this follow-up to Claude.

Blocked by: #690 (PR 693 asserts the current count in the tests this change updates)

## Abstract

A touchdown on the last turn of the first half can be followed by a Secret Weapon send-off. When it is, the public drive counter goes up twice: once when the touchdown settles, and again when the half changes after the send-off. Without a send-off, the same touchdown advances the counter once. Advance it once in every case.

## Objective

A touchdown that ends a half advances the public drive counter by exactly one. This holds whether or not cleanup pauses for Star of the Show or a Secret Weapon send-off.

## Context / Existing Behavior

Line numbers are for `main` at `375e127ac`.

- After an accepted decision, `SetupSession.java:1269-1272` advances `drive` when any of these happened, unless a Star of the Show offer is still open:
  - the half changed;
  - the score changed;
  - a Star of the Show response resolved (`resolvedStarOfTheShow`, `:1242`).
- `StepEndTurn.java:450` returns to wait for each send-off choice. `startHalf` runs later, at `:544`. So the half changes in the decision that resolves the last send-off choice, not in the scoring decision.
- A half-ending touchdown followed by a send-off therefore advances `drive` twice:
  - **With Star of the Show:** once on the Star response, and once on the final send-off choice. Tests observe this.
  - **Without Star of the Show:** once on the scoring decision, and once on the final send-off choice. This comes from reading the code; no test checks the drive count on this path (`assertHalftimeAfterRestoringSecretWeaponChain`, `MatchAnnouncementPresentationTest.java`).
- Without a pause, the touchdown and the half change happen in the same decision, and `drive` advances once. `lastTurnScorePrecedesStarAndAdvancesOneDrive` checks this (`MatchAnnouncementPresentationTest.java:133`).
- Mid-half, a send-off pause doesn't double-count, because only the scoring or Star decision changes the score or resolves the offer. `offTurnStarThenRestoredSendOffKeepsOneTouchdownAndDriveAdvance` checks this (`:257`).
- `offTurnStarAndRestoredSendOffBothPrecedeHalftime` asserts the current double count (`:271`, `driveBeforeFinalBribe() + 1`). PR 693's halftime send-off cases assert it too.
- `endRunawayPeriod` (`SetupSession.java:1885`) applies a similar rule (half or score changed) when the decision limit ends a period.
- The counter is public and has several readers:
  - the public view and every match record (`periodView`, `:1849`), including the hosted result's "Half · Drive" line (`browser-client/src/HostedResult.tsx:98`) and browser playback grouping;
  - the drive tracker (`:1538`, `:1929`);
  - a computer coach's per-drive saved-formation draw (`:1341`) and its turn key (`:760`).
- Recovery restores `drive` from the checkpoint (`:379`) and does not rerun commands (ADR 0004). A compact snapshot's drive tracker summary must match the restored `drive` (`:424`).

## In Scope
- Count a half-ending touchdown as one drive advance across a Star of the Show response and a Secret Weapon send-off chain.
- Cover touchdowns with and without Star of the Show.
- Update the tests that assert the current double count.

## Out of Scope
- The Star of the Show re-roll recipient (#690, PR 693).
- HALFTIME announcement ordering (#685).
- Turn-clock and reserve accounting (#588).
- Rewriting drive numbers already stored in match records, checkpoints or transcripts.

## Functional Requirements
- A touchdown that ends a half advances `drive` by exactly one between the scoring decision and second-half setup.
- This holds:
  - with and without Star of the Show;
  - with and without a Secret Weapon send-off chain;
  - for home and away scorers;
  - on the scorer's own turn and on the opponent's turn.
- The advance happens when the touchdown settles: on the scoring decision, or on the Star response when an offer opens. During the send-off pause the public view already shows the new drive, as it does mid-half today.
- These cases keep their current counts:
  - mid-half touchdowns;
  - half ends without a touchdown;
  - half-ending touchdowns that don't pause.

## Acceptance Criteria
- A last-turn touchdown followed by a Secret Weapon send-off reaches second-half setup with `drive` one higher than before the touchdown. This holds with and without Star of the Show.
- The same holds for home and away scorers, on their own turn and on the opponent's turn.
- Recovering during the send-off pause and retrying the accepted decisions adds no further advance.
- A checkpoint written before this change during the send-off pause, whose `drive` has already advanced, restores and reaches second-half setup with the same `drive`.
- In a compact snapshot taken during or after the pause, the drive tracker summary still matches the restored `drive`.
- Existing counts don't change for:
  - mid-half touchdowns, with or without a send-off;
  - half-ending touchdowns that don't pause;
  - voluntary or turnover half ends followed by a send-off.

## Constraints
- No browser UI change is expected. If one becomes necessary, preserve MUTP colors, typography, style and themes, reuse existing CSS and UI scaffolding, and support Android, Windows and Linux.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions (`AGENTS.md`).
- Do not commit or push unless explicitly requested. An implementing agent picking up this issue as `ready-for-agent` branches, commits, pushes and opens a PR that references it. It never merges.
- Add no recovery-format field if existing state can decide the count. If one is needed, call it out in the PR for separate review.
- Leave drive numbers in existing match records and transcripts unchanged.

## Implementation Guidance
- Keep the advance at the decision where the touchdown settles. Don't advance again when the half changes for the same touchdown.
  - Deciding at the touchdown keeps older checkpoints saved during the pause correct, because their `drive` has already advanced.
  - Deferring the advance to the half change would count those checkpoints twice.
- `StepEndTurn` already serializes its settled touchdown (`fTouchdown`, `StepEndTurn.java:1076`, `:1106`). `SetupSession` already reads that step's state through `homeTurnEndingBeforeStarOfTheShow()` (`SetupSession.java:1831`). A similar read may tell the final send-off decision apart from a half end with no touchdown, without a new field.
- Check whether `endRunawayPeriod` (`:1885`) can reach the same pause.
- Replace the asserted double count with the single advance in two places: `MatchAnnouncementPresentationTest.java:271` on `main`, and PR 693's halftime send-off cases once that PR merges. Add a last-turn touchdown without Star of the Show that continues into a send-off.

## Validation
- Create new or update existing regression tests as required.
- Run `mvn test` for `ffb-server` and `ffb-statetest`.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
