# Feature: Let the drive tracker own its summary instead of the transcript

Issue: [#654](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/654)

Part of #651 (ticket 3). Moved from #548.

## Abstract

The match transcript owns the drive tracker's presentation state and feeds it every stored record. Give the tracker its own summary of the current drive, fed with current facts, so that the transcript has no presentation state and a later restore doesn't need to read records for it.

## Objective

`MatchTranscript` no longer refers to the drive tracker, and the tracker shows the same thing live, after recovery and in replay.

## Context / Existing Behavior

Line numbers are at `main` `eb5057d02`.

- `MatchTranscript` holds a `DriveTrackerPresentation.History` (`:18`), exposes it (`:124`) and appends every record to it (`:156`).
- The history covers the current drive only. It reads each record's `state` (drive, phase, actor) and `native` (`DriveTrackerPresentation.java:86-102`). #651 replaces `state` in records with a patch.
- Restore rebuilds the history by adding every stored record (`MatchTranscript.java:34-57`).
- `DriveTrackerPresentation.project` also reads native outcomes that aren't in the transcript yet (`:30-38`), because the public snapshot is built before its outcomes are appended.
- `SetupSession` calls `project` for every view (`:1289`), and records events at `:1581`.

## In Scope

- A tracker summary owned outside the transcript. It is fed with the current drive, phase, actor and newly captured outcomes when a revision is recorded.
- Export and restore of that summary as a small value.
- Until the compact snapshot exists (ticket 6 of #651), today's restore feeds the tracker from stored records through one adapter.

## Out of Scope

- Any stored format change. The exported summary isn't stored yet.
- What the drive tracker shows.
- Other presentation state, such as announcements and block context.

## Functional Requirements

- The tracker takes facts, not stored records, so that format 4 doesn't change it again.
- A new drive clears the summary, as today.
- Outcomes that are captured but not yet recorded stay visible, exactly once.

## Acceptance Criteria

- [ ] `MatchTranscript` doesn't refer to `DriveTrackerPresentation`.
- [ ] Tests compare the tracker's output with today's live, after recovery and in replay, over complete headless matches that include kick-off events, Secret Weapons and knock-out recovery.
- [ ] Tests cover drive rollover, and pending outcomes before and after a record is appended.
- [ ] Exporting the summary, restoring it and continuing gives the same output as a session that was never restored, without reading records.
- [ ] No stored or wire format changes.

## Constraints

- Presentation observes state and never decides rules (architecture map).
- Create a new branch for this work.
- Preserve unrelated working-tree changes.
- Follow existing repository conventions.
- Do not commit or push unless explicitly requested.

## Implementation Guidance

- Feed the tracker where a revision is recorded, with the same captured outcomes that the transcript receives.
- Use real native outcomes in tests. The training fixture mocks `ServerCommunication`, which leaves the log empty.

## Validation

- Create new or update existing regression tests as required.
- Run `DriveTrackerPresentationTest`, `SetupSessionPlacementTest`, the focused state tests and the browser drive-tracker tests, then `mvn clean install`.

## Completion Notes

- Unit, browser and game-server build tests should be performed after code changes are complete.
- Failures in these tests should prompt one or more debug session rounds until the code is clear, a roadblock is encountered, or user input is needed.
