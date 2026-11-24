# Skip Checks jobs when Java is not required

[Issue #622](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/622) · [Maintainer scope update](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/622#issuecomment-7118625161)

## Objective

Save Java runner time on pull requests confined to documentation, notes, and Development history. Keep the required check contexts, full push-to-main and manual validation, and the DEV deploy precondition. The main ruleset requires Java 21 target verification. The four matrix jobs can therefore be skipped at job level.

## Requirements

- Classify the complete pull request diff against the event's base, using only contents read permission. An inconclusive or failed classification requires full Java verification.
- Allow paths under `docs/` or `.notes/` and Markdown files. Add precisely `site/updates.json` and `site/src/development/index.html` for Development history. Every changed path must qualify.
- Require Java for any other site path, Java source anywhere, files under `tools/` or `.github/workflows/`, and the Maven bootstrap audit directory at `.notes/overhaul-analysis/verification/`. Verify that the skipped jobs do not read new allowlisted paths. Check both paths of a rename.
- Publish `java_required=false` only after successful classification of an allowlisted pull request. Pushes and manual dispatches always require Java. Skip Validate, the native matrix, and Firebase game session service when Java is not required.
- Keep Java 21 target verification required. Log **Java not required** when the matrix is intentionally skipped. When Java is required, reject failed, skipped, cancelled, or missing shard execution.
- Keep the non-Java jobs and deployment precondition in place. Static checks verify that Development history matches the generated page.

## Acceptance

- A docs-only PR reports each required check and runs no Maven.
- A PR with both Markdown and Java changes runs full Java verification.
- A browser fixture change runs full Java verification.
- A PR with only the two Development history paths skips Java. Adding another site path runs full Java verification.
- A push to main runs full Java verification and remains eligible for the DEV deploy precondition after Checks succeeds.
- Cancellation of a shard fails Java 21 target verification whenever Java is required.
- Classifier tests cover the allowlist, mixed changes, workflow changes, and a failed diff.

The Maven cache and ffb-server shard changes in [#624](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/624) and [#625](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/625) merged before this change. [Outcome and verification](checks-skip-java-docs-only-outcome.md)
