# Remove `COMPLETION_PENDING` handling

[Issue #653](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/653) · [Design](remove-completion-pending.md)

## Acceptance review

| Requirement | Evidence |
| --- | --- |
| Remove the retired code from the browser, computer player, soak driver, tests and current disconnect guide. | `git grep COMPLETION_PENDING -- browser-client computer-player ffb-server ffb-statetest` found zero matches, including fixtures. |
| Show full time in the browser after a deferred result write. | `RecoveryApplicationTest` forces deferral and verifies that the initial action publishes `ACCEPTED` with `FULL_TIME` while the durable match is still `ACTIVATED`. It also verifies restart recovery and duplicate recognition. The PR unit suite exercises the acting coach's final reply and the waiting coach's unsolicited full-time broadcast. The standalone Chrome reconnect check verifies the “Match finished.” view and retained-request clearance. |
| Keep the computer player's normal full-time exit and persistence backoff. | The Bogruk and Gobbo formation transport cases finish with a normal `FULL_TIME` reply and verify exit code 0. Separate load and action refusal cases verify that `PERSISTENCE_FAILED` yields to the daemon without repeating the refused operation. |
| Preserve exact retries for the remaining uncertain responses and recognize a full-time refusal in the soak. | The live V2 client tests retain and explicitly retry the original request for `PERSISTENCE_FAILED` and `MATCH_OUTCOME_UNKNOWN`. The soak cases cover both uncertain codes and log `MATCH_COMPLETED` without sending a retained retry. |

The PR leaves server deferral, retries and log labels as implemented before #653. It preserves the two uncertain codes, and the full-time redesign stays with ticket 10 of #651.

The initial Chrome change turned the mid-match retry into a full-time retry. The review follow-up restores the mid-match case and keeps a separate full-time case. It also adds PR CI coverage for both coach replies and the live persistence-refusal paths.

The Chrome rendering check runs locally. The tests cover publication and the match view. Reading the result page during deferral remains outside that coverage. The soak recognizes the full-time refusal in its logs. If a refusal arrives while the driver still sees the old revision, it can attempt its existing debug fallback before reloading.

## Validation

- The initial implementation passed 594 browser unit tests, 97 computer-player tests, the Chrome reconnect check, and the production browser and game-server builds. `RecoveryApplicationTest` passed 23 cases and skipped two opt-in isolated JDBC cases.
- The review follow-up passed 66 focused browser unit tests, the Chrome reconnect check, 25 computer-player transport tests, and five Java completion tests. `git diff --check` passed.
- The initial dev-local quick start from the issue worktree passed the database and engine health checks, Firebase credential check, live browser WebSocket probe and computer worker readiness. `/play` returned HTTP 200. The review follow-up changes tests and documentation.

The quick start uses the shared dev-local stack. Concurrent startup from another worktree later replaced the issue #653 instance.
