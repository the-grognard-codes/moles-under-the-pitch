import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

function sortedSamples(values) {
  assert.ok(values.length && values.every(value => Number.isFinite(value) && value >= 0));
  return [...values].sort((a, b) => a - b);
}

function median(sorted) {
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

function nearestRank(sorted, percentile) {
  return sorted[Math.ceil(sorted.length * percentile) - 1];
}

export function sampleStatistics(values, minimumP95Samples = 1) {
  const sorted = sortedSamples(values);
  const count = sorted.length;
  const p95 = count >= minimumP95Samples ? nearestRank(sorted, .95) : null;
  return { count, median: median(sorted), p95, max: sorted.at(-1) };
}

export function measurementStatistics(values) {
  if (!values.length) return { count: 0, p50: null, p90: null, p99: null, max: null };
  const sorted = sortedSamples(values);
  return { count: sorted.length, p50: median(sorted), p90: nearestRank(sorted, .90),
    p99: sorted.length >= 100 ? nearestRank(sorted, .99) : null, max: sorted.at(-1) };
}

export function analysisFiles(directory) {
  return {
    readJson(name) {
      return JSON.parse(readFileSync(resolve(directory, name), 'utf8'));
    },
    writeJson(name, data) {
      writeFileSync(resolve(directory, name), JSON.stringify(data, null, 2));
    },
    writeMarkdown(name, lines) {
      writeFileSync(resolve(directory, name), lines.join('\n'));
    },
  };
}

export function readHistoryEvidence(readJson) {
  return {
    provenance: readJson('provenance.json'),
    fixtures: readJson('fixtures.json').fixtures,
    verification: readJson('verification.json'),
    runs: [1, 2, 3].map(index => readJson(`run-${index}.json`)),
  };
}

export function analyzerSourceSha256(analyzerUrl) {
  const hash = createHash('sha256');
  for (const url of [analyzerUrl, import.meta.url]) {
    const source = readFileSync(new URL(url), 'utf8').replaceAll('\r\n', '\n');
    // Frame each source so the file boundary is part of the fingerprint.
    hash.update(`${Buffer.byteLength(source)}:`).update(source);
  }
  return hash.digest('hex');
}
