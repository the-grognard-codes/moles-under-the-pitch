import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { measurementStatistics } from '../history-analysis.mjs';

const [selected, results] = process.argv.slice(2);
const read = path => JSON.parse(readFileSync(path, 'utf8'));
const sizes = read(join(selected, 'sizes.json'));
const { phases } = read(join(results, 'summary.json'));
const number = n => n === null ? '—' : Number(n.toFixed(3)).toString();
const cell = row => row ? [row.count, row.p50, row.p90, row.p99, row.max].map(number).join(' / ') : '—';
const lines = ['# State sync storage measurements', '',
  'Each size is decoded JSON bytes / gzip bytes. Individual fields were compressed independently. JSON wrappers and cross-field compression affect the total.', '',
  '| Field | Early | Late | Full time |', '| --- | ---: | ---: | ---: |'];
for (const field of ['total', 'compact', ...Object.keys(sizes.early.groups)]) {
  const period = ['early', 'late', 'terminal'].map(stage => {
    const { decoded, gzip } = sizes[stage][field] ?? sizes[stage].groups[field];
    return decoded + ' / ' + gzip;
  });
  lines.push('| ' + [field, ...period].join(' | ') + ' |');
}
lines.push('', 'Each distribution gives sample count / p50 / p90 / p99 / maximum. A dash means fewer than 100 samples for p99, or a metric that does not apply. A parent duration includes its children.', '',
  '| Cohort / operation | Milliseconds | Allocated bytes | Output bytes |',
  '| --- | ---: | ---: | ---: |');
for (const [name, group] of Object.entries(phases)) {
  lines.push('| ' + [name, cell(group.ms), cell(group.allocated), cell(group.bytes)].join(' | ') + ' |');
}
const overlaps = new Map();
const publication = new Map();
const paths = new Map();
const jfr = process.env.FFB_STATE_SYNC_JAVA ? join(dirname(process.env.FFB_STATE_SYNC_JAVA), 'jfr') : 'jfr';
for (const name of readdirSync(results).filter(name => /^run-\d+\.json$/.test(name))) {
  const events = JSON.parse(execFileSync(jfr, ['print', '--json', '--events', 'jdk.GCPhasePause',
    join(results, name.replace('.json', '.jfr'))], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })).recording.events;
  const pauses = events.map(({ values }) => {
    const start = Date.parse(values.startTime);
    const ms = Number(values.duration.slice(2, -1)) * 1000;
    return [start, start + ms];
  });
  const rows = read(join(results, name));
  for (const stage of ['early', 'late', 'terminal']) {
    const baseline = rows.filter(r => r.stage === stage && r.move === 'baseline');
    for (const [path, components] of Object.entries({
      compactRestore: ['compactRead', 'compactDecode', 'compactNativeRestore'],
      currentRestore: ['jdbcRecoveryLoad', 'validateCheckpoint', 'restoreSession'],
      currentComputerFallback: ['jdbcRecoveryLoad', 'validateCheckpoint', 'computerFallback'],
    })) {
      const columns = components.map(phase => baseline.filter(r => r.phase === phase));
      if (columns.some(col => !col.length || col.length !== columns[0].length)) throw new Error('Missing ' + path);
      const group = stage + '/' + path;
      if (!paths.has(group)) paths.set(group, []);
      columns[0].forEach((_, i) => paths.get(group).push(columns.reduce((total, col) => total + col[i].ms, 0)));
    }
  }
  let lastCommit;
  for (const row of rows) {
    if (row.phase === 'commit') lastCommit = row;
    if (row.phase !== 'prototypeTask') continue;
    if (!lastCommit || lastCommit.stage !== row.stage || lastCommit.move !== row.move)
      throw new Error('Missing commit for prototype task');
    const group = row.stage + '/' + row.move;
    if (!publication.has(group)) publication.set(group, []);
    publication.get(group).push(row.ms - lastCommit.ms);
    lastCommit = undefined;
  }
  for (const row of rows.filter(row => row.ms > 500)) {
    const end = row.endUtcMs;
    const gc = pauses.reduce((sum, [a, b]) => sum + Math.max(0, Math.min(end, b) - Math.max(end - row.ms, a)), 0);
    const key = [row.stage, row.move, row.phase].join('/');
    if (!overlaps.has(key)) overlaps.set(key, []);
    overlaps.get(key).push(gc);
  }
}
lines.push('', '### GC during samples above 500 ms', '',
  'The table intersects each slow sample with JFR stop-the-world GC pause intervals. The rest of the elapsed interval includes CPU work, I/O and scheduling. Wall-clock alignment is approximate to one millisecond.', '',
  '| Cohort / operation | Pause milliseconds within slow samples (n / p50 / p90 / p99 / max) |', '| --- | ---: |');
for (const [name, values] of overlaps) lines.push('| ' + name + ' | ' + cell(measurementStatistics(values)) + ' |');
lines.push('', '### Projection and delivery', '',
  'Each sample subtracts the commit inside that same worker task. It includes public projection, patch generation, serialization, copying five payloads and measurement overhead.', '',
  '| Cohort | Milliseconds (n / p50 / p90 / p99 / max) |', '| --- | ---: |');
for (const [name, values] of publication) lines.push('| ' + name + ' | ' + cell(measurementStatistics(values)) + ' |');
lines.push('', '### Restore and fallback components within each repetition', '',
  'These totals sum the named JDBC read, decode or validation, and native restore or computer check from each individual repetition. Role-view construction is separate.', '',
  '| Cohort | Milliseconds (n / p50 / p90 / p99 / max) |', '| --- | ---: |');
for (const [name, values] of paths) lines.push('| ' + name + ' | ' + cell(measurementStatistics(values)) + ' |');
console.log(lines.join('\n'));
