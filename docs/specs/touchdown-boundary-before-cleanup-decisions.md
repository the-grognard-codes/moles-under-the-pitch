# Feature: Send the touchdown before any decision that depends on it

Issue: #603

Follow-up to ADR 0007, accepted in PR #551 on 2026-11-21.

## Abstract

At the end of a turn that ends in a touchdown, the server currently offers Star of the Show before it records the score and sends the touchdown announcement. Move the server's touchdown boundary so that the settled touchdown is recorded and sent first, and every decision that depends on the touchdown is raised after it. This is the first emission-alignment follow-up to ADR 0007.

## Objective

When the server raises a cleanup decision that depends on a touchdown, the touchdown has already been settled, scored and sent to every recipient. The order comes from the server, so the browser no longer has to infer it.

## Context / Existing Behavior

- **Governing decision.** [ADR 0007](../adr/0007-server-owned-turn-sequence-presentation.md) requires native resolution to settle the touchdown or turnover first. The server records and stages the boundary event before raising a dependent decision and publishes the accepted facts after commit.
- **"Sent", not "received".** The server never waits for a client to acknowledge the touchdown. A hung or slow client must not stall the match (ADR 0006). The touchdown event and the following offer may travel in the same update; the browser presents the TOUCHDOWN splash before revealing the dialog.
- **The current gap**, in `ffb-server/src/main/java/com/fumbbl/ffb/server/step/bb2025/StepEndTurn.java` `executeStep()`:
  - `:299-301` detects the touchdown with `UtilServerSteps.checkTouchdown`.
  - `:303-316` finds the scorer from the ball square. If the scorer has an unused `canGrantReRollAfterTouchdown` skill (Star of the Show), it shows `DialogSkillUseParameter` and returns `StepAction.CONTINUE`.
  - Only after that decision does `:335-363` run: the scorer's touchdown and team score are incremented, off-turn touchdown handling changes `homePlaying` and the turn number, and `getGameState().observeTurnEnd(touchdownPlayer, fNewHalf)` is called.
  - `observeTurnEnd` is where the browser runtime builds and flushes the TOUCHDOWN announcement (`ffb-server/src/main/java/com/fumbbl/ffb/server/match/SetupSession.java:224`, `announcements.end(...)` then `announcements.flush(this)`).
  - So the Star of the Show offer is published while the score and the TOUCHDOWN announcement are still missing.
- **What Star of the Show changes.** The decision only sets `useStarOfTheShow`. Its effect is applied later, at `StepEndTurn.java:560-564` (`ReportShowStarReRoll`). It does not affect whether the touchdown counts, the score or the next drive's setup. Moving the offer after the boundary is therefore a change of emission order, not a rules change.
- **Other cleanup decisions already follow the boundary.** Secret Weapon send-offs, Argue the Call, Bribery and Corruption, and Bribes (`StepEndTurn.java:462-505`) run after `observeTurnEnd`, on both the touchdown and the new-half paths. The turnover and voluntary-end path calls `observeTurnEnd(null, fNewHalf)` at `:333`, also before those decisions. These need regression coverage, not changes.
- **Restore state.** `StepEndTurn` serializes `fTouchdown`, `useStarOfTheShow`, the scorer and its continuation flags. The phase inventory notes this (`docs/specs/turn-sequence-phase-inventory.md`, "Before/after Star of the Show" recovery row). A step resumed while the Star of the Show dialog is open must not count the touchdown again or re-announce it.
- **Other emission sites.** The inventory's cleanup rows record `StepEndTurn` as the known gap. No full audit of every site that can raise a decision depending on a touchdown or turnover has been done against the ADR rule.

## In Scope
- Reorder `StepEndTurn` so the touchdown is settled, scored and announced before Star of the Show is offered.
- Keep re-entry and restore idempotent: one score increment, one TOUCHDOWN announcement per touchdown, whichever request or restore resumes the step.
- Off-turn touchdowns and touchdowns on the last turn of a half, including the score, the scoring team, the turn number and the end-of-half state used by the announcement.
- Regression tests for touchdown-dependent and turnover-dependent cleanup decisions in `StepEndTurn`.
- An audit of the emission sites in the phase inventory against the ADR rule. File a separate `needs-triage` issue for each other misaligned site found, citing this one.

## Out of Scope
- The ADR 0007 presentation-marker schema and transcript readers and writers. Track those in separate implementation slices. Revisit the storage and sync cutover in #611 when its proposed ADR is accepted.
- Browser splash scheduling, the animated splash feature and #541's client ordering.
- Rules changes, including when Star of the Show is available or what it grants.
- Turn-clock and decision-time accounting (#588).
- Fixing sites found by the audit other than `StepEndTurn`. File those separately.

## Functional Requirements
- When a turn ends in a legal touchdown, the server applies, in this order:
  1. the scorer's touchdown and team score, with off-turn adjustments to the playing team and turn number;
  2. the end-of-half check that depends on them;
  3. `observeTurnEnd(scorer, halfEnded)`, so the TOUCHDOWN announcement is built and flushed;
  4. only then any Star of the Show offer.
- Star of the Show is still offered only to the scorer with an unused `canGrantReRollAfterTouchdown` skill, and its effect is still applied where it is now.
- After durable acceptance, publish the touchdown boundary and score before or in the same update as the offer. Server progression is independent of client acknowledgement.
- Resuming `StepEndTurn` after the Star of the Show decision, or after a restore with the dialog open, does not repeat the score increment, the off-turn turn-number change or the announcement. Exact retries of an accepted command append nothing.
- Secret Weapon send-offs, Argue the Call, Bribery and Corruption, and Bribes keep their current position after the boundary on the touchdown, turnover and new-half paths.
- Voluntary turn ends still produce no TURNOVER or TOUCHDOWN announcement.
- Preserve recovery compatibility for supported native `StepEndTurn` state saved before this change. ADR 0007 retires the older embedded transcript format. Any native recovery-format change requires separate review.

## Acceptance Criteria
- In a native server test where a Star of the Show scorer scores, the accepted update that offers `DialogSkillUseParameter` contains, or is preceded by, the touchdown announcement and the updated score. The offer never appears in an update before them.
- The same holds for an off-turn touchdown (the scoring team is the scorer's, not the previous actor's) and for a touchdown on the last turn of the half.
- A touchdown by a scorer without Star of the Show behaves as it does now, apart from ordering.
- Accepting or declining Star of the Show, an exact retry of that command and a restore with the dialog open each leave exactly one touchdown counted and one TOUCHDOWN announcement in the transcript.
- Restore a supported pre-change checkpoint and verify the match can continue without duplicating the touchdown score or announcement.
- Tests show that Secret Weapon send-off, Argue the Call, Bribery and Corruption, and Bribe offers follow the touchdown or turnover boundary on each path that can raise them.
- The audit result is recorded on this issue: each emission site checked, its status, and links to any follow-up issues filed.

## Constraints
- No browser UI change is expected. If one becomes necessary, keep MUTP colors, typography, style and themes, reuse existing CSS and UI scaffolding, and keep support on Android, Windows and Linux.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions (`AGENTS.md`): BB2025 rules stay in `bb2025` packages, no legacy `f`/`p` prefixes on new fields, and imports are organized as documented.
- Do not commit or push unless explicitly requested. An implementing agent picking up a `ready-for-agent` issue branches, commits, pushes and opens a PR that references this issue. It never merges.
- Native step and dialog behavior remains the rules authority. Don't infer the touchdown from an unsettled ball or score snapshot, and don't reorder only in the browser.
- No engine sleep, retry or blocking acknowledgement on the single engine worker (ADR 0006).

## Implementation Guidance
- The likely shape is to move the scoring and `observeTurnEnd` block ahead of the Star of the Show offer, behind a serialized "touchdown applied" flag in `StepEndTurn` that makes re-entry skip it. Leave `markPlayedAndSecretWeapons`, stunned recovery and the drive transition where they are unless the reorder requires otherwise. If one of them must move, record why.
- Check that `observeTurnEnd` building and flushing the announcement before the dialog is shown still gives one ordered update in `SetupSession`. Moving the call should be enough to put the announcement ahead of the offer.
- Off-turn handling changes `homePlaying` and the turn number before `checkEndOfHalf` runs again. Keep that order so `halfEnded` is correct in the announcement.
- For the audit, use the cleanup, drive-end and kick-off rows of `docs/specs/turn-sequence-phase-inventory.md` as the checklist.
- `ffb-statetest` and the existing `StepEndTurn` tests are the natural place for the native ordering tests.

## Validation
- Create new or update existing regression tests as required, including native server tests for each acceptance criterion and a restore-with-dialog-open test.
- Run `mvn test` for `ffb-server` and `ffb-statetest`. Run the browser test suite in `browser-client` to confirm no client regression.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
- Record the emission-site audit on the issue, with links to any follow-up issues.
