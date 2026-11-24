# Native shard server opt-in allowance review

For issue #621, the server test sources and Surefire reports were checked against the 19 method allowances and four class allowances added to [the native shard manifest](../../tools/validation/native-shards.json). These prerequisites were already part of the tests. Checks provides neither the isolated MariaDB fixtures and DEV credentials nor the diagnostic workload opt-ins. The validator compares method names and observed skip reasons to the manifest exactly. [Issue #621](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/621) · [PR #625](https://github.com/the-grognard-codes/moles-under-the-pitch/pull/625)

## Method allowances

| Server class | Allowed method | Reason for opting in |
| --- | --- | --- |
| `JdbcProbeMariaDbTest` | `preparedCreateRecoversThroughNestedTeamLoadWithinTheSingleProbeTask` | Uses the synthetic JDBC bounds database for a real nested probe. |
| `BrowserSchemaMariaDbTest` | `freshBootstrapCreatesAndVerifiesOnlyBrowserTables` | Bootstraps tables in a dedicated empty browser schema. |
| `Marker6SchemaTest` | `verifiesOptInMarkerSixMariaDbTargetReadOnly` | Verifies an explicitly configured marker-6 MariaDB target. The allowance covers the unconfigured target reason. |
| `SkillUsePreferenceSchemaIntegrationTest` | `migrationPersistsIndependentAccountCasesAcrossConnectionsAndRejectsInvalidRows` | Runs the migration and writes test data in a dedicated empty skill-use schema. |
| `FirebaseJdbcBudgetMariaDbTest` | `realProviderAndJdbcShareTheAbsoluteRequestDeadlineIncludingLateLockWait` | Exercises the live DEV Firebase provider and synthetic JDBC bounds database together. |
| `HistoryScalingWorkloadTest` | `generateFixtures` | Generates the diagnostic history-scaling fixtures when `HISTORY_SCALING_RUN=1`. |
| `HistoryScalingWorkloadTest` | `measureFixtures` | Runs the history-scaling workload against those fixtures when `HISTORY_SCALING_RUN=1`. |
| `HistoryScalingWorkloadTest` | `verifyFixtures` | Validates those generated diagnostic fixtures when `HISTORY_SCALING_RUN=1`. |
| `JdbcAuthorizationMariaDbTest` | `joinedRefreshPreservesImmediatePermissionsAndReducesRoundTrips` | Exercises real authorization queries in an empty capture authorization schema. |
| `JdbcHealthyWorkerMariaDbTest` | `successfulHealthyAndColdProviderPathsMeasureFreshConnectionsAndFullWorkerOccupancy` | Measures workers using the synthetic JDBC bounds database. |
| `JdbcHealthyWorkerMariaDbTest` | `actualFirebaseAndJdbcMeasureNativeCheckpointAndMatchedPublicationWithinOneWorkerBudget` | Uses a DEV Firebase acceptance token and the synthetic JDBC bounds database. The allowance covers the missing token reason. |
| `JdbcLobbyReadMariaDbTest` | `maximumNonresidentPageHasFixedQueriesAndPreservesPublicContent` | Populates a synthetic lobby schema to measure real paginated queries. |
| `MatchMemoryWorkloadTest` | `longHistoryRestoresAndSerializesWithinTheDeploymentHeap` | Runs a diagnostic heap workload and writes measurements to the path set by `MATCH_MEMORY_WORKLOAD`. |
| `OperatingEnvelopeTest` | `captureIntegratedEnvelope` | Writes and measures an integrated workload against an isolated envelope database. |
| `RecoveryJournalMariaDbTest` | `additiveMigrationCasCrashReconciliationAndCompactionPreserveNativeState` | Migrates and mutates a dedicated empty recovery-journal database. |
| `RecoveryJournalMariaDbTest` | `savedComputerJobStaysOffTheInventoryAcrossRestartAndReturnsOnResume` | Stores and restores session data in the dedicated recovery-journal database. |
| `SessionRetentionMemoryTest` | `lateSessionFitsItsRetainedBudgetAndIsCollectableAfterCompletionAndIdleEviction` | Runs heap-limited subprocess workloads when `SESSION_RETENTION_MEMORY_RUN=1`. |
| `SessionRetentionMemoryTest` | `measureNearLimitCheckpointAndResultWithOtherResidents` | Runs the near-limit checkpoint workload when `SESSION_RETENTION_MEMORY_RUN=1`. |
| `V2PreparationJdbcTest` | `createsJoinsRetriesReleasesAndExpiresInvitationsOnIsolatedMarker6Target` | Exercises invitation writes and recovery against an isolated marker-6 MariaDB target. |

## Class allowances

Each class checks both `JDBC_BOUNDS_TEST_URL` and `JDBC_BOUNDS_TEST_PASSWORD` in `@BeforeAll` before touching its synthetic schema.

| Server class | Methods behind the class prerequisite | Reason for opting in |
| --- | ---: | --- |
| `JdbcBoundsMariaDbTest` | 9 | Exercises real lock policy, write deadlines, and acknowledgment loss against MariaDB. |
| `JdbcPoolMariaDbTest` | 12 | Exercises pool exhaustion, reset, and session reuse against a synthetic schema. |
| `JdbcPublicationMariaDbTest` | 6 | Measures live SQL query counts and publication authorization with synthetic accounts and matches. |
| `JdbcRecoveryBudgetMariaDbTest` | 1 | Measures ambiguous commit and retry behavior using the real recovery schema. |

Surefire emits a zero-case XML report without an abort reason for these existing `@BeforeAll` assumptions. The gate requires that report in its assigned shard, with no failures or errors, and records a missing prerequisite. This covers the expected empty report for these four classes. Because Checks omits the database prerequisites, it cannot distinguish another class-level abort in these classes from the expected abort, or verify execution of their 28 methods. With both prerequisites set, an empty report fails. Changes to these assumptions require another explicit review.
