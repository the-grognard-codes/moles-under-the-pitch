import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

import { Engine } from '../../computer-player/training/engine.mjs';
import { play } from '../../computer-player/training/match.mjs';
import { validateParameters } from '../../computer-player/src/bogruk-parameters.mjs';
import { decodeMatchKeyframe, decodeMatchRecord } from '../../browser-client/src/match-record-protocol.ts';
import { applyMatchKeyframe, applyMatchRecord, startMatchReduction } from '../../browser-client/src/match-record-reducer.ts';

const classpath = (await readFile(process.env.FFB_STATE_SYNC_CLASSPATH_FILE ?? '.tools/training-classpath.txt', 'utf8')).trim();
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const parameters = validateParameters({});
const config = { timeoutMs: 600_000, maxDecisions: 4000, trajectories: 'off', recklessness: 'off' };
const removals = new Set();
let playerLeftPitch = false;

for (const [seed, rosters] of [[43, ['Human', 'Orc']], [44, ['Orc', 'Human']], [45, ['Human', 'Orc']]]) {
  const engine = new Engine({ java, classpath, heapMiB: 768, matchesPerJvm: 1, priority: 'normal',
    jvmArguments: ['-Dffb.format4.parity=true'] });
  let reduced, records = 0, outcomes = 0, keyframes = 0;
  const workerNanos = [];
  let lastNative = 0, lastAt = 0, finished = false;
  let homeOffer, passDeclared = false;
  try {
    const tracked = {
      get error() { return engine.error; },
      async command(request, timeout) {
        // The normal training policy rarely chooses Pass. Exercise a real declaration and its
        // removal during one complete match by taking an offer from the human-side view.
        if (seed === 43 && request.operation === 'apply' && request.role === 'home'
          && homeOffer?.revision === request.request.expectedRevision) {
          const action = passDeclared && homeOffer.passing
            ? homeOffer.actions.find(option => option.kind === 'pass'
              && homeOffer.players.some(p => p.role === 'home' && p.id !== homeOffer.activePlayerId
                && p.x === option.target?.x && p.y === option.target?.y))
              ?? homeOffer.actions.find(option => option.kind === 'endAction')
            : !passDeclared && !homeOffer.activePlayerId && homeOffer.actions.find(option => option.kind === 'declarePass');
          if (action) {
            const { version, type, requestId, matchId, expectedRevision } = request.request;
            request = { ...request, request: { version, type, requestId, matchId, expectedRevision,
              operation: 'action', actionId: action.id } };
            if (action.kind === 'declarePass') passDeclared = true;
          }
        }
        const response = await engine.command(request, timeout);
        if (response.state?.callerRole === 'home') homeOffer = response.state;
        if (response.format4) {
          const entry = response.format4;
          const record = decodeMatchRecord(entry.record);
          reduced ??= startMatchReduction(record.matchId);
          const prior = reduced.view;
          assert.equal(record.revision, records++);
          if (prior) workerNanos.push(entry.workerNanos);
          assert.ok(record.at >= lastAt);
          lastAt = record.at;
          for (const outcome of record.outcomes) {
            assert.ok(outcome.commandNr > lastNative);
            lastNative = outcome.commandNr;
            outcomes++;
          }
          record.patch.removeSections.forEach(section => removals.add(section));
          for (const player of record.patch.players) {
            if (player.x === null && prior?.players.find(before => before.id === player.id)?.x != null)
              playerLeftPitch = true;
          }
          reduced = applyMatchRecord(reduced, record);
          assert.deepEqual(reduced.view, independentRecordedView(entry.expected), `seed ${seed}, revision ${record.revision}`);
          assert.equal(entry.keyframe !== null, record.revision % 32 === 0);
          if (entry.keyframe !== null) {
            const frame = decodeMatchKeyframe(entry.keyframe);
            assert.deepEqual(frame.view, reduced.view);
            assert.deepEqual(applyMatchKeyframe(reduced, frame).driftPaths, []);
            keyframes++;
          }
        }
        return response;
      },
    };
    const result = await play(tracked, { id: 'format-4-parity', seed, rosters,
      coaches: ['bogruk', 'bogruk'], parameters: [parameters, parameters] }, config, null);
    assert.ok(result.completed, `seed ${seed} failed: ${result.error ?? result.failureCode}\n${engine.diagnostic}`);
    assert.equal(reduced.view.phase, 'FULL_TIME');
    assert.ok(records > 100 && outcomes > 100);
    assert.deepEqual([reduced.view.homeScore, reduced.view.awayScore], result.score);
    await engine.finish();
    finished = true;
    workerNanos.sort((first, second) => first - second);
    const p99 = workerNanos[Math.ceil(workerNanos.length * 0.99) - 1] / 1e6;
    console.log(`Seed ${seed} (${rosters.join(' vs ')}) matched all ${records} revisions, ${outcomes} native outcomes and ${keyframes} keyframes. Worker p99 without database I/O: ${p99.toFixed(1)} ms.`);
  } finally {
    if (!finished) await engine.close();
  }
}

for (const section of ['ball', 'passing', 'kickoff', 'activePlayerAction', 'blockContext'])
  assert.ok(removals.has(section), `Full matches must exercise removal of ${section}`);
assert.ok(playerLeftPitch, 'Full matches must exercise a player leaving the pitch');

function independentRecordedView(snapshot) {
  const { projectionVersion, callerRole, actions, prompt, homeTeamName, awayTeamName, ...expected } = snapshot;
  assert.equal(callerRole, 'spectator');
  assert.deepEqual(actions, []);
  assert.equal(prompt, null);
  if (expected.ball === null) delete expected.ball;
  expected.players = expected.players.map(({ name, ...player }) => player);
  return expected;
}
