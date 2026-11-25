# Feature: Pace the computer coaches and let the player set their speed

Issue: [#649](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/649)

Blocked by: #648 (fixed splash times)

Related: #647 (kick-off splash order), #588 (decision-time clocks), #595 (computer daemon capacity), #526 and #547 (playback catch-up), #567 (training harness).

## Abstract

Computer coaches send each decision the moment the server accepts the last one, so play is too fast to follow. Make them wait for splashes to clear, leave a small gap between decisions, pause longer before selecting and moving a player, and let the human coach set the speed with a slider. Pacing exists only in live matches and cannot reach the training harness.

## Objective

A human coach can follow a computer turn decision by decision, at a speed they choose. Training, evaluation and the rules engine run exactly as they do now.

### Maintainer requirements (2026-11-24)

- A computer coach does not act until the splashes between turns would have cleared.
- There is a minimum gap between its decisions.
- Player selection and movement have a longer gap than dice choices, rerolls and similar decisions, which still have a small gap.
- The longer movement gap comes once, before a move starts, not before every square.
- A speed slider in the UI is wanted.
- **This must not affect the reinforcement learning training harness.** See [Training isolation](#training-isolation).

**To confirm in triage:** the gap values and the slider stops.

## Context / Existing Behavior

- **No pacing.** `computer-player/src/main.mjs:77-113` (`decide`) chooses and sends as soon as a state arrives. Recorded AI revisions arrive about 140 ms apart at the median (`docs/specs/gameplay-soak-review.md`).
- **Moves are one square per decision.** Bogruk picks one offered square at a time and re-plans (`bogruk-policy.mjs:142-163`). The browser walks a square in 200 ms (`PLAYER_STEP_MS`), so it falls behind and plays at 4× once eight revisions are queued (`presentation-timing.ts`).
- **The worker cannot see splashes or dice.** Its connection accepts only `register` and setup operations (`BrowserV2Adapter.java:557-598`). It receives state but no transcript records, and `main.mjs:165` ignores other messages.
- **Splash times are fixed after #648.** Today a coach can hold one open.
- **The training harness is separate.** `computer-player/training/match.mjs` calls `chooseBogrukDecision` and `chooseDecision` directly and sends to `TrainingEngineHost`. `test/engine-match.mjs` also calls the policies directly. Neither runs `main.mjs`, the daemon or the WebSocket adapter.
- **Strict policy context.** `state.computer` has an exact set of fields and is policy input (`computer-policy-protocol.ts`), so it is the wrong place for a speed setting.
- **Clocks.** The turn clock keeps running while the opponent makes an untimed decision, and during presentation, until #588 lands. A timed reaction has 30 s.
- **Worker slots.** The daemon runs four workers, and a worker keeps its slot for the whole computer turn (#595).
- **Slider precedent.** Game Menu already has a range control for skill marker size (`GameMenu.tsx:88`).

## In Scope
- Waiting for the previous result and its splashes before each live computer decision.
- Minimum gaps by kind of decision.
- A speed slider for the human coach in a computer match.
- Passing the server's presentation time and the chosen speed to the live worker.
- Tests and an import check that keep pacing out of training.

## Out of Scope
- Computer coach decision quality, policies, parameters and difficulty.
- Pacing in training, evaluation, benchmarks and headless engine runs.
- Making the server or the engine wait, or requiring a browser acknowledgement.
- Splash, dice and movement presentation times.
- Daemon capacity changes (#595) and clock rules (#588).

## Functional Requirements

### Waiting
- Before each live decision, the worker waits until the previous accepted result would have finished presenting at normal live speed, then for the gap below. The result includes its dice, movement and splashes, with their exits and gaps.
- The wait uses recorded facts and standard times. It never waits for a browser, so a slow, hidden or disconnected browser does not change it.
- A coach who hides a kind of splash gets the same wait.
- The time is measured from when the result was published. Time the policy spends choosing counts toward it, so a slow policy adds no extra delay.
- If the state changes during the wait (a save request, a concession, an expired reaction or a newer revision), the worker drops the wait, reads the latest state and chooses again. It never sends a decision chosen from an old state.

### Gaps

Proposed values at Normal speed:

| Decision | Gap |
| --- | --- |
| Select a player, declare an action, start a move, or commit to a block, blitz, foul, pass, hand-off or kick | 1,000 ms |
| Later squares of the same move | None beyond the 200 ms walk, so movement stays smooth |
| Dice choice, reroll, skill use, push, follow-up, Apothecary, Bribe, Argue the Call or other response | 600 ms |
| End an activation or a turn | 600 ms |
| Each setup or kick-off placement | 150 ms |
| Confirm setup or a kick-off event | 1,000 ms |

- A change of direction after a dodge, pick-up or other roll in the middle of a move is still part of the same move, after the roll has been presented.
- Save, resume and other match controls are answered without a gap.
- A timed reaction is answered well inside its 30 s allowance at every speed, including when splashes come first.

### Speed slider
- Game Menu > Game Settings shows "Computer coach speed" in a computer match, for the human coach only. Spectators and human matches do not see it.
- Proposed stops: 0.5×, 0.75×, 1× (Normal, the default), 1.5× and 2×. The stop divides the gaps. It does not change splash, dice or movement time, and no gap falls below 250 ms, except the 150 ms setup placement and squares within a move.
- A change applies to the computer coach's next decision. The choice is remembered for later matches.
- The server accepts the setting only from the match's human coach. It is not a game decision: it does not advance the revision and is not written to the transcript, the recovery checkpoint, the policy memory or the completed match. After a server restart the browser sends it again, and Normal applies until then.
- Keyboard, touch and screen reader use work as they do for the existing slider.

### Training isolation

Hard requirements:

- Pacing lives in its own module used only by the live worker (`main.mjs`). The policies, `chooseComputerDecision`, `training/`, `ml/`, `TrainingEngineHost`, the Bogruk engine harness, `SetupSession` and the rules engine contain no pacing, waits or speed values.
- Pacing is off unless the daemon turns it on. Any other use of the worker, including tests, soak runs and future harnesses, runs at full speed by default.
- Presentation time and speed travel beside the state for the live worker only. They are not part of `state`, `state.computer`, the projection that training reads, or any training feature, trajectory, policy memory, seed or decision hash.
- The same state gives the same decision at every speed and with pacing off.
- Timestamps in recorded computer matches are not a training input.
- A future learned coach gets pacing from the worker with no change to its policy or training.

## Acceptance Criteria
- A worker test with a fake clock shows each kind of decision waits for the previous result's presentation plus its gap, and that a changed state cancels the wait and is re-decided.
- After a turnover followed by START OF TURN, and after a touchdown, half-time, kick-off event and weather splash, the computer coach's next decision is sent no earlier than the standard time for those splashes.
- In a live match against each computer coach at Normal, with the browser in the foreground, the playback backlog never reaches the catch-up threshold during a computer turn.
- A computer move of several squares plays as one continuous walk.
- Each slider stop changes the gaps as stated and the next decision uses it. A spectator, the opposing seat in a human match and the computer connection cannot set it.
- A timed reaction is answered within its allowance at the slowest stop, after splashes.
- With pacing on at the slowest stop, a full computer turn stays within the 120 s turn allowance in measured matches. Report the measured turn lengths.
- With four computer matches active at once, report the time a computer turn waits for a worker slot, before and after.
- **Training:** a test fails if any file under `training/` or `ml/`, or any policy file, imports the pacing module. A fixed-seed training run gives the same decision hashes before and after the change, and throughput stays within the normal variation between runs. Both results are recorded in the PR.
- `npm test` in `computer-player` does not become slower because of pacing.

## Constraints
- Ensure the themes are all supported on Android, Windows, and Linux
- MUTP colors, typography, style, and themes shall be preserved. Reuse CSS and other UI/UX scaffolding where appropriate.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested. An implementing agent picking up a `ready-for-agent` issue branches, commits, pushes and opens a PR that references this issue. It never merges.
- No engine sleep, retry, client acknowledgement or added engine work beyond a small per-result calculation (ADR 0006, ADR 0007).
- The V2 protocol stays versioned and compatible (ADR 0012). Stored formats are unchanged.

## Implementation Guidance
- **Slices.** 1: presentation time and fixed gaps. 2: the slider. Slice 1 is useful without slice 2.
- **Presentation time.** The server can add one number per accepted result to the computer connection's publication, at the publisher layer. #588 needs the same deterministic presentation budget, so share one calculation, and share its timings with `presentation-timing.ts` and `announcementReadingDuration` so they cannot drift apart. If #647 lands first, include its kick-off dice and splash sequence. Otherwise #647 adds it.
- **Worker.** Schedule the send from `decide()`. Keep one scheduled decision per state so the 30 s reload and repeated publications do not restart the wait. Cancel it on close, stop and abandon. The idle lease, the 15 s response timeout and the retry of an unanswered request keep their current behavior.
- **Clock cost.** Until #588 lands, the computer's choices during the human's turn use the human's clock. Keep those to the 600 ms gap or less.
- **ADR 0007.** The design fits the ADR: the computer coach paces itself in its own process, with no acknowledgement. Add one sentence saying so.

## Validation
- Create new or update existing regression tests as required.
- Run `npm test` in `computer-player`, the `browser-client` unit, game-menu and playback-burst suites, and `mvn test` for `ffb-server`.
- Play one full match against each coach at Normal and at both ends of the slider, and state what was observed.
- Run the fixed-seed training comparison above.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
