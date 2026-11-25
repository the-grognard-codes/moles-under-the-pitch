import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

// Preserve Java long values (including the dice RNG state) across the JSON round trip.
const parse = text => JSON.parse(text, (_, value, context) =>
  typeof value === 'number' && !Number.isSafeInteger(value) ? JSON.rawJSON(context.source) : value);
const directory = process.argv[2];
assert.ok(directory, 'Usage: node tools/diagnostics/state-sync/prepare.mjs <selected-fixture-directory>');
const write = (name, data) => writeFileSync(join(directory, name + '.json'), JSON.stringify(data));
const sizes = text => ({ decoded: Buffer.byteLength(text), gzip: gzipSync(text).byteLength });
const measurements = {};

const stages = process.argv[3] === '--early' ? ['early'] : ['early', 'late', 'terminal'];
for (const stage of stages) {
  const text = readFileSync(join(directory, stage + '.json'), 'utf8');
  const { payload, sha256 } = parse(text);
  const { native, transcript, history, eventsJson, chat, homeView, awayView, ...metadata } = payload;
  const { gameLog, ...engine } = native;
  const { records, ...cursor } = transcript;
  assert.ok(gameLog.commandArray.length && records.some(r => r.native.length), 'Fixture needs native commands and outcomes');
  assert.ok(homeView.players.length >= 22, 'Fixture needs two full teams');
  const groups = { engine, gameLog, eventsJson, transcriptState: records.map(r => r.state),
    transcriptNative: records.map(r => r.native), history, chat: chat ?? null, homeView, awayView,
    rest: { sha256, metadata, cursor, records: records.map(({ state, native, ...header }) => header) } };
  const compact = { ...metadata, native: engine, lastCapturedCommand: transcript.nativeCursor,
    recordCount: records.length, chatCount: chat?.messages.length ?? 0,
    driveTracker: homeView.driveTracker ?? null };
  if (compact.saveResume) {
    const { history: receipts, ...clocks } = compact.saveResume;
    compact.saveResume = clocks;
  }
  write(stage + '-compact', compact);
  let rare = 1;
  let maximum = 0;
  for (let i = 1; i < records.length; i++) {
    const bytes = Buffer.byteLength(JSON.stringify(records[i].native));
    if (bytes > maximum) { maximum = bytes; rare = i; }
  }
  write(stage + '-move', { previous: records.at(-2).state, current: records.at(-1), homeView, awayView,
    rarePrevious: records[rare - 1].state, rareCurrent: records[rare] });

  const groupsBySize = Object.fromEntries(Object.entries(groups).map(([field, value]) => [field, sizes(JSON.stringify(value))]));
  const compactFields = Object.fromEntries(Object.entries(compact).map(([field, value]) => [field, sizes(JSON.stringify(value)).decoded]));
  measurements[stage] = { revision: payload.revision, total: sizes(text), groups: groupsBySize,
    compact: sizes(JSON.stringify(compact)), compactFields, rareRevision: records[rare].revision, rareNativeBytes: maximum };
}
write('sizes', measurements);
