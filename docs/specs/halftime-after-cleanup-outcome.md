# Halftime after first-half cleanup

[Issue #685](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/685) applies the announcement ordering in [ADR 0007](../adr/0007-server-owned-turn-sequence-presentation.md#ordering-and-timing).

The server captures the halftime score and turn when it settles the final turn of the first half. It records any touchdown or turnover before opening the resulting cleanup choices. When the cleanup step completes, its final model sync records halftime with the transition to the second half, before the engine enters setup. A voluntary last turn produces no turnover. The same accepted update carries halftime when there are no pending choices.

An open cleanup dialog can carry one deferred halftime event in the existing announcement checkpoint. An older checkpoint that already published halftime can finish cleanup without appending it again. The wire event, touchdown rules, and client playback controls retain their existing behavior. Phase markers and compact-store request receipts remain part of [#611](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/611).

## Acceptance evidence

| Scenario | Verification |
| --- | --- |
| Last-turn touchdown and Star of the Show | Own-turn and off-turn scores precede the offer. Accepting or declining after recovery records halftime with the transition to setup. |
| Secret Weapon cleanup | Last-turn touchdown, turnover and voluntary end cover player selection, Argue the Call, Bribery and Corruption, and Bribes. Halftime follows the last response, including when Star of the Show precedes the send-off. |
| No pending choice | The voluntary last turn records one halftime in the same accepted revision, with the first-half turn number. |
| Recovery and exact retry | A restored cleanup dialog can finish once. The active recovery path returns a duplicate for the exact accepted retry and appends no record. The compact snapshot path restores the deferred event, captures it once and rejects an old revision without producing another capture. |
| Browser playback | The announcement tests cover playback, decision visibility, replay and recovery using the existing client scheduler. |

The new ordering assertions failed against the base branch for a last-turn touchdown, a rules-triggered turnover and a voluntary last turn. The focused Java run passed all 43 tests across the match, Secret Weapon, turnover and kickoff coverage. The browser announcement Playwright suite and all 597 browser unit tests passed.
