import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises';
import { cpus, platform, release, totalmem } from 'node:os';
import { join, relative, resolve } from 'node:path';

import { Engine } from '../../computer-player/training/engine.mjs';
import { play } from '../../computer-player/training/match.mjs';
import { validateParameters } from '../../computer-player/src/bogruk-parameters.mjs';

const [mode, outputArgument, ...extra] = process.argv.slice(2);
assert.ok(['--smoke', '--measure'].includes(mode) && outputArgument && !extra.length,
  'Usage: node tools/diagnostics/capture-match-state-sync-fixtures.mjs --smoke|--measure .tools/<new-output-directory>');
const output = resolve(outputArgument);
const outputRelative = relative(process.cwd(), output);
assert.match(outputRelative, /^\.tools[/\\][\w-]+$/, 'Use a new .tools directory from the worktree root');

const java = process.env.FFB_STATE_SYNC_JAVA ?? (process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', platform() === 'win32' ? 'java.exe' : 'java') : 'java');
const classpath = (await readFile(process.env.FFB_STATE_SYNC_CLASSPATH_FILE ?? '.tools/training-classpath.txt', 'utf8')).trim();
const sourceCommit = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
const javaVersion = execFileSync(java, ['--version'], { encoding: 'utf8' }).trim();
const config = { timeoutMs: 900000, maxDecisions: 4000, trajectories: 'off', recklessness: 'off', traceEvery: 0 };
const earlyRevision = 50;

async function playMatch(seed, directory) {
  await mkdir(directory);
  // The GC logging option uses colons as delimiters, so keep its path relative on Windows.
  const logDirectory = relative(process.cwd(), directory).replaceAll('\\', '/');
  const jvmArguments = [
    '-XX:+UseG1GC',
    '-XX:ActiveProcessorCount=2',
    `-Dffb.stateSync.artifacts=${directory}`,
    '-Xlog:jfr+startup=off',
    `-XX:StartFlightRecording=filename=${logDirectory}/engine.jfr,settings=profile,dumponexit=true`,
    `-Xlog:gc*:file=${logDirectory}/gc.log:uptime,level,tags`,
  ];
  const engine = new Engine({ java, classpath, heapMiB: 768, matchesPerJvm: 1, priority: 'normal', jvmArguments });
  let final, terminalRevision, earlyNext, lastCommand, finished = false;
  const tracked = {
    get error() { return engine.error; },
    async command(command, timeout) {
      if (command.operation !== 'close') lastCommand = command;
      if (command.operation !== 'apply') return engine.command(command, timeout);
      const atMillis = Date.now();
      const response = await engine.command(command, timeout);
      if (response.code === 'ACCEPTED' && response.state?.revision === command.request.expectedRevision + 1) {
        const decision = { role: command.role, request: command.request, atMillis };
        if (command.request.expectedRevision === earlyRevision) earlyNext = decision;
        if (response.state.phase === 'FULL_TIME') {
          final = decision;
          terminalRevision = response.state.revision;
        }
      }
      return response;
    },
  };
  try {
    const parameters = validateParameters({});
    const result = await play(tracked, { id: 'measurement', seed, rosters: ['Human', 'Orc'],
      coaches: ['bogruk', 'bogruk'], parameters: [parameters, parameters] }, config, null,
      ({ decisions }) => { if (decisions % 100 === 0) console.log(`Seed ${seed}: ${decisions} decisions.`); });
    assert.ok(result.completed && final,
      `Seed ${seed} did not complete after ${result.decisions} decisions: ${result.failureCode}: ${result.error ?? ''}`
      + `\nLast command: ${JSON.stringify(lastCommand)}\nEngine stderr:\n${engine.diagnostic}`);
    assert.ok(terminalRevision > earlyRevision + 1);
    assert.equal(final.request.expectedRevision, terminalRevision - 1);
    await engine.finish(); // JFR writes its recording on JVM exit.
    finished = true;
    for (const log of ['engine.jfr', 'gc.log']) assert.ok((await stat(join(directory, log))).size > 0);
    const lateRevision = terminalRevision - 1;
    const artifacts = {};
    for (const [label, revision] of [['early', earlyRevision], ['late', lateRevision], ['terminal', terminalRevision]])
      artifacts[label] = await validateArtifact(directory, label, revision);
    return { seed, earlyRevision, lateRevision, terminalRevision, earlyNext, final, decisions: result.decisions,
      decisionHash: result.decisionHash, score: result.score, turns: result.turns, artifacts };
  } finally {
    if (!finished) await engine.close();
  }
}

async function validateArtifact(directory, label, expectedRevision) {
  const artifact = await readFile(join(directory, `${label}.json`), 'utf8');
  const { payload } = JSON.parse(artifact);
  const gameLog = payload.native.gameLog.commandArray;
  const records = payload.transcript.records;
  const nativeOutcomes = records.reduce((sum, record) => sum + record.native.length, 0);
  assert.equal(payload.revision, expectedRevision);
  assert.equal(records.length, expectedRevision + 1);
  assert.equal(payload.homeView.players.length, 22, 'Fixture must have 22 rostered players');
  for (const role of ['home', 'away']) assert.equal(payload.homeView.players.filter(player => player.role === role).length, 11);
  assert.ok(gameLog.length > 0, `${label} native game log is empty`);
  assert.ok(nativeOutcomes > 0, `${label} transcript has no native outcomes`);
  assert.equal(payload.pendingTerminal, label === 'terminal');
  if (label === 'terminal') {
    const record = records.at(-1);
    assert.equal(record.kind, 'FULL_TIME');
    assert.ok(!['concede', 'sizeLimit'].includes(record.decision.operation), 'Fixture must reach natural full time');
  }
  return { revision: payload.revision, bytes: Buffer.byteLength(artifact), commands: gameLog.length, nativeOutcomes };
}

await mkdir(output);
const trials = [];
const failures = [];
async function runTrial(seed, directory) {
  try {
    const trial = await playMatch(seed, join(output, directory));
    trials.push({ ...trial, directory });
    console.log(`${directory} reached full time at revision ${trial.terminalRevision}.`);
  } catch (error) {
    failures.push({ seed, directory, reason: error.message });
    console.error(error.message);
  }
}
for (const seed of mode === '--smoke' ? [43] : [43, 44, 45]) await runTrial(seed, `seed-${seed}`);
assert.ok(trials.length, 'No complete candidate match');
const longest = trials.reduce((maximum, trial) => trial.terminalRevision > maximum.terminalRevision ? trial : maximum);
const reference = trials.find(trial => trial.seed === 43 && trial.terminalRevision >= 700) ?? longest;
for (let repeat = 1; mode === '--measure' && trials.length < 3 && repeat <= 3; repeat++)
  await runTrial(reference.seed, `repeat-${repeat}-seed-${reference.seed}`);
const selectedDirectory = join(output, 'selected');
await rename(join(output, reference.directory), selectedDirectory);
await writeFile(join(selectedDirectory, 'match.json'), JSON.stringify({ ...reference, sourceCommit,
  javaVersion, heapMiB: 768, collector: 'G1', activeProcessors: 2,
  hardware: { cpu: cpus()[0].model, logicalCores: cpus().length, memoryBytes: totalmem(), platform: platform(), release: release() },
  candidates: trials.map(({ seed, terminalRevision, directory }) => ({
    seed, terminalRevision, directory: directory === reference.directory ? 'selected' : directory,
  })), failures }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ selected: reference.seed, terminalRevision: reference.terminalRevision, artifacts: reference.artifacts }));
if (mode === '--measure') assert.equal(trials.length, 3, 'Three complete matches in fresh JVMs are required');
