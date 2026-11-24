import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { pathToFileURL } from 'node:url';
import { javaRequiredFor, requiresJava } from '../validation/java-pr-changes.mjs';

function repository(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'java-pr-changes-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
  git('init', '--quiet', '--initial-branch=main');
  git('config', 'user.name', 'Validation Test');
  git('config', 'user.email', 'validation@example.invalid');
  git('config', 'commit.gpgsign', 'false');
  git('config', 'core.autocrlf', 'false');
  const commit = (...paths) => {
    for (const path of paths) {
      const file = join(cwd, path);
      mkdirSync(dirname(file), { recursive: true });
      writeFileSync(file, 'test content\n');
    }
    git('add', '--all');
    git('commit', '--quiet', '--allow-empty', '-m', 'Test revision');
    return git('rev-parse', 'HEAD');
  };
  return { cwd, git, commit, base: commit() };
}

test('accepts the documentation directories and Markdown outside excluded paths', t => {
  const { base, cwd, commit } = repository(t);
  const head = commit('docs/specs/decision.txt', '.notes/example.json', 'README.md', 'other/nested guide.md');
  assert.equal(requiresJava(base, head, cwd), false);
});

test('finds a Java change from an earlier commit in a mixed pull request', t => {
  const { base, cwd, commit } = repository(t);
  commit('ffb-server/Server.java');
  const head = commit('docs/specs/update.md');
  assert.equal(requiresJava(base, head, cwd), true);
});

test('allows documentation when unrelated changes landed on the base branch', t => {
  const { base, cwd, commit, git } = repository(t);
  const head = commit('docs/specs/update.md');
  git('switch', '--quiet', '--detach', base);
  const updatedBase = commit('ffb-server/Server.java');
  assert.equal(requiresJava(updatedBase, head, cwd), false);
});

test('requires Java for workflow, tooling, Java snapshot, and browser fixture paths', () => {
  for (const path of [
    '.github/workflows/README.md',
    'tools/nested/README.md',
    'browser-client/test/fixtures/example.json',
    '.notes/example/Example.java',
    'docs/example/Example.JAVA',
    '.notes/overhaul-analysis/verification/apache-maven-3.9.9-bin.zip',
  ]) {
    assert.equal(javaRequiredFor(path), true, path);
  }
});

test('accepts the exact Development history paths and requires Java for other site paths', t => {
  const { base, cwd, commit } = repository(t);
  const changes = commit('site/updates.json', 'site/src/development/index.html');
  assert.equal(requiresJava(base, changes, cwd), false);
  const mixed = commit('site/src/index.html');
  assert.equal(requiresJava(base, mixed, cwd), true);
  assert.equal(javaRequiredFor('site/updates.json.bak'), true);
  assert.equal(javaRequiredFor('site/src/development/index.html.bak'), true);
  assert.equal(javaRequiredFor('site/README.md'), true);
});

test('checks both sides when a path is moved into documentation', t => {
  const { cwd, commit, git } = repository(t);
  const base = commit('tools/README.md', 'docs/existing.md');
  git('mv', 'tools/README.md', 'docs/moved.md');
  assert.equal(requiresJava(base, commit(), cwd), true);
});

test('requires Java when the diff cannot be established', t => {
  const { base, cwd, commit } = repository(t);
  const head = commit('docs/specs/update.md');
  assert.equal(requiresJava('1'.repeat(40), head, cwd), true);
  assert.equal(requiresJava(undefined, head, cwd), true);
  assert.equal(requiresJava(base, base, cwd), true);
});

test('requires Java until the shallow checkout has the full commit history', t => {
  const { base, cwd, commit } = repository(t);
  const head = commit('docs/specs/update.md');
  const checkout = join(cwd, 'checkout');
  const git = (...args) => execFileSync('git', args, { cwd: checkout, stdio: 'pipe' });
  execFileSync('git', ['clone', '--quiet', '--depth=1', pathToFileURL(cwd).href, checkout]);
  assert.equal(requiresJava(base, head, checkout), true);
  git('fetch', '--unshallow', '--no-tags', 'origin', base, head);
  assert.equal(requiresJava(base, head, checkout), false);
});
