# Moles Under the Pitch

This context covers teams that players create and select for matches, the pitch on which matches are presented, and the records of those matches.

## Language

**MUTP**:
The shorthand for Moles Under the Pitch, the project and its public site.

**Team catalog**:
The versioned set of roster choices and constraints available for creating a team and entering a match.

**Team draft**:
An unfinished selection of players and resources that has not yet been accepted as a saved team.

**Saved team**:
A validated team owned by one account and available for that account to select when creating or joining a match.
_Avoid_: Exported team, local roster

**Match team**:
A frozen copy of a saved team selected for one match, including its team and player names. Changes to the saved team do not change this copy.

**Jersey number**:
The number displayed for a player within a team, unique among that team's players.

**Pitch**:
The 26-by-15 playing surface, including both end zones and the wide and central zones. Its squares have the same identities from either coach's viewpoint.
_Avoid_: Game board (when naming the playing surface)

**Coach view**:
A coach's view of the pitch from their own end toward the opposing half. Opposing coaches see the same match from opposite ends.

**Top-down view**:
A view of the pitch directly from above, used to inspect crowded positions while retaining the same square and player identities.

**Staged placement**:
A player position change during setup or a kick-off event that every viewer sees live, but that is not recorded until the coach confirms the stage. Recovery and replay ignore it.
_Avoid_: Provisional move (which names client-side movement forecasts)

**Match checkpoint**:
The durable state from which an unfinished match can resume at its last accepted decision. It carries no history. Staged placements are not part of it.

**Match record**:
One accepted decision with its revealed outcomes, phase markers and public changes, or the match's starting state at revision zero. Live play, catch-up and replay all use it.

**Keyframe**:
The complete recorded part of the public view at one point in a match, from which a reader can start instead of at the beginning.

**Match transcript**:
The ordered, durable match records and keyframes of one match, with its chat. A confirmed setup or kick-off event appears as its final positions.

**Match replay**:
A read-only view reconstructed from stored match records and keyframes after a match finishes.

**Public view**:
What every viewer of a match may know at one point in it.

**Coach's offer**:
The choices the server currently offers one coach, private to that coach.

**Turn play (`turnPlay`)**:
The playable part of a numbered team turn, including its player activations and pending decisions that determine how play ends.

**Player activation**:
The part of team-turn play in which one player acts, including the reactions needed to resolve that player's action.

**Turn cleanup (`turnCleanup`)**:
The decisions and effects that follow a settled touchdown, turnover, or voluntary end of a team turn.

**Drive end (`driveEnd`)**:
The closing recovery, removals, and expiration of effects after a touchdown or half boundary.

**Half end (`halfEnd`)**:
The transition after a half and its applicable cleanup, before the following half begins or the match ends.

**Match end (`matchEnd`)**:
The terminal phase that settles applicable match-ending choices and the final result.

**Drive start (`driveStart` / `kickoff`)**:
The phase from pre-setup decisions through team setup, the kick-off event, and ball settlement before the first numbered turn.

**Turn start (`turnStart`)**:
The initialization and start-of-turn choices before the first playable selection of a numbered team turn.
