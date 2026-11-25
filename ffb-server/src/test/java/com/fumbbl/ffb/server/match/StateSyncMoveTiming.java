package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.sun.management.ThreadMXBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.lang.management.ManagementFactory;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Process-local instrumentation for the actual apply call in synthetic full-match runs. */
public final class StateSyncMoveTiming {
	private static final ThreadLocal<Move> current = new ThreadLocal<>();
	private static final ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();

	static void install() {
		if (!allocations.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Thread allocation timing is unavailable");
		allocations.setThreadAllocatedMemoryEnabled(true);
		Advice nativeCapture = Advice.to(Native.class);
		Advice blockCopy = Advice.to(BlockCopy.class);
		Advice transferScope = Advice.to(TransferScope.class);
		Advice transferCopyStart = Advice.to(TransferCopyStart.class);
		Advice transferCopyEnd = Advice.to(TransferCopyEnd.class);
		new AgentBuilder.Default().disableClassFormatChanges().with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
			.type(named("com.fumbbl.ffb.server.match.NativeOutcomeCapture"))
			.transform((builder, type, loader, module, domain) ->
				builder.visit(nativeCapture.on(named("since").and(takesArguments(3)))))
			.type(named("com.fumbbl.ffb.server.match.BlockPreviewPresentation"))
			.transform((builder, type, loader, module, domain) ->
				builder.visit(blockCopy.on(named("copy").and(takesArguments(0)))))
			.type(named("com.fumbbl.ffb.server.match.PlannedBallTransfer"))
			.transform((builder, type, loader, module, domain) ->
				builder.visit(transferScope.on(named("validate").and(takesArguments(7)))))
			.type(named("com.fumbbl.ffb.server.GameState"))
			.transform((builder, type, loader, module, domain) ->
				builder.visit(transferCopyStart.on(isConstructor().and(takesArguments(1)))))
			.type(named("com.fumbbl.ffb.server.util.UtilSkillBehaviours"))
			.transform((builder, type, loader, module, domain) ->
				builder.visit(transferCopyEnd.on(named("registerBehaviours").and(takesArguments(2)))))
			.installOn(ByteBuddyAgent.install());
	}

	static void begin(int revision, String operation) {
		current.set(new Move(revision, operation));
	}

	static JsonObject result(int revision) {
		Move move = current.get();
		return new JsonObject().add("beforeRevision", move.revision).add("revision", revision).add("operation", move.operation)
			.add("applyNanos", System.nanoTime() - move.started).add("nativeCapture", move.nativeCapture)
			.add("blockCopy", move.blockCopy).add("transferCopy", move.transferCopy);
	}

	static void clear() {
		current.remove();
	}

	public static boolean active() { return current.get() != null; }
	public static long bytes() { return allocations.getCurrentThreadAllocatedBytes(); }

	public static void record(String phase, long start, long allocated) {
		if (start == 0) return;
		long nanos = System.nanoTime() - start;
		long bytes = bytes() - allocated;
		Move move = current.get();
		JsonArray samples = switch (phase) {
			case "native" -> move.nativeCapture;
			case "block" -> move.blockCopy;
			case "transfer" -> move.transferCopy;
			default -> throw new IllegalArgumentException(phase);
		};
		samples.add(new JsonObject().add("nanos", nanos).add("bytes", bytes));
	}

	public static void transfer(boolean inside) {
		Move move = current.get();
		if (move != null) {
			move.transfer = inside;
			move.transferStarted = 0;
		}
	}

	public static void transferCopyStart() {
		Move move = current.get();
		if (move != null && move.transfer && move.transferStarted == 0) {
			move.transferAllocated = bytes();
			move.transferStarted = System.nanoTime();
		}
	}

	public static void transferCopyEnd() {
		Move move = current.get();
		if (move != null && move.transferStarted != 0) {
			record("transfer", move.transferStarted, move.transferAllocated);
			move.transferStarted = 0;
		}
	}

	private static final class Move {
		final int revision;
		final String operation;
		final JsonArray nativeCapture = new JsonArray();
		final JsonArray blockCopy = new JsonArray();
		final JsonArray transferCopy = new JsonArray();
		final long started = System.nanoTime();
		boolean transfer;
		long transferStarted;
		long transferAllocated;

		Move(int revision, String operation) {
			this.revision = revision;
			this.operation = operation;
		}
	}

	public static final class Native {
		@Advice.OnMethodEnter
		public static void enter(@Advice.Local("start") long start, @Advice.Local("bytes") long bytes) {
			if (active()) { bytes = bytes(); start = System.nanoTime(); }
		}

		@Advice.OnMethodExit
		public static void exit(@Advice.Local("start") long start, @Advice.Local("bytes") long bytes) {
			record("native", start, bytes);
		}
	}

	public static final class BlockCopy {
		@Advice.OnMethodEnter
		public static void enter(@Advice.Local("start") long start, @Advice.Local("bytes") long bytes) {
			if (active()) { bytes = bytes(); start = System.nanoTime(); }
		}

		@Advice.OnMethodExit
		public static void exit(@Advice.Local("start") long start, @Advice.Local("bytes") long bytes) {
			record("block", start, bytes);
		}
	}

	public static final class TransferScope {
		@Advice.OnMethodEnter
		public static void enter() { transfer(true); }

		@Advice.OnMethodExit(onThrowable = Throwable.class)
		public static void exit() { transfer(false); }
	}

	public static final class TransferCopyStart {
		@Advice.OnMethodEnter
		public static void enter() { transferCopyStart(); }
	}

	public static final class TransferCopyEnd {
		@Advice.OnMethodExit
		public static void exit() { transferCopyEnd(); }
	}
}
