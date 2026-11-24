# Match resume deadlines and expired-match acknowledgement

[Issue #639](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/639)

## Abstract

Show coaches how long they have to resume an activated match. Once the server confirms expiry, keep the match visible until the signed-in coach chooses **Acknowledge and remove**. Store that choice on the server for the coach's account so it holds across browsers and devices.

## Objective

A coach can tell whether a match is still playable and clear an expired listing once for their account. Each participant controls their own lobby.

## Context / Existing Behavior

- Save/resume retention (`SetupSession.SaveResumeState`) gives an activated match one of three deadlines:
  - Two human coaches: 30 days after the last human action.
  - Against the computer: 1 hour after the human coach began owing a decision. There is no deadline while the computer owes the decision.
  - A mutually saved (suspended) match: 30 days after suspension.
- Expiry is durable and never a background deletion. `abandonIfInactive` records it on the next request or timer that touches the match. In a computer match, a save proposal or reactive decision that times out before the deadline is applied first and can move the deadline.
- The lobby (`currentMatches`) lists an activated match by its phase until a resume attempt fails with `MATCH_ABANDONED`. A cold human `load` returns the committed projection without checking the deadline (`SetupApplication.java:939` on `main`). A direct link therefore opens the match, and its first action fails.
- `ffb_v2_match_members` has an exact verified shape (`Marker6Schema`), and the server requires schema marker 10. Migrations are explicit SQL files applied while the service is stopped (`docs/operations/recovery-journal-schema.md`).
- An uncommitted prototype in `.worktrees/expired-match-lobby` adds a countdown, an `EXPIRED` lifecycle, the expired direct-link view and browser-local dismissal. Its base is five commits behind `main` (checked 2026-11-23). Clearing browser storage or switching devices loses its dismissal.

## In Scope

- Remaining resume time for activated save/resume matches, the transition to expired, and the expired direct-link view.
- Durable per-account, per-match acknowledgement and consistent lobby results across sessions and devices.
- The schema marker 11 migration, its operator procedure, and the tools that check schema markers.

## Out of Scope

- Changing retention windows, deleting match data, or changing the opponent's lobby when one coach acknowledges.
- Expiry or release of preparations that are not activated (`WAITING_FOR_OPPONENT`, `AWAITING_SETUP`). They keep their own rules.
- Removing `UNAVAILABLE` entries, including matches whose checkpoints are corrupt or in an unsupported format. The maintainer deferred this (D3).
- Notifying the opponent, and a history view of acknowledged matches.
- Redesigning checkpoint storage or replay transport under #611.

## Functional Requirements

1. **One expiry rule.** The lobby, the direct link and the acknowledgement check use the same predicate that `abandonIfInactive` applies, including the computer-match ordering of proposal and reactive-decision timeouts. Do not keep a second copy of the rule.
2. **Countdown from server time.** The `currentMatches` response gives each resumable match its deadline and gives the server's current time. The client counts down from the difference, not from the device clock alone. A match without a deadline shows no countdown. That covers legacy checkpoints without save/resume, a computer match while the computer owes the decision, and full time.
   - **Quiet matches only.** Show the countdown only once the match's deadline clock has run for at least 5 minutes without a reset. That clock starts at the last human action, when the human began owing a decision in a computer match, or at suspension. A match being actively played shows no countdown. Keep the 5 minutes as one server constant; the existing 30-second lobby refresh picks up a match when it crosses the threshold.
   - **Format.** With 24 hours or more left, show days and hours, for example `29d 23h to resume`. With under 24 hours, show hours and minutes, for example `22h 16m to resume`. With under 1 hour, show minutes only, for example `9m to resume`. Round minutes up, so the last minute reads `1m to resume`.
3. **Recheck at zero.** When the countdown reaches zero, show "Checking match status", withhold Resume and Concede, and request a fresh read. Keep the existing refresh when a coach returns to the lobby. Only the fresh server response decides whether the match is still resumable.
4. **Expired state.** An expired match shows **Match expired**, and **Acknowledge and remove** is its only action. Its direct link explains that the time to resume has ended and offers no play, chat-send or retained-request retry controls. It offers **Acknowledge and remove** and a way back to the lobby. A cold load reports expiry from the committed deadline without adding a checkpoint write to that request.
5. **Acknowledgement request.** Add an authenticated V2 request carrying the match ID and a request ID. The server derives the account from the session and checks membership and expiry. It answers as follows:
   - Unknown match or non-member: `NOT_FOUND`, as other match requests do.
   - Match not expired: a distinct refusal, for example `MATCH_NOT_EXPIRED`. Nothing is recorded.
   - Already acknowledged: `ACCEPTED`. The first timestamp is kept.
   - Storage failure: `PERSISTENCE_FAILED`, or an outcome-unknown code when a commit times out (ADR 0006). The client keeps the action available, and an exact retry reconciles from the stored row.
6. **Lobby filtering.** After acknowledgement, the `currentMatches` SQL query omits the match for that account before its `LIMIT`. Pages stay full and the `next` cursor stays correct.
7. **Narrow effect.** Acknowledgement changes only that account's `currentMatches` result. Match authorization, publication, spectating, preparation and the computer job inventory ignore it.
8. **Mixed versions.** A tab loaded before a deployment keeps listing games against the new server. Gate the new lifecycle and fields behind an opt-in request field, as `browse` does with `includeDetails`. A new client falls back to today's behavior against an older server.
9. **No browser-local dismissal.** Only durable server state records acknowledgement. Clearing a stale stored resume or chat request for the match is a separate client cleanup.

## Acceptance Criteria

- [ ] With a controlled server clock, each deadline kind shows the server-correct remaining time: two human coaches, a computer match while the human owes a decision, and a suspended match. The display is the same with the device clock 10 minutes fast or slow.
- [ ] A computer match shows no countdown while the computer owes the decision. After the human acts, the countdown disappears and returns once the new deadline clock has run for 5 minutes.
- [ ] A newly created or actively played match shows no countdown. A match with no human action for 5 minutes shows one.
- [ ] The label reads `29d 23h`, `22h 16m` and `9m` (each followed by `to resume`) at 29 days 23 hours, 22 hours 16 minutes and 9 minutes remaining.
- [ ] At zero, Resume and Concede are withheld until a fresh response arrives. That response shows either Resume or **Match expired**.
- [ ] An expired match stays listed until acknowledged. Its direct link shows the expired state without resume, including when the deadline has passed and abandonment is not yet recorded.
- [ ] After acknowledgement on device A, the match is absent on refresh in an independent browser on device B signed into the same account, including after signing out and back in.
- [ ] The opponent still sees the expired match until they acknowledge it. An unrelated account gets `NOT_FOUND`. Acknowledging a resumable match is refused and records nothing.
- [ ] Repeated and concurrent acknowledgements leave one stored timestamp. A failed acknowledgement can be retried from the lobby and from the direct link.
- [ ] An account with more acknowledged matches than one page holds still gets full pages and a correct `next` cursor, and the query stays on the account index.
- [ ] A tab loaded from the previous build still lists current games against the new server.
- [ ] Acknowledgement leaves the match checkpoint, transcript and chat unchanged.

## Constraints

- Use the existing lobby styling. Support narrow and desktop layouts and the supported themes on Android, Windows and Linux.
- Keep lobby reads bounded and paginated, including for cold matches. Keep the 2,000 ms request ceiling (ADR 0006) and the existing lobby rate limits.
- The countdown must not announce every tick to screen readers.
- Accepted ADRs stand, in particular ADR 0004 (durable recovery journal) and ADR 0006 (single engine worker).
- Branch from current `main` in a new worktree. Use the prototype in `.worktrees/expired-match-lobby` as reference only, and leave it untouched.
- Record the user-visible change under Unreleased in `CHANGELOG.md`.

## Implementation Guidance

- **Slices.** Slice A covers requirements 1–4 and 8 and needs no schema change. Expired matches then show **Match expired** with no removal yet. Slice B adds requirements 5–7 and 9 with the migration. The maintainer approved marker 11 and two PRs (D1). Deliver slice A first and slice B after it, each referencing this issue.
- **Storage.** Recommended: a nullable `acknowledged_at_epoch_ms BIGINT UNSIGNED` column on `ffb_v2_match_members`, keyed by the existing `(matchid, account_id)` primary key. Set it with a single conditional update that writes only when the column is null. If the account index must change to keep the filtered query bounded, say so in the PR.
- **Migration.** Add `011-*.sql` (marker 10 to 11). Update `Marker6Schema`, `LocalSchema`, `LocalAcceptanceBootstrap` and the review `schema-bootstrap` service. Review every script that reads `ffb_local_schema` and accept marker 11 where it accepts marker 10 today. Known cases are `tools/dev-local-clear-games.mjs` (the old game-clearing script: also confirm acknowledgements are cleared with memberships), `tools/match-review-provision.mjs` (add the 011 step and expect marker 11) and `tools/review-current-matches-index.mjs`, plus their tests. Search `tools/`, `containers/` and `deployment/` again rather than relying on this list. The legacy marker-5 and marker-6 tools (`m6-provision-copy`, `r5-backup`, `r5-restore`) stay unchanged. Document the rollout and rollback beside the marker 10 procedure in `docs/operations/recovery-journal-schema.md`. Update the marker in `deployment/game-service/proxy/README.md` and `containers/local/match-review.md`.
- **Client.** Keep the strict decoder in `current-matches-protocol.ts`. Accept the new fields only in responses to the opt-in request.
- Commit this spec as `docs/specs/expired-match-lobby.md` on the topic branch.

## Validation

- Server tests with a controlled clock for each deadline kind, the computer proposal and reaction ordering, the cold-load expired path and every acknowledgement response.
- MariaDB tests: existing membership rows after migration, two coaches, repeated and concurrent acknowledgements, pagination across acknowledged rows, and the query plan.
- A migration test from a populated marker 10 database, following the schema-boundary test added in #575.
- Script tests showing the game-clearing, review-provisioning and index-review tools accept marker 11, and the clearing tool still preserves accounts, teams and preferences.
- Browser checks: countdown, the recheck at zero, the expired lobby card, the expired direct link, retry after a failed save, and narrow and desktop layouts.
- Cross-device: two independent browser contexts for one account and a separate session for the opponent.
- A tab from the previous build against the new server.

## Completion Notes

At delivery, record the focused server, MariaDB and browser results, build results, the migration check and evidence of the cross-session check in `docs/specs/expired-match-lobby-outcome.md`.
