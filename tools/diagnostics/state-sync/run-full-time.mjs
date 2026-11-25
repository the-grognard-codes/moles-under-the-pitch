import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { cpus, totalmem } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { measurementStatistics } from '../history-analysis.mjs';

const [mode, destination, fixtures, ...extra] = process.argv.slice(2);
assert.ok(destination && !extra.length && (mode === 'smoke' ? !fixtures : ['measure', 'retained'].includes(mode) && fixtures),
  'Usage: node tools/diagnostics/state-sync/run-full-time.mjs smoke .tools/<new-output> | measure|retained .tools/<new-output> <selected>');
const output = relative(process.cwd(), resolve(destination)).replaceAll('\\', '/');
assert.match(output, /^\.tools\/[\w-]+$/, 'Run from the worktree root and choose a new .tools output directory');
assert.ok(process.env.STATE_SYNC_JDBC_URL && process.env.STATE_SYNC_DB_PASSWORD, 'Start the isolated state-sync MariaDB first');
const java = process.env.FFB_STATE_SYNC_JAVA ?? 'java';
const jfr = isAbsolute(java) ? join(dirname(java), process.platform === 'win32' ? 'jfr.exe' : 'jfr') : 'jfr';
const classpath = readFileSync(process.env.FFB_STATE_SYNC_CLASSPATH_FILE ?? '.tools/training-classpath.txt', 'utf8').trim();
mkdirSync(output);
const evidence = [];
const cohorts = mode === 'measure' ? ['early', 'late'] : [mode];
for (const cohort of cohorts) {
  for (let jvm = 1; jvm <= (mode === 'smoke' ? 1 : 3); jvm++) {
    const prefix = `${output}/${cohort}-${jvm}`;
    const args = ['-Xmx768m', '-XX:ActiveProcessorCount=1', '-XX:+UseG1GC', '-XX:+EnableDynamicAgentLoading',
      `-XX:StartFlightRecording=settings=profile,filename=${prefix}.jfr,dumponexit=true`,
      `-Xlog:gc*:file=${prefix}-gc.log:uptime,level,tags`, '-cp', classpath,
      'com.fumbbl.ffb.server.match.StateSyncFullTimeTest'];
    const env = { ...process.env, STATE_SYNC_FULL_TIME: cohort, STATE_SYNC_FULL_TIME_OUTPUT: resolve(prefix + '.json') };
    if (fixtures) env.STATE_SYNC_FIXTURES = resolve(fixtures);
    const { status, error } = spawnSync(java, args, { env, stdio: 'inherit' });
    if (error) throw error;
    assert.equal(status, 0, `Timing JVM failed for ${cohort} repetition ${jvm}`);
    for (const log of [prefix + '.jfr', prefix + '-gc.log']) assert.ok(statSync(log).size > 0);
    const report = JSON.parse(readFileSync(prefix + '.json', 'utf8'));
    assert.equal(report.maxHeapBytes, 768 * 1024 * 1024);
    const { recording } = JSON.parse(execFileSync(jfr, ['print', '--events', 'jdk.GCPhasePause', '--json', prefix + '.jfr'],
      { encoding: 'utf8' }));
    const pauses = recording.events.map(({ values }) => ({
      start: Date.parse(values.startTime),
      durationMs: Number(values.duration.slice(2, -1)) * 1000,
    }));
    evidence.push({ jvm, cohort, runs: report.runs, pauses });
  }
}

const groups = new Map();
const slow = [];
function record(group, value) {
  if (!groups.has(group)) groups.set(group, []);
  groups.get(group).push(value);
}
for (const { cohort, jvm, runs, pauses } of evidence) {
  for (const { deferInjected, retryTasks, coldLoadTask, tasks, protocolPayloadBytesByTask } of runs) {
    const group = cohort === 'smoke' && deferInjected ? 'smoke-deferred' : cohort;
    for (const task of [coldLoadTask, ...tasks].filter(Boolean)) {
      let stage = 'publication-retry';
      if (task === coldLoadTask) stage = 'cold-load';
      else if (task.order === 0) stage = 'terminal-request';
      else if (task.order <= retryTasks) {
        stage = Object.keys(task.phases).some(path => path.endsWith('/SetupSession.completedMatch'))
          ? 'completion-retry' : 'completion-backoff';
      }
      else if (task.order === retryTasks + 1) stage = 'follow-up-load';
      const prefix = `${group}/${stage}`;
      for (const metric of ['queueWaitNanos', 'workerNanos', 'unattributedWorkerNanos', 'allocatedBytes', 'foregroundAtStart', 'advisoryAtStart']) {
        record(`${prefix}/${metric}`, task[metric]);
      }
      if (task !== coldLoadTask) {
        for (const [role, bytes] of Object.entries(protocolPayloadBytesByTask[task.order])) {
          record(`${prefix}/${role}/protocolPayloadBytes`, bytes);
        }
      }
      const identity = { cohort, jvm, stage, startedEpochMillis: task.startedEpochMillis };
      if (task.workerNanos > 500e6) {
        const end = task.startedEpochMillis + task.transportNanos / 1e6;
        const gcPauseMs = pauses.reduce((total, pause) => total + Math.max(0,
          Math.min(end, pause.start + pause.durationMs) - Math.max(task.startedEpochMillis, pause.start)), 0);
        slow.push({ ...identity, nanos: task.workerNanos, gcPauseMs, overrun: task.overrun });
      }
      const exclusive = new Map();
      for (const [phase, sample] of Object.entries(task.phases)) {
        if (sample.nanos > 500e6) slow.push({ ...identity, phase, nanos: sample.nanos, failures: sample.failures });
        for (const metric of ['count', 'failures', 'nanos', 'selfNanos', 'allocatedBytes', 'decodedBytes', 'gzipBytes']) {
          record(`${prefix}/${phase}/${metric}`, sample[metric]);
        }
        const method = phase.split('/').at(-1);
        const keys = [`all/${method}`];
        if (phase.includes('/SetupSession.completedMatch')) keys.push(`completedMatch/${method}`);
        for (const key of keys) exclusive.set(key, (exclusive.get(key) ?? 0) + sample.selfNanos);
      }
      for (const [method, nanos] of exclusive) record(`${prefix}/exclusive/${method}/nanos`, nanos);
    }
  }
}
const phases = Object.fromEntries([...groups].map(([group, values]) => [group, measurementStatistics(values)]));
const outcomes = evidence.flatMap(({ cohort, jvm, runs }) =>
  runs.map(({ coldLoadTask, tasks, protocolPayloadBytesByTask, ...run }) => ({ cohort, jvm, ...run })));
writeFileSync(resolve(destination, 'summary.json'), JSON.stringify({
  java: execFileSync(java, ['--version'], { encoding: 'utf8' }).trim(),
  cpu: cpus()[0].model, memory: totalmem(), revision: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  outcomes, phases, slow,
}, null, 2));
