# ADR request: turn-sequence phases and presentation

Status: resulting ADR accepted by the maintainer on 2026-11-21 for
[PR #551](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/551).
Source: [#549](https://github.com/the-grognard-codes/fumbbl-rebuild/issues/549),
the [2026-11-14 ordering decision](https://github.com/the-grognard-codes/fumbbl-rebuild/pull/541#issuecomment-6884610201)
and [spec patch](https://github.com/the-grognard-codes/fumbbl-rebuild/pull/541#issuecomment-6885261078).
Reviewed: 2026-11-15, `main` at `65ed24fdb7f242645d5c4673a3aebce7b4b92110`;
pending #541 is distinguished from that base below.
Template: [ADR request template](../agents/adr-template.md).
Policy decisions: [PR #551 review](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/551#issuecomment-7068110035),
[confirmed by the maintainer](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/551#issuecomment-7068224583).

## Objective

Choose an authoritative phase and presentation contract across native BB2025
steps, durable transcript/recovery, and browser playback. Outcomes that decide
whether a touchdown or turnover occurs must settle before its splash. In live
play, cleanup choices follow the full splash and drive-start choices precede
turn start. Use server-owned phase facts to establish this order.

This crosses a durable protocol boundary and trades explicit metadata/versioning
against repeated client inference. It also separates engine progress from a
coach's potentially unbounded announcement hold. Those costs warrant an ADR.

## Context and evidence

The [phase inventory](turn-sequence-phase-inventory.md) records the selected
native steps, sequence generators, direct and shared dialog producers, modes,
edge cases and recovery points at the reviewed revision. It is a static source
inventory, not a claim to have executed every optional skill or rules branch.

`StepEndTurn` spans several proposed phases. Its generator first resolves
forgone stalling, dropped-player effects, Apothecary and ball resolution. Inside
the final step, Star of the Show can be offered before `observeTurnEnd` emits a
touchdown. Secret Weapon choices, recovery, next-half/drive setup and start-turn
work follow in the same continuation. The inventory cites those distinct sites.
The checked touchdown path does call `observeTurnEnd` with the scorer; the
issue's abbreviated “not called on a touchdown” note does not describe this
revision. The source gap concerns emission before Star of the Show, rather
than a missing touchdown hook altogether.
The desired ordering is therefore a policy and future emission requirement,
not a description of universal current server compliance.

At the reviewed base, the [announcement spec](match-transition-announcements.md)
still contained the earlier choice-priority prose. The approved ordering patch traveled separately
with [#541](https://github.com/the-grognard-codes/fumbbl-rebuild/pull/541), at
`d25833125d855f4e66755692df469cee987274c4` after review fixes. That PR gates
choices behind announcements already supplied to the browser; its synthetic
same-update test does not prove that every native cleanup choice is preceded by
a supplied event. The wider matrix is [#547](https://github.com/the-grognard-codes/fumbbl-rebuild/issues/547).

## Scope and deliverables

- This request, the linked source inventory, and
  [ADR 0007](../adr/0007-server-owned-turn-sequence-presentation.md).
- A phase model, server-owned signal, local presentation ordering,
  embedded version-3 transcript policy, restore behavior and existing budgets.
- Explicit amendment to ADR 0002's embedded-format policy and
  consistency review against ADRs 0004 and 0006 and decision-time clocks (#588).

No protocol implementation, `StepEndTurn` refactor, animated artwork, rules
change, #547 tests or #550 implementation is included. Formal acceptance
precedes any glossary or architecture-map update.

## Required decision content

The ADR must distinguish team-turn play from one player's activation, coarse
browser state from engine `TurnMode`, and a native phase boundary from a local
splash/input barrier. It must assign entry/exit conditions and decision ownership
for turn play/activation resolution, turn cleanup, drive end, half/match end,
drive start/kick-off and turn start.

Freeze server-owned presentation metadata with each accepted revision. The
server settles the outcome and records its boundary for delivery before
raising dependent cleanup decisions. The event goes out before or with its
offer after durable acceptance. Clients gate presentation locally. Compare
this with inference-only, server-held dialogs and an unversioned additive
extension. Use only embedded transcript version 3, explicitly reject version 2
and unknown versions, and regenerate or remove version-2 fixtures.

The timing contract is the maintainer's decision: live catch-up cannot shorten
reading/fade/gap or cancel splashes; queued native moves/dice continue; cleanup
choices follow their splash even in one update or until-dismissed mode.
Activation outcome choices precede a settled splash. Kick-off free turns do not
produce numbered START OF TURN splashes. This does not invent new halftime or
full-time artwork or change when BB2025 rules actually offer a choice.

On initial load, reconnect, hidden-tab recovery and replay seek, splashes are
best effort. Restore the current native phase and any still-valid offer. The
game log, turn clock and current prompt convey match state.

Apply existing record and transport caps to the entire payload, including
markers. Keep ADR 0006 worker bounds and size checks for large diagnostic
outputs. Ordinary match records use existing admission without a separate
marker count, byte limit or reservation.

[#588](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/588)
owns turn-clock and timed-decision accounting. A presentation acknowledgement
can only end that coach's clock pause early. The deterministic server budget
bounds the pause independently of acknowledgements. A longer until-dismissed
hold uses the coach's decision time and planned time bank as applicable.

## Risks and mitigation ownership

| Risk / trigger | Consequence | Required mitigation or decision | Owner / tracking | Remaining risk |
| --- | --- | --- | --- | --- |
| Mixed native phases or a missed skill hook | Wrong choice/splash order | Source inventory, decision ownership and native end-to-end matrix | Maintainer review of this ADR; #547 | Static enumeration does not execute all branches |
| Held splash exceeds the presentation budget | Coach uses decision time while the splash is still held | #588 bounds the clock pause and uses the planned time bank as applicable. Validate the offer at the current revision | #588 and future implementation | The coach accepts the cost of holding the splash |
| Client acknowledgements gate engine progress | Worker or other matches stall | Local presentation barrier and clock-only acknowledgement | ADR 0006 / future implementation | Local clients can still lag |
| Crash during Star of the Show or a send-off chain | Duplicate/missing event or repeated choice | Persist phase identity with the same accepted checkpoint/journal tail | ADRs 0002/0004; future recovery tests | No separate volatile marker channel |

## Alternatives and resolved decisions

The ADR compares explicit markers with client inference, server-held dialogs
and an unversioned extension. The accepted contract records these choices.

1. `turnPlay` contains individual activations. The other phases distinguish
   cleanup, drive/half/match boundaries, drive start and turn start.
2. Embedded transcript version 3 is the sole supported format.
3. #588 owns clock accounting. A hold past its presentation budget uses the
   coach's time.
4. Existing size caps cover markers. Native emission sites must record and
   stage the settled event before raising dependent decisions.
5. Restore splashes are best effort. Normal live play presents the splash
   before its cleanup choices.

## Acceptance criteria

### Draft ready for maintainer review

- [x] Request and proposed ADR exist and cross-reference #549, #541, #547 and
  the announcement spec.
- [x] Inventory cites the selected native steps, generators and dialog producers;
  covers off-turn/last-turn touchdowns, free turns, send-offs and recovery points.
- [x] Proposed signal, alternatives, consequences, ADR 0002 amendment, existing
  limits, failure behavior, revisit conditions and confirmed choices are explicit.
- [x] Documentation links and cited source symbols checked at the stated base.

### Acceptance and follow-up

- [x] Maintainer approves a final policy with a date and evidence link.
- [x] Add accepted vocabulary to [CONTEXT.md](../../CONTEXT.md) and an
  invariant link to the [architecture map](../agents/architecture-map.md).
- [ ] After publication, audit every inventory emission site and file one or more
  issues for misaligned sites, starting with `StepEndTurn` and Star of the Show.
- [ ] File separately scoped protocol, fixture and validation tickets.

## Validation and handoff

Documentation-only checks cover relative links, source symbols, catalog coverage
and consistency with accepted ADRs. No build or gameplay claim follows from them.
Implementation must later prove native decision/splash ordering, version-3
recovery and replay, explicit rejection of unsupported versions, exact retry,
existing size admission and worker occupancy with the matrix in the ADR.
Where #588 applies, verify that acknowledgement and budget expiry affect only
clock accounting.
