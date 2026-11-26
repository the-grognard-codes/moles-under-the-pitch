import { test } from 'node:test';
import assert from 'node:assert/strict';

import { confirmedMoves, playbackBeats } from '../src/pitch-playback.ts';
import { multipleBlockRoll } from '../src/multiple-block-dice.ts';
import type { TranscriptRecord } from '../src/transcript-protocol.ts';

test('native coordinate changes retain every confirmed square in command order', () => {
  const change = (playerId: string, x: number, y: number) => ({ modelChangeId: 'fieldModelSetPlayerCoordinate',
    modelChangeKey: playerId, modelChangeValue: { x, y } });
  const record = { state: { players: [{ id: 'runner' }] }, native: [
    { commandNr: 2, reportList: { reports: [{ reportId: 'dodgeRoll', roll: 5, minimumRoll: 3, successful: true, playerId: 'runner' }] },
      modelChangeList: { modelChangeArray: [change('runner', 7, 5), change('runner', 8, 5)] } },
    { commandNr: 4, modelChangeList: { modelChangeArray: [change('other', 9, 5), change('runner', 9, 6), change('runner', -1, 0)] } }
  ] } as unknown as TranscriptRecord;
  assert.deepEqual(confirmedMoves(record), [
    { playerId: 'runner', x: 7, y: 5 }, { playerId: 'runner', x: 8, y: 5 }, { playerId: 'runner', x: 9, y: 6 }
  ]);
  assert.deepEqual(playbackBeats(record).map(beat => beat.kind), ['dice', 'move', 'move', 'move', 'player']);
  assert.deepEqual(playbackBeats(record).at(-1), { kind: 'player', playerId: 'runner', changes: { x: null, y: null } });
});


test('restores the selected Multiple Block target from an earlier public report', () => {
  const record = (revision: number, reports: Record<string, unknown>[], homeTurn = 1) => ({
    revision,
    state: { revision, matchId: 'match', activePlayerAction: 'multipleBlock', activePlayerId: 'attacker',
      half: 1, homeTurn, awayTurn: 1, players: [], actions: [], callerRole: 'spectator' },
    native: [{ commandNr: revision, reportList: { reports } }],
  } as unknown as TranscriptRecord);
  const rolls = record(3, [
    { reportId: 'blockRoll', defenderId: 'first', blockRoll: [1, 6], choosingTeamId: 'home' },
    { reportId: 'blockRoll', defenderId: 'second', blockRoll: [4, 4], choosingTeamId: 'away' },
  ]);
  const handoff = record(4, []);
  const records = { 3: rolls, 4: handoff };
  assert.deepEqual(multipleBlockRoll(handoff.state, records, 'first')?.faces, ['SKULL', 'POW']);
  assert.deepEqual(multipleBlockRoll(handoff.state, records, 'second')?.faces, ['PUSHBACK', 'PUSHBACK']);
  const later = record(5, [], 2);
  assert.equal(multipleBlockRoll(later.state, { ...records, 5: later }, 'first'), null);
});
