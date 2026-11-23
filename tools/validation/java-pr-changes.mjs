import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';

export function requiresJava(base, head, cwd) {
  const sha = /^[0-9a-f]{40}$/u;
  if (!sha.test(base) || !sha.test(head)) return true;

  const git = (...args) => execFileSync('git', args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    // Shallow history can hide a better common ancestor or entire sides of the diff.
    if (git('rev-parse', '--is-shallow-repository').toString().trim() !== 'false') return true;
    const mergeBase = git('merge-base', '--all', base, head).toString().trim();
    if (!sha.test(mergeBase)) return true;

    // Treat a rename as a deletion and an addition, so both paths must qualify.
    const diff = git('diff', '--name-only', '--no-renames', '--ignore-submodules=none', '-z', mergeBase, head, '--');
    if (diff.length === 0 || diff.at(-1) !== 0) return true;
    const paths = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })
      .decode(diff).slice(0, -1).split('\0');
    return paths.some(path =>
      path.startsWith('tools/') ||
      path.startsWith('.github/workflows/') ||
      !(path.startsWith('docs/') || path.startsWith('.notes/') || path.endsWith('.md'))
    );
  } catch {
    return true;
  }
}

if (import.meta.main) {
  const java = requiresJava(process.env.PR_BASE_SHA, process.env.PR_HEAD_SHA);
  console.log(java
    ? 'Java verification required. The diff contains other changes or could not be confirmed as documentation-only.'
    : 'Confirmed that the complete pull request diff is documentation-only.');
  appendFileSync(process.env.GITHUB_OUTPUT, `java=${java}\n`);
}
