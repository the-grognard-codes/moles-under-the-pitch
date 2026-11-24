# Maven bootstrap cache outcome

[Issue #620](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/620) · [PR #624](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/624)

Validate and all four Java state-test jobs restore the Maven ZIP before bootstrap. The cache key hashes the toolchain manifest. The jobs request one dependency cache key, with `Linux-java21-` as the restore prefix. The shards populate that cache with dependencies from a successful full reactor build.

Bootstrap retries rate limits, server errors, and interrupted transfers, with a maximum of five attempts per source. It accepts `Retry-After` seconds and HTTP dates and caps waits at 60 seconds. It tries the Apache archive if the primary download is unavailable and moves downloads into the cache only after checking SHA-512. A checksum mismatch stops bootstrap and reports the last error from each source tried. The Apache archive matched the pinned hash independently.

## Acceptance evidence

| Check | Result |
| --- | --- |
| Local bootstrap and tooling | 24 checks passed in PowerShell 7 and Windows PowerShell 5.1. Covered offline setup, HTTP 429, retry delays and the cap, fallback, checksum rejection, interrupted transfers, and temporary-file cleanup. |
| Workflow syntax | `actionlint` 1.7.12 and `git diff --check` passed. |
| First Checks run | Passed. Validate saved `Linux-maven-archive-c715195e…`. All five Java jobs requested `Linux-java21-0fa7f88c…`, which shard 2 saved after its build. Validate passed all 24 tooling checks on Linux. [Run 45251095733](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45251095733) |
| POM-only Checks run | Passed. All five Java jobs used the cached ZIP, requested `Linux-java21-e0e4d17f…`, and restored `Linux-java21-0fa7f88c…` via the prefix. The bootstrap logs show ZIP reuse. [Run 45252595143](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45252595143) |
| Checks after reverting the POM probe | Checks and all 14 required checks passed. All five Java jobs restored both exact cache keys. [Run 45254228574](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45254228574) |
| Review follow-up `754d16df7` | All 25 tooling checks passed in PowerShell 7, Windows PowerShell 5.1 and Linux Validate, including corrupt-primary and corrupt-fallback diagnostics. All 14 required checks passed. [Run 45277237864](https://github.com/the-grognard-codes/moles-under-the-pitch/actions/runs/45277237864) |

The warm-cache logs establish that bootstrap used the cached ZIP. The Maven launcher suppresses dependency transfer progress, so Maven's own network traffic is outside the scope of this evidence.

The probe added a temporary XML comment to `pom.xml`. The revert at `18d89f0a2` restores the exact implementation tree from `019d3c302`. The workflow runs under the established check names with the pinned Maven 3.9.9 and Java 21 toolchain.

## Review decisions

The maintainer decided on the PR that a checksum mismatch should fail the step, to be rerun by hand, rather than try the fallback or retry automatically. On CI's PowerShell 7, a truncated transfer raises a network error, which bootstrap retries, so a mismatch means wrong content rather than an interrupted download. The review round also brought `tools/README.md` and `CHANGELOG.md` up to date with the new behaviour.

## Merge

PR #624 merged as `1c6015f298ab1813366e00ea540e58284f8c58ca` on 2026-11-23 at 22:47 UTC. Issue #620 closed as completed.

## Remaining gaps

None of these blocked the merge.

- `tools/README.md` says the loopback tests cover "the Apache fallback path". They use only `127.0.0.1` URLs. Nothing checks that the archive.apache.org URL exists or serves the pinned ZIP, so a broken fallback would show only when Maven Central throttles CI.
- No test covers the audit-archive branch of `Install-Archive`. The tooling fixture has no `.notes/overhaul-analysis/verification` folder, so the hash check before the copy never runs in the suite.
- `Install-Archive` copies the audit archive straight to `.tools/downloads`, without a temporary file. An interrupted copy leaves a partial ZIP there, and later runs stop with "remove this archive and retry" until it is deleted by hand. This predates #620.
