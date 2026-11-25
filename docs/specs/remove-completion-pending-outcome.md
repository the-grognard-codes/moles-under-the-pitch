# Remove the retired completion reply

[Issue #653](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/653) · [Design](remove-completion-pending.md)

The browser decoder, retained-request handling, computer player and soak driver now omit the retired completion reply. The soak records `MATCH_COMPLETED` as a refusal at full time. `PERSISTENCE_FAILED` and `MATCH_OUTCOME_UNKNOWN` still preserve retained browser requests, and `PERSISTENCE_FAILED` still yields the computer worker to the daemon.

The Java recovery test forces the result write to defer and checks that the original final action publishes `ACCEPTED` with `FULL_TIME` while the durable match is still `ACTIVATED`. It also checks recovery across restart and duplicate recognition. The Chrome reconnect test supplies the corresponding final-action reply and verifies that the match view shows “Match finished.” and clears the retained request without a result reply.

## Validation

- A tracked search found no references to the retired reply under `browser-client`, `computer-player`, `ffb-server` or `ffb-statetest`, including fixtures. `git diff --check` passed.
- All 594 browser unit tests passed, including the soak retained-request tests. The Chrome reconnect check and production browser build passed.
- All 97 computer-player tests passed, including transport and daemon persistence backoff.
- `RecoveryApplicationTest` passed 23 cases. Its two opt-in isolated JDBC tests were skipped.
- Dev-local quick start from the issue worktree built the server and Hosting artifact. The database and engine health checks, Firebase credential check, live browser WebSocket probe and computer worker readiness passed. `/play` returned HTTP 200.

The quick start uses the shared dev-local stack. Concurrent startup from another worktree later replaced the issue #653 instance.
