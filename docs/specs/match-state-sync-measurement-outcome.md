# Match state sync measurement status

Issue [#650](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/650) remains open. Source inspection at `0277e63b3` found a prerequisite for measuring full time on the serial engine worker. No full-match measurements or prototype timings are available yet. The historical 5–14 s waits alone cannot establish how much time completion consumed. Targets for [#611](https://github.com/the-grognard-codes/moles-under-the-pitch/issues/611) remain open.

## Where the timing loses attribution

* `SetupSession.completedMatch()` calls `recoveryEvents(0)`, parses the returned array, materializes the transcript and optional chat, and constructs a `CompletedMatch`. The constructor serializes the graph and then validates it. Timing the outer method gives one duration for all of these operations.
* In the deferred completion path, `SetupApplication` can reconstruct an evicted session, then calls `completeLoaded`, prepares a publication and releases the session. Publication and reconstruction could otherwise be misattributed to artifact construction or JDBC.
* `BrowserMatchTransport.runQueued()` selects work under the lane lock. Its ingress gauge counts acquired permits, including the executing ingress task, and excludes control work. It does not record how many tasks were waiting in the two lanes when a particular completion task started.
* `BrowserRequestTiming.handling()` records queue wait and execution for control tasks, but skips its per-request timing log when the task has no browser request ID. Existing phase names also omit event materialization, transcript materialization, terminal validation, and release.

A test can time a second call to a constituent method. That call may see different allocation pressure, caches and GC pauses from the call that blocked the worker. Sampled JFR stacks can help explain a slow interval but cannot supply exact start and end times for each phase in that interval.

## Analyzer changes verified independently

Both history analyzers now share file handling and sample statistics. The first/warm report fingerprints both source files after normalizing CRLF to LF. The legacy history-scaling report retains its small-sample p95 behavior, and the first/warm report continues to suppress p95 below 20 samples.

Run `node --test tools/diagnostics/history-analysis.test.mjs` for the synthetic CLI and statistics checks. These cover both line-ending styles and detect a change to the helper source. Reprocessing the retained November 8 history-scaling evidence produced byte-identical Markdown and JSON summaries before and after the refactor. Those two-player results serve only as a compatibility check.

## Proposed prerequisite PR

Add opt-in, server-local timing to the existing worker diagnostics. Capture the number of remaining foreground and advisory tasks while dequeuing under the existing lock. Emit one bounded record per completion attempt, including control tasks, with queue wait, whole-task execution time, completion outcome, and decoded artifact bytes. Use monotonic clocks. Keep request and match payloads out of the record.

Record non-overlapping elapsed times for event materialization (including parse), transcript and chat materialization, other artifact assembly, canonical serialization, validation, durable completion, publication preparation, and release. Record reconstruction separately on the deferred path. Show existing nested SQL and compression spans as detail within the durable write. Calculate residual execution time from the outer worker duration and exclusive phase times, with queue wait reported separately.

Validate with a controlled clock on immediate and deferred completions, including a deferred attempt that restores the session and an attempt that fails during persistence. Check that diagnostics disabled preserves existing outputs and that a control-task completion produces a record without a browser request. Then resume the isolated three-JVM measurement described in the [measurement spec](match-state-sync-measurement.md). The instrumentation needs its own review before it becomes the measurement baseline.
