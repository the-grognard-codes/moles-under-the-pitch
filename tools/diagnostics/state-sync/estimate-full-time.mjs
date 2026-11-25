import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

const directory = process.argv[2];
assert.ok(directory && process.argv.length === 3,
  'Usage: node tools/diagnostics/state-sync/estimate-full-time.mjs <fixture-directory>');

function parse(text) {
  return JSON.parse(text, (_, value, context) =>
    typeof value === 'number' && !Number.isSafeInteger(value) ? JSON.rawJSON(context.source) : value);
}

function sizes(text) {
  return { decoded: Buffer.byteLength(text), gzip: gzipSync(text).byteLength };
}

function measure(stage) {
  const text = readFileSync(join(directory, stage + '.json'), 'utf8');
  const { payload } = parse(text);
  const { ruleset, catalogVersion, presetId, presetVersion } = payload.frozen.home;
  const { homeScore, awayScore, players } = payload.homeView;
  const { revision, engineVersion, matchId, transcript, chat } = payload;
  assert.equal(players.length, 22, 'Expected two eleven-player rosters');
  assert.equal(payload.pendingTerminal, stage === 'terminal');
  assert.equal(transcript.records.length, revision + 1);
  assert.ok(payload.native.gameLog.commandArray.length && transcript.records.some(record => record.native.length),
    'Expected commands and native outcomes');
  if (stage === 'terminal') assert.equal(transcript.records.at(-1).kind, 'FULL_TIME');

  // Fields of SetupSession.completedMatch in CanonicalJson key order. At active
  // revisions this sizes the history a completed result would contain so far.
  const completion = { awayScore, catalogVersion, chat, engineVersion, events: parse(payload.eventsJson),
    finalRevision: revision, formatVersion: chat ? 3 : 2, homeScore, matchId, presetId, presetVersion, ruleset, transcript };
  return { revision, checkpoint: sizes(text), completion: sizes(JSON.stringify(completion)) };
}

const early = measure('early');
const late = measure('late');
const terminal = measure('terminal');
assert.ok(early.revision < late.revision && terminal.revision === late.revision + 1);
const projection = {};
for (const field of ['checkpoint', 'completion']) {
  projection[field] = {};
  for (const size of ['decoded', 'gzip']) {
    const bytesPerRevision = (late[field][size] - early[field][size]) / (late.revision - early.revision);
    const projected = Math.round(late[field][size] + bytesPerRevision);
    projection[field][size] = { projected, terminalMinusProjected: terminal[field][size] - projected };
  }
}
console.log(JSON.stringify({ early, late, terminal, projection }, null, 2));
