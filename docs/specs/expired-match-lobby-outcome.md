# Match resume deadlines and expired matches

[Issue #639](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/639) specifies two reviewable slices.

## Countdown and expired state

Activated matches show a countdown after five quiet minutes. The client anchors each page's deadlines to its server clock sample and tracks elapsed time with a monotonic clock. At zero the lobby withholds Resume and Concede while it requests the server's decision. The direct link explains expiry and clears a stale retained intent for that account and match. Expired matches stay listed in this slice.

Two-coach matches count 30 days from the last human action, including resume requests, rejections and cancellations during suspension. A suspended computer match counts from suspension. Countdown labels round down and omit zero lower units. The final minute displays `0m to resume` until the status recheck. Pending computer timeouts keep their ordering. Cold reads used by the lobby and direct link check the committed state without writing a checkpoint.

| Check | Result |
| --- | --- |
| Pinned Java tests | 111 distinct tests passed across BrowserV2AdapterTest, SetupApplicationCheckpointTest, SetupApplicationReadOnlyLoadTest and SetupBrowseSummaryTest. |
| Native recovery scenarios | Two RecoveryApplicationTest end-to-end scenarios passed for expired active and suspended games, including the unchanged checkpoint on cold load and durable abandonment on the next resident request. |
| Browser contracts and types | 80 focused Node tests passed. TypeScript typecheck passed. |
| Browser interactions | Chromium at 390 and 1440 pixels covered device clocks ten minutes ahead and behind, both zero-time outcomes, direct-link cleanup and fallback to a prior server. Lobby read bounds and preparation lifecycle drivers passed. |
| Dev-local | Quick restart built the browser and server, verified engine health and Firebase ADC, and reached the Java authentication gate twice through the proxy. |
| Local index maintenance | Five tests passed, including startup against marker 10. |

The unextended current-games request retains its previous response shape. The browser compatibility checks use protocol fixtures. Android, Linux and all theme combinations were not separately exercised on physical devices.

## Durable acknowledgement

[Countdown PR #643](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/643) and [acknowledgement PR #644](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/644) deliver the two approved slices. Merge the countdown first.

A coach can acknowledge an expired match from either surface. The server authorizes the session, checks the same expiry predicate as the lobby, and records the first acknowledgement on that account's membership. Repeating the request after a lost commit response returns success. The account index includes acknowledgement state and match ID so the database can seek visible memberships before applying its page limit.

| Acceptance check | Evidence |
| --- | --- |
| Migration and concurrency | A populated MariaDB marker-10 fixture migrated to 11 with identical checkpoint and journal bytes. Two concurrent writes and a later retry retained one timestamp. The opponent kept access and an independent listing. |
| Pagination | With 103 acknowledged and 102 visible matches, the first query returned the 100 entries and lookahead. The next returned the final two. EXPLAIN used the new account index without a filesort. |
| Authorization and failures | The 42-test V2 adapter suite passed. It covers unrelated accounts, live-match refusal, repeat acceptance, storage failure, uncertain commit, reauthentication, and the old request shape. Four focused JDBC membership tests passed alongside the real MariaDB test. |
| Runtime schema support | 34 recovery and saved-team tests passed, including marker 11 acceptance and marker 12 rejection. Local schema, acceptance-bootstrap and access selectors ran 21 tests with 20 passes and one optional environment-specific check skipped. The isolated acceptance server bootstrapped and started against real marker 11 storage. |
| Tooling | All 21 game-clearing, review index and review migration tests passed. Removing memberships clears their acknowledgement timestamps. Accounts, teams and preferences remain outside the game cleanup. |
| Dev-local migration | Backed up the stopped-service marker-10 database and verified the gzip stream, schema contents and dump footer. Migration preserved the membership count, initialized every existing acknowledgement to null and advanced the single marker to 11. The quick startup built the client and server, verified engine health and Firebase ADC, and exercised reconnect through the proxy. |
| Browser behavior | TypeScript and 83 focused tests passed. Chromium tested exact request-ID reuse after a failed lobby save and an uncertain direct-link commit. Both views remained usable after failure. |
| Cross-device and mixed versions | Independent Chromium contexts signed in with synthetic Firebase identities against the native V2 server and MariaDB. One coach removed an expired match from the desktop lobby. A fresh same-account sign-in and reload omitted it. The opponent saw it in a narrow lobby and removed it through the cold direct link. The actual previous client at `47d08f3d4` listed the game through the legacy request. The server returned `MATCH_NOT_EXPIRED` for a live match and `NOT_FOUND` for an unrelated account. Checkpoint generation and bytes, including transcript and chat, stayed unchanged after acknowledgement. |
| Layout and themes | Both surfaces passed 12 Chromium viewport and light/dark system preference combinations. The site's fixed dark theme retained readable controls, at least 4.5:1 tested contrast and no horizontal clipping. Windows Chrome ran natively. Linux used a browser profile, and Android phone and tablet used device emulation. |

The real-browser test used a synthetic activated match with its retention clock aged offline on a stopped, isolated service. The final run passed after an earlier startup sign-in failed. Physical Android and Linux OS behavior and a restore of a production backup remain outside the observed evidence.
