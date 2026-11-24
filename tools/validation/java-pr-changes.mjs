import { execFileSync } from 'node:child_process';
import { appendFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const fullSha = /^[0-9a-f]{40}$/u;
const updateHistory = new Set(['site/updates.json', 'site/src/development/index.html']);

export function javaRequiredFor(path) {
  if (path.startsWith('site/')) return !updateHistory.has(path);
  // The shard workflow scans Java sources across the checkout, including archived sources.
  return /\.java$/iu.test(path) ||
    path.startsWith('tools/') ||
    path.startsWith('.github/workflows/') ||
    // Bootstrap can use its audit ZIP from this directory on a cold cache.
    path.startsWith('.notes/overhaul-analysis/verification/') ||
    !(path.startsWith('docs/') || path.startsWith('.notes/') || path.endsWith('.md'));
}

export function requiresJava(base, head, cwd) {
  if (!fullSha.test(base) || !fullSha.test(head)) return true;

  const git = (...args) => execFileSync('git', args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    // Shallow history can hide a better common ancestor or entire sides of the diff.
    if (git('rev-parse', '--is-shallow-repository').toString().trim() !== 'false') return true;
    const mergeBase = git('merge-base', '--all', base, head).toString().trim();
    if (!fullSha.test(mergeBase)) return true;

    // Treat a rename as a deletion and an addition, so both paths must qualify.
    const diff = git('diff', '--name-only', '--no-renames', '--ignore-submodules=none', '-z', mergeBase, head, '--');
    if (diff.length === 0 || diff.at(-1) !== 0) return true;
    const paths = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })
      .decode(diff).slice(0, -1).split('\0');
    return paths.some(javaRequiredFor);
  } catch {
    return true;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (!process.env.GITHUB_OUTPUT) throw new Error('Missing workflow output file');
  const javaRequired = requiresJava(process.env.PR_BASE_SHA, process.env.PR_HEAD_SHA);
  await appendFile(process.env.GITHUB_OUTPUT, `java_required=${javaRequired}\n`);
  console.log(javaRequired
    ? 'Java required. The pull request diff needs verification or could not be determined.'
    : 'Java not required for the complete pull request diff.');
}
