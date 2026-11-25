# Open issue triage — draft comments, 2026-11-22

**Posted on 2026-11-22** by `grognard-claude[bot]`, with the maintainer's approval:
19 closes, 15 label changes and 16 comments. 53 issues remain open. Each
entry gives the action, then the comment text posted. The 2026-10-28 audit
([`open-issue-audit-2026-10-28.md`](open-issue-audit-2026-10-28.md)) reserved
those remaining issues for manual review, and its manual holds on #347 and
#355 still apply.

Snapshot: 72 open issues and 2 open PRs (#604, draft #580) on 2026-11-22.
Since the previous review, PRs #598 and #552 merged. No issue in the previous
list has been closed.

## Summary

| Action | Issues |
| --- | --- |
| **A. Close: completed** | #509, #549, #490, #491, #492, #493, #530, #532, #553 |
| **B. Close: work moved to a newer issue** | #522, #324, #535, #489, #336, #520, #363, #39, #508 |
| **C. Close: superseded** | #566 |
| **D. Triage → `ready-for-agent`** | #593, #594, #595, #596, #597, #603, #519, #460, #392, #588 (after #603) |
| **E. Relabel → `ready-for-human`** (only manual acceptance remains) | #347, #355, #527 |
| **F. `needs-info`** (check against current `main` first) | #487, #129, #581 |
| **G. Maintainer decision** (still wanted?) | #37, #38, #40, #41, #42, #43, #44, #45, #46, #383, #388, #389, #471, #550, #602 |
| **No change** (already `ready-for-agent`, still accurate) | #161, #327, #330, #332–#335, #337, #338, #503, #510, #511, #544–#548, #572, #576–#579 |

The implemented smooth-gameplay slices #327, #332–#335, #337 and #338 sit
under "no change" only so that they get no comment of their own. The #324
draft proposes closing them together, if you accept them.

---

## A. Close: completed

### #509 ADR: record the single engine worker and its blocking-I/O rule

Close as completed.

> ADR 0006 (`docs/adr/0006-single-engine-worker.md`) records this decision: accepted on 2026-11-09, linked back to this issue, with the maintainer's policy decisions in `docs/specs/single-engine-worker-adr-0006-review.md`. The four breaches it named were then addressed under #505, #506, #510 and #511. #510 and #511 stay open on their own. Closing the ADR request as completed.

### #549 ADR: turn-sequence phases and the presentation contract

Close as completed.

> ADR 0007 (`docs/adr/0007-server-owned-turn-sequence-presentation.md`) merged in PR #551, and the maintainer accepted it on 2026-11-21. Code is now aligned to the ADR in follow-up issues. The first is #603, which sends the touchdown before any decision that depends on it. Closing the ADR request as completed.

### #490, #491, #492, #493 Performance capture follow-ups

Close each as completed. One comment, posted on each issue:

> Merged in PR #497 ("Reduce capture-observed gameplay overhead and add matched validation", 2026-11-08), with follow-up observability in PR #501. The implementation comment on this issue describes `feature/496-capture-performance` as uncommitted, but that work was merged in #497.
> - #490: the joined reauthorization query, measured on real MariaDB.
> - #491: the 95–133 ms delay didn't reproduce. All 108 actions per artifact showed pending feedback before a held 300 ms reply.
> - #492: diagnostics subscription and stationary-pointer hit-testing fixes, each with failing-before tests.
> - #493: matched workloads and a comparator, with all nine browser cohorts passing.
>
> Later server-side latency work is tracked in #593 and #548. Closing as completed.

### #530 Engine restart: roll-up of deferred PR #528 review findings

Close as completed.

> PR #540 merged on 2026-11-15, with no pending soak acceptance for this slice. The remaining restart-policy and health-route tidy-ups are tracked in #546. Closing as completed.

### #532 AI match becomes permanently unloadable after PERSISTENCE_FAILED

Close as completed.

> The recovery fix merged in PR #534 on 2026-11-13. The retained match loads at `FULL_TIME` (revision 488) from both the browser and the computer socket, and a native regression covers continued play after a refused write. The parent soak issue is #522. The integrated soak on 2026-11-20 (#590) reported no permanently unloadable match. Its remaining load-time costs are tracked in #593 (serial stall) and #548 (live completion path). Closing as completed.

### #553 Computer coach loops in Solid Defence; bound kick-off placements

Close as completed, unless you want a live Solid Defence check first.

> PR #558 merged on 2026-11-16. It applies the 50-adjustment kick-off budget, amends ADR 0008 and stops the computer's Solid Defence loop. Staged placements (#555, #556) removed the per-placement history growth. The integrated soak of 2026-11-20 doesn't report a kick-off loop. Closing as completed. Reopen it if a live Solid Defence kick-off loops again.

---

## B. Close: work moved to a newer issue

### #522 Gameplay soak review (2026-11-12)

Close. The tracking role passes to #590's follow-ups.

> All five child findings are resolved or in acceptance:
> - closed: #523, #524, #525 and #526;
> - in acceptance: #527.
>
> Finding 4 was evidence for #489. The integrated soak of 2026-11-20 (#590) re-measured this baseline, and its findings are tracked in #593–#597. Closing this umbrella. #527 stays open for its acceptance trace.

### #324 Smooth gameplay: measured responsiveness and continuous presentation

Close the umbrella. Optionally close its implemented slices with it.

> PR #339 delivered this umbrella on 2026-10-22. The 2026-10-28 audit found that its slices #327, #332, #333, #334, #335, #337 and #338 are implemented, with only acceptance left. The later cadence work (#372, #377, #541) replaced the old acceleration and immediate-prompt criteria. Current latency work is tracked in:
> - #593: completion stall;
> - #595: computer response delays;
> - #548: completion and serialization budget;
> - #330: Windows and physical-tablet acceptance.
>
> Closing this umbrella. Tell us if you also want the implemented slices closed as accepted. Otherwise they stay open for your manual review.

### #535 Reliable match completion under the request budget

Close. The remaining budget problem moves to #593.

> PR #537 merged the client-facing part. In the 2026-11-17 soak, all 16 deferred completions were reconciled, and no coach request was refused. Still open is the result write and final-state broadcast starting with no JDBC allowance. That is the same serial-worker completion cost that #593 now specifies, with fresh evidence from the 2026-11-20 soak. Closing here and carrying the budget problem in #593 and #548.

### #489 Performance: isolate residual checkpoint and recovery serialization spikes

Close as a duplicate of #548.

> PR #542 merged the measurement tooling. The last comment says the remaining work is live SQL, completion and JFR timings, which is exactly #548's scope. To keep one owner for that measurement, closing this in favour of #548.

### #336 Reduce gameplay persistence latency with durable recovery journal

Close. The residual tail moves to #548.

> The durable journal merged in #339. The 2026-10-28 audit narrowed what's left to one residual checkpoint tail, about 2 s. Later work (#505, #534, #537, #539 and #543) changed that path, and #548 now owns measuring it on current code. Closing as completed. The tail question continues in #548.

### #520 Investigate remaining healthy single-worker execution overruns

Close. The work moves to #593.

> PR #521 cut worker p99 from about 1,318 ms to 538 ms in contended runs. The 2026-11-20 integrated soak traced the remaining serial-queue waits of 5–14 s to match completion and `setup/load` restores. #593 now specifies measuring and removing that work. Closing in favour of #593.

### #363 Dev-local: computer-match recovery exhausts heap and strands v2 connections

Close as superseded.

> Each part of this issue has since been fixed or moved:
> - heap retention: #524 and #554 (closed);
> - the dead worker behind a green health check: #523 (closed);
> - the daemon reload loop: #573 (closed).
>
> The one remaining thread, retained legacy matches that can't be recovered, is now in #595: stop retrying `RECOVERY_UNSUPPORTED` as if it were transient. Closing as superseded.

### #39 R4 concurrent-match envelope

Close as completed. The 32-resident worst case moves to #594.

> PR #521 measured the declared operating envelope. The results, runbook and validation are linked in the 2026-11-11 comment. #559 and #543 then set the 32-match activation limit and memory budget. One case was never exercised: a full cache plus a completion at the size limit. That is now #594. Closing as completed.

### #508 Throttle and batch browse and current-match reads

Close as completed, if the missed p99 target is acceptable for now.

> PR #521 merged the bounded, coalesced lobby reads and the race fixes. Every behaviour in scope is delivered. One measurement missed its target: lobby p99 was about 404 ms against 100 ms. The 2026-11-20 soak didn't list lobby reads as a stall cause. Closing as completed. If the 100 ms target still matters, it should be its own measured issue.

---

## C. Close: superseded

### #566 Old World Classic stadium mockup gallery (round three)

Close as not planned (superseded).

> Round three didn't match the concept (see the two-axis review on #571). PR #571 archived its gallery. Round four, #572, replaced it with a changed method, and its approved Gatehouse still is on draft PR #580. Closing as superseded by #572.

---

## D. Triage → `ready-for-agent`

### #593, #594, #595, #596, #597 Integrated soak follow-ups

Relabel each: remove `needs-triage`, add `ready-for-agent`. Comment:

> Specs merged in PR #598 (`docs/specs/` mirrors). The prerequisites #559 and #543 are merged. Suggested order:
> - #593 and #595 first, in parallel;
> - then #596 and #594;
> - #597 in time for the next soak, which verifies the others.

### #603 Send the touchdown before any decision that depends on it

Add `ready-for-agent`.

> ADR 0007 was accepted on 2026-11-21 (#551), so this is no longer blocked. The local spec mirror `docs/specs/touchdown-boundary-before-cleanup-decisions.md` isn't on `main` yet. Commit it before implementation starts, so the issue's link resolves.

### #519 Complete delayed-publication recovery follow-ups from PR #518

Replace `needs-triage` with `ready-for-agent`.

> The scope is fully specified: five numbered items, each with its review link and acceptance criteria. Item 1, client backoff for failed recovery reads, is the priority. Ready for an agent.

### #460 Investigate recovered failed Dodge continuing a planned pass after declining the reroll

Replace `needs-triage` with `ready-for-agent`.

> This rules bug has a deterministic probe. After restore, a declined Dodge reroll leaves the player standing and lets the pass complete. First reproduce it on current `main`, since recovery formats changed in #556 and #543. If it still happens, fix it with a regression test. Otherwise close it with the passing probe.

### #392 Stabilize legacy-condition recovery test around native kickoff effects

Replace `needs-triage` with `ready-for-agent`.

> This is the same kind of CI flakiness as #576. Since all 18 checks are required to merge, a flaky test blocks every PR. First check whether it still fails on current `main`: #407 and later recovery changes touched this area. Then fix the nondeterministic assertion or close the issue with the evidence.

### #588 Count turn time only while the coach can decide

Replace `needs-triage` with `ready-for-agent`, sequenced after #603.

> The spec (`docs/specs/turn-clock-decision-time.md`, merged in #589) is complete. Its definition of "presentation finished" depends on the server-owned phase markers in ADR 0007. Start this after #603 lands the first ADR 0007 alignment, so the clock pauses on the same boundaries.

---

## E. Relabel → `ready-for-human`

### #347 Allow Solid Defence players moved to reserves to redeploy legally

Replace `ready-for-agent` with `ready-for-human`.

> Implemented in PR #356. You asked for this to stay open until you've checked Solid Defence manually (2026-10-28 audit). Only that check remains, so `ready-for-human` fits better than `ready-for-agent`. The large-projection optimization from the 2026-10-26 comment is now covered by staged placements (#556) and the kick-off budget (#558). Close this once you've checked it.

### #355 Offer a bribe after declining Argue the Call on a spotted foul

Replace `ready-for-agent` with `ready-for-human`.

> Implemented in PR #356, with automated coverage. You asked for this to stay open until a live bribe opportunity is checked. Only that manual check remains.

### #527 Bound match-page DOM growth that slows panning

Replace `ready-for-agent` with `ready-for-human`, or fold it into #597.

> PR #538 merged. What's left is acceptance: matched early and late traces from a long live match, showing Layerize share and pan p95 within 20%. Either record those traces in the next soak (add them to #597's checklist) or check them by hand. No agent implementation remains.

---

## F. `needs-info`

### #487 Spectator setup publications include actor-only saved formation controls

Replace `needs-triage` with `needs-info`.

> `SetupSession.view` now adds `savedFormations` only when `role.equals(viewActor)` (`SetupSession.java:1284`, changed in `e3564e2e0`, 2026-11-16). The spectator projection may no longer include them. Before any fix, add a spectator SETUP regression on current `main` with a saved-formation team. Close this if it passes.

### #129 Fix black or missing pitch and scenery regions in the 30 degree view

Replace `ready-for-agent` with `needs-info`.

> The black pixels never reproduced in headless Chrome. Since then the stadium direction has moved to overhead only (#572), and 30° remains only as a stress-check option in the stadium prototype. Does the problem still happen on the affected display with the current build? If not, close this as not reproducible.

### #581 Prepare deploy trust, paths and links for the rename

Keep `ready-for-agent`, and add a status comment.

> The rename has happened. The preparation (#582), the Codex grant (#587) and the archive paths (PR #604, open) have landed or are in review. What remains is the post-rename verification: an ID-only deploy run per environment, with the run links recorded in `docs/development-and-release.md`, and the Development and Support page links switched to the new slug. Can you confirm whether the post-rename checks have run? Then close this after #604.

---

## G. Maintainer decision

These need an answer from you, not an agent. Suggested comment for each:

| Issue | Question |
| --- | --- |
| #37 Success percentages | Since the overlay redesign, do you still want numeric success percentages, excluding all rerolls? If not, close as not planned. |
| #38, #40, #43, #45, #46 R4 and R5 operational readiness | These are pre-launch operational gates with no work since September. Keep them as a launch-readiness milestone, merge them into one tracking issue, or close them until launch is scheduled? |
| #41 Mutual save and restore | The 2026-10-28 audit found a real gap: no separate 24-hour disconnect window. Make that gap a `ready-for-agent` bug and close the rest? |
| #42 Transcript and chat umbrella | Narrow it to the remaining gates (live multi-user chat, public replay access) or close it? |
| #44 Restored two-user browser journey | It isn't blocking anything. Schedule it with #45 and #46, or close it? |
| #383 Java modernization backlog | Keep as backlog, or close as not planned? |
| #388 Hardware and support page | The support page was released on 2026-11-01 in `moles-v0.1.10`, and compact layout is in #389. Close #388, or say which policy proposals remain open? |
| #389 Compact layout corrections | You deferred this. Keep it as `needs-triage` backlog, or schedule it? |
| #471 Guided team-building modal | You requested this as backlog. Keep it, or schedule it? |
| #550 Engine review and overhaul ADR | The scope was extended on 2026-11-15 with a ruleset-boundary audit. Ready to hand to an agent for the review report? |
| #602 Agent delivery workflow | Uncommitted edits to `AGENTS.md`, `docs/agents/feature-workflow.md` and `.codex/rules/project.rules` are in the local checkout. Is this already in progress? If so, label it and give it its own branch. |
