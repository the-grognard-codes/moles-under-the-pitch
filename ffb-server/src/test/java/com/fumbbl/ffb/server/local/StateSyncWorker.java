package com.fumbbl.ffb.server.local;

import com.fumbbl.ffb.server.FantasyFootballServer;
import com.fumbbl.ffb.server.net.ServerCommunication;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** Runs synthetic ingress and control work on the production transport and communication worker. */
public final class StateSyncWorker implements AutoCloseable {
	private final BrowserMatchTransport transport;
	private final ServerCommunication communication;

	public StateSyncWorker(FantasyFootballServer server) {
		communication = server.getCommunication();
		transport = new BrowserMatchTransport(server);
		new Thread(communication, "state-sync-engine").start();
	}

	public <T> T ingress(String message, Callable<T> work) throws Exception {
		return submit(work, (run, reject) -> transport.submit(message, run, reject));
	}

	public <T> T control(Callable<T> work) throws Exception {
		return submit(work, transport::submitControl);
	}

	/** Prepares a resident engine between measurements on the same native owner thread. */
	public <T> T prepare(Callable<T> work) throws Exception {
		return submit(work, (run, reject) -> {
			if (communication.execute(run)) return true;
			reject.run();
			return false;
		});
	}

	/** Give the smoke test one known queued task waiting behind another. */
	public void twoQueuedControls() throws Exception {
		var parked = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		if (!communication.execute(() -> {
			parked.countDown();
			try { resume.await(30, TimeUnit.SECONDS); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		})) throw new IllegalStateException("Fixture worker stopped");
		try {
			if (!parked.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture worker did not park");
			for (int task = 0; task < 2; task++) {
				if (!transport.submitControl(() -> { }, () -> { })) throw new IllegalStateException("Transport rejected control");
			}
		} finally {
			resume.countDown();
		}
		drain();
	}

	private <T> T submit(Callable<T> work, BiFunction<Runnable, Runnable, Boolean> submit) throws Exception {
		CompletableFuture<T> finished = new CompletableFuture<>();
		submit.apply(() -> {
			try { finished.complete(work.call()); }
			catch (Exception | AssertionError failure) { finished.completeExceptionally(failure); }
		}, () -> finished.completeExceptionally(new IllegalStateException("Transport rejected fixture task")));
		T result = finished.get(30, TimeUnit.SECONDS);
		// The callable returns just before the transport closes the worker sample.
		drain();
		return result;
	}

	private void drain() throws Exception {
		CompletableFuture<Void> drained = new CompletableFuture<>();
		if (!communication.execute(() -> drained.complete(null))) throw new IllegalStateException("Fixture worker stopped");
		drained.get(30, TimeUnit.SECONDS);
	}

	@Override public void close() {
		transport.destroy();
		communication.shutdown();
	}
}
