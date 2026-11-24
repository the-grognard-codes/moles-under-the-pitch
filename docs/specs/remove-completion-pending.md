# Feature: Remove COMPLETION_PENDING handling from the browser and the computer player

Issue: [#653](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/653)

Part of #651 (ticket 2). Moved from #548.

## Abstract

#537 changed deferred completion so that the server no longer sends `COMPLETION_PENDING`. The browser, the computer player, the soak driver and their tests still handle it. Remove that handling.

## Objective

No client code, test or current guide treats `COMPLETION_PENDING` as a live code, and full time works as it does today.

## Context / Existing Behavior

Line numbers are at `main` `eb5057d02`.

- **Server:** nothing under `ffb-server/src/main` mentions the code. #537 (merged 2026-11-15, `0261fd53e`) removed the reply and renamed the log label to `COMPLETION_DEFERRED`.
- **Browser:** `setup-protocol.ts:15, 39` (the code type and the accepted set), `setup-recovery.ts:28` and `v2-client.ts:31` (outcomes treated as uncertain), and `browser-client/disconnect.md:31`.
- **Computer player:** `computer-player/src/main.mjs:150` yields to the daemon on this code or on `PERSISTENCE_FAILED`.
- **Tests and soak:** `browser-client/test/setup-recovery.test.ts:9`, `soak-retained-requests.test.ts:116-157`, `overnight-soak.mjs:356-358`, `computer-player/test/transport.test.mjs:125`, and `RecoveryApplicationTest.java:536`.
- **Historical reports** under `docs/` and `.notes/` mention the old code and stay as they are.

## In Scope

- Remove the code from the browser protocol, the uncertain-outcome sets, the computer player, the soak driver and the tests.
- Update `browser-client/disconnect.md`.

## Out of Scope

- The server's deferred completion, its retries and its log labels.
- `PERSISTENCE_FAILED` and `MATCH_OUTCOME_UNKNOWN`, which stay uncertain outcomes.
- The full-time redesign (ticket 10 of #651).

## Functional Requirements

- A completed match still shows full time in the browser, including after a deferred completion.
- The computer player still ends its work at full time, and still yields on `PERSISTENCE_FAILED`.
- A retained request is kept and resolved exactly as today for the remaining uncertain codes.

## Acceptance Criteria

- [ ] `git grep COMPLETION_PENDING -- browser-client computer-player ffb-server ffb-statetest` finds nothing.
- [ ] A test shows full time reaching the browser after a deferred completion.
- [ ] The soak's retained-request tests cover the remaining uncertain codes, and still recognize a refused request at full time.
- [ ] The computer player's transport test covers `PERSISTENCE_FAILED`.

## Constraints

- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- Decoders are strict, so check that no recorded fixture still contains the code before removing it from the accepted set.
- Where a test used the code only as an example of an uncertain outcome, use `PERSISTENCE_FAILED` or `MATCH_OUTCOME_UNKNOWN` instead of dropping the case.

## Validation

- Create new or update existing regression tests as required.
- Run the browser unit tests, the computer-player tests and the focused state tests.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
