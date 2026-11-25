import assert from 'node:assert/strict';
import { statSync } from 'node:fs';
import { join } from 'node:path';

import { analysisFiles, analyzerSourceSha256, measurementStatistics } from './history-analysis.mjs';

const [directory, ...extra] = process.argv.slice(2);
const moveOnly = directory === '--move-only';
assert.ok(directory && (moveOnly ? extra.length : !extra.length),
  'Usage: node tools/diagnostics/analyze-state-sync-move-timing.mjs <capture-directory> | --move-only <trial-directory>...');
const files = moveOnly ? null : analysisFiles(directory);
const metadata = moveOnly ? { earlyRevision: 50 } : files.readJson('selected/match.json');
const phases = ['nativeCapture', 'blockCopy', 'transferCopy'];
const halfWindow = 25;

function validateMoves(moves, candidate) {
  assert.equal(moves.length, candidate.terminalRevision, 'Every committed revision must have a move sample');
  assert.ok(moves.every((row, index) => row.revision === index + 1));
  assert.ok(moves.some(row => row.nativeCapture.length), 'No native capture timings found');
  assert.ok(moves.some(row => row.blockCopy.length), 'No block preview copy timings found');
}

const runs = moveOnly ? extra.map(label => {
  const trial = analysisFiles(label);
  const { payload } = trial.readJson('late-journal.json');
  const terminal = payload.state.transcript.records.at(-1);
  assert.equal(terminal.kind, 'FULL_TIME', 'Trial must contain its natural terminal revision');
  assert.ok(!['concede', 'sizeLimit'].includes(terminal.decision.operation), 'Trial must reach natural full time');
  const candidate = { label, terminalRevision: terminal.revision };
  const moves = trial.readJson('move-timing.json');
  validateMoves(moves, candidate);
  return { ...candidate, moves, journal: [] };
}) : metadata.candidates.map(candidate => {
  const label = candidate.directory ?? (candidate.seed === metadata.seed ? 'selected' : `seed-${candidate.seed}`);
  const moves = files.readJson(`${label}/move-timing.json`);
  const journal = files.readJson(`${label}/journal-cadence.json`);
  validateMoves(moves, candidate);
  assert.equal(journal.at(-1).path, 'terminal-checkpoint');
  return { ...candidate, label, moves, journal };
});

function summarize(rows) {
  const summary = { moves: rows.length, fixtureApplyMs: measurementStatistics(rows.map(row => row.applyNanos / 1e6)) };
  for (const phase of phases) {
    const samples = rows.flatMap(row => row[phase]);
    summary[phase] = {
      callsPerMove: measurementStatistics(rows.map(row => row[phase].length)),
      perMoveMs: measurementStatistics(rows.map(row => row[phase].reduce((sum, sample) => sum + sample.nanos, 0) / 1e6)),
      perCallMs: measurementStatistics(samples.map(sample => sample.nanos / 1e6)),
      perCallBytes: measurementStatistics(samples.map(sample => sample.bytes)),
    };
  }
  return summary;
}

function inWindow(revision, run, cohort) {
  const last = run.terminalRevision - 1;
  return cohort === 'early'
    ? revision >= metadata.earlyRevision - halfWindow && revision < metadata.earlyRevision + halfWindow
    : revision >= last - halfWindow * 2 && revision < last;
}

const early = summarize(runs.flatMap(run => run.moves.filter(row => inWindow(row.beforeRevision, run, 'early'))));
const late = summarize(runs.flatMap(run => run.moves.filter(row => inWindow(row.beforeRevision, run, 'late'))));

const ratio = (lateMedian, earlyMedian) => lateMedian !== null && earlyMedian > 0 ? lateMedian / earlyMedian : null;
const lateToEarly = Object.fromEntries(phases.map(phase => [phase, {
  callsPerMoveP50: ratio(late[phase].callsPerMove.p50, early[phase].callsPerMove.p50),
  perMoveMsP50: ratio(late[phase].perMoveMs.p50, early[phase].perMoveMs.p50),
  perCallMsP50: ratio(late[phase].perCallMs.p50, early[phase].perCallMs.p50),
  perCallBytesP50: ratio(late[phase].perCallBytes.p50, early[phase].perCallBytes.p50),
}]));

const chosenRun = runs.find(run => run.label === 'selected');
const compactions = chosenRun?.journal.filter(row => row.path === 'compaction');
const journalCadence = chosenRun && {
  seed: chosenRun.seed,
  appendEntryBytes: measurementStatistics(chosenRun.journal.filter(row => row.path === 'journal').map(row => row.entryBytes)),
  appendEntriesBeforeCompaction: measurementStatistics(compactions.map(row => row.precedingJournalEntries)),
  appendBytesBeforeCompaction: measurementStatistics(compactions.map(row => row.precedingJournalBytes)),
  revisionsBetweenCompactions: measurementStatistics(compactions.map(row => row.revisionsSinceCompaction)),
  earlyFirstEntryBytes: statSync(join(directory, 'selected', 'early-journal.json')).size,
  lateTerminalDeltaBytes: statSync(join(directory, 'selected', 'late-journal.json')).size,
};
const firstCompactions = runs.flatMap(run => {
  const row = run.journal.find(row => row.path === 'compaction');
  return row?.checkpointBuildNanos === undefined ? [] : [{ trial: run.label, seed: run.seed, revision: row.revision,
    checkpointBytes: row.checkpointBytes, journalDecisionMs: row.journalBuildNanos / 1e6, checkpointBuildMs: row.checkpointBuildNanos / 1e6 }];
});
function journalBuildStatistics(cohort) {
  const rows = runs.flatMap(run => run.journal.filter(row => inWindow(row.revision - 1, run, cohort)));
  const appends = rows.filter(row => row.path === 'journal' && row.journalBuildNanos !== undefined);
  const compactions = rows.filter(row => row.path === 'compaction' && row.checkpointBuildNanos !== undefined);
  return {
    appendMs: measurementStatistics(appends.map(row => row.journalBuildNanos / 1e6)),
    appendBytes: measurementStatistics(appends.map(row => row.entryBytes)),
    compactionMs: measurementStatistics(compactions.map(row => (row.journalBuildNanos + row.checkpointBuildNanos) / 1e6)),
    checkpointBytes: measurementStatistics(compactions.map(row => row.checkpointBytes)),
  };
}
const journalBuild = { early: journalBuildStatistics('early'), late: journalBuildStatistics('late'), firstCompactions };
const slow = runs.flatMap(run => run.moves.filter(row => row.applyNanos > 500e6).map(row => ({
  trial: run.label, seed: run.seed, revision: row.revision, operation: row.operation, fixtureApplyMs: row.applyNanos / 1e6,
  measuredPhases: Object.fromEntries(phases.map(phase => [phase, row[phase].reduce((sum, sample) => sum + sample.nanos, 0) / 1e6])),
})));
const slowApplyAttribution = {
  count: slow.length,
  elapsedMs: slow.reduce((total, row) => total + row.fixtureApplyMs, 0),
  byPhaseMs: Object.fromEntries(phases.map(phase => [phase, slow.reduce((total, row) => total + row.measuredPhases[phase], 0)])),
};
const summary = {
  sourceCommit: metadata.sourceCommit, javaVersion: metadata.javaVersion, hardware: metadata.hardware,
  analyzerSha256: analyzerSourceSha256(import.meta.url), heapMiB: metadata.heapMiB,
  candidates: runs.map(({ label, seed, terminalRevision }) => ({ label, seed, terminalRevision })),
  freshJvmCount: runs.length, early, late, lateToEarly, journalCadence, journalBuild, slowApplyAttribution, slowFixtureApplies: slow,
};
if (moveOnly) console.log(JSON.stringify(summary));
else {
  files.writeJson('move-summary.json', summary);
  console.log(JSON.stringify({ ...summary, slowFixtureApplies: slow.length }));
}
