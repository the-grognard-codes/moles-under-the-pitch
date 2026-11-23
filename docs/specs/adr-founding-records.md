# Feature: Bring the founding architecture decisions into the ADR log

Issue: [#610](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/610)

## Agreed delivery

- Keep active match publication at 0001 and move account-owned match-ready teams to 0009. Promote the Java, browser stack, browser protocol and application-seam decisions as 0010–0013, subject to the final check against main.
- A draft uses its issue and a slug. Merging assigns its permanent number. Acceptance requires dated maintainer approval and can occur separately.
- After merge, update #550 to cite the promoted application-seams decision and use the new draft naming and numbering conventions. #611 already uses that convention.

The request below describes the starting point at `5f6779969`. See the [delivery outcome](adr-founding-records-outcome.md) for the resulting records and verification.

## Abstract

The project's founding architecture decisions were accepted by the owner on 2026-09-06, but they live only in `.notes/overhaul-analysis/04-technology-and-decisions.md`, numbered ADR-001 to ADR-005. That is a second series beside `docs/adr`, which itself has two files numbered 0001. Numbers are also reserved when drafting starts, so parallel drafts collide.

This ticket promotes the accepted founding decisions into `docs/adr`, resolves the duplicate 0001, assigns ADR numbers at merge, and gives the architecture map a short foundations section. The match state ADR and the #550 engine ADR can then name exactly which records they replace.

## Objective

The `docs/adr` log is the only place where accepted decisions are recorded. Every accepted decision there has a unique number, and the architecture map links the foundation decisions that the current architecture rests on.

## Context / Existing Behavior

- **Founding decisions in the notes:** `.notes/overhaul-analysis/04-technology-and-decisions.md:3` records that ADR-001 to ADR-004 were accepted on 2026-09-06. ADR-002's board-renderer choice was amended on 2026-09-25, and ADR-005 is deferred.
  - ADR-001: Java backend; modernize the runtime independently (`:28`).
  - ADR-002: TypeScript, React DOM interface, PixiJS board, Vite (`:40`).
  - ADR-003: versioned JSON over browser-native WebSockets (`:67`).
  - ADR-004: application seams, not an engine rewrite prerequisite (`:79`).
  - ADR-005: hosting and operating cost, deferred (`:103`).
- **Later decisions build on them:** `docs/adr/0001-active-match-publication.md:3` says it "Refines ADR-004 in `.notes/...`". Some founding statements have since been overtaken by recorded outcomes. For example, Java 21 is now the only baseline (`docs/specs/java21-baseline-outcome.md`), and V1 is retired (`docs/specs/v1-retirement-outcome.md`).
- **Duplicate number:** the account-owned match-ready teams record and `docs/adr/0001-active-match-publication.md` were both added on 2026-09-24. `docs/specs/0001-active-match-publication.md` shares the second file's name.
- **References:** about 184 references to ADR numbers exist outside `docs/adr` on `main`. Only 0001 is ambiguous. It is referenced by filename from `.notes/overhaul-analysis/m4-workstreams-reference.md:360`, `docs/specs/0001-active-match-publication.md:69` and `docs/specs/team-builder/README.md:3`, among others.
- **Numbering rule:** `docs/agents/adr-template.md:13-14` assigns "the next unused number at drafting time". That rule caused the 0007/0008 correction on #550 and the competing 0009 claims of 2026-11-22.
- **Pending decisions:** the match state, history and live sync ADR (#611) and the #550 engine-strategy ADR will replace or amend several of these records.

## In Scope

- **Promote ADR-001 to ADR-004** into `docs/adr`, one file each.
  - Keep the original decision text, status `accepted` and the 2026-09-06 acceptance date, with a link to the notes section as source.
  - Record ADR-002's 2026-09-25 renderer amendment as a dated amendment.
  - Record later changes as dated amendments, each with a link to its evidence (for example the Java 21 baseline and the V1 retirement). Don't rewrite the original decision.
- **Number the promoted records** with the next free numbers at merge time, in their original order.
- **Resolve the duplicate 0001:** give the account-owned match-ready teams record the next free number. Keep `0001-active-match-publication.md`, which matches its spec file's name. Update every reference to the renumbered file.
- **Update references to the notes series:**
  - `docs/adr/0001-active-match-publication.md` and its spec cite the promoted application-seams record instead of "ADR-004 in `.notes`".
  - Each promoted section of the notes file gets a one-line pointer to its new record. The rest of the notes text stays as history.
- **Number at merge:**
  - Amend `docs/agents/adr-template.md` so that a draft is named `docs/adr/<slug>.md` and referred to by its issue until it merges.
  - The number is assigned at merge, as the next unused one.
  - Explain when an ADR is *superseded* and when it is *amended*: replace a decision outright rather than add another amendment once its rule has changed.
- **Foundations section in the architecture map:** a short list linking the promoted records and any accepted ADR that later replaces one. Hosting is noted as undecided.

## Out of Scope

- Promoting ADR-005 (hosting). It stays deferred in the notes and is noted as undecided in the map.
- Renumbering ADRs 0002 to 0008.
- Changing the substance of any accepted decision. Amendments only record changes already decided elsewhere.
- Turning coding standards into ADRs. `AGENTS.md` stays the standards document.
- The match state ADR (#611) and the #550 engine-strategy ADR.
- Code changes.

## Functional Requirements

- Every accepted decision appears exactly once in `docs/adr`, under a unique number.
- Each promoted record states its original acceptance date and its source, and lists later changes as dated amendments with links.
- No reference points at a renumbered file or at the notes series for a promoted decision. Historical notes may keep their original text beside the pointer.
- The ADR template's numbering rule is "assigned at merge".

## Acceptance Criteria

- Four promoted records exist in `docs/adr`, each with status, original date, source link and amendments.
- Only one file in `docs/adr` uses each number. `git grep` finds no reference to the old filename of the renumbered 0001.
- `docs/adr/0001-active-match-publication.md` and `docs/specs/0001-active-match-publication.md` cite the promoted application-seams record.
- `docs/agents/adr-template.md` states that numbers are assigned at merge, and explains superseding versus amending.
- `docs/agents/architecture-map.md` has a foundations section linking the promoted records.
- Every relative link in changed files resolves.

## Constraints

- Documentation only: no code, UI or stored-format changes.
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.
- Land this before the match state ADR (#611) and the #550 ADR are drafted, so that they can cite the promoted records by number.

## Implementation Guidance

- Use the resulting-ADR template in `docs/agents/adr-template.md` for the promoted records. Map the notes' "Decision status" line and text onto it.
- Keep the amendment lists short. One dated line with a link is enough, for example "2026-11-0x: Java 21 is the only baseline; see …".
- In the PR description, list the numbers assigned at merge so that reviewers can check the references.

## Validation

- Check every relative link in the changed files, for example with a short script that resolves the Markdown link targets.
- `git grep` for the old 0001 filename and for "ADR-00[1-4]" outside `.notes`, and confirm that what remains is intentional.

## Completion Notes

- No code changes are expected. If any are made, run the unit, browser and game-server build tests after the change.
- Failures in these checks should prompt one or more debug rounds until the work is clean, a roadblock is encountered, or user input is needed.
