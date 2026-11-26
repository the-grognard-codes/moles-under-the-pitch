import assert from 'node:assert/strict';
import { createReadStream } from 'node:fs';
import { createInterface } from 'node:readline';

import { decodeMatchKeyframe, decodeMatchRecord } from '../../browser-client/src/match-record-protocol.ts';
import { applyMatchKeyframe, applyMatchRecord, startMatchReduction } from '../../browser-client/src/match-record-reducer.ts';

export function createFormat4ParityVerifier(label) {
  let reduced, records = 0, outcomes = 0, keyframes = 0;
  let lastNative = 0, lastAt = 0, playerLeftPitch = false;
  const removals = new Set();

  return {
    accept(entry) {
      const record = decodeMatchRecord(entry.record);
      reduced ??= startMatchReduction(record.matchId);
      const prior = reduced.view;
      assert.equal(record.revision, records++);
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
      assert.deepEqual(reduced.view, independentRecordedView(entry.expected), `${label}, revision ${record.revision}`);
      assert.equal(entry.keyframe !== null, record.revision % 32 === 0);
      if (entry.keyframe !== null) {
        const frame = decodeMatchKeyframe(entry.keyframe);
        assert.deepEqual(frame.view, reduced.view);
        assert.deepEqual(applyMatchKeyframe(reduced, frame).driftPaths, []);
        keyframes++;
      }
    },
    finish() {
      assert.equal(reduced?.view?.phase, 'FULL_TIME', `${label} must cover the complete match`);
      assert.ok(records > 1 && outcomes > 0, `${label} must capture real native outcomes`);
      return { view: reduced.view, records, outcomes, keyframes, removals, playerLeftPitch };
    },
  };
}

function independentRecordedView(snapshot) {
  const { projectionVersion, callerRole, actions, prompt, homeTeamName, awayTeamName, ...expected } = snapshot;
  assert.equal(callerRole, 'spectator');
  assert.deepEqual(actions, []);
  assert.equal(prompt, null);
  if (expected.ball === null) delete expected.ball;
  expected.players = expected.players.map(({ name, ...player }) => player);
  return expected;
}

if (import.meta.main) {
  const [path] = process.argv.slice(2);
  assert.ok(path, 'Expected a file of captured format-4 revisions');
  const verifier = createFormat4ParityVerifier('CI seeded match');
  const lines = createInterface({ input: createReadStream(path, { encoding: 'utf8' }), crlfDelay: Infinity });
  for await (const line of lines) verifier.accept(JSON.parse(line));
  const { records, outcomes, keyframes } = verifier.finish();
  console.log(`Verified ${records} revisions, ${outcomes} native outcomes and ${keyframes} keyframes.`);
}
