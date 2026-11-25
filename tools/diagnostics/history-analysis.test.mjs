import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdtempDisposableSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { measurementStatistics, sampleStatistics } from './history-analysis.mjs';

test('sample statistics preserve exact medians and each analyzer’s p95 policy', () => {
  const values = [10, 1, 4, 2];
  assert.deepEqual(sampleStatistics(values), { count: 4, median: 3, p95: 10, max: 10 });
  assert.deepEqual(values, [10, 1, 4, 2]);

  const twenty = Array.from({ length: 20 }, (_, index) => index + 1);
  assert.deepEqual(sampleStatistics(twenty.slice(0, 19), 20), { count: 19, median: 10, p95: null, max: 19 });
  assert.deepEqual(sampleStatistics(twenty, 20), { count: 20, median: 10.5, p95: 19, max: 20 });

  for (const invalid of [[], [-1], [NaN], [Infinity]]) {
    assert.throws(() => sampleStatistics(invalid), { name: 'AssertionError' });
  }
});

test('state sync measurements report p99 only with at least 100 samples', () => {
  const values = Array.from({ length: 100 }, (_, index) => index + 1);
  assert.deepEqual(measurementStatistics([]), { count: 0, p50: null, p90: null, p99: null, max: null });
  assert.deepEqual(measurementStatistics(values.slice(0, 99)), { count: 99, p50: 50, p90: 90, p99: null, max: 99 });
  assert.deepEqual(measurementStatistics(values), { count: 100, p50: 50.5, p90: 90, p99: 99, max: 100 });
});

test('analyzers write compatible summaries and fingerprint all normalized sources', context => {
  const workspace = mkdtempDisposableSync(join(tmpdir(), 'history-analysis-'));
  context.after(() => workspace.remove());
  const directory = workspace.path;
  const writeJson = (name, value) => writeFileSync(join(directory, name), JSON.stringify(value));
  const readText = name => readFileSync(join(directory, name), 'utf8');
  const readJson = name => JSON.parse(readText(name));

  const fixtures = [0, 1].map(addedDecisions => ({ addedDecisions, artifactBytes: 1024, file: `${addedDecisions}.json` }));
  const cohorts = fixtures.flatMap(({ addedDecisions }) => ['move', 'pass'].flatMap(action => ['journal', 'compaction']
    .map(storagePath => ({ addedDecisions, action, storagePath }))));
  const sample = duration => ({
    timing: Object.fromEntries(['handlingMs', 'sessionApplyMs', 'checkpointMs', 'recoverySerializeMs',
      'recoveryCursorMs', 'recoveryFingerprintMs', 'checkpointStoreMs'].map(field => [field, duration])),
    allocatedBytes: 1024,
    coldLoadMs: 1,
  });
  writeJson('provenance.json', { samplesPerCohort: 2, sourceCommit: 'abc123', limitations: [] });
  writeJson('fixtures.json', { fixtures });
  writeJson('verification.json', { maxHeapBytes: 2048 * 1048576, verified: cohorts.map(({ addedDecisions, action, storagePath }) =>
    ({ addedDecisions, action, journal: storagePath === 'journal', verified: true })) });
  for (const repeat of [1, 2, 3]) {
    writeJson(`run-${repeat}.json`, { repeat, maxHeapBytes: 768 * 1048576, javaVersion: '21', results: cohorts.map(cohort =>
      ({ ...cohort, firstSample: sample(9), samples: [sample(1), sample(2)] })) });
  }

  const history = 'analyze-history-scaling.mjs';
  const firstWarm = 'analyze-checkpoint-first-warm.mjs';
  const helper = 'history-analysis.mjs';
  const writeScripts = lineEnding => {
    for (const name of [history, firstWarm, helper]) {
      const text = readFileSync(new URL(name, import.meta.url), 'utf8');
      writeFileSync(join(directory, name), text.replace(/\r?\n/g, lineEnding));
    }
  };
  const run = (script, ...options) => execFileSync(process.execPath, [join(directory, script), directory, ...options]);

  writeScripts('\n');
  run(history, '--assert-flat');
  assert.deepEqual(readJson('summary.json').rows[0].checkpointMs, { count: 6, median: 1.5, p95: 2, max: 2 });
  assert.match(readText('summary.md'), /^# Retained-history action scaling\n/);

  run(firstWarm, '--checkpoint-ceiling-ms=9');
  const summary = readJson('first-warm-summary.json');
  assert.match(summary.analyzerSha256, /^[a-f0-9]{64}$/);
  assert.deepEqual(summary.rows[0].phases.checkpointMs, { count: 3, median: 9, p95: null, max: 9 });
  assert.deepEqual(summary.rows[1].phases.checkpointMs, { count: 6, median: 1.5, p95: null, max: 2 });
  assert.match(readText('first-warm-summary.md'), /9\.00 \/ n\/a \/ 9\.00/);

  writeScripts('\r\n');
  run(firstWarm);
  assert.deepEqual(readJson('first-warm-summary.json'), summary);

  for (const source of [firstWarm, helper]) {
    writeScripts('\n');
    appendFileSync(join(directory, source), '\n// Source fingerprint variation.\n');
    run(firstWarm);
    assert.notEqual(readJson('first-warm-summary.json').analyzerSha256, summary.analyzerSha256, source);
  }
});
