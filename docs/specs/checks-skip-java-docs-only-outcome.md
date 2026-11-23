# Documentation-only Java checks outcome

[Issue #622](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/622) · [Validation design](parallel-validation.md)

Checks classifies the full pull request diff from the merge base to the event's head commit. A confirmed documentation-only diff skips Validate, all four Java shards, and Firebase game session service. The required Java 21 target verification check verifies the matrix was intentionally skipped and records that decision. Any other diff, an inconclusive classification, a push, or a manual dispatch requires full Java verification. The full gate still requires successful shards and all four execution reports.

The maintainer selected job-level matrix skipping in the issue discussion. The active ruleset requires the aggregate check and has no required shard contexts. The implementation keeps the existing required check names and the existing Firebase deployment precondition.

## Verification

- Self-reviewed the workflow's job dependencies and compare logic, including both paths of a rename, an older Java commit followed by a Markdown-only commit, divergent base history, and failed or shallow diffs. Checked Java test sources and POMs for documentation dependencies.
- Passed 59 tests in the affected Node tooling group. The native-gate regression tests verify rejection of a cancelled matrix result and a missing shard report.
- Passed actionlint 1.7.12 and `git diff --check`.
- Checked compatibility with the prerequisite [PR #625](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/625). The branches combined cleanly and use the same aggregate dependency contract. Land #625 first.

## Hosted acceptance

The probes used a temporary base with the same Checks jobs and a branch trigger for that base. The temporary push exercised the same Java job conditions as a push to main. Source review confirmed that the DEV deployment workflow still looks for a successful Checks run on the exact main commit.

| Probe | Result | Checks run |
| --- | --- | --- |
| Documentation only | Passed. The Java jobs reported skipped, the required aggregate passed and logged the intentional skip, and the complementary Checks jobs passed. | [45286459816](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286459816) |
| Java commit followed by a Markdown commit | All Java jobs ran. The aggregate rejected shard 3 after a movement test assertion failed. The same test passed in all four shards of the push probe. | [45286466024](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286466024) |
| Browser fixture only | All Java jobs ran. `PassWorkflowTest` rejected the probe's extra newline in the fixture, and the aggregate rejected the failed shard. | [45286474490](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286474490) |
| Workflow only | Validate, Firebase game session service, and all four Java shards passed. | [45286481358](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286481358) |
| Push | Passed. Validate, Firebase game session service, and all four shards ran successfully. The aggregate verified all four reports. | [45286281694](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45286281694) |
