# Public match view outcome

[#660](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/660) implements the [approved public-view field list](match-public-view-allowlist.md) under [ADR 0014](../adr/0014-match-state-records-and-live-sync.md#public-view-and-coach-offers).

| Requirement | Outcome and verification |
| --- | --- |
| Derive public decisions and the decision owner from the committed engine | The native step, dialog or side identifies the decision. The deciding coach receives their own offers. Waiting coaches and spectators see the public decision and normal waiting text. Optional-square reactions keep the Charge banner and the original dice lifetime. |
| Preserve the shared pitch, guidance and revealed outcomes | Pass and Kick guidance use committed state. Native and browser tests cover ordinary blocks, Blitz, movement interruptions and both Multiple Block pools. Own-choice, uphill and mixed Multiple Block pairs carry the correct defender and chooser. The deciding pool stays pinned during live playback for either coach and spectators. |
| Keep role boundaries on live and historical reads | Both coaches, spectators and replay agree on the committed board at touchback and full time. The live save status exposes its proposal ID to coaches. The terminal push preserves each recipient's wire shape. Replay omits live save status, clocks and reaction deadlines. Staged placement remains transient. The waiting coach's movement-range inspection stays read-only and revision-bound. |
| Keep replay and game logs compatible | Public history and result replay use allowlists. Secure the Ball, kickoff deviation and pass-reaction details survive native report projection. Block choices match their target's pool, including identical faces and partial rerolls. Older transcripts retain their existing revealed reports and the choosing coach can still use old die offers when an older checkpoint lacks the initial reports. |
| Preserve durability and the existing wire contract | Recovery retains its canonical inputs and native outcomes. Checkpoint and compaction tests exercise restores and retries across changes of actor. The change uses the existing full snapshot and transcript protocols under ADRs 0002 and 0004. |

## Validation

Claude's review of the original PR head found 13 failing server tests and 18 failing state tests. The stale test fixtures read private offers from waiting seats or spectators. The checkpoint workload also read the wrong live session. The native log regression came from the omitted Secure the Ball report field.

The local Java sweep covered 991 server tests and 301 state tests. The remaining failures in that sweep came from stale expectations, regenerated fixtures, and the new parity assertions. Targeted reruns checked every affected class on the updated expectations, including the full-time publisher, Multiple Block, movement forecasts, pass and reroll fixtures, and recovered touchback, save/resume and full time. The local sweep skipped 28 opt-in server and state tests, including the MariaDB checks.

The 602 browser unit tests and all 98 computer-player tests passed, as did the browser production build. Headless Chrome verified block visibility and older replay, dice pinning across both coach roles and spectators, partial rerolls, native dice, push artwork, and the pass workflow.

## Rollout and diagnostics

Deploy the browser, game server and computer player together because both clients use the strict full-snapshot decoder. The server still reads older checkpoints. A rollback to the earlier server may reject checkpoints written with the new decision kind and recipient-scoped actions. The production worker p99 under load at the ADR 0006 envelope remains unmeasured.

The public match-log debug view keeps the revision, native command number and report ID. Operators can correlate request IDs in the existing server request timing trace when enabled. Exact accepted action IDs remain in private recovery records and are unavailable from public replay diagnostics.
