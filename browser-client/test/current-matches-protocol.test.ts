import assert from 'node:assert/strict';
import test from 'node:test';
import { currentMatchStatus, decodeCurrentMatches, resumeTimeLabel } from '../src/current-matches-protocol.ts';
import { assertV2Projection } from '../src/v2-projection.ts';

const match = { matchId: '12345678-1234-1234-1234-123456789abc', callerRole: 'home' as const,
  lifecycle: 'ACTIVATED' as const, homeTeamName: 'Orcs 4 Hire', awayTeamName: "Bugman's Best", phase: 'PLAY' };

test('current match pages include owned preparations, active matches and unavailable entries', () => {
  for (const lifecycle of ['WAITING_FOR_OPPONENT', 'AWAITING_SETUP', 'ACTIVATED', 'UNAVAILABLE']) {
    const row = { ...match, lifecycle, awayTeamName: lifecycle === 'WAITING_FOR_OPPONENT' ? null : match.awayTeamName };
    assert.deepEqual(decodeCurrentMatches([row], null), [row]);
  }
  assert.deepEqual(decodeCurrentMatches([], null), []);
  assert.equal(currentMatchStatus(match), 'In progress');
  assert.equal(currentMatchStatus({ ...match, phase: 'SETUP' }), 'Awaiting kickoff');
  for (const phase of ['PRE_MATCH', 'READY_FOR_KICKOFF'])
    assert.equal(currentMatchStatus({ ...match, phase }), 'Awaiting kickoff');
});

test('current match disclosure rejects identities, completed games, malformed names and invalid cursors', () => {
  const envelope = { version: 2, type: 'currentMatches', requestId: 'list', code: 'ACCEPTED', matches: [match], next: null };
  assert.doesNotThrow(() => assertV2Projection(envelope));
  for (const addition of ['owner', 'accountId', 'email', 'invitationCode', 'checkpoint']) {
    assert.throws(() => assertV2Projection({ ...envelope, [addition]: 'private' }));
    assert.throws(() => decodeCurrentMatches([{ ...match, [addition]: 'private' }], null));
  }
  for (const change of [{ lifecycle: 'COMPLETED' }, { callerRole: 'spectator' }, { homeTeamName: null },
    { awayTeamName: null }, { homeTeamName: '' }, { phase: 1 }])
    assert.throws(() => decodeCurrentMatches([{ ...match, ...change }], null));
  assert.throws(() => decodeCurrentMatches([match, match], null));
  assert.throws(() => decodeCurrentMatches([match], '00000000-0000-0000-0000-000000000000'));
  assert.throws(() => decodeCurrentMatches([], 'not-a-match'));
  assert.throws(() => decodeCurrentMatches(Array.from({ length: 101 }, () => match), null));
});

test('resume details require an opted-in response with a valid server time', () => {
  const serverNow = 1_800_000_000_000;
  const live = { ...match, resumeUntil: serverNow + 1 };
  const expired = { ...match, lifecycle: 'EXPIRED' as const };
  const envelope = { version: 2, type: 'currentMatches', requestId: 'list', code: 'ACCEPTED', matches: [live], next: null, serverNow };
  assert.doesNotThrow(() => assertV2Projection(envelope, true));
  assert.doesNotThrow(() => assertV2Projection({ ...envelope, matches: [expired] }, true));
  assert.throws(() => assertV2Projection(envelope));
  assert.throws(() => assertV2Projection({ ...envelope, serverNow: null }, true));
  const { serverNow: _omitted, ...olderEnvelope } = envelope;
  assert.throws(() => assertV2Projection(olderEnvelope, true));
  assert.throws(() => assertV2Projection({ ...olderEnvelope, matches: [expired] }, true));
  assert.doesNotThrow(() => assertV2Projection({ ...olderEnvelope, matches: [match] }, true));
  assert.equal(currentMatchStatus(expired), 'Match expired');
  for (const invalid of [{ ...live, resumeUntil: serverNow }, { ...live, resumeUntil: serverNow + 0.5 },
    { ...live, resumeUntil: String(live.resumeUntil) }, { ...live, lifecycle: 'AWAITING_SETUP' }, { ...expired, resumeUntil: live.resumeUntil }])
    assert.throws(() => assertV2Projection({ ...envelope, matches: [invalid] }, true));
});

test('formats resume time with day, hour and rounded minute thresholds', () => {
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;
  for (const [remaining, label] of [
    [29 * day + 23 * hour, '29d 23h to resume'],
    [day, '1d 0h to resume'],
    [22 * hour + 16 * minute, '22h 16m to resume'],
    [hour, '1h 0m to resume'],
    [hour - 1, '60m to resume'],
    [8 * minute + 1, '9m to resume'],
    [1, '1m to resume'],
    [0, 'Checking match status'],
  ] as const) assert.equal(resumeTimeLabel(remaining), label);
});
