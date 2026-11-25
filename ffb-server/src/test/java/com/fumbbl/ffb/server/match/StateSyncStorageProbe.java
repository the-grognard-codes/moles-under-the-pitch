package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.util.UtilSkillBehaviours;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Opt-in CLI; run in a fresh measurement JVM against the disposable Compose schema. */
public final class StateSyncStorageProbe {
	private final ThreadMXBean allocations = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
	private final StateSyncProjection projection = new StateSyncProjection();
	private final JsonArray samples = new JsonArray();
	private final String url = System.getenv("STATE_SYNC_JDBC_URL");
	private final String password = System.getenv("STATE_SYNC_DB_PASSWORD");
	private final String row = UUID.randomUUID().toString();
	private final Path directory;
	private String stage, move;
	private long generation;
	private JsonObject summary;

	private StateSyncStorageProbe(Path directory) {
		this.directory = directory;
		if (url == null || !url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/ffb_state_sync_test") || password == null)
			throw new IllegalArgumentException("Isolated state-sync Compose database required");
		if (!allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
	}

	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args[0]);
		StateSyncStorageProbe probe = new StateSyncStorageProbe(dir);
		probe.run(Integer.parseInt(args[2]), args.length == 4 && "--early".equals(args[3]));
		Files.writeString(Path.of(args[1]), probe.samples.toString());
	}

	private Connection open() throws Exception { return DriverManager.getConnection(url, "root", password); }
	private String read(String file) throws Exception { return Files.readString(directory.resolve(file + ".json")); }
	private record WireBytes(int total) { }
	private interface Operation<T> { T run() throws Exception; }

	private <T> T time(String name, Operation<T> operation) throws Exception {
		long before = allocations.getCurrentThreadAllocatedBytes();
		long start = System.nanoTime();
		T value = operation.run();
		long elapsed = System.nanoTime() - start;
		long bytes = allocations.getCurrentThreadAllocatedBytes() - before;
		JsonObject sample = new JsonObject().add("stage", stage).add("move", move).add("phase", name)
			.add("ms", elapsed / 1_000_000d).add("allocated", bytes);
		sample.add("endUtcMs", System.currentTimeMillis());
		if (value instanceof java.util.Optional<?> reply) sample.add("parkedReply", reply.isPresent());
		if (value instanceof byte[] output) sample.add("bytes", output.length);
		if (value instanceof WireBytes output) sample.add("bytes", output.total());
		samples.add(sample);
		return value;
	}

	private void run(int repeats, boolean earlyOnly) throws Exception {
		if (repeats < 1) throw new IllegalArgumentException("Positive sample count required");
		long seed = JsonObject.readFrom(read("match")).getLong("seed", -1);
		try (BogrukEngineFixture fixture = new BogrukEngineFixture("Human", "Orc", seed, true, true);
			Connection admin = open(); ExecutorService worker = Executors.newSingleThreadExecutor()) {
			for (String period : earlyOnly ? new String[]{"early"} : new String[]{"early", "late", "terminal"}) {
				stage = period;
				measureStage(fixture, fixture.document, admin, worker, repeats);
			}
		}
	}

	private void measureStage(BogrukEngineFixture fixture, MatchDocument document, Connection admin, ExecutorService worker, int repeats) throws Exception {
		String checkpoint = read(stage), compact = read(stage + "-compact");
		// Use the actual last active snapshot before sealing. For active rows, vary the revision
		// outside the timed region so InnoDB must replace the compressed blob in each trial.
		JsonObject predecessor = JsonObject.readFrom(compact);
		predecessor.set("revision", predecessor.getInt("revision", -1) - 1);
		byte[] previousSnapshot = gzip(stage.equals("terminal") ? read("late-compact") : predecessor.toString());
		JsonObject payload = JsonObject.readFrom(read(stage + "-move"));
		JsonObject current = payload.get("current").asObject(), state = current.get("state").asObject();
		JsonObject previous = projection.project(payload.get("previous").asObject());
		int population = stage.equals("early") ? 50 : 700;
		summary = new JsonObject().add("revision", state.get("revision")).add("actor", state.get("actor"))
			.add("phase", state.get("phase")).add("half", state.get("half")).add("turn", state.get("turn"))
			.add("homeScore", state.get("homeScore")).add("awayScore", state.get("awayScore"));
		final JdbcRecoveryRepository repository = new JdbcRecoveryRepository(() -> DriverManager.getConnection(url, "root", password), true);
		String baseKey = UUID.randomUUID().toString();
		long baseGeneration = 1;
		check(repository.save(new RecoveryRepository.Record(baseKey, 1, checkpoint), 0));
		initPrototype(admin, compact, population);
		String entryFile = stage + "-journal";
		String entry = Files.exists(directory.resolve(entryFile + ".json")) ? read(entryFile) : null;
		try {
			for (int i = 0; i < repeats; i++) {
				move = "baseline";
				long expected = baseGeneration;
				if (entry != null) {
					time("journalAppend", () -> { check(repository.append(new RecoveryRepository.Record(baseKey, expected + 1, entry), expected)); return null; });
					baseGeneration++;
				}
				long beforeCompact = baseGeneration++;
				time("checkpointStore", () -> { check(repository.save(new RecoveryRepository.Record(baseKey, beforeCompact + 1, checkpoint), beforeCompact)); return null; });
				SetupSession restored = restore(fixture, document, repository, baseKey);
				time("checkpointBuild", restored::recoveryArtifact);
				byte[] compressed = time("compactRead", () -> queryBlob("SELECT snapshot FROM probe_current WHERE match_id=?"));
				var nativeState = time("compactDecode", () -> {
					try (GZIPInputStream stream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
						return JsonObject.readFrom(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).get("native").asObject();
					}
				});
				time("compactNativeRestore", () -> new GameState(fixture.server).initFrom(fixture.server, new RecoveryNativeJson().normalize(nativeState)));
				var nativeField = SetupSession.class.getDeclaredField("state");
				nativeField.setAccessible(true);
				GameState logged = (GameState) nativeField.get(restored);
				for (boolean log : new boolean[]{true, false}) {
					time(log ? "copyIncludingLog" : "copyOmittingLog", () -> {
						GameState copy = new GameState(fixture.server).initFrom(fixture.server, logged.toJsonValue(log, 0));
						UtilSkillBehaviours.registerBehaviours(copy.getGame(), fixture.server.getDebugLog());
						return copy;
					});
				}
				for (String role : new String[]{"home", "away"}) time("view-" + role,
					() -> bytes(restored.reply("probe", "ACCEPTED", false, role)));
				time("view-spectator", () -> bytes(restored.spectatorView()));
				for (boolean changed : new boolean[]{false, true}) {
					time(changed ? "activeProbe" : "quietProbe", () -> {
						JsonObject status = JsonObject.readFrom(new String(queryBlob("SELECT summary_json FROM probe_current WHERE match_id=?"), StandardCharsets.UTF_8));
						int revision = status.getInt("revision", -1);
						int known = changed ? state.getInt("revision", -1) - 1 : state.getInt("revision", -1);
						if (revision == known) return bytes(new JsonObject().add("revision", revision).add("unchanged", true));
						return queryBlob("SELECT snapshot FROM probe_current WHERE match_id=?");
					});
				}
				time("receiptLookup", () -> receiptLookup(population / 2));
				time("dbRoundTrip", () -> {
					try (Connection ping = open(); var stmt = ping.createStatement(); var rows = stmt.executeQuery("SELECT 1")) {
						check(rows.next()); return rows.getInt(1);
					}
				});
			}
			for (int i = 0; i < (repeats == 1 ? 1 : 34); i++) {
				for (String shape : new String[]{"ordinary", "keyframe", "rare"}) {
					// Replay both shapes against the same logical predecessor and index population.
					resetPredecessor(admin, population, previousSnapshot);
					move = shape;
					boolean rare = shape.equals("rare");
					JsonObject before = rare ? projection.project(payload.get("rarePrevious").asObject()) : previous;
					JsonObject after = rare ? payload.get("rareCurrent").asObject() : current;
					long queued = System.nanoTime();
					worker.submit(() -> {
						double waitMs = (System.nanoTime() - queued) / 1_000_000d;
						samples.add(new JsonObject().add("stage", stage).add("move", move).add("phase", "queueWait").add("ms", waitMs));
						return time("prototypeTask", () -> publish(before, after, payload, compact, shape.equals("keyframe")));
					}).get();
				}
			}
		} finally {
			for (String table : new String[]{"probe_receipt", "probe_record", "probe_current"}) {
				try (PreparedStatement cleanup = admin.prepareStatement("DELETE FROM " + table + " WHERE match_id=?")) {
					bind(cleanup, row); cleanup.executeUpdate();
				}
			}
			update(admin, "DELETE FROM ffb_match_recovery WHERE matchid=?", baseKey);
		}
	}

	private SetupSession restore(BogrukEngineFixture fixture, MatchDocument document, JdbcRecoveryRepository repository, String baseKey) throws Exception {
		String stored = time("jdbcRecoveryLoad", () -> repository.find(baseKey).json);
		RecoveryCheckpoint codec = new RecoveryCheckpoint();
		var validated = time("validateCheckpoint", () -> codec.validate(stored));
		time("computerFallback", () -> codec.computerReply(validated, document, "away", System.currentTimeMillis()));
		return time("restoreSession", () -> new SetupSession(fixture.server, document, validated));
	}

	private void resetPredecessor(Connection admin, int population, byte[] previousSnapshot) throws Exception {
		try (PreparedStatement clear = admin.prepareStatement("DELETE FROM probe_record WHERE match_id=?")) {
			bind(clear, row); clear.executeUpdate();
		}
		try (PreparedStatement clear = admin.prepareStatement("DELETE FROM probe_receipt WHERE match_id=? AND request_id=?")) {
			bind(clear, row, "accepted"); clear.executeUpdate();
		}
		generation = population;
		try (PreparedStatement reset = admin.prepareStatement("UPDATE probe_current SET generation=?,digest=?,snapshot=?,sealed=FALSE,result_json=NULL WHERE match_id=?")) {
			bind(reset, generation, new byte[32], previousSnapshot, row); reset.executeUpdate();
		}
	}

	private WireBytes publish(JsonObject previous, JsonObject current, JsonObject payload, String compact, boolean keyframe) throws Exception {
		JsonObject view = time("publicProjection", () -> projection.project(current.get("state").asObject()));
		JsonObject delta = time("patch", () -> projection.patch(previous, view)
			.add("effects", projection.effects(current.get("native").asArray())));
		time("patchEncode", () -> bytes(delta));
		if (keyframe) time("keyframeEncode", () -> bytes(view));
		JsonObject record = new JsonObject().add("revision", view.get("revision")).add("kind", current.get("kind"))
			.add("patch", delta).add("native", current.get("native"));
		if (keyframe) record.add("keyframe", view);
		time("commit", () -> { commit(compact, record, current); return null; });
		int deliveredBytes = 0;
		for (String role : new String[]{"home", "away", "spectator"}) {
			JsonObject envelope = new JsonObject().add("role", role).add("revision", view.get("revision")).add("patch", delta);
			if (keyframe) envelope.add("keyframe", view);
			if (role.equals(view.getString("actor", "")) && !role.equals("spectator")) {
				JsonValue actions = "rare".equals(move) ? current.get("state").asObject().get("actions")
					: payload.get(role + "View").asObject().get("actions");
				if (actions != null) envelope.add("actions", actions);
			}
			byte[] wire = time("envelope-" + role, () -> bytes(envelope));
			int recipients = role.equals("spectator") ? 3 : 1;
			time("delivery-" + role, () -> {
				ByteArrayOutputStream sink = new ByteArrayOutputStream(wire.length * recipients);
				for (int i = 0; i < recipients; i++) sink.write(wire);
				return sink.toByteArray();
			});
			deliveredBytes += wire.length * recipients;
		}
		return new WireBytes(deliveredBytes);
	}

	private void initPrototype(Connection admin, String compact, int population) throws Exception {
		generation = population;
		update(admin, "INSERT INTO probe_current(match_id,generation,snapshot,summary_json,digest) VALUES(?,?,?,?,?)",
			row, generation, gzip(compact), summary.toString(), new byte[32]);
		try (PreparedStatement stmt = admin.prepareStatement("INSERT INTO probe_receipt VALUES (?,?,?,?,?)")) {
			for (int i = 0; i < population; i++) {
				bind(stmt, row, "home", "seed-" + i, i, new byte[32]); stmt.addBatch();
			}
			stmt.executeBatch();
		}
	}

	private void commit(String compact, JsonObject record, JsonObject current) throws Exception {
		byte[] data = bytes(record);
		try (Connection connection = open()) {
			connection.setAutoCommit(false);
			try {
				byte[] prior;
				try (PreparedStatement stmt = connection.prepareStatement("SELECT digest FROM probe_current WHERE match_id=? AND generation=? FOR UPDATE")) {
					bind(stmt, row, generation);
					try (ResultSet rows = stmt.executeQuery()) { check(rows.next()); prior = rows.getBytes(1); }
				}
				MessageDigest hash = MessageDigest.getInstance("SHA-256");
				hash.update(prior);
				byte[] digest = hash.digest(data);
				boolean terminal = stage.equals("terminal");
				update(connection, "UPDATE probe_current SET generation=?,snapshot=?,summary_json=?,digest=?,sealed=?,result_json=? WHERE match_id=? AND generation=?",
					generation + 1, gzip(compact), summary.toString(), digest, terminal, terminal ? summary.toString() : null, row, generation);
				update(connection, "INSERT INTO probe_record VALUES(?,?,?)", row, current.getInt("revision", -1), data);
				update(connection, "INSERT INTO probe_receipt VALUES(?,?,?,?,?)", row, current.getString("actor", "home"), "accepted", current.getInt("revision", -1), hash.digest(bytes(current.get("decision"))));
				connection.commit();
				generation++;
			} catch (Exception failure) { connection.rollback(); throw failure; }
		}
	}

	private byte[] queryBlob(String sql) throws Exception {
		try (Connection connection = open(); PreparedStatement stmt = connection.prepareStatement(sql)) {
			bind(stmt, row);
			try (ResultSet rows = stmt.executeQuery()) { check(rows.next()); return rows.getBytes(1); }
		}
	}
	private int receiptLookup(int index) throws Exception {
		try (Connection connection = open(); PreparedStatement stmt = connection.prepareStatement(
			"SELECT revision FROM probe_receipt WHERE match_id=? AND actor=? AND request_id=?")) {
			bind(stmt, row, "home", "seed-" + index);
			try (ResultSet rows = stmt.executeQuery()) { check(rows.next()); return rows.getInt(1); }
		}
	}
	private byte[] gzip(String text) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (GZIPOutputStream compressed = new GZIPOutputStream(buffer)) { compressed.write(text.getBytes(StandardCharsets.UTF_8)); }
		return buffer.toByteArray();
	}
	private void update(Connection connection, String sql, Object... args) throws Exception {
		try (PreparedStatement stmt = connection.prepareStatement(sql)) { bind(stmt, args); check(stmt.executeUpdate() > 0); }
	}
	private void bind(PreparedStatement stmt, Object... args) throws Exception {
		for (int i = 0; i < args.length; i++) stmt.setObject(i + 1, args[i]);
	}
	private byte[] bytes(JsonValue value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
	private void check(boolean condition) { if (!condition) throw new IllegalStateException("Unexpected measurement state"); }
}
