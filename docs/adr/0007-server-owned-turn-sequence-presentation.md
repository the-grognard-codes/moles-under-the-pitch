# Let the server describe turn phases and clients order their presentation

Status: accepted by the maintainer on 2026-11-21 for [PR #551](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/551).
Amended: 2026-11-25, stored format, phase cursor and decision gates ([amendments](#amendments)).
Issue: [#549](https://github.com/the-grognard-codes/fumbbl-rebuild/issues/549)
Request: [turn-sequence contract](../specs/turn-sequence-contract-adr.md)
Evidence: [phase inventory](../specs/turn-sequence-phase-inventory.md), reviewed
2026-11-15 at `65ed24fdb7f242645d5c4673a3aebce7b4b92110`.
Policy feedback: [maintainer confirmation](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/551#issuecomment-7068224583).

The server owns versioned phase and boundary metadata persisted with accepted
native outcomes. Each client schedules its own splashes and input. This makes cleanup
ordering explicit without making the engine wait for a coach's animation or
until-dismissed preference.

## Decision and boundaries

### Phase model

A phase describes native causality independently of the browser's existing
`phase` field and the engine's transient `TurnMode`. The model describes the
ordering of existing BB2025 rules.

| Phase | Entry / exit | Decisions owned here | Splash |
| --- | --- | --- | --- |
| `turnPlay` | Playable numbered team turn; contains zero or more player activations; ends after the action/turn-ending outcome settles | Activation traits, movement/block/pass/catch/reroll, injury/Apothecary, foul send-off responses and Blood Lust feeding | None while the outcome remains undecided |
| `turnCleanup` | A settled touchdown/turnover or voluntary end; exits after applicable cleanup choices/effects | Star of the Show, applicable Secret Weapon player selection, Argue the Call, Bribe and Bribery and Corruption reroll | TOUCHDOWN or TURNOVER first; none for a voluntary end |
| `driveEnd` | Touchdown or half boundary; final drive recovery/removal/effect expiry; exits to half/match end or next drive | Only native decisions actually applicable at that boundary; shared injury/inducement work inherits its causative phase | No extra splash defined |
| `halfEnd` / `matchEnd` | Native half-end test or terminal branch after the preceding cleanup; exits to next-half drive setup or final result | Coin/receive for applicable overtime belongs to next drive; terminal awards/penalty-shootout choices belong to match end | Existing halftime/result UI follows preceding touchdown/cleanup; no new artwork defined |
| `driveStart` / `kickoff` | Pre-setup inducements, two setups, kick target/scatter, kick-off event and ball settlement; exits when first numbered turn is playable | Setup validation, Dwarfen Wisdom/Swarming, kick skills, Charge/High Kick/Solid Defence/Quick Snap choices, injury/Apothecary, touchback and after-kick-off inducements | No START OF TURN during free turns or unresolved kick-off choices |
| `turnStart` | Start-of-own-turn inducements and native turn initialization; exits at first playable selection | Applicable start-turn inducement/skill choices | START OF TURN after those decisions, for a numbered REGULAR turn only |

`turnPlay` contains individual player activations and their outstanding
reactions. Ending an activation can leave the team turn playable.
End-of-own/opponent-turn inducements in BETWEEN_TURNS can still affect the
outcome before final `StepEndTurn`, so they belong to `turnPlay` resolution.
End-turn sequence steps that settle a catch, injury or Blood Lust also belong
to the causative phase.

`TurnMode` is preserved separately. Nested interception, Dump Off, bomb, wizard,
reactive movement and target-selection modes inherit their caller's phase.
SETUP can already be the native mode while old-turn Secret Weapon cleanup is
awaiting input. Mode or final actor alone is therefore insufficient evidence.

### Signal and ownership

Use an ordered `presentation` extension in each version-3 transcript
record and its V2 publication. Its candidate items describe phase entry/exit,
the three existing announcement kinds, and a gate/reference for a decision
offered in the final public state. Each includes a stable identity derived from
match, accepted revision, native command number where available, and ordinal;
phase identity also identifies the affected team, half and numbered turn.
These are candidate fields, not an implemented wire schema.

The engine-owning worker assigns phase and causal order at native resolution
sites. Native resolution settles the touchdown or turnover first. The server
records the boundary event and stages it for delivery before raising any
dependent cleanup decision. After durable acceptance, the server sends the
recorded event before or in the same update as the offer. The client presents
the splash before revealing the dialog. Client receipt and acknowledgement
are independent of server progression, so a slow or hung client cannot stall
the match.

Star of the Show is a cleanup decision whose preceding marker is the touchdown
boundary. In the current path, `StepEndTurn` can offer Star of the Show before
`observeTurnEnd` emits the touchdown. The implementation must bring this
emission site into line with the ordering above.

Markers describe accepted facts; native steps/dialogs remain rules authority.
The server still validates actor, revision and the current choice. Metadata
does not authorize inputs or allow a client to execute an old queued offer.
Do not duplicate full dialog payloads or private engine state in presentation
metadata. Current prompt/action identities must bind the gate to the actual
public offer. Final field names belong to the implementation's schema review.

Clients consume the same ordered markers, independently scheduling their own
preferences. No splash acknowledgement gates server mutation, AI execution,
checkpointing or another recipient. No engine thread sleeps for presentation.

### Ordering and timing

1. Resolve all causative activation decisions before declaring its outcome.
2. In normal live play, present TOUCHDOWN or TURNOVER before its cleanup dialogs,
   including when event and offer arrive in one update. Voluntary end gets no
   turnover.
3. Keep splash reading/fade/gap independent of live catch-up. Replay-selected
   speed and reduced motion retain their explicit semantics. Until-dismissed
   cleanup choices wait for dismissal.
4. Continue presenting every queued native move/dice outcome while a splash
   plays; a required choice does not snapshot or discard that backlog. Reveal
   only the latest still-valid offer after its preceding presentation barrier.
5. At drive start, settle setup, kick-off event, free-turn and touchback choices
   before START OF TURN. A kick-off `TurnMode.BLITZ` is distinct from a normal
   Blitz player action during a REGULAR team turn.
6. An off-turn touchdown identifies the scorer's team, not necessarily the
   previous actor. A last-turn touchdown and its applicable cleanup precede any
   halftime/full-time presentation. These rules do not create choices the native
   rules would otherwise omit at full time.

This extends the [announcement ordering decision](https://github.com/the-grognard-codes/fumbbl-rebuild/pull/541#issuecomment-6884610201)
and [#541](https://github.com/the-grognard-codes/fumbbl-rebuild/pull/541);
the native end-to-end edge-case matrix remains
[#547](https://github.com/the-grognard-codes/fumbbl-rebuild/issues/547).

### Durable formats and replay

Use embedded transcript format 3 for writers and readers across recovery,
journal assembly, completed results, replay and diagnostics. This amends the
format section of [ADR 0002](0002-bounded-match-transcript.md).
During the build phase, readers explicitly reject version 2 and all unknown
versions. Replace version-2 fixtures with regenerated version-3 fixtures or
remove obsolete fixtures. Existing version-2 matches are unsupported.

Freeze metadata once with its accepted revision, include it in the same durable
checkpoint or journal tail/CAS and terminal transcript/digest, and publish only
after that commit. Exact retry appends no new marker. Compaction preserves
identities/order; restore consumes recorded continuation and metadata, never
reruns actions, dice or choices to recreate markers. Rebuild a small current
phase cursor during the existing bounded restore pass; normal requests must not
rescan/materialize all history or retain a second expanded transcript.

Preserve current outer recovery/result compatibility unless a separately
reviewed version change is necessary. A new embedded transcript version is not
permission to alter native recovery JSON, journal generations or result limits.

### Failure, recovery, spectators and clocks

- Duplicate delivery deduplicates by recorded identity, never by display text.
  Spectators receive the same public phase facts and no actionable coach input.
  Their animation speed/holds cannot affect a coach or server progression.
- Initial load, reconnect, hidden-tab recovery and replay seek establish a
  baseline and restore the current native phase and any still-valid offer.
  Splashes are best effort at these points. A restore may show a different
  splash or omit splashes entirely. The coach can use the game log, turn clock
  and current prompt to determine match state. Normal live play retains the
  splash-before-cleanup ordering, and ordinary forward replay plays the
  recorded events in order.
- After a crash during cleanup, native continuation/dialog state and recorded
  phase identity resume from the same accepted generation. A refused/unknown
  commit follows existing recovery/uncertainty handling; metadata cannot publish
  a new volatile acceptance. No separate best-effort marker store is allowed.
- [Decision-time clocks (#588)](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/588)
  own turn-clock and timed-decision behavior. Under that policy, committed
  presentation including splashes pauses the applicable clock up to a
  deterministic server budget. The coach's browser can acknowledge presentation
  to end the pause early. This acknowledgement affects only that coach's
  clock accounting. Server state progression, AI and other recipients proceed
  independently. An until-dismissed hold past the budget uses that coach's
  decision time, including the planned time bank as applicable. Offer validity
  continues to use the native actor, revision and current choice checks.

### Budget and admission

Phase assignment/freezing runs on the existing single engine worker under
[ADR 0006](0006-single-engine-worker.md): healthy p99 execution target <=100 ms
and aggregate failure occupancy <=2,000 ms, including existing work. It adds no
remote call, per-recipient provider lookup, worker retry/sleep or blocking
client acknowledgement. Local playback duration is not worker occupancy.

Use constant-size current phase state and work proportional to the new action's
emitted items. Markers count as ordinary record content under existing admission
and size limits, including `BrowserMatchTransport.MAX_LIVE_TRANSCRIPT_BYTES`
(256 KiB), `BrowserMatchTransport.MAX_OUTBOUND_BYTES` (2 MiB),
`BoundedJsonStorageCodec.PREPARED_ENCODED_LIMIT`, `CompletedMatch.MAX_BYTES`,
and `FirebaseProviderBounds.MAX_BODY_BYTES` for provider requests. Apply those
limits with the existing failure and uncertain-commit semantics. Required
markers stay intact and publication requires durable acceptance.

Normal match writes use existing record admission without marker-specific
limits, reservations or disk-space checks. Production filesystem monitoring
covers disk capacity. Large diagnostic output such as heap dumps still requires
size checks.

## Alternatives considered

| Alternative | Benefit | Cost / reason not preferred |
| --- | --- | --- |
| Client derives phases from reports/final snapshots | No new stored field | Mixed steps, same-update choices, off-turn scoring and recovery make causal classification incomplete |
| Server supplies markers; client gates presentation | One authoritative classification, independent recipients, no engine animation wait | Requires a new schema, emission sites and client scheduler |
| Server withholds cleanup until splash acknowledgement | Could align visibility with decision timing | Coach, spectator, AI, disconnect and hold semantics become engine dependencies; a blocking implementation violates ADR 0006; no recommendation to add this handshake |
| Add unversioned keys to existing records | Smaller apparent rollout | Exact-shape readers reject them; falsely claims stored-format compatibility |

## Consequences, consistency and known gaps

[ADR 0002](0002-bounded-match-transcript.md) continues to govern the public
durable transcript, recorded outcomes, digest and admission. ADR 0007 amends
its embedded-format policy.
[ADR 0004](0004-durable-recovery-journal.md) keeps one generation-CAS transaction
and exact recorded continuation. Markers travel with its tails and compaction.
ADR 0006 keeps one owner and bounded execution. Splash presentation stays
asynchronous to the engine.

Current code already emits durable `matchAnnouncements` and restores native
steps/dialogs. It does not expose the accepted phase model/version, universally
emit before cleanup decisions, or prove the inventory's whole scenario matrix.
#541 fixes client scheduling of available announcements; it does not by itself
close these server/protocol gaps. #547 owns its existing tests. New protocol,
fixture replacement and emission work belong in separate implementation tickets.

### Implementation follow-up

After publication, audit every emission site in the phase inventory against
the native ordering rule and file one or more issues for sites that need
correction, starting with `StepEndTurn` and Star of the Show. Track protocol
and validation work in separate implementation slices.

Implementation validation must cover each inventory dialog family and shared
hook, activation vs cleanup Argue/Apothecary/Blood Lust, Star of the Show,
same-update event/offer, off-turn/last-turn touchdown, all kick-off free modes,
voluntary end, halftime/overtime/fulltime, send-off chains and each recovery
point. Also require duplicate/CAS/unknown-commit parity, version-3 replay and
seek, explicit rejection of unsupported versions, coach/spectator independence,
stale-offer rejection, existing admission limits and ADR 0006 worker bounds.
Where #588 applies, verify that acknowledgement and budget expiry affect only
clock accounting.

## Revisit conditions

Revisit if the native matrix exposes an unassignable decision, format
requirements change, or phase metadata causes execution to exceed ADR 0006's
worker budget.

## Amendments

- 2026-11-25. [ADR 0014](0014-match-state-records-and-live-sync.md) ([approval](../specs/match-state-sync-adr.md#maintainer-acceptance)) replaces the storage that this record builds on:
  - Format 3, which was never built, becomes format 4. The ordered `presentation` markers are part of each match record.
  - The current phase cursor is restored from the snapshot instead of rebuilt during a restore pass.
  - A gate in a record is a public marker reference. The prompt and action identity that binds it goes only to the deciding coach.
  - Where this record relies on ADRs 0002 and 0004 for durability, ordering, digest, admission and format limits, read ADR 0014's transaction, history integrity and per-record limits. The phase model, the ordering rules, presentation that never gates the engine, and the worker budget are unchanged.
