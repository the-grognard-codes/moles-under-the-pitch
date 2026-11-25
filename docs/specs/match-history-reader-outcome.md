# Completed match history reader outcome

[Issue #661](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/661) implements [ticket 4](match-state-sync-tickets.md#4-one-reader-for-match-history) of the match state and live sync plan.

Results, replay events, final board state, log pages and preparation retention read through `MatchHistory`. `MatchService` opens the reader for authorized matches, and reads can report storage failures to existing callers. A retained concession can also read the validated artifact from its recovery checkpoint before the completed result is available. Completed chat pages and retries use their own reader ahead of ticket 8.

The history reader exposes the first recorded play of each coach turn and the nearest keyframe at or before a requested revision. Legacy records hold full boards, so each recorded revision is a keyframe. The current browser continues to derive its turn picker from log pages. Ticket 9a can supply record pages and keyframes for the browser reducer alongside the legacy single-event replay adapter.

Validated formats 2 and 3 use the transcript board for replay, keyframes and the final state. Format 1 falls back to events for board views and keeps its existing log and chat availability. The legacy adapter still loads the complete artifact internally and validates both board copies before serving any slice. The persisted format, browser replies, and participant and spectator access checks retain their existing contracts.

The same characterization cases passed against the original readers and through the new interface. They cover result metadata, every replay event, middle and empty-end log pages, chat availability and full validation when a caller requests an unaffected revision. The review follow-up covers turn indexing across both halves and coaches, size-limit final views, chat retries through the setup application, retained concession status, and failure codes for range storage errors.

Validation used the pinned Java toolchain for focused server tests, the native recovery suite and game-server packaging. The browser production build, 21 targeted browser unit tests, and headless match-log and live transcript catch-up checks passed. Tests that require separately provisioned MariaDB fixtures were skipped.
