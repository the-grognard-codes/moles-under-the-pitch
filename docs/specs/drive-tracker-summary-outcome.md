# Drive tracker summary outcome

The match session owns the drive tracker. It feeds the tracker the drive, phase,
actor, and captured native outcomes when it records a revision. Replay snapshots
still get the pending outcomes before the revision is appended. Once the append
succeeds, the summary retains the facts and the native cursor advances.

The summary holds only the current drive's first setup actor, Secret Weapon and
knock-out checks, and supported kickoff event. A drive change clears those facts.
The summary is an immutable value that the tracker can restore directly. Current
checkpoint recovery rebuilds that value through one adapter reading the existing
transcript records. Checkpoint, transcript, replay, and browser wire formats are
unchanged.

Tracker display, announcements, and block presentation retain their previous
behavior. The summary export and restore API has no production caller yet.
Ticket 6 of #651 will store the summary in the compact checkpoint and replace
the transcript recovery adapter.

## Acceptance evidence

- `MatchTranscript` owns only transcript state and has no tracker reference.
- Two complete headless matches used real native model-sync reports for Solid
  Defence or Charge, a Secret Weapon ban, and knock-out recovery. Every revision
  matched the live view, both player views after checkpoint recovery, and both
  terminal replay representations. This includes revisions where the tracker
  is hidden. Both recovered matches continued from touchdown to a visible
  next-drive tracker.
- The readable fixture records the tracker at every revision on main at
  `0277e63b3`, including when the Secret Weapon and knock-out indicators appear.
- At every revision, a fresh tracker restored all six summary fields, consumed
  only that revision's facts and native outcomes, and matched the uninterrupted
  session's summary and projection. Unit tests cover drive rollover, the first
  setup actor across repeated setup revisions, and event precedence before and
  after a record is appended.
- Stored transcript validation still rejects malformed native report lists and
  fields used by the tracker. Older records with absent or null report lists
  remain readable.
- The self-review checked the first setup actor, event precedence within and
  across revision batches, and checkpoint rebuild order before view validation.

## Validation

- The initial implementation passed `mvn clean install` across the reactor
  using Maven 3.9.9 and Temurin 21.0.13. Maven reported 1,269 passed, 26 skipped,
  and zero failures or errors.
- Focused Maven selection passed 66 tests across common, server, and state-test
  modules, including `SetupSessionPlacementTest` and `SecretWeaponDecisionTest`.
- The dedicated browser drive-tracker suite and all nine tracker and
  pitch-movement unit tests passed.
- The supplemental `roundup6-presentation-ui.mjs` suite failed the existing
  touchdown-splash timing assertion on both main and this worktree. Its seven
  other cases passed in both runs.
- Dev-local `--start --quick` built the #654 server and reached database,
  engine, and Firebase ADC readiness. The proxy could not start because #652
  acquired the shared port during startup. The supported alternate ports were
  also occupied by #652's isolated acceptance stack. Per the maintainer's
  direction, the shared backend was restored to #652, and its Chrome proxy
  probe passed. Both port sets were still occupied during the review follow-up.

## Review follow-up validation

- All 57 focused Java tests passed, including both complete matches,
  `DriveTrackerPresentationTest`, `MatchTranscriptTest`,
  `SetupSessionPlacementTest`, and `MatchServiceTest`.
- The old manifest reproduced the missing-registration error. All 21 native
  shard validator fixture checks passed. Native shard 3 ran the newly
  registered class and passed with 348 tests passed and 11 skipped across 56
  suites.

[Issue #654](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/654)
