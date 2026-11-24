# Feature: Stop Checks failing on Maven Central rate limits

Issue: #620

## Abstract

Four Checks runs on 22 and 23 November failed before any test ran. `tools/bootstrap.ps1` got HTTP 429 from Maven Central while downloading the pinned Maven distribution. Every Java job downloads that archive on every run, without a cache or a retry.

Cache the archive, retry with backoff, and add a second source. Separately, let a POM change start from the previous dependency cache, so five jobs don't download the whole dependency tree from Maven Central at the same moment.

## Objective

A Checks run doesn't fail because Maven Central throttles the shared runner address, and a run with warm caches sends no request to Maven Central.

## Context / Existing Behavior

- **The failures:** each failed job stopped in **Set up project-local Maven**. `Invoke-WebRequest` reported `Your ip has exceeded rate limits` and linked to Sonatype's 429 FAQ.

  | Run | Event | Failed with 429 |
  | --- | --- | --- |
  | 44981117319 | push to main | shards 1 and 4 |
  | 45121796464 | PR #613 | shard 3 |
  | 45185215587 | PR #613 | shard 4 |
  | 45220230458 | PR #608 | shard 1 |

- **The download:** `Install-Archive` (`tools/bootstrap.ps1:16`) works as follows:
  - it reuses `.tools/downloads/<archive>` if that file exists (`:18`);
  - otherwise it copies the audit archive, if there is one (`:19`);
  - otherwise it downloads `maven.url` from `tools/target-build-toolchain.json` once, straight to the archive path, with no retry (`:27`);
  - it checks the SHA-512 before extracting (`:30`).
- **Where it runs:** **Validate** (`.github/workflows/maven-verify.yml:61`) and each of the four **Java 21 state tests** jobs (`:101`) run bootstrap. That makes five downloads per Checks run. GitHub's hosted runners share outbound addresses, so other repositories' traffic counts against the same per-IP limit.
- **The cache:**
  - Each job's `actions/cache` step comes after bootstrap and covers only `.tools/repository` (`:67`, `:105`).
  - Validate's key is `browser-runtime-<hash>` (`:70`). The shards' key is `java21-<hash>` (`:109`). Both hash the same files.
  - Neither key has `restore-keys`, so a POM change misses both caches and every job resolves the full dependency tree from Maven Central.
- **Dependencies today:** in a passing run on main (45222166871), every shard restored the exact `java21-` key.
- **Tooling tests:** `tools/test-tooling.ps1` runs bootstrap offline in a throwaway fixture. It checks that a missing archive is rejected, that a checksum failure prevents extraction, and that the pinned archive is accepted (`:35-45`).

## In Scope

- **Cache the archive:**
  - restore `.tools/downloads` before bootstrap runs in Validate and in each shard job;
  - key the cache on `tools/target-build-toolchain.json`, kept separate from the dependency cache.
- **Retry:** `Install-Archive` retries HTTP 429 and 5xx responses and connection failures, with bounded exponential backoff that honours `Retry-After`.
- **Second source:**
  - the toolchain manifest lists a fallback URL for the same archive: `https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip`;
  - bootstrap tries the fallback when the primary URL keeps failing after its retries.
- **Dependency cache:**
  - add `restore-keys: ${{ runner.os }}-java21-` so a POM change restores the previous repository and fetches only what's new;
  - Validate and the shards use the same key.

## Out of Scope

- Changing the pinned Maven or JDK versions.
- The **Firebase game session service** job, which uses the runner's Maven and the `setup-java` cache.
- A Maven repository mirror or proxy.
- Which ffb-server tests each shard runs (#621), and skipping Java checks on documentation-only PRs (#622).

## Functional Requirements

- **Integrity:**
  - bootstrap verifies the SHA-512 before extracting, whichever source or cache supplied the archive;
  - a corrupt archive fails as it does today.
- **No partial files:** a download goes to a temporary file and moves to the archive path only after its SHA-512 matches. A failed or partial download never stays at the archive path, where a later run or cache save could pick it up.
- **Offline mode:** `-Offline` never touches the network.
- **Retry limits:**
  - at most 5 attempts per URL;
  - a single wait of at most 60 s, including waits that `Retry-After` asks for;
  - after the last attempt, bootstrap names every URL it tried and the last status for each.
- **PowerShell versions:** works on Windows PowerShell 5.1 (`#Requires -Version 5.1`) and on pwsh 7. `Invoke-WebRequest -MaximumRetryCount` exists only in pwsh 7, so the retry is a loop in the script.
- **Check names:** no check name changes.

## Acceptance Criteria

- [ ] In a Checks run with warm caches, no job logs `Downloading https://repo.maven.apache.org/...`.
- [ ] `tools/test-tooling.ps1` covers these cases against a local HTTP stub, never Maven Central:
  - a 429 followed by success;
  - `Retry-After` is honoured, up to the cap;
  - the fallback is used after the primary keeps failing;
  - a bad archive from the fallback is rejected;
  - no partial archive is left behind.
- [ ] A POM-only change restores the previous dependency cache (`Cache restored from key: Linux-java21-<previous hash>`) and the run passes.
- [ ] Validate and the four shards use one dependency cache key.
- [ ] The existing offline bootstrap checks still pass.

## Constraints

- Keep every required check name.
- Don't write secrets or account identifiers to the toolchain manifest or logs.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- `[System.Net.HttpListener]` on `localhost` gives `test-tooling.ps1` a stub server without adding a test framework, and it runs under both PowerShell editions.
- Read `Retry-After` as either a number of seconds or an HTTP date.
- A list of URLs in the manifest (`urls`, with the current `url` first) keeps the order explicit. If the schema changes, update every reader of `maven.url`.

## Validation

- Create new or update existing regression tests as required.
- Run `tools/test-tooling.ps1` locally on Windows and in the Validate job on Linux.
- Run Checks twice on the branch: the first run warms the archive cache, and the second run's logs must show no Maven Central download.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
