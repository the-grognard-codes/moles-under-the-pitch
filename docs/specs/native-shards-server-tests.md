# Feature: Run each ffb-server test class in one Java shard

Issue: #621

## Abstract

The four **Java 21 state tests** jobs split ffb-statetest's test classes between them, but every shard runs the whole ffb-server suite. ffb-server's suites take 3 to 4.5 minutes in each shard, longer than the shard's share of ffb-statetest.

Assign ffb-server test classes to shards through the same manifest, so that each class runs once. The shards should then finish about two to three minutes sooner, and the coverage gate still proves that every class ran.

## Objective

Every ffb-server and ffb-statetest test class runs exactly once per Checks run, in a known shard, and the coverage gate rejects a run where one is missing, duplicated, or failed.

## Context / Existing Behavior

- **Shard filter:**
  - The `native-shard` profile in `ffb-statetest/pom.xml:44-66` is the only Surefire filter. It turns on when `native.shard.includesFile` is set.
  - Each shard runs `clean verify` on the whole reactor (`tools/validation/native-shard.ps1:147`, `tools/target-build.ps1:66`), so ffb-common's and ffb-server's tests run in all four shards.
- **Discovery and validation:**
  - `native-shard.ps1` reads test sources from `ffb-statetest/src/test/java` and reports from `ffb-statetest/target/surefire-reports` (`:21-22`).
  - `Read-NativeManifest` (`:48`) requires each discovered class exactly once across the four shards.
  - `Read-NativeSuites` (`:78`) checks the reports against the shard's assignment.
  - `tools/validation/coverage-gate.mjs:24-27` checks the shard reports against `tools/validation/native-shards.json`.
- **Timings from a passing run on main** (45222166871):

  | Shard | ffb-server module | ffb-statetest module | Maven step | Job |
  | --- | ---: | ---: | ---: | ---: |
  | 1 | 3:05 | 2:00 | 315 s | 380 s |
  | 2 | 3:30 | 1:49 | 329 s | 431 s |
  | 3 | 3:31 | 1:59 | 341 s | 431 s |
  | 4 | 4:32 | 2:21 | 430 s | 505 s |

  - The same ~148 ffb-server suites add up to 178–261 s of Surefire time per shard; the spread is runner speed.
  - ffb-statetest's suites add up to 108–140 s per shard, about 485 s across all four.
  - Compiling and packaging take only seconds, so test time is almost all of the Maven step.
  - The largest ffb-server suites are `SetupSessionMovementTest` (58 s), `CaptureCheckpointWorkloadTest` (40 s) and `RecoveryJournalTest` (24 s).
- **The earlier decision:** [parallel-validation.md](parallel-validation.md) (line 23) accepted the repeated core-module tests so that each shard's build stays independent. This change keeps that: each shard still compiles and packages the whole reactor itself, and only the set of ffb-server tests it runs changes.
- **Class names:** ffb-server has 143 test classes and ffb-statetest 53, and no fully qualified class name appears in both modules. ffb-common has 8 test classes (about 6 s including compilation), and ffb-tools has none.
- **Validate** runs four ffb-server classes directly (`.github/workflows/maven-verify.yml:71-73`). This change doesn't affect it.

## In Scope

- A `native-shard` profile for ffb-server's Surefire, turned on by the same property as ffb-statetest's.
- Discovery that covers both `ffb-server/src/test/java` and `ffb-statetest/src/test/java`, and report validation that covers both modules' `target/surefire-reports`.
- Every ffb-server test class assigned in `native-shards.json`, balanced by measured duration.
- The native coverage gate requires the ffb-server suites as well.
- Updated fixtures in `tools/validation/test-native-shard.ps1`.
- An update to [parallel-validation.md](parallel-validation.md) (line 23) describing the new split.

## Out of Scope

- ffb-common's tests: they keep running in every shard.
- Changing the shard count, or sharing build output between jobs.
- Parallel test execution inside a JVM (`forkCount`, JUnit parallel mode).
- Editing, disabling, or moving any test.
- The Maven download and caching (#620), and skipping Java checks on documentation-only PRs (#622).

## Functional Requirements

- Running `tools/target-build.ps1 verify` or `test` without a shard runs every test in every module, as it does today.
- A shard run fails if any assigned class from either module is missing, duplicated, failed, unexpectedly skipped, or reported by the wrong shard.
- A new test class in either module fails validation until it's assigned to a shard. ffb-statetest classes already work this way.
- `allowedSkips` works for ffb-server classes too.
- When a shard assigns no classes from a module, the run neither passes silently nor fails spuriously. Either require at least one class per module per shard, or make that case explicit.
- The check names and the four-shard layout don't change.

## Acceptance Criteria

- [ ] In a Checks run, each ffb-server suite appears in exactly one shard's Surefire reports and `native-N.json`.
- [ ] Leaving an ffb-server class out of the manifest, or adding an unassigned ffb-server test class, fails `test-native-shard.ps1` and the shard run.
- [ ] Local `tools/target-build.ps1 verify` still runs every ffb-server test.
- [ ] On the same runner type, the slowest shard job takes at least two minutes less than run 45222166871's 505 s. Report every shard's job time.
- [ ] [parallel-validation.md](parallel-validation.md) describes the new split.

## Constraints

- Keep every required check name.
- Keep each shard's build independent: no artifacts are passed between shard jobs.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- **One includes file per shard:** each shard's includes file can list both modules' classes. Surefire matches includes against each module's own test source root, so a class from the other module simply doesn't match. Both profiles can then read `native.shard.includesFile`.
- **Schema version:** consider raising the manifest's `schemaVersion`, so that an older manifest or report fails loudly instead of validating against the narrower scope.
- **Balancing:**
  - balance with the timings above, then with the per-suite durations in the `java-native-*` artifacts, which will include ffb-server suites;
  - keep each large class whole, as the parallel-validation rules require;
  - total test work is about 700 s, so about 175 s per shard.
- **Order dependence:** each shard's ffb-server JVM will run a different subset of classes than it does today, which can expose tests that depend on running order or on state left by another class. If one appears, fix the shared state; don't pin the class to a particular shard to hide it.

## Validation

- Create new or update existing regression tests as required.
- Run `tools/validation/test-native-shard.ps1` locally.
- Run each shard locally with `tools/validation/native-shard.ps1 -Shard N`, one at a time in the same checkout.
- Run Checks on the branch and compare per-shard timings with the table above.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
