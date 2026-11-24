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
