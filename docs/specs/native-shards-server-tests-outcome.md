# Java server test sharding outcome

[Issue #621](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/621) · [PR #625](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/625) · Commit `58f48d556`

The four Java jobs in Checks each run `clean verify` on the full Maven reactor. Surefire uses the shard's class list in both `ffb-server` and `ffb-statetest`. The eight `ffb-common` suites run in every job. Each shard must include classes from both filtered modules. Plain `verify` uses Surefire's full test discovery.

The shard runner checks the source inventory, module ownership and Surefire XML before publishing schema 2 reports. The aggregate gate requires every suite in its assigned shard. Existing opt-in method skips have exact allowances. Four existing database suites sometimes produce empty reports when their `@BeforeAll` assumptions abort. The reports identify the absent prerequisite for those four suites. Other empty reports fail validation.

## GitHub Checks

Each run used `ubuntu-24.04`. Job durations come from GitHub's job start and completion timestamps. All four shards and the aggregate coverage gate passed. The four artifacts contained 201 unique suites that matched the source inventory, including 148 server suites and 53 state-test suites. [Baseline Checks](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45222166871) · [PR Checks](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45253080124)

| Shard | Server suites | State suites | Baseline | PR #625 | Time saved |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 36 | 12 | 6m20s | 5m13s | 1m07s |
| 2 | 36 | 14 | 7m11s | 5m19s | 1m52s |
| 3 | 39 | 14 | 7m11s | 5m14s | 1m57s |
| 4 | 37 | 13 | 8m25s | 5m40s | 2m45s |

The slowest job improved by 165 seconds against the 120-second acceptance target. The assignments were balanced at about 174 seconds of estimated suite work each, using timing logs and reports from the baseline run.

## Local verification

- Passed 21 native shard fixture checks in PowerShell 5.1 and 7, and 16 Node validation tests. A real shard invocation rejected an omitted server class before starting Maven.
- Passed 588 browser-client unit tests, 13 site unit tests, the site build, and all 13 hosted browser suites.
- Unsharded `tools/target-build.ps1 verify` passed with 148 server, 53 state-test, and 8 common suite reports.
- All four local shards passed sequentially in 333, 279, 313, and 278 seconds. The Surefire XML filenames matched the expected module and class inventory for every shard. The local aggregate gate accepted all 201 assigned suites at commit `58f48d556`.

The implementation affects CI scheduling and test reporting. No dev-local startup was required. All 14 required PR checks passed. The PR is open and ready for independent review.

## Review follow-up

Commit `2b0742fbf` documents and verifies the 19 method and four class allowances, makes report schema selection explicit, and updates the fixtures, Maven profile comments, workflow step label, and changelog. The four class allowances still depend on missing database prerequisites because Surefire omits the class-level abort cause. [Opt-in allowance review](https://github.com/the-grognard-codes/moles-under-the-pitch/blob/2b0742fbf/docs/specs/native-shard-server-opt-ins.md) · [Issue discussion](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/621#issuecomment-7116082335)

- Passed 28 Node tests, 21 native fixture checks in each PowerShell version, and 13 hosted browser suites. The revised aggregate validator also accepted all four saved local native reports from the original verification.
- All 14 required checks passed on the review commit. The four Java jobs finished in 6m17s, 4m39s, 4m33s, and 5m03s. The slowest stayed 2m08s below the 8m25s baseline. [Review commit Checks](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45282054662)
