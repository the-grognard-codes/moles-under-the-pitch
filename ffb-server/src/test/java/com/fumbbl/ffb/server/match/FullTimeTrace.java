package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.server.local.BrowserRequestTiming;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.sun.management.ThreadMXBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Samples the existing single worker and its real callees in an opt-in test JVM. No payloads are retained. */
public final class FullTimeTrace implements AutoCloseable {
	private static final String MATCH = "com.fumbbl.ffb.server.match.";
	private static final String LOCAL = "com.fumbbl.ffb.server.local.";
	private static final String JDBC = "com.fumbbl.ffb.server.db.JdbcRequestConnections";
	private static final String ARRAY = "com.eclipsesource.json.JsonArray";
	private static final ThreadMXBean ALLOCATION = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
	private static final ThreadLocal<Task> CURRENT = new ThreadLocal<>();
	private static volatile FullTimeTrace active;
	private final List<JsonObject> tasks = new ArrayList<>();
	private final Instrumentation instrumentation;
	private final ResettableClassFileTransformer transformer;

	private FullTimeTrace() {
		Map<String, ElementMatcher.Junction<MethodDescription>> phases = Map.ofEntries(
			measure(LOCAL + "BrowserV2Adapter", "receive", "expireDecisions", "retryPublications"),
			measure(LOCAL + "ActiveMatchPublisher", "sendState", "publish", "publishStopped"),
			measure(LOCAL + "LiveMatchTranscript", "frame"),
			measure(MATCH + "SetupApplication", "handleWithOutcome", "reconstruct", "findRecovery", "checkpoint",
				"fullCheckpoint", "complete", "retryPendingCompletion", "releaseCompleted", "retainedConcessionOutcome",
				"completedReplyWithOutcome", "liveTranscriptPage", "publishedState"),
			measure(MATCH + "SetupSession", "apply", "completedMatch", "recoveryEvents", "reply"),
			measure(MATCH + "MatchTranscript", "json", "page"),
			measure(MATCH + "MatchChat", "json"),
			measure(MATCH + "CanonicalJson", "encode"),
			measure(MATCH + "MatchJson", "storageText", "validateCompletion", "decodeAuthorized", "parse"),
			measure(MATCH + "MatchService", "loadForSetup", "complete", "completeLoaded"),
			measure(MATCH + "JdbcMatchRepository", "write", "find"),
			measure(MATCH + "JdbcRecoveryRepository", "find", "findForRestore", "save", "append"),
			measure(MATCH + "RecoveryCheckpoint", "validate"),
			measure(MATCH + "BoundedJsonStorageCodec", "encode", "decode", "decodeForActivation", "decodeEnvelope", "inflate"),
			measure(MATCH + "NativeOutcomeCapture", "since"),
			measure(MATCH + "BlockPreviewPresentation", "copy"),
			measure(MATCH + "PlannedBallTransfer", "validate"),
			measure("com.fumbbl.ffb.server.GameState", "initFrom", "toJsonValue"),
			measure(JDBC, "open"),
			Map.entry(ARRAY, named("readFrom").and(takesArguments(String.class)))
		);
		instrumentation = ByteBuddyAgent.install();
		transformer = new AgentBuilder.Default().disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
			.type(namedOneOf(phases.keySet().toArray(String[]::new)))
			.transform((builder, type, loader, module, domain) -> {
				var observed = builder.visit(Advice.to(PhaseAdvice.class).on(phases.get(type.getName())));
				return type.getName().equals(MATCH + "BoundedJsonStorageCodec")
					? observed.visit(Advice.to(GzipAdvice.class).on(named("gzip"))) : observed;
			})
			.type(named(JDBC + "$Bounded"))
			.transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(SqlAdvice.class).on(named("call"))))
			.type(named(LOCAL + "BrowserMatchTransport"))
			.transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(WorkerAdvice.class).on(named("runQueued"))))
			.type(named(LOCAL + "BrowserRequestTiming"))
			.transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(TaskStartAdvice.class)
				.on(named("handling").and(takesArguments(2)))))
			.type(named(LOCAL + "BrowserMatchTransportMetrics"))
			.transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(TaskFinishAdvice.class).on(named("taskFinished"))))
			.installOn(instrumentation);
	}

	private static Map.Entry<String, ElementMatcher.Junction<MethodDescription>> measure(String type, String... methods) {
		return Map.entry(type, namedOneOf(methods));
	}

	static synchronized FullTimeTrace start() {
		if (active != null) throw new IllegalStateException("A full-time capture is already open");
		active = new FullTimeTrace();
		return active;
	}

	synchronized JsonArray snapshot() {
		JsonArray copy = new JsonArray();
		tasks.forEach(copy::add);
		return copy;
	}

	synchronized void clear() {
		tasks.clear();
	}

	@Override public void close() {
		synchronized (FullTimeTrace.class) {
			try {
				if (!transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION))
					throw new IllegalStateException("Full-time instrumentation could not be removed");
			} finally {
				active = null;
			}
		}
	}

	public static void startWorker(Object lock, Deque<?> foreground, Deque<?> advisory) {
		FullTimeTrace trace = active;
		if (trace != null) CURRENT.set(new Task(trace, lock, foreground, advisory));
	}

	public static void startTask() {
		Task task = CURRENT.get();
		if (task == null) return;
		synchronized (task.lock) {
			task.foregroundAtStart = task.foreground.size();
			task.advisoryAtStart = task.advisory.size();
		}
	}

	public static void finishTask(BrowserRequestTiming.TaskMeasurement measurement) {
		Task task = CURRENT.get();
		if (task != null) task.measurement = measurement;
	}

	public static void finishWorker(boolean failed) {
		Task task = CURRENT.get();
		CURRENT.remove();
		if (task != null && task.measurement != null) {
			FullTimeTrace trace = task.trace;
			synchronized (trace) {
				trace.tasks.add(task.json(trace.tasks.size(), failed, System.nanoTime()));
			}
		}
	}

	public static Span beginPhase(String method) {
		Task task = CURRENT.get();
		if (task == null) return null;
		Span parent = task.stack.peek();
		String phase = method.substring(method.lastIndexOf('.') + 1);
		if (method.startsWith(ARRAY) && (parent == null || !parent.phase.equals("SetupSession.completedMatch"))) return null;
		String type = method.substring(0, method.length() - phase.length() - 1);
		return task.begin(type.substring(type.lastIndexOf('.') + 1) + "." + phase);
	}

	public static Span beginSql(Method method) {
		Task task = CURRENT.get();
		if (task == null) return null;
		return switch (method.getName()) {
			case "setAutoCommit", "prepareStatement", "executeUpdate", "executeQuery", "commit", "rollback" ->
				task.begin("JDBC." + method.getName());
			default -> null;
		};
	}

	public static void endPhase(Span span, boolean failed) {
		if (span != null) CURRENT.get().end(span, failed);
	}

	public static void endGzip(Span span, byte[] decoded, byte[] gzip, boolean failed) {
		if (span == null) return;
		Task task = CURRENT.get();
		task.end(span, failed);
		if (gzip == null) return;
		Sample sample = task.phases.get(span.path);
		sample.decodedBytes += decoded.length;
		sample.gzipBytes += gzip.length;
	}

	public static class WorkerAdvice {
		@Advice.OnMethodEnter
		public static void enter(@Advice.FieldValue("queueLock") Object lock,
			@Advice.FieldValue("foregroundWork") Deque<?> foreground, @Advice.FieldValue("advisoryWork") Deque<?> advisory) {
			startWorker(lock, foreground, advisory);
		}
		@Advice.OnMethodExit(onThrowable = Throwable.class)
		public static void exit(@Advice.Thrown Throwable failure) { finishWorker(failure != null); }
	}

	public static class TaskStartAdvice {
		@Advice.OnMethodEnter
		public static void enter() { startTask(); }
	}

	public static class TaskFinishAdvice {
		@Advice.OnMethodEnter
		public static void enter(@Advice.Argument(0) BrowserRequestTiming.TaskMeasurement measurement) { finishTask(measurement); }
	}

	public static class PhaseAdvice {
		@Advice.OnMethodEnter
		public static Span enter(@Advice.Origin("#t.#m") String method) { return beginPhase(method); }

		@Advice.OnMethodExit(onThrowable = Throwable.class)
		public static void exit(@Advice.Enter Span span, @Advice.Thrown Throwable failure) { endPhase(span, failure != null); }
	}

	public static class SqlAdvice {
		@Advice.OnMethodEnter
		public static Span enter(@Advice.Argument(2) Method method) { return beginSql(method); }

		@Advice.OnMethodExit(onThrowable = Throwable.class)
		public static void exit(@Advice.Enter Span span, @Advice.Thrown Throwable failure) { endPhase(span, failure != null); }
	}

	public static class GzipAdvice {
		@Advice.OnMethodEnter
		public static Span enter() { return beginPhase("com.fumbbl.ffb.server.match.BoundedJsonStorageCodec.gzip"); }

		@Advice.OnMethodExit(onThrowable = Throwable.class)
		public static void exit(@Advice.Enter Span span, @Advice.Argument(0) byte[] decoded, @Advice.Return byte[] gzip,
			@Advice.Thrown Throwable failure) { endGzip(span, decoded, gzip, failure != null); }
	}

	public static final class Span {
		private final String phase;
		private final String path;
		private final long started = System.nanoTime();
		private final long allocated = ALLOCATION.getCurrentThreadAllocatedBytes();
		private long childNanos;
		private Span(String phase, Span parent) {
			this.phase = phase;
			this.path = parent == null ? phase : parent.path + "/" + phase;
		}
	}

	private static final class Sample {
		private long count, failures, nanos, selfNanos, allocatedBytes, decodedBytes, gzipBytes;
	}

	private static final class Task {
		private final FullTimeTrace trace;
		private final Object lock;
		private final Deque<?> foreground;
		private final Deque<?> advisory;
		private final long started = System.nanoTime();
		private final long startedEpochMillis = System.currentTimeMillis();
		private final long allocated = ALLOCATION.getCurrentThreadAllocatedBytes();
		private final Deque<Span> stack = new ArrayDeque<>();
		private final Map<String, Sample> phases = new LinkedHashMap<>();
		private int foregroundAtStart = -1, advisoryAtStart = -1;
		private BrowserRequestTiming.TaskMeasurement measurement;

		private Task(FullTimeTrace trace, Object lock, Deque<?> foreground, Deque<?> advisory) {
			this.trace = trace;
			this.lock = lock;
			this.foreground = foreground;
			this.advisory = advisory;
		}

		private Span begin(String phase) {
			Span span = new Span(phase, stack.peek());
			stack.push(span);
			return span;
		}

		private void end(Span span, boolean failed) {
			long elapsed = System.nanoTime() - span.started;
			long allocated = ALLOCATION.getCurrentThreadAllocatedBytes() - span.allocated;
			if (stack.pop() != span) throw new IllegalStateException("Unbalanced timing advice");
			Span parent = stack.peek();
			if (parent != null) parent.childNanos += elapsed;
			Sample sample = phases.computeIfAbsent(span.path, ignored -> new Sample());
			sample.count++;
			if (failed) sample.failures++;
			sample.nanos += elapsed;
			sample.selfNanos += elapsed - span.childNanos;
			sample.allocatedBytes += allocated;
		}

		private JsonObject json(int index, boolean failed, long ended) {
			long allocatedBytes = ALLOCATION.getCurrentThreadAllocatedBytes() - allocated;
			JsonObject output = new JsonObject();
			long traced = 0;
			for (var entry : phases.entrySet()) {
				Sample sample = entry.getValue();
				traced += sample.selfNanos;
				output.add(entry.getKey(), new JsonObject().add("count", sample.count).add("failures", sample.failures)
					.add("nanos", sample.nanos).add("selfNanos", sample.selfNanos)
					.add("allocatedBytes", sample.allocatedBytes).add("decodedBytes", sample.decodedBytes)
					.add("gzipBytes", sample.gzipBytes));
			}
			return new JsonObject().add("order", index).add("startedEpochMillis", startedEpochMillis)
				.add("kind", BrowserRequestTiming.TaskKind.values()[measurement.kind()].name())
				.add("queueWaitNanos", measurement.queueNanos()).add("workerNanos", measurement.executionNanos())
				.add("transportNanos", ended - started).add("unattributedWorkerNanos", measurement.executionNanos() - traced)
				.add("allocatedBytes", allocatedBytes)
				.add("foregroundAtStart", foregroundAtStart).add("advisoryAtStart", advisoryAtStart)
				.add("overrun", measurement.overrun()).add("failed", failed).add("phases", output);
		}
	}
}
