# Feature design and delivery workflow

## Default workflow

Follow these stages unless the maintainer overrides a stage for the current
task. The design record supplies the acceptance criteria, and GitHub issues
track implementation.

| Stage | Owner | Output |
| --- | --- | --- |
| 1. Idea | Maintainer | Problem or outcome to discuss |
| 2. Discussion and spec | Maintainer and Claude | Agreed design, GitHub issue, and local spec in `docs/specs/` |
| 3. Refinement for complex work | Codex, with the maintainer settling changes | Issue comment on feasibility, gaps, and implementation slices |
| 4. Implementation | Codex | Validated topic branch and PR linked to the issue |
| 5. Review | Claude | PR review in comment mode |
| 6. Refinement rounds | Codex and Claude | Fix commits, replies, and review of the updated PR |
| 7. Merge | Maintainer | Reviewed PR merged into `main` |

Use [the repository's GitHub App identities](github-app.md) for agent delivery
and review.

## Choose the design path

### A consequential decision in a major improvement or feature

1. The maintainer and Claude use `/grill-with-docs` to settle the scope,
   requirements, success criteria, constraints, and alternatives. Investigate
   facts in the repository or primary sources. Use a prototype when a design
   question needs a runnable answer.
2. Record an ADR when a decision is hard to reverse, would surprise a future
   reader, and involves a real trade-off. Refine the ADR until its decision and
   reason are clear. A large feature does not automatically need an ADR.
3. Claude reviews the proposed behavior and decisions with the maintainer.
   Use `/to-spec` to capture the agreed outcome, implementation and testing
   decisions, and exclusions in a parent GitHub issue and a local spec.
   Follow the [issue tracker conventions](issue-tracker.md).
4. Identify the required technical and nontechnical workstreams, their owners
   or responsible roles, deliverables, dependencies, and acceptance evidence.
   Use `/to-tickets` for independently verifiable implementation slices and
   their blocking relationships. Create separate tracked deliverables for
   nontechnical work that cannot fit a vertical implementation slice.
5. For work that needs an ADR, spans several slices, or is flagged as complex
   by Claude or the maintainer, Codex posts its feasibility, gaps, and slicing
   review on the issue. The maintainer settles the proposed changes before
   implementation. Apply this step to focused work that meets the same criteria.
6. Treat each approved issue and its linked design records as the durable
   handoff for that workstream. Use `/handoff` when moving a live conversation
   to another session, directory, or person and point it to the existing records.

### A focused design without a consequential decision

1. Claude discusses the idea or requirement with the maintainer and records the
   agreed scope, success criteria, constraints, and exclusions in the issue and
   its local mirror.
2. For work that spans sessions, use `/to-spec` for the parent spec and
   `/to-tickets` for independently verifiable slices. Include genuine blocking
   relationships. A single focused change can proceed from its approved issue.
3. Hand each ready slice to the implementation workflow below. Complete slices
   in dependency order; independent slices may proceed when their blockers are
   done.

## Implementation handoff for each slice

The **normal process**, also called the **usual method**, applies when Codex
takes up an issue marked `ready-for-agent` or the maintainer hands off agreed
work. Codex may branch from current `main`, stage and
commit the scoped changes, push the topic branch, open its PR, and push review
refinements. Runtime command approvals still apply.

The handoff identifies the issue, design record, acceptance criteria, branch
base, and relevant constraints.

1. Separate the work into independently verifiable slices. Create or reuse a
   GitHub issue for each required slice and keep a local mirror as described in
   [issue-tracker.md](issue-tracker.md).
2. Start a short-lived branch from current `main`, using a separate worktree
   when the current checkout contains other work.
3. Implement the slice and its relevant tests, documentation, change-list entry,
   and media or visual assets. Keep the slice independently verifiable.
4. Self-review the diff against the issue and design intent, including behavior,
   repository standards, and unintended scope. Fix findings.
5. Run focused checks and the relevant project-required validation. Inspect
   visual or media output in its actual use context when applicable.
6. Push the topic branch by explicit ref and open a PR that references the
   issue. Open it ready for review when the slice is complete or as a draft
   while work remains. Send the maintainer the PR link.

## PR review and merge

1. Claude independently reviews the PR against the issue or spec, accepted
   ADRs, and the [architecture map](architecture-map.md). Submit a GitHub
   review in comment mode, with inline findings where a line applies. Give
   each finding a severity, `file:line`, a concrete failure scenario, and a
   suggested fix. Distinguish defects from judgment calls.
2. Codex addresses the findings with new commits on the same topic branch,
   reruns relevant validation, and pushes normally. Reply to each finding
   with the fix or the reason for declining it.
3. Claude reviews the updated head and posts a summary when no hard defects
   remain. Repeat the review and refinement steps until that summary or until
   the maintainer redirects the work.
4. Codex verifies the PR head, review decision, merge state, and required
   checks with `gh pr view` and `gh pr checks --required`. Check the active
   [main rules](github-app.md#requiring-approval) and hand the maintainer the
   PR link, head, scope, checks, and any blockers.
5. The maintainer handles formal approval and merges the PR. Auto-merge,
   deployments, releases, direct pushes to `main`, and protection changes
   are reserved for the maintainer. A direct push to `main` by an agent
   requires an explicit, temporary bypass from the maintainer for that push.

Follow [deployment usage](../../deployment/README.md) when the maintainer
authorizes deployment.

## Completion

Compare the finished result with the original plan and parent spec. Check every
functional requirement, visual and media requirement, exclusion, and the
original intent against the implemented work and acceptance evidence. Record
matches, changes made during implementation, remaining gaps, issue links, and
validation evidence in a Markdown outcome report next to the local design mirror.
Present that report to the user.

After the maintainer confirms the merge, verify the actual merge commit and
timestamp on GitHub. For meaningful changes, check whether the PR is already in
`site/updates.json` or an open updates PR. Record uncovered changes through a
follow-up PR using the site's [update-history workflow](../../site/README.md).

Then use `/retro` to examine what the coding sessions suggest improving in the
agent's environment. Its current purpose is process improvement; the outcome
report above is the product acceptance review.

## Authorization text for a future handoff

Name the agreed scope and use:

> Implement the agreed work using the usual method.

For an approved spec, multiple issues or tickets, or work organized around an
ADR or design with several implementation slices, optionally append:

> Repeat until all slices are complete.

When included, this clause carries implementation, self-review, validation, and
reporting through every approved slice in dependency order.

This authorization applies to the named work. Record scope changes against the
spec before implementation continues. The maintainer merges the PR and
authorizes any release or deployment separately.
