import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

export function sampleStatistics(values, minimumP95Samples = 1) {
  assert.ok(values.length && values.every(value => Number.isFinite(value) && value >= 0));
  const sorted = [...values].sort((a, b) => a - b);
  const count = sorted.length;
  const middle = Math.floor(count / 2);
  const median = count % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  const p95 = count >= minimumP95Samples ? sorted[Math.ceil(count * .95) - 1] : null;
  return { count, median, p95, max: sorted.at(-1) };
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

export function analyzerSourceSha256(analyzerUrl) {
  const hash = createHash('sha256');
  for (const url of [analyzerUrl, import.meta.url]) {
    const source = readFileSync(new URL(url), 'utf8').replaceAll('\r\n', '\n');
    // Frame each source so the file boundary is part of the fingerprint.
    hash.update(`${Buffer.byteLength(source)}:`).update(source);
  }
  return hash.digest('hex');
}
