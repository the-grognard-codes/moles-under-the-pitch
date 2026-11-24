# Java Checks classification outcome

[Issue #622](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/622) · [Spec mirror](checks-skip-java-docs-only.md) · [Validation design](parallel-validation.md)

Checks classifies the full pull request diff from the merge base to the event's head commit. The allowlist covers documentation, notes and the exact Development history paths `site/updates.json` and `site/src/development/index.html`. The shard workflow scans archived Java sources for disabled tests, and bootstrap can read its audit ZIP from `.notes/overhaul-analysis/verification/`. Those Java paths and the audit directory require full verification. Static checks still compare the generated Development page to its history.

A successful allowlisted classification logs **Java not required** and skips Validate, all four Java shards, and Firebase game session service. The required Java 21 target verification check confirms the intentional skip. Other changes, inconclusive classifications, pushes, and manual dispatches run the full Java path. The gate verifies the native execution dependencies and all four reports. A classifier failure alone cannot invalidate successful native execution.

The active ruleset requires the aggregate check. The review update incorporates merged [PR #625](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/625) at `4b22def0`, so the combined workflow verifies the updated shard inventory.

## Acceptance evidence

The hosted probes use a temporary base with a branch trigger added to Checks. They exercise the same Java job conditions as PRs and pushes against main. The original probes predate the merge of #625.

| Criterion | Evidence |
| --- | --- |
| Docs-only PR runs no Maven and can merge | Validate, the matrix and Firebase game session service reported skipped. The required aggregate passed and logged the intentional skip. All other Checks jobs passed. The first docs-only PR against protected main will verify the full ruleset. [Probe](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286459816) |
| Mixed Markdown and Java runs full verification | All Java jobs ran. The gate rejected shard 3 after a movement test assertion failed. The same test passed in all four shards of the push probe. [Probe](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286466024) |
| Browser fixture change runs full verification | All Java jobs ran. `PassWorkflowTest` rejected the extra newline in the fixture, and the aggregate rejected the failed shard. [Probe](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286474490) |
| Development history can skip Java, and another site path requires it | The classifier tests cover the exact pair, the pair with another site file, and similar path names. |
| Main push runs full verification and the DEV precondition works | Validate, Firebase game session service, and all four shards passed on the temporary push. The aggregate verified the four reports. Source review confirmed the DEV precondition still checks Checks success on the exact main commit. The first main push after merge will provide the run the DEV precondition expects. [Probe](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286281694) |
| Cancelling a shard fails the gate | The local gate test rejects a cancelled matrix result and a missing shard report. The mixed and fixture probes exercised failed shards on GitHub. Individual shard cancellation was covered locally through the matrix result. |
| Classifier coverage | Node tests exercise the allowlist, mixed changes, a workflow path, archived Java, the bootstrap audit ZIP, a failed diff, shallow history, and renames. The workflow-only hosted probe passed all Java jobs and the aggregate. [Probe](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286481358) |
