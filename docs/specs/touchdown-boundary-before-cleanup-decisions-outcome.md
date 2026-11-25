# Touchdown boundary implementation outcome

The native server applies the score, scoring team, off-turn counter adjustment and half-end check before it stages the touchdown announcement and opens Star of the Show. The announcement and score use the existing accepted match update. The original ending team owns the clock during the Star of the Show offer and supplies the turn context when its response resumes cleanup. The next drive advances when the skill response resumes the drive transition.

If an off-turn touchdown then pauses for a Secret Weapon choice, the resumed step uses the scoring team for effect expiry and the Star of the Show re-roll. That re-entry behavior predates #603. The re-roll recipient is tracked in [#690](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/690).

The paused native step stores one optional field, `touchdownTurnEndingHome`, for the ending team. Its presence also records that the touchdown boundary has been applied. An otherwise supported checkpoint from the previous implementation can keep its open Star of the Show offer and score once on the response. A checkpoint written during the new pause requires a reader that understands the field because recovery compares the serialized native continuation exactly. The field is cleared when the drive transition resumes.

## Acceptance evidence

| Scenario | Evidence |
| --- | --- |
| Own-turn and off-turn scoring, including the last turn of the half | Native accepted outcomes contain the team score change and touchdown announcement before the Star of the Show dialog. The announcement identifies the scorer's team and its adjusted turn. |
| Accept, decline, recover and retry | The match resumes from the open offer, applies the selected skill effect, counts and announces one touchdown, and advances one drive. Off-turn cases also verify the ending coach's reserve and the scorer's reserve. |
| Pre-change native recovery | An actual checkpoint captured from `4db2c4aee` with Star of the Show open is restored through `SetupSession`. Decline and exact retry leave one score, one touchdown announcement and one drive transition. |
| Star of the Show followed by a send-off | The off-turn regression restores the match during Star of the Show and again during Argue the Call. Accepting Star of the Show, then declining Argue the Call and the Bribe, sends off the player and leaves one touchdown and one drive advance. |
| Other cleanup decisions | Secret Weapon selection, Argue the Call, Bribery and Corruption, and Bribes follow the appropriate touchdown or last-turn turnover in native outcomes. A voluntary last turn emits neither boundary. Existing coverage confirms a score without the skill proceeds to setup. |

The regression cases run in `MatchAnnouncementPresentationTest`. The initial local Maven verification ran 996 `ffb-server` cases and 300 `ffb-statetest` cases with zero failures or errors and 28 skips. A focused run covered the subsequent clock and checkpoint changes, and all 31 focused server and state tests passed after the review fixes. GitHub's Java 21 target verification passed on the initial review head. All 597 browser unit tests passed.

The boundary tests exercise sequential send-offs. `SecretWeaponDecisionTest` checks legacy checkpoint compatibility and pending bribe offers, and code inspection covers the legacy boundary order. The captured pre-change checkpoint covers an own-turn decline. Clock accounting across an off-turn halftime score and a subsequent send-off remains part of [#588](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/588).

The phase inventory audit found a separate halftime ordering gap. HALFTIME is currently staged before first-half cleanup completes. The kick-off event splash has an existing follow-up in #647. [Issue #603 audit](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/603#issuecomment-7172782151) · [Halftime follow-up #685](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/685)
