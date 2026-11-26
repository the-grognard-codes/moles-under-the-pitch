# Star of the Show re-roll outcome

An accepted Star of the Show now gives the recorded scorer's team one extra re-roll and tracks that re-roll for the next drive. The award still happens after expiring the previous drive's re-rolls and starting the next half when applicable. The server already serializes the touchdown scorer, so the award requires no checkpoint format change. [Issue #690](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/690)

Games Workshop's Count Luthor preview describes the special rule as granting his coach an extra team re-roll. [Warhammer Community](https://www.warhammer-community.com/en-gb/articles/Rd4n5Kxx/cut-your-teeth-on-the-new-vampire-star-players/)

| Scenario | Acceptance evidence |
| --- | --- |
| Home and away off-turn scorers, mid-half and at halftime | The four unpaused cases reproduced the wrong team on `b485326b4`. With the fix, both the total and the Star of the Show drive count increase by one for the scorer's team. The opponent gets zero. |
| Secret Weapon send-off | Both teams are tested mid-half and at halftime. The award is pending when Argue the Call opens. The test restores at that prompt, retries the accepted Star response, declines Argue the Call and Bribes, then retries the response again. The scorer receives one award. |
| Checkpoints from before #690 | Two checkpoints captured on `b485326b4`, with the Star offer and with Argue the Call open, each resume and award the home scorer exactly one re-roll. |
| Own-turn and decline | An own-turn acceptance grants one re-roll for the next drive. Mid-half and halftime declines grant zero. |

The existing halftime send-off path advances the drive counter on the Star response and again when the half advances after the send-off. This was observed on `b485326b4` and is outside the re-roll recipient change. [Halftime ordering #685](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/685)

All 32 focused `MatchAnnouncementPresentationTest` cases and 597 browser unit tests passed locally. The full `ffb-server` run reported 1,022 cases with zero failures or errors and 21 skips.
