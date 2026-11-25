# Feature: Show the kick-off event splash before the event resolves

Issue: [#647](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/647)

Blocked by: #648 (fixed splash times)

Related: #565 (animated splashes, which set the current order), #603 (touchdown boundary before cleanup decisions), #547 (announcement timing tests), #588 (decision-time clocks).

## Abstract

The kick-off event splash appears after the event has already played out. Show the 2D6 kick-off roll on the pitch, then the splash once the dice have faded, then the event's results.

## Objective

Coaches and spectators learn which event was rolled before they see or choose anything it causes, in live play and forward replay.

Every kick-off event:

1. The ball is kicked and deviates.
2. The 2D6 kick-off dice roll, settle and fade away.
3. The kick-off event splash plays and exits.
4. The event's results play: its follow-up dice, choices and effects.
5. The ball lands, any touchback is resolved, and START OF TURN shows.

Changing Weather replaces step 4 with:

1. The weather dice roll, settle and fade away.
2. The weather splash shows the resulting condition, even when it is unchanged, and exits.
3. If the result is Nice, the ball scatters.

### Maintainer decisions (2026-11-24)

- The splash belongs at the moment the 2D6 decides the event. This replaces the order in #565.
- The 2D6 roll is shown as dice. The splash starts when they have faded away.
- Changing Weather uses the sequence above, with a weather splash even when the condition is unchanged.
- Changing Weather needs new artwork and an art review. See [Art review](#art-review).
- The kick-off and Changing Weather dice stay settled for 1.0 s instead of 2.0 s. Other dice are unchanged.
- Splashes play for a fixed time with no dismissal. That is #648, which lands first.
- ADR 0007 is amended so that the kick-off and Changing Weather splashes hold later results. See [ADR 0007 amendment](#adr-0007-amendment).

## Context / Existing Behavior

- **Documented order.** `docs/specs/animated-match-splashes.md:135` (requirement 3) shows the kick-off result after its event resolves.
- **Server cause.** `StepKickoffResultRoll.java:99-102` reports the result at the roll. `MatchAnnouncementPresentation.java:46-52` stores it in `pendingKickoff`, and `playable()` (`:129-145`) releases it at the first playable turn. A weather change is stored in `deferredWeather` the same way (`:60-61`).
- **Sync points.** `StepExecutor.java:150` syncs after every step, so the roll is already sent ahead of the effects.
- **Browser playback.** The loop starts a splash and continues without waiting (`use-pitch-playback.ts:342-360`), so automatic results would play behind the card.
- **Choices already wait.** Dialogs and board input are withheld while a splash is active (`GameView.tsx:1050`, `:1178`).
- **No kick-off dice.** `ReportKickoffResult` carries the 2D6 roll, but `NumericDicePresentation.java` and `reportDiceValues` (`dice-presentation.ts:165`) don't present it.
- **Dice timing today.** Dice fade in and tumble together, settle at 620 ms, stay settled for 1,980 ms, then fade for 200 ms. They are gone 2,800 ms after they appear (`dice-stream.ts:68-70`, `presentation-timing.ts`). Playback moves on after 1,000 ms (`DICE_GROUP_INTERVAL_MS`), while the dice are still visible.
- **Changing Weather card.** It shows the resulting condition and its art (`MatchAnnouncementPresentation.java:136-138`, `MatchSplash.tsx:78-85`). `weather()` ignores an unchanged condition (`:56`).
- **Nice scatter.** `StepApplyKickoffResult.java:644-706` rolls the weather and the scatter in one sync, so the browser cannot place a splash between them. Two reports in one sync also skip the square-by-square scatter (`pitch-playback.ts`, `reports.length === 1`).
- **Tests that pin the old order.** `MatchAnnouncementPresentationTest.java:266` and `:362`, `browser-client/test/fixtures/match-splash-events.json`, and `browser-client/test/match-announcements-ui.mjs:400-415`.

## In Scope
- Send the kick-off announcement with the roll, for all eleven BB2025 events.
- Show the 2D6 kick-off roll as dice.
- Wait for the kick-off and Changing Weather dice to fade, then hold later results until the splash has exited.
- The Changing Weather sequence and its new artwork.
- Recovery of matches saved between the roll and the first turn.
- The ADR 0007 amendment, and updates to `animated-match-splashes.md`, `match-transition-announcements.md` (rule 5 and its summary), `dice-effects-visible-duration.md` and `polygon-numeric-dice-inventory.md`.

## Out of Scope
- Kick-off event rules, dice odds, choices and limits.
- The order or duration of other splashes, and the visible time of other dice.
- The initial weather roll at the start of a match.
- Removing splash dismissal (#648).
- Making the server wait for a splash. Computer coach pacing is #649.

## Functional Requirements
- **Server emission.** The kick-off announcement is attached to the sync that carries the roll, before `StepApplyKickoffResult` starts, and is recorded once per kick-off event.
- **Kick-off dice.** The 2D6 roll shows as one group. Its caption names the roll, not the event, so the splash makes the reveal. When an overtime option fixes the event, or a coach chooses it, no dice show and the splash comes first.
- **Dice time.** The kick-off and Changing Weather dice stay settled for 1,000 ms, and are gone 1,820 ms after they appear. Other dice keep their current time. Replay speed scales it.
- **Waiting and holding.** The splash starts when its dice have gone. Until it has exited, no later dice, board change, choice or state change is presented: player states, bribes, rerolls, turn counters, weather and ball position included. The held results then play in order and none are skipped.
- **Changing Weather.**
  - The kick-off card names the event and does not show the condition.
  - The server sends the weather result on its own sync before any Nice scatter.
  - A weather announcement is sent for every Changing Weather roll, including an unchanged condition.
- **Hidden splashes.** If the coach hides that kind of splash, nothing is held for it. The dice still show.
- **Log.** The match log lists the kick-off event before its results.
- **Recovery.** A restored match does not repeat the announcement. A match saved by the current build with `pendingKickoff` or `deferredWeather` set still restores, and announces each at most once before START OF TURN.
- **Existing guarantees.** Event-id dedupe, team attribution, reduced motion, catch-up never shortening a splash, and no splash on load, reconnect or replay seek are unchanged.

### Art review

- New emblem for the Changing Weather splash, showing several possible conditions together (for example sun, rain and snow).
- It matches the existing splash art: theme, palette, outline, pixel scale and canvas, following `assets/game/ui/match-events/README.md`.
- Codex creates it, shows it in the splash review page beside the existing kick-off and weather cards at all four viewport sizes, and posts captures on the issue.
- **The maintainer approves the art before it is coded into the game.** The rest of this work can proceed while the art is in review. Until then the card uses `dice-events-v1/weather.svg`, which the code already falls back to.

### ADR 0007 amendment

Ordering rule 4 keeps presenting queued moves and dice while a splash plays. Add, with the implementing PR:

> Exception: the kick-off event splash and the Changing Weather result splash are presentation barriers. The client starts each after its dice have faded and holds later outcomes until it has exited, then presents them in order. Holding never discards or reorders the backlog and never delays the server, a computer coach or another client.

## Acceptance Criteria
- For each of the eleven events, a native test shows the announcement on the sync containing `kickoffResult`, exactly once, with no event result before it.
- For Solid Defence, High Kick, Quick Snap and Charge, a browser test shows dice, splash, then choice, including when the splash and the choice arrive in one update. The splash names the same team as today.
- For Time-out, Brilliant Coaching, Dodgy Snack and Pitch Invasion, a browser test shows that counters, rerolls, player states and follow-up dice do not change until the splash has exited, and none are skipped.
- A Changing Weather kick-off shows the kick-off dice, the event splash, the weather dice and the weather splash, each after the previous one has gone. An unchanged condition still shows its weather splash. A Nice result scatters the ball square by square after the weather splash exits.
- The splash never overlaps its dice, at live speed, at replay speeds and with reduced motion.
- The order holds when results arrive in later revisions, during catch-up, against a computer coach, and for spectators.
- START OF TURN follows all results and touchback choices. Charge free turns still show none.
- Restoring between the roll and the first turn produces no duplicate or late splash, for a checkpoint from this build and one from the current build.
- The new Changing Weather art has recorded maintainer approval on the issue before it is merged.

## Constraints
- Ensure the themes are all supported on Android, Windows, and Linux
- MUTP colors, typography, style, and themes shall be preserved. Reuse CSS and other UI/UX scaffolding where appropriate.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested. An implementing agent picking up a `ready-for-agent` issue branches, commits, pushes and opens a PR that references this issue. It never merges.
- Announcements come from authoritative server events, never inferred from dice or the log.
- No engine sleep, retry or blocking acknowledgement on the single engine worker (ADR 0006).
- Stored transcript and recovery formats stay readable.

## Implementation Guidance
- **Server.** `kickoff()` can add its event straight to `pending`, so the roll step's own sync carries it. Sync after the weather report and before the scatter, as the Solid Defence, Quick Snap and Charge handlers already do.
- **Browser.** Make these two announcements barrier beats: `advance()` stops, and resumes from the next index when the card has exited. The dice wait needs the dice stream to report that its groups have gone.
- **Dice time.** Add a per-group retention beside `EMPHASIZED_DICE_RETENTION_MS` and leave `DICE_RETENTION_MS` alone.
- **Pacing.** A normal event reaches its results about 4.7 s after the dice appear. Changing Weather takes about 9.3 s, plus about 3.8 s for a Nice scatter. At the previous 2.0 s dice time those were 5.7 s and 11.3 s. Check the Changing Weather pacing by hand.

## Validation
- Create new or update existing regression tests as required.
- Update the tests and fixture listed under Context, regenerating `match-splash-events.json` from the native test.
- Run `mvn test` for `ffb-server` and `ffb-statetest`, and the `browser-client` unit, announcement, dice, playback, playback-burst, reconnect and DOM-growth suites.
- Check live play, replay and recovery by hand for one interactive event, one automatic event and Changing Weather with a Nice result. State which checks ran.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
