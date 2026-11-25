import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve, relative } from 'node:path';
import { cpus, totalmem } from 'node:os';
import { measurementStatistics } from '../history-analysis.mjs';

const [fixtures, destination, mode = 'measure'] = process.argv.slice(2);
assert.ok(fixtures && destination && ['smoke', 'measure', 'trace-checkpoints'].includes(mode),
  'Usage: node tools/diagnostics/state-sync/run-storage.mjs <selected> .tools/<new-output> [smoke|measure|trace-checkpoints]');
const relativeOutput = relative(process.cwd(), resolve(destination)).replaceAll('\\', '/');
assert.match(relativeOutput, /^\.tools\/[\w-]+$/);
const java = process.env.FFB_STATE_SYNC_JAVA ?? 'java';
const classpath = readFileSync(process.env.FFB_STATE_SYNC_CLASSPATH_FILE ?? '.tools/training-classpath.txt', 'utf8').trim();
mkdirSync(destination);
if (mode !== 'trace-checkpoints') {
  execFileSync(process.execPath, ['tools/diagnostics/state-sync/prepare.mjs', fixtures], { stdio: 'inherit' });
}
const groups = new Map();
const slow = [];
for (let jvm = 1; jvm <= (mode === 'smoke' ? 1 : 3); jvm++) {
  const prefix = relativeOutput + '/run-' + jvm;
  execFileSync(java, ['-Xmx768m', '-XX:ActiveProcessorCount=2', '-XX:+UseG1GC',
    '-XX:StartFlightRecording=settings=profile,filename=' + prefix + '.jfr',
    '-Xlog:gc*:file=' + prefix + '-gc.log:uptime,level,tags', '-cp', classpath,
    'com.fumbbl.ffb.server.match.StateSyncStorageProbe', fixtures, prefix + '.json',
    mode === 'smoke' ? '1' : '3', ...(mode === 'trace-checkpoints' ? ['--trace-checkpoints'] : [])], { stdio: 'inherit' });
  for (const row of JSON.parse(readFileSync(prefix + '.json', 'utf8'))) {
    const key = [row.stage, row.move, row.phase].join('/');
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(row);
    if (row.ms > 500) slow.push({ jvm, key, ms: row.ms, allocated: row.allocated });
  }
}
const phases = {};
for (const [key, rows] of groups) {
  phases[key] = Object.fromEntries(['ms', 'allocated', 'bytes'].filter(field => rows.every(row => field in row))
    .map(field => [field, measurementStatistics(rows.map(row => row[field]))]));
  if (rows[0].parkedReply !== undefined) phases[key].parkedReplies = rows.filter(r => r.parkedReply).length;
}
writeFileSync(join(destination, 'summary.json'), JSON.stringify({
  java: execFileSync(java, ['--version'], { encoding: 'utf8' }).trim(), cpu: cpus()[0].model, memory: totalmem(),
  revision: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(), phases, slow,
}, null, 2));
