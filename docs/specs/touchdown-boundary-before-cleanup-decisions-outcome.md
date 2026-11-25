# Touchdown boundary implementation outcome

The native server applies the score, scoring team, off-turn counter adjustment and half-end check before it stages the touchdown announcement and opens Star of the Show. The announcement and score use the existing accepted match update. The original ending team still owns its remaining turn cleanup and clock reserve. The next drive advances when the skill response resumes the drive transition.

The paused native step stores one optional field, `touchdownTurnEndingHome`, for the ending team. Its presence also records that the touchdown boundary has been applied. An otherwise supported checkpoint from the previous implementation can keep its open Star of the Show offer and score once on the response. A checkpoint written during the new pause requires a reader that understands the field because recovery compares the serialized native continuation exactly. The field is cleared when the drive transition resumes.

## Acceptance evidence

| Scenario | Evidence |
| --- | --- |
| Own-turn and off-turn scoring, including the last turn of the half | Native accepted outcomes contain the team score change and touchdown announcement before the Star of the Show dialog. The announcement identifies the scorer's team and its adjusted turn. |
| Accept, decline, recover and retry | The match resumes from the open offer, applies the selected skill effect, counts and announces one touchdown, and advances one drive. Off-turn cases also verify the ending coach's reserve and the scorer's reserve. |
| Pre-change native recovery | An actual checkpoint captured from `4db2c4aee` with Star of the Show open is restored through `SetupSession`. Decline and exact retry leave one score, one touchdown announcement and one drive transition. |
| Other cleanup decisions | Secret Weapon selection, Argue the Call, Bribery and Corruption, and Bribes follow the appropriate touchdown or last-turn turnover in native outcomes. A voluntary last turn emits neither boundary. Existing coverage confirms a score without the skill proceeds to setup. |

The regression cases run in `MatchAnnouncementPresentationTest`. The browser unit suite passed all 597 tests.

The phase inventory audit found a separate halftime ordering gap. HALFTIME is currently staged before first-half cleanup completes. The kick-off event splash has an existing follow-up in #647. [Issue #603 audit](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/603#issuecomment-7172782151) · [Halftime follow-up #685](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/685)
