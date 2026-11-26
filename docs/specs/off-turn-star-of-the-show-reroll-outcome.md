# Star of the Show re-roll outcome

An accepted Star of the Show now gives the recorded scorer's team one extra re-roll and tracks that re-roll for the next drive. The award still happens after expiring the previous drive's re-rolls and starting the next half when applicable. The server already serializes the touchdown scorer, so the award requires no checkpoint format change. [Issue #690](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/690)

The BB2025 rule gives one Team Re-roll to Count Luthor's controlling coach when he scores. The coach can use it until the end of the following drive. [BB2025 rule text supplied by the maintainer](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/693#discussion_r4704080942)

| Scenario | Acceptance evidence |
| --- | --- |
| Home and away off-turn scorers, mid-half and at halftime | The four unpaused cases reproduced the wrong team on `b485326b4`. Both teams start with two re-rolls and spend one each in the halftime scenarios to exercise the refill. After acceptance, the scorer's team has three total and one Star of the Show drive re-roll. The opponent has two total and zero Star of the Show drive re-rolls. |
| Secret Weapon send-off | Both teams are tested mid-half and at halftime. The award is pending when Argue the Call opens. The test restores at that prompt, retries the accepted Star response, declines Argue the Call and Bribes, then retries the response again. The scorer receives one award. |
| Checkpoints from before #690 | The home scorer's two send-off checkpoints from `b485326b4` each resume and grant one award. A checkpoint taken on `fcf43d319` keeps an off-turn away scorer's offer open with no send-off pending. On the original code, accepting from that checkpoint increased home's total from two to three. The fix credits away once, including after an exact retry. |
| Own-turn and decline | An away own-turn scorer can accept mid-half and a home own-turn scorer can accept at halftime. Off-turn mid-half and halftime declines and the own-turn halftime decline grant zero extra re-rolls. |

The halftime send-off path advances the drive counter on the Star response and again when the half advances after the send-off. The tests record that existing behavior. The maintainer assigned the separate drive-counter follow-up to Claude. [Review discussion](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/693#discussion_r4704052864)

The initial pinned JDK 21 Maven verify passed, including packaging. `ffb-server` reported 1,022 cases with 21 skips and `ffb-statetest` reported 300 cases with 7 skips. Both reported zero failures or errors.

On the review head, 64 focused `ffb-server` cases, 15 focused `ffb-statetest` cases, and 602 browser unit tests passed. The Java selectors covered announcements, movement, recovery, re-roll accounting, and Secret Weapon decisions.
