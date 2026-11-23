# ADR requests and decision records

Use this template for requests to document a consequential architectural choice,
especially when the deliverable is a policy, risk assessment, or ownership rule.
Use [the feature template](feature-template.md) for changes to product behavior.
Keep straightforward ADRs short; include the detailed sections below only when
they help a reviewer assess the trade-off.

## Locations and lifecycle

- Put the request and its acceptance criteria in `docs/specs/<topic>-adr.md`,
  linked to the existing GitHub issue. A request describes work to be done; it
  does not accept a decision or prove that mitigations are implemented.
- While the decision is `proposed`, keep its issue open and its PR unmerged.
  Draft it on the PR branch as `docs/adr/<slug>.md` and refer to it by the
  issue number. Link the draft to its request and issue.
- When the maintainer accepts the decision as ready for use, the PR author
  records `accepted`, the date and the approval link. In that same revision,
  take the next unused four-digit number on current `main`, rename the file to
  `docs/adr/NNNN-<slug>.md`, and update its references and the architecture map.
  Submit that numbered revision for the approving PR review and required checks.
- Recheck the number against `main` immediately before merge. If another ADR
  has taken it, update the number and references and have the revised head
  reviewed and checked. Merge the accepted decision and close its request
  when the issue's acceptance criteria are met.
- Amend a decision when its governing choice still holds. Preserve its original
  text. Add a short notice near
  the status identifying which part has changed and linking to the amendments
  section at the end. Append dated entries there in chronological order and link
  each to the approval or outcome that supports it.
- When the governing decision changes, use a replacement ADR. On acceptance,
  mark a wholly replaced record `superseded` and link both records. For a
  partial replacement, identify the specific rule and the parts still in force.
  A retired decision without a successor may be marked `deprecated`.
- When promoting a previously accepted decision, retain its original body,
  acceptance date and evidence. Label the promotion issue and request separately.
  Follow [the domain documentation layout](domain.md).
- Keep implementation tickets and their validation separate from the decision.
  Acceptance of a policy does not close its known compliance gaps.

## Request template

Replace every placeholder. Distinguish source findings, checked code, proposed
policy, and unresolved choices. Do not copy a feature's UI or build requirements
into a documentation-only request.

```md
# ADR request: <architectural choice>

Status: requested; resulting ADR not yet accepted.
Source: <issue URL and relevant discussion>
Reviewed: <date and repository revision>
Template: <relative link to this template>

## Objective

<Decision needed, why it matters, and the intended review outcome.>

## Context and evidence

<Current behavior, ownership boundaries, code links, existing ADRs, and limits
of the evidence. Explain why the choice warrants an ADR.>

## Scope and deliverables

<Required ADR, cross-references, and any prerequisites or sequencing.>
<Explicit exclusions, including implementation work tracked elsewhere.>

## Required decision content

<Rules the ADR must define, allowed/prohibited work, failure semantics,
configuration or budget selection, and compatibility constraints. Label any
candidate policy as proposed.>

## Risks and mitigation ownership

| Risk / trigger | Consequence | Required mitigation or decision | Owner / tracking | Remaining risk |
| --- | --- | --- | --- | --- |
| <risk> | <impact> | <control> | <responsible role or issue> | <residual exposure> |

## Alternatives and open decisions

<Real alternatives, trade-offs, and choices the maintainer must resolve. Do not
invent approval, measurements, thresholds, or implementation results.>

## Acceptance criteria

### Draft ready for maintainer review

- <Observable requirements for the drafted ADR and documented open decisions.>
- <Consistency with existing ADRs, evidence coverage, and checked links.>

### Accepted

- <Approval evidence, resolved policy choices, and any required tracking owners.>
- <Architecture-map invariant link and gates on subsequent implementation.>

## Validation and handoff

<Documentation checks now; evidence required from later implementation tickets.>
<Constraints, authorization limits, and any outstanding evidence gaps.>
```

## Resulting ADR template

```md
# <Decision stated directly>

Status: proposed
Issue: <issue link>
Request: <spec link>

<One to three sentences: context, chosen approach, and why.>

## Decision and boundaries

<Ownership, permitted/prohibited operations, budgets and failure behavior.>

## Alternatives considered

<Credible alternatives and why they were rejected or deferred.>

## Consequences and known gaps

<Benefits, costs, residual risks, and mitigation tickets. Clearly identify
which controls already exist and which remain to be implemented.>

## Revisit conditions

<Evidence or preconditions that would justify changing the decision.>
```

Upon acceptance, replace the proposed status with the approval date and link to
the maintainer's acceptance. Omit optional sections if the opening paragraph
fully explains a small decision.
