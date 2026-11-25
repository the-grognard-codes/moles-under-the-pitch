# Public match view field proposal

The maintainer approved the [#660 proposal](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/660#issuecomment-7172518696) in the implementation handoff under [ADR 0014 decision 27](../adr/0014-match-state-records-and-live-sync.md#public-view-and-coach-offers). Prior behavior was traced at `4db2c4aee`. The field names below describe the shared projection. The existing snapshot envelope carries that projection during the transition.

## Recorded view

Both coaches, authorized spectators and replay receive the same committed facts at a revision. Optional facts remain absent outside their applicable phases. Every pitch coordinate object below has exactly `x` and `y`.

| Field | Source | Who sees it today | Part |
| --- | --- | --- | --- |
| `matchId`, `revision` | Session identity and accepted revision | Both coaches, spectator, replay | Recorded view |
| `phase`, `half`, `drive`, `turn`, `homeTurn`, `awayTurn`, `turnMode` | Native step, game, turn data and session drive | Both coaches, spectator, replay | Recorded view |
| `homeScore`, `awayScore`, `weather` | Native result and field model | Both coaches, spectator, replay | Recorded view |
| `actor`, new `decisionKind` | Authoritative pending native decision. See below. | All audiences get `actor`. Live clients infer kind from phase, prompt, reaction and legal actions today. | Recorded view |
| `activePlayerId`, `activePlayerAction` | Native acting player and declared action, including Hail Mary mode | Both coaches, spectator, replay | Recorded view |
| `players[]` | Native roster, field model and frozen match-team details | Both coaches, spectator, replay | Recorded view |
| `players[].{id,name,slot,role,number,position,positionRace,positionRole}` | Frozen match-player identity, roster slot and position | Both coaches, spectator, replay | Recorded view |
| `players[].{ma,st,ag,pa,av,skills,conditions,limitedAction}` | Native characteristics, skills and revealed conditions or per-player spent-action state | Both coaches, spectator, replay | Recorded view |
| `players[].{x,y,state,status,offPitch}` | Native coordinate and player state | Both coaches, spectator, replay | Recorded view |
| `players[].art.{rosterId,positionId}`, `players[].skillMarkers.{version,entries[].{id,category,origin},details[]}` | Frozen player art identity and projected native skill markers | Both coaches, spectator, replay | Recorded view |
| `homeTeamName`, `awayTeamName`, `homeTeamArt`, `awayTeamArt` | Match teams. Each art object has `rosterId`, `league`, and optional `colors.{version,primary,secondary}`. | Both coaches, spectator, replay | Recorded view |
| `homeRerolls`, `awayRerolls`, `homeResources`, `awayResources` | Native turn and team data. Each resources object has `apothecaries`, `assistantCoaches`, `cheerleaders` and optional `bribes`. | Both coaches, spectator, replay | Recorded view |
| `ball`, `ballState.{version,carrierPlayerId,inPlay,moving}` | Native field and kickoff ball presentation | Both coaches, spectator, replay | Recorded view |
| `passing.{version,playerId,from,weatherPenalty,rangeLimited,ranges,action,targets,hailMary}` | Existing shared pass and teammate-flight guidance for the declared action. `targets` are displayed teammate-flight roll thresholds. | Both coaches, spectator, replay | Recorded view |
| `kickPreview.{deviation,withKick}` | Two landing squares from the native Kick skill dialog | Both coaches, spectator, replay | Recorded view |
| `kickoff.{version,event,actor,stage,allowed,completed,selected,participantIds}` | Native kickoff mode, budget and confirmed participants. Stage-only changes follow the live overlay below. | Both coaches, spectator, replay | Recorded view |
| `driveTracker.{version,receivingRole,phase,secretWeapons,knockouts,event,eventComplete}` | Session drive tracker and captured native outcomes | Both coaches, spectator, replay | Recorded view |
| `blockContext.{version,activeTargetPlayerId,blocks[]}` | Committed block presentation. Each block holds `playerId`, `targetPlayerId`, `from`, `to`, `offensiveAssists[]` and `defensiveAssists[]`. Each assist holds `playerId`, `x`, `y`. | Both coaches, spectator, replay | Recorded view |
| `stopReason` | Committed terminal outcome when the match reaches its safe size limit | Both coaches and final spectator update through the legacy stop path | Recorded view |
| Ordered confirmed movement, dice, reveals and announcements | Captured native outcomes listed below, associated with the revision | Authorized live history and replay readers | Recorded outcomes |

`decisionKind` describes one public decision category. The proposed values are `coin`, `receive`, `setup`, `kickoff`, `touchback`, `kickoffEvent`, `turn`, `blockDie`, `rerollDie`, `reroll`, `push`, `followUp`, `skill`, `kickSkill`, `apothecary`, `interception`, `argueTheCall`, `bribe`, `endAction`, `secretWeapon`, `pileDriver`, `hitAndRun`, `reaction`, `reactionMovement`, `engine` and `none`. Prompt branches use the deciding team or player carried by the native dialog or step. Touchback uses the receiving side, and ordinary turn input uses the native active side. A native wait with no supported coach input uses `engine`. Full time uses `none` and can retain the legacy `actor` field for snapshot compatibility. Historical snapshots missing the new decision kind keep their existing replay behavior. The pass guidance is computed from native state separately from public-view assembly, once per matching committed state. Kick geometry comes directly from the dialog.

## Current status and live overlays

| Field | Source | Who sees it today | Part |
| --- | --- | --- | --- |
| `saveResume.{status,proposer,expiresAt}` | Session save/resume status and proposal expiry | Both coaches and live spectator | Current status |
| `clock.{activeRole,turnElapsedMs,homeReserveMs,awayReserveMs}` | Active team's timer start or frozen elapsed time, reserve usage and server time | Both coaches and live spectator | Current status. Preserve the existing snapshot clock rendering. Absolute clock anchor delivery and generation ordering are later wire work. |
| `reaction.{actor,title,detail,expiresAt,serverNow,allowanceMs}` | Native pending reaction and session deadline | Both coaches and live spectator; the spectator countdown stays hidden | Current status. The decision actor and kind also exist in the recorded view without a deadline. |
| `stageSeq`, `setupAdjustmentsExhausted`, staged changes to `players[].{x,y,state,status,offPitch}` and `kickoff.{completed,selected}` | ADR 0008 staged placement | Both coaches and live spectator when applicable | Live overlay on the committed view |
| `projectionVersion`, `callerRole` | Snapshot encoder and recipient role | Both coaches, spectator and legacy replay (home-oriented) | Snapshot envelope metadata |

## Recipient-only controls

| Field | Source | Who sees it today | Part |
| --- | --- | --- | --- |
| `actions`, `prompt` with `id` and `options` | Native action enumeration and pre-match prompt | Both coaches and live spectator can receive these today. Replay uses empty actions and a null prompt. | Only the coach authorized for each action or prompt |
| `movementForecast`, `playerActionMenu`, `blockPreview`, `threats` | Movement forecast, native menu and assistant guidance | Forecast and menu can reach both coaches and live spectator today. Legacy replay can contain a menu. Block preview and threats already have role checks on primary paths. | Eligible coach only |
| `savedFormations` | Frozen team setup data | Acting coach, with a known spectator broadcast compatibility issue tracked in #487 | Acting coach only |
| `saveResume.proposalId`, `reaction.id` | Proposal and reaction controls | Both coaches and live spectator | Proposal ID for participants with save/resume controls. Reaction ID for its deciding coach. The observer countdown can identify an instance from the live status and revision. |
| `computer` | Computer policy context | Authenticated computer channel | Computer channel only |

The existing authorized coach movement-range request continues to return read-only inspection results, including when the other side acts. It does not grant a movement forecast or command. The range request remains outside the shared state and replay.

## Accepted decisions and native outcomes

| Payload | Include | Source |
| --- | --- | --- |
| Accepted decision | `operation`, revealed `optionId` for coin or receive, and `reason` for a system size-limit stop. `logPresentation.{version,action,movement}` carries the accepted narrative. Its action has `kind`, `label`, `playerId` and `target` (player ID or square). Its movement has `commitRevision`, `playerId`, `from`, `to` and `complete`. For a confirmed kickoff selection include `playerIds`. For committed placement include `playerId` and `to`. Setup formation application is represented by the operation and resulting board. | Validated, accepted operation and engine-derived facts. Gameplay decisions, reports and resulting boards retain the selected or executed fact. Transport IDs, offer IDs, formation indices, and planned waypoints belong to the private request/recovery path. |
| Native model sync | `commandNr`; `modelChangeList.modelChangeArray[]` for `fieldModelSetBallCoordinate`, `fieldModelSetBallMoving`, `fieldModelSetBallInPlay`, `fieldModelSetPlayerState` and `fieldModelSetPlayerCoordinate`, with the typed `modelChangeValue` and player `modelChangeKey` only where applicable; ordered `reportList.reports[]`; `blockContext`, `logOutcomes`, `matchAnnouncements`. | Captured in canonical home orientation from native model syncs. Freeze after presentation facts are attached. |
| Native report | `reportId` with a per-report allowlist of revealed participants, coordinates, dice and result details. Preserve the existing typed `logRoll`, `logActors`, `logTest` and `logBlock` presentation fields. The implementation must enumerate allowed properties by report ID and cover the existing generic log details, as well as the explicitly formatted reports. An unknown report payload or property is ineligible for the shared projection. | Native report serializers, dice and log consumers, and the existing presentation decoders. |

The new allowlists govern public output. Use compatibility conversion for legacy transcript and result reads and retain legacy durability, paging, command cursor, and recovery behavior under ADRs 0002 and 0004. Keep legacy transport diagnostics that require identifiers on an authorized diagnostic path. The persistence format, record patches, keyframes and negotiated live sync belong to the later tickets.

## Parity and privacy checks

Capture pre-change behavior for home and away coaches, a spectator and replay. Compare the new authorized output for coin and receive, setup with saved formations, staged placement, touchback and kickoff, regular and reactive turns, public pass and Kick geometry, confirmed movement and dice, live save/resume, and full time. Assert the intended waiting text for the waiting coach and spectator with empty actions. Check that a waiting coach can still inspect movement range and that public snapshots and history contain no private fields or nested extras. Exercise a legacy replay and restore fixture alongside newly written history.
