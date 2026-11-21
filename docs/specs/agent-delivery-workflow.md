# Feature: Make the idea-to-merge agent workflow the documented default

Issue: #602

## Abstract

The maintainer has set a default path for every change: the maintainer has an idea, Claude discusses it with them, Codex refines complex designs, Codex implements and opens a PR, Claude reviews the PR in comments, the two iterate, and the maintainer merges. The agent docs still describe an older process, in which implementation stops at the working tree and every commit, push and PR needs separate authorization. This change rewrites those docs so both agents follow the new default.

## Objective

Make the repository's agent instructions describe one default workflow, with clear roles, authority and hand-off points. A new Codex or Claude session that reads `AGENTS.md` should then open, review and refine PRs without per-step authorization, and never merge.

## Context / Existing Behavior

- `docs/agents/feature-workflow.md:47-53` defines the "normal process" / "usual method" as ending with "leave the changes in the working tree for user review". It says staging, committing, pushing and opening a PR each need explicit authorization.
- `docs/agents/feature-workflow.md:76-78` lands changes in `main` only "when separately authorized".
- `docs/agents/feature-workflow.md:98-118` gives authorization text that names commit, push and PR authority separately.
- No document describes Claude's review loop on a PR, or how Codex responds to review comments.
- `AGENTS.md:4` summarizes the workflow the same way: "Publishing and merging require explicit authorization".
- `CLAUDE.md:6-16` gives Claude the reviewer and planner role. Line 16 lets review results go to "a PR comment, an issue comment, or a Markdown file beside the spec, as the user directs", with no default.
- `docs/agents/github-app.md:150-155` ("Requiring approval") describes branch protection as one required approval. The active ruleset on `main`, `main-branch-protection`, now also requires:
  - approval of the latest push, with stale approvals dismissed;
  - all 18 status checks;
  - "Restrict updates", so only the repository-admin bypass can update `main`. Neither bot can merge or push to `main`.
- Each agent has its own GitHub App identity: Codex as `grognard-codex[bot]`, Claude as `grognard-claude[bot]`. `docs/agents/github-app.md` describes only the Codex app. Claude's app uses a separate data folder, selected with `MUTP_GITHUB_APP_HOME`.

## In Scope

- Rewrite `docs/agents/feature-workflow.md` around the default workflow and each participant's role.
- Update the `AGENTS.md` Delivery Workflow summary.
- Update the review-output line in `CLAUDE.md`.
- Update `docs/agents/github-app.md`:
  - make its approval section match the current ruleset;
  - say how a second agent app (Claude's) is set up beside Codex's.

## Out of Scope

- Changing the GitHub ruleset, app permissions or bypass actors.
- Changing the token tooling in `tools/github-app/`. This includes:
  - `-Mode env` not emitting `MUTP_GITHUB_APP_HOME`;
  - the Claude desktop app replacing a `PATH` set in `.claude/settings.local.json`, which keeps the `gh` shim off `PATH`.

  Track these separately if wanted.
- The maintainer's personal instructions outside the repository.
- CI, deployment or release procedure, beyond restating that they stay with the maintainer.

## Functional Requirements

1. **The default workflow.** `feature-workflow.md` states that the workflow below applies to all work unless the maintainer says otherwise. It names who acts at each stage:
   1. **Idea** — the maintainer.
   2. **Discussion and spec** — the maintainer and Claude. Claude records the agreed design as a spec in `docs/specs/` and a GitHub issue, using the existing `/grill-with-docs`, `/to-spec` and `/to-tickets` paths.
   3. **Refinement for complex tasks** — Codex. A task is complex when it needs an ADR, spans several slices, or the maintainer or Claude flags it. Codex reviews the spec for feasibility, gaps and slicing, and posts the result as an issue comment. The maintainer settles any changes before implementation starts.
   4. **Implementation** — Codex.
   5. **Review** — Claude, posted on the PR.
   6. **Refinement rounds** — Codex and Claude, as many as needed.
   7. **Merge** — the maintainer only.
2. **Codex's authority.** When an issue is labelled `ready-for-agent`, or the maintainer hands Codex the work, Codex may do the following without further authorization:
   - branch from current `main`;
   - commit;
   - push the branch;
   - open a PR that references the issue.

   The PR is opened as ready for review, unless the work is incomplete. The docs remove the old rule that every commit, push and PR needs separate authorization.
3. **The maintainer's authority.** Merging, enabling auto-merge, deployment, releases and direct pushes to `main` stay with the maintainer.
   - Neither agent merges or enables auto-merge.
   - An agent pushes to `main` only after the maintainer grants a temporary bypass for that push.
4. **How Claude reviews.**
   - Claude reviews against the issue or spec, the accepted ADRs and the architecture-map invariants.
   - Claude posts findings on the PR as a review in comment mode, with inline comments where a line applies. Each finding gives:
     - its severity;
     - its `file:line`;
     - the concrete failure scenario;
     - a suggested fix.
   - Claude separates hard defects from judgement calls.
   - Claude does not submit approving or "request changes" reviews. Merge approval belongs to the maintainer.
5. **How Codex refines.**
   - Codex addresses each finding with new commits on the same branch, not force-pushes.
   - Codex replies on each comment with the fix or the reason for declining it.
   - Codex re-runs the relevant validation.
6. **The loop.**
   - Claude re-reviews the new commits.
   - Rounds repeat until Claude reports no remaining hard defects in a summary PR comment.
   - The maintainer can stop or redirect the loop at any point.
   - The PR then waits for the maintainer to review, approve and merge.
7. **Overriding the default.** The maintainer may override any stage for a given task, for example "Claude implements this" or "skip review". The override applies to that task only.
8. **Changes to existing text:**
   - The "usual method" / "normal process" term stays, but is redefined to match the default workflow.
   - The "Authorization text for a future handoff" section is replaced by short handoff text consistent with requirements 2 and 3.
   - The Completion section (outcome report and `/retro`) is kept.
9. **`AGENTS.md`.** The Delivery Workflow bullet summarizes the default workflow in one or two sentences and links to `feature-workflow.md`.
10. **`CLAUDE.md`.** Review results go to PR comments by default; issue comments or Markdown files are used when the maintainer directs.
11. **`github-app.md`:**
    - The "Requiring approval" section describes the current ruleset and its effect: only the maintainer's admin bypass can update `main`, and an approval is dismissed by any later push.
    - A section explains running a second agent app, such as Claude's `grognard-claude`, beside Codex's. That means a separate key folder, a separate `MUTP_GITHUB_APP_HOME` data folder, and `setup-machine.ps1` run with `-AppSlug`, `-ClientId`, `-KeyFolder` and `-SkipCodexConfig`.

## Acceptance Criteria

- A reader of `AGENTS.md` and `feature-workflow.md` can answer, with no other source:
  - who acts at each of the seven stages;
  - what Codex may do without asking;
  - what only the maintainer may do;
  - how review comments are posted and resolved.
- No document in `docs/agents/`, `AGENTS.md` or `CLAUDE.md` still says that Codex needs separate authorization to commit, push or open a PR for `ready-for-agent` work.
- No document tells an agent it may merge, enable auto-merge or push to `main` without a maintainer-granted bypass.
- `github-app.md` matches the live ruleset. Check it with `gh api repos/the-grognard-codes/moles-under-the-pitch/rules/branches/main`.
- Every relative link in the changed files resolves.

## Constraints

- Documentation only: no UI, theme, gameplay or tooling changes.
- Keep the existing doc structure and voice. Prefer editing sections in place over a full rewrite where the content still holds (design-path choices, ADR guidance, Completion).
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions, including `docs/agents/issue-tracker.md` for the issue mirror.
- Commit, push and open a PR referencing this issue, as the new workflow allows. Do not merge.

## Implementation Guidance

- Put a short "Default workflow" section at the top of `feature-workflow.md`, covering the seven stages and who acts at each. The existing design-path and implementation-handoff sections then become the detail behind stages 2 to 4.
- A compact table or numbered list of stage, actor and output works well. The outputs:
  - spec and issue;
  - refinement comment;
  - PR;
  - review comments;
  - fix commits and replies;
  - merge.
- Keep the agents' GitHub identities in one place, `github-app.md`, and link to it from `feature-workflow.md`.
- No `CHANGELOG.md` entry: this change is not user-visible.

## Validation

- No new regression tests: this change is documentation only.
- Check every relative link in the changed files, for example with a quick script or by opening each target.
- Compare the "Requiring approval" text in `github-app.md` with the live ruleset, using the `gh api` command above.

## Completion Notes

- No unit, browser or game-server tests are needed: no code changes.
- Record in the PR description which sections changed in each file, and any old text intentionally kept.
