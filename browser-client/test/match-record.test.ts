import assert from 'node:assert/strict';
import test from 'node:test';
import fixture from './fixtures/match-format-4.json' with { type: 'json' };
import {
  decodeMatchKeyframe, decodeMatchRecord, decodeMatchStatus, presentationMarkerId, resolveRecordedViewNames,
} from '../src/match-record-protocol.ts';
import { applyMatchKeyframe, applyMatchRecord, applyMatchStatus, startMatchReduction } from '../src/match-record-reducer.ts';

const encode = JSON.stringify;

test('format 4 rebuilds the recorded view, releases completed sections and resolves names on read', () => {
  const start = decodeMatchRecord(encode(fixture.start));
  const first = applyMatchRecord(startMatchReduction(start.matchId), start);
  const keyframe = decodeMatchKeyframe(encode(fixture.initialKeyframe));
  assert.deepEqual(first.view, keyframe.view);
  const next = decodeMatchRecord(encode(fixture.turn));
  const reduced = applyMatchRecord(first, next);
  assert.deepEqual(reduced.view, fixture.after);
  assert.deepEqual(first.view, keyframe.view);
  assert.ok(!Object.hasOwn(reduced.view!, 'ball'));
  assert.equal(reduced.view!.activePlayerId, null);
  assert.equal(reduced.view!.players[1].offPitch, 'knockedOut');
  assert.notEqual(presentationMarkerId(next, next.presentation[0]), presentationMarkerId(next, next.presentation[1]));
  assert.equal(presentationMarkerId(next, next.presentation[0]), next.matchId + ':1:4:0');
  const display = resolveRecordedViewNames(reduced.view!, { team: id => 'Team ' + id, player: id => 'Player ' + id });
  assert.equal(display.players[0].name, 'Player home-player');
  assert.equal(display.homeTeamName, 'Team ' + next.matchId + ':home');
  assert.equal(display.ball, null);
});

test('patches preserve explicit null and replace whole players while retaining stable order', () => {
  const first = applyMatchRecord(startMatchReduction(fixture.start.matchId), decodeMatchRecord(encode(fixture.start)));
  const turn = structuredClone(fixture.turn);
  turn.patch.removeSections = turn.patch.removeSections.filter(section => section !== 'ball');
  Object.assign(turn.patch.sections, { ball: null });
  const next = applyMatchRecord(first, decodeMatchRecord(encode(turn)));
  assert.equal(next.view!.ball, null);
  assert.deepEqual(next.view!.players.map(player => player.id), first.view!.players.map(player => player.id));
  Object.assign(turn.patch, { players: [], removePlayers: ['away-player'], playerOrder: ['home-player'] });
  const removed = applyMatchRecord(first, decodeMatchRecord(encode(turn)));
  assert.deepEqual(removed.view!.players.map(player => player.id), ['home-player']);
  assert.throws(() => applyMatchRecord(removed, decodeMatchRecord(encode(turn))), /revision/);
});

test('keyframe repair compares board facts and retains a save, resume and cleared reaction at the same revision', () => {
  const frame = decodeMatchKeyframe(encode(fixture.initialKeyframe));
  const start = applyMatchRecord(startMatchReduction(frame.view.matchId), decodeMatchRecord(encode(fixture.start)));
  const saved = decodeMatchStatus(encode(fixture.saved));
  const suspended = applyMatchStatus(start, saved);
  assert.equal(suspended.view, start.view);
  const suspensionRepair = applyMatchKeyframe(suspended, frame);
  assert.deepEqual(suspensionRepair.driftPaths, []);
  assert.equal(suspensionRepair.match.status, saved);

  const resumed = decodeMatchStatus(encode(fixture.resumed));
  const resumedMatch = applyMatchStatus(suspensionRepair.match, resumed);
  assert.equal(applyMatchStatus(resumedMatch, saved), resumedMatch);
  const repaired = applyMatchKeyframe({ ...resumedMatch, view: { ...frame.view, turn: 2 } }, frame);
  assert.deepEqual(repaired.driftPaths, ['turn']);
  assert.equal(repaired.match.status, resumed);
  assert.equal(repaired.match.status?.reaction, null);

  const advanced = applyMatchRecord(repaired.match, decodeMatchRecord(encode(fixture.turn)));
  assert.equal(applyMatchKeyframe(advanced, frame).match, advanced);
});

test('format-4 decoders refuse unknown versions, duplicate keys and private nested fields', () => {
  assert.throws(() => decodeMatchRecord(encode({ ...fixture.start, formatVersion: 5 })));
  for (const [token, malformed] of fixture.invalidNumberEncodings)
    assert.throws(() => decodeMatchRecord(encode(fixture.start).replace(token, malformed)), /integer/);
  assert.throws(() => decodeMatchKeyframe(encode({ ...fixture.initialKeyframe, formatVersion: 2 })));
  assert.throws(() => decodeMatchStatus(encode({ ...fixture.saved, formatVersion: 3 })));
  assert.throws(() => decodeMatchRecord(encode(fixture.start).replace('"revision":0', '"revision":0,"revision":0')));
  assert.throws(() => decodeMatchRecord(encode(fixture.start).replace('"revision":0', '"revision":0,"__proto__":{"accountId":"hidden"}')));
  const record = structuredClone(fixture.turn);
  Object.assign(record.actor, { accountId: 'private-account' });
  assert.throws(() => decodeMatchRecord(encode(record)), /accountId/);
  Object.assign(record.patch.players[0], { name: 'private-name' });
  assert.throws(() => decodeMatchRecord(encode({ ...record, actor: fixture.turn.actor })), /name/);
  const command = structuredClone(fixture.turn);
  Object.assign(command.outcomes[0].reportList.reports[0], { coach: 'private-account' });
  assert.throws(() => decodeMatchRecord(encode(command)));
  assert.throws(() => decodeMatchStatus(encode({ ...fixture.saved, saveResume: { ...fixture.saved.saveResume, proposalId: 'private' } })));
});

test('an omitted patch field causes drift against the independent recorded view', () => {
  const first = applyMatchRecord(startMatchReduction(fixture.start.matchId), decodeMatchRecord(encode(fixture.start)));
  const turn = structuredClone(fixture.turn);
  turn.patch.removeSections = turn.patch.removeSections.filter(section => section !== 'ball');
  const bad = applyMatchRecord(first, decodeMatchRecord(encode(turn)));
  assert.notDeepEqual(bad.view, fixture.after);
  const frame = decodeMatchKeyframe(encode({ formatVersion: 4, view: fixture.after }));
  assert.deepEqual(applyMatchKeyframe(bad, frame).driftPaths, ['ball']);
});
