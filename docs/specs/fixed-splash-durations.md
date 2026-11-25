# Feature: Show match splashes for a fixed time

Issue: [#648](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/648)

Related: #647 (kick-off splash order, to follow this), #588 (decision-time clocks), #565 (animated splashes).

## Abstract

Remove the ways a coach can close a match splash early or hold it open. Each splash plays for its standard time and closes by itself.

## Objective

Every coach sees the same splash for the same length of time, so match pacing is predictable and no setting can freeze a coach's own view.

### Maintainer decisions (2026-11-24)

- Remove splash dismissal and the "Keep announcements until dismissed" setting.
- Keep the settings that hide each kind of splash.
- Keep replay speed and replay seeking.
- Do this before #647.

## Context / Existing Behavior

- **Closing early.** Each card has a close button (`MatchSplash.tsx:95-96`), and Escape closes it (`:38-48`). Escape in the gap between two cards opens the next one immediately (`onSkipGap`, `use-pitch-playback.ts:409-411`, `:450`, `:480`).
- **Holding open.** Game Menu > Game Settings has "Keep announcements until dismissed" (`GameMenu.tsx:96-97`). It is saved as `ffb.match.announcements.hold` (`use-announcement-preferences.ts:4`) and stops the timer that closes a card (`use-pitch-playback.ts:462`).
- **Hiding.** Seven checkboxes hide each kind (`GameMenu.tsx:98`), saved as `ffb.match.announcements.hidden`. Hiding a kind while its card is showing closes that card (`use-pitch-playback.ts:421`).
- **Standard times.** `announcementReadingDuration` in `match-announcements.ts` sets the time per kind, followed by a 200 ms exit and a 350 ms gap, or 125 ms between consecutive cards. The live rate is always 1. Replay speed scales it.
- **One close path.** The automatic timer and the hide setting both call `dismissAnnouncement` (`use-pitch-playback.ts:406`), so the internal close stays.
- **Documents.** ADR 0007 refers to the until-dismissed preference at lines 13, 86-87 and 147. `match-transition-announcements.md` (lines 39, 52, 84-85, 120) and `animated-match-splashes.md` (lines 26, 37-40, 115-116, 146, 168, 201) also specify dismissal.
- **Tests.** `match-announcements-ui.mjs` presses Escape about 30 times, mostly to move past a card. `playback-burst-ui.mjs:102` clicks the close button.

## In Scope
- Remove the close button, Escape and gap-skip dismissal, the hold setting and its stored preference.
- Keep hiding by kind, replay speed, replay seeking and Skip animations.
- Update tests and documents, and amend ADR 0007.

## Out of Scope
- Changing any splash's standard time, art or order.
- Synchronizing splashes between coaches. Each browser still plays at its own pace.
- Turn-clock accounting for splashes (#588).
- Removing the hide settings.

## Functional Requirements
- A splash closes only when its standard time ends, when its kind is hidden, or when recovery, seeking or leaving the match clears it.
- There is no close button, and no key, click or tap closes a splash or shortens the gap after it.
- While a splash plays, Escape does not reach pitch shortcuts. An open dialog or the Game Menu still closes with Escape.
- The "Keep announcements until dismissed" checkbox is removed. The stored `ffb.match.announcements.hold` value is ignored and cleared.
- Hiding a kind works as it does now, including for a card already showing.
- In live play, every coach and spectator gets the same time for a given kind. Replay speed still scales it.
- Reduced motion keeps its current behavior and the same time.
- Every splash always ends by itself, including in a hidden tab or when an exit animation does not finish.
- The match log keeps the full text of each splash, and the live region announces it once.
- Board input and choices still wait for the splash, as they do now.

### ADR 0007 amendment

With the implementing PR, record a dated amendment that splashes play for a fixed time with no until-dismissed preference. Remove the until-dismissed references in the opening summary, ordering rule 3 and the decision-time clocks paragraph. #647 makes a separate amendment to ordering rule 4.

## Acceptance Criteria
- For each kind, a browser test shows the splash lasts its standard time with no close control, and that Escape, clicks and taps do not shorten it or the following gap.
- Escape during a splash does not trigger a pitch action and still closes an open Game Menu.
- With `ffb.match.announcements.hold` already saved as `true`, splashes close by themselves.
- The Game Menu shows the hide settings and no hold setting.
- Hiding a kind suppresses its splashes, and closes one already showing.
- Replay plays splashes at the selected speed, and seeking clears them.
- A hidden tab or a missing exit animation does not leave a splash or its shade on screen.
- `match-dom-growth-ui.mjs` shows no growth.
- The documents listed above and ADR 0007 no longer describe dismissal, and `CHANGELOG.md` has an Unreleased entry.

## Constraints
- Ensure the themes are all supported on Android, Windows, and Linux
- MUTP colors, typography, style, and themes shall be preserved. Reuse CSS and other UI/UX scaffolding where appropriate.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested. An implementing agent picking up a `ready-for-agent` issue branches, commits, pushes and opens a PR that references this issue. It never merges.
- No server or protocol change is expected.

## Implementation Guidance
- Remove `onDismiss`, `hold`, `onSkipGap` and `skipGap` from `MatchSplashProps` and `use-pitch-playback.ts`, and rename `dismissAnnouncement` to describe closing.
- Keep a capture-phase Escape guard while a splash is active so the key does not fall through to the pitch.
- Remove the `.match-splash-close` styles and `data-held`, and check the card layout without the button at all four review sizes.
- Update `sprite-proof-review.tsx:100` and the splash review page, which pass the removed props.
- In tests, advance the clock past a card instead of pressing Escape.

## Validation
- Create new or update existing regression tests as required.
- Run the `browser-client` unit, announcement, game-menu, playback, playback-burst, reconnect and DOM-growth suites.
- Check live play and replay by hand on desktop and Android sizes, with and without reduced motion. State which checks ran.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
