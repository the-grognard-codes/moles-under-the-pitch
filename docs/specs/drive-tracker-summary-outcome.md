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

## Acceptance evidence

- `MatchTranscript` owns only transcript state and has no tracker reference.
- Two complete headless matches used real native model-sync reports for Solid
  Defence or Charge, a Secret Weapon ban, and knock-out recovery. Every revision
  matched the live and recorded tracker. Both player views matched after
  checkpoint recovery wherever the tracker appeared. Both terminal replay
  representations matched the live tracker at every revision.
- The two sequences of tracker projections matched main at `0277e63b3`.
  The regression tests pin those two baseline hashes.
- Focused tracker tests covered drive rollover, pending reports before and after
  a commit, and continuing from an exported summary with no history input.
- The self-review checked the first setup actor, event precedence within and
  across revision batches, and checkpoint rebuild order before view validation.

## Validation

- `mvn clean install` passed across the reactor using Maven 3.9.9 and Temurin
  21.0.13. Maven reported 1,269 passed, 26 skipped, and zero failures or errors.
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
  probe passed. #654 has no running manual-testing instance.

[Issue #654](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/654)
