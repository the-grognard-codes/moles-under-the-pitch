import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { basename, join } from 'node:path';

const [mode, ...directories] = process.argv.slice(2);
assert.ok((mode === 'compare' && directories.length >= 2) ||
  (mode === 'trace-checkpoints' && directories.length === 1),
  'Usage: node tools/diagnostics/state-sync/storage-rounds-report.mjs compare <run> <run> ... | trace-checkpoints <run>');

function phases(directory) {
  return JSON.parse(readFileSync(join(directory, 'summary.json'), 'utf8')).phases;
}

const stages = ['early', 'late', 'terminal'];

function table(label, runs, cell) {
  console.log(`| Run | Early ${label} (ms) | Late ${label} (ms) | Terminal ${label} (ms) |`);
  console.log('| --- | ---: | ---: | ---: |');
  for (const [name, timings] of runs) {
    console.log(`| ${name} | ${stages.map(stage => cell(timings, stage)).join(' | ')} |`);
  }
}

if (mode === 'compare') {
  const runs = directories.map(directory => [basename(directory), phases(directory)]);
  const variants = ['ordinary', 'keyframe', 'rare'];
  for (const [label, phase] of [['commit p99', 'commit'], ['whole-task p99', 'prototypeTask']]) {
    table(label, runs, (timings, stage) => variants.map(variant => {
      const stats = timings[`${stage}/${variant}/${phase}`].ms;
      assert.ok(stats.count >= 100 && Number.isFinite(stats.p99));
      return stats.p99.toFixed(2);
    }).join(' / '));
    console.log();
  }
  table('DB round trip p50 / p90 / max', runs, (timings, stage) => {
    const stats = timings[`${stage}/baseline/dbRoundTrip`].ms;
    return [stats.p50, stats.p90, stats.max].map(value => value.toFixed(2)).join(' / ');
  });
} else {
  const timings = phases(directories[0]);
  const format = stats => [stats.count, stats.p50.toFixed(2), stats.p90.toFixed(2), stats.max.toFixed(2)].join(' / ');
  const names = ['checkpointStore', 'STORAGE_ENCODE', 'STORAGE_COMPRESSION', 'CHECKPOINT_SQL', 'DB_CONNECT', 'DB_WRITE', 'DB_COMMIT'];
  console.log('| Phase | Early (n / p50 / p90 / max ms) | Late (n / p50 / p90 / max ms) |');
  console.log('| --- | ---: | ---: |');
  for (const name of names) {
    console.log(`| ${name} | ${format(timings[`early/baseline/${name}`].ms)} | ${format(timings[`late/baseline/${name}`].ms)} |`);
  }
}
