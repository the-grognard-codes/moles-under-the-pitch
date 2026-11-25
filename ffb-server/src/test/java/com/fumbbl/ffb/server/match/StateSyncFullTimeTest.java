package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.server.db.JdbcRequestConnections;
import com.fumbbl.ffb.server.local.BrowserConnection;
import com.fumbbl.ffb.server.local.BrowserV2Adapter;
import com.fumbbl.ffb.server.local.StateSyncWorker;
import com.fumbbl.ffb.server.team.bb2025.RosterCatalog;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in JDBC full-time and subsequent setup/load capture. A small concession exercises the trace in smoke mode. */
class StateSyncFullTimeTest {
	@Test void captureFullTimeAndCompletedLoad() throws Exception {
		assumeTrue(System.getenv("STATE_SYNC_FULL_TIME") != null, "Set STATE_SYNC_FULL_TIME to opt in");
		captureFromEnvironment();
	}

	public static void main(String[] args) throws Exception {
		new StateSyncFullTimeTest().captureFromEnvironment();
	}

	private void captureFromEnvironment() throws Exception {
		String mode = System.getenv("STATE_SYNC_FULL_TIME");
		String url = System.getenv("STATE_SYNC_JDBC_URL");
		String password = System.getenv("STATE_SYNC_DB_PASSWORD");
		if (url == null || password == null || !url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/ffb_state_sync_test"))
			throw new IllegalArgumentException("Use the isolated loopback state-sync schema");
		if (mode == null || !List.of("smoke", "early", "late", "retained").contains(mode))
			throw new IllegalArgumentException("Set STATE_SYNC_FULL_TIME=smoke, early, late, or retained");
		Path output = Path.of(System.getenv("STATE_SYNC_FULL_TIME_OUTPUT"));
		Path inputs = "smoke".equals(mode) ? null : Path.of(System.getenv("STATE_SYNC_FIXTURES"));
		// Run both immediate and deferred completion in the smoke. Full-size measurements preserve natural deferrals.
		JsonArray runs = new JsonArray().add(capture(mode, inputs, url, password, false));
		if ("smoke".equals(mode)) runs.add(capture(mode, inputs, url, password, true));
		Files.createDirectories(output.toAbsolutePath().getParent());
		Files.writeString(output, new JsonObject().add("cohort", mode).add("maxHeapBytes", Runtime.getRuntime().maxMemory())
			.add("javaVersion", System.getProperty("java.version")).add("runs", runs).toString());
	}

	private JsonObject capture(String mode, Path inputs, String url, String password, boolean defer) throws Exception {
		JsonObject match = inputs == null ? new JsonObject() : JsonObject.readFrom(Files.readString(inputs.resolve("match.json")));
		long seed = match.getLong("seed", 650);
		boolean retained = "retained".equals(mode);
		try (BogrukEngineFixture fixture = new BogrukEngineFixture("Human", "Orc", seed, true, true);
			JdbcRequestConnections connections = new JdbcRequestConnections(
				url + "?connectTimeout=500&socketTimeout=1500&sessionVariables=innodb_lock_wait_timeout=1", "root", password, false)) {
			String id = fixture.document.matchId;
			AtomicBoolean failWrite = new AtomicBoolean();
			JdbcMatchRepository documents = new JdbcMatchRepository(() -> {
				if (failWrite.get() && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
					frame.getClassName().equals(JdbcMatchRepository.class.getName()) && frame.getMethodName().equals("write")))
					&& failWrite.compareAndSet(true, false))
					throw new SQLException("Controlled full-time write failure");
				return connections.open();
			});
			JdbcRecoveryRepository recovery = new JdbcRecoveryRepository(connections::open, true, true);
			String before = inputs == null ? fixture.session.recoveryArtifact()
				: Files.readString(inputs.resolve((retained ? "terminal" : mode) + ".json"));
			JsonObject finalDecision = match.get("final") == null ? new JsonObject() : match.get("final").asObject();
			int revision = JsonObject.readFrom(before).get("payload").asObject().getInt("revision", -1);
			JsonObject mutation = switch (mode) {
				case "late" -> new JsonObject(finalDecision.get("request").asObject());
				case "retained" -> request("setup", "terminal-load").add("matchId", id).add("operation", "load");
				default -> request("setup", "finish").add("matchId", id).add("operation", "concede").add("expectedRevision", revision);
			};
			mutation.set("version", 2);
			boolean fixedClock = "late".equals(mode) && finalDecision.get("atMillis") != null;
			Clock clock = fixedClock
				? Clock.fixed(Instant.ofEpochMilli(finalDecision.getLong("atMillis", -1)), ZoneOffset.UTC) : Clock.systemUTC();
			String role = mutation.get("computerDecision") != null ? "computer"
				: "late".equals(mode) ? finalDecision.getString("role", null) : "home";
			MatchService matches = new MatchService(documents, null, new RosterCatalog());
			SetupApplication setup = new SetupApplication(fixture.server, matches, recovery, true, clock, true);
			setup.configureStorageAdmission(connections::requireAvailable);
			Protocol protocol = new Protocol(setup, matches, id);
			clearRows(url, password, id);
			try {
				documents.insert(new MatchRepository.Record(id, fixture.document.documentVersion, new MatchJson().storageText(fixture.document)));
				assertTrue(recovery.save(new RecoveryRepository.Record(id, 1, before), 0));
				before = null;
				fixture.session = null;
				try (FullTimeTrace trace = FullTimeTrace.start(); StateSyncWorker worker = new StateSyncWorker(fixture.server)) {
					if ("smoke".equals(mode)) {
						worker.twoQueuedControls();
						JsonArray controls = trace.snapshot();
						assertEquals(2, controls.size());
						assertEquals(1, controls.get(0).asObject().getInt("foregroundAtStart", -1));
						assertEquals(0, controls.get(1).asObject().getInt("foregroundAtStart", -1));
					}
					protocol.authenticate(worker);
					trace.clear();
					JsonObject coldTask = null;
					String coldCode = "terminal-load-measured-below";
					String primerCode = "not-needed";
					if (!retained) {
						JsonObject coldLoad = protocol.load(worker, "home", "cold-load");
						coldTask = last(trace.snapshot());
						coldCode = coldLoad.getString("code", null);
						if ("smoke".equals(mode)) assertEquals("ACCEPTED", coldCode);
						if (!"ACCEPTED".equals(coldCode) && !"smoke".equals(mode)) {
							JsonObject prime = request("setup", "prime-engine").set("version", 1).add("matchId", id).add("operation", "load");
							primerCode = worker.prepare(() -> setup.handle("home", prime)).getString("code", null);
						}
						protocol.load(worker, "home", "warmup-home");
						protocol.load(worker, "away", "warmup-away");
						protocol.watch(worker);
					}
					trace.clear();
					protocol.drainPayloadBytes();
					JsonArray delivery = new JsonArray();
					failWrite.set(defer);
					JsonObject completion = protocol.send(worker, role, mutation);
					delivery.add(protocol.drainPayloadBytes());
					JsonObject immediate = last(trace.snapshot());
					int retryTasks = 0;
					while (documents.find(id).documentVersion < 4 && retryTasks < 3) {
						worker.control(() -> { protocol.adapter.expireDecisions(); return null; });
						delivery.add(protocol.drainPayloadBytes());
						retryTasks++;
						if (fixedClock) break;
						if (documents.find(id).documentVersion < 4) Thread.sleep(1100);
					}
					JsonObject load = protocol.load(worker, "home", "completed-load");
					delivery.add(protocol.drainPayloadBytes());
					worker.control(() -> { protocol.adapter.retryPublications(); return null; });
					delivery.add(protocol.drainPayloadBytes());
					JsonArray tasks = trace.snapshot();
					if ("smoke".equals(mode)) verifySmoke(tasks, immediate, completion, load, defer);
					boolean finalDecisionApplied = hasPhase(immediate, "SetupSession.apply")
						&& "ACCEPTED".equals(completion.getString("code", null)) && "FULL_TIME".equals(statePhase(completion));
					return new JsonObject().add("deferInjected", defer).add("startingRevision", revision)
						.add("completedRevision", revision(completion)).add("completionCode", completion.getString("code", null))
						.add("completionPhase", statePhase(completion)).add("finalDecisionApplied", finalDecisionApplied)
						.add("resultStored", documents.find(id).documentVersion == 4)
						.add("coldLoadCode", coldCode).add("fixturePrimingCode", primerCode)
						.add("coldLoadTask", coldTask == null ? JsonValue.NULL : coldTask)
						.add("retryTasks", retryTasks).add("loadCode", load.getString("code", null))
						.add("loadPhase", statePhase(load)).add("tasks", tasks).add("protocolPayloadBytesByTask", delivery);
				}
			} finally {
				clearRows(url, password, id);
			}
		}
	}

	private void verifySmoke(JsonArray tasks, JsonObject immediate, JsonObject completion, JsonObject load, boolean defer) {
		assertEquals("ACCEPTED", completion.getString("code", null));
		assertEquals("FULL_TIME", statePhase(completion));
		assertEquals("ACCEPTED", load.getString("code", null));
		assertEquals("FULL_TIME", statePhase(load));
		assertEquals("INGRESS", immediate.getString("kind", null));
		assertTrue(immediate.getInt("foregroundAtStart", -1) >= 0);
		for (String phase : List.of("SetupApplication.checkpoint", "SetupSession.completedMatch", "SetupSession.recoveryEvents",
			"JsonArray.readFrom", "MatchTranscript.json", "CanonicalJson.encode", "MatchJson.validateCompletion",
			"MatchJson.storageText", "BoundedJsonStorageCodec.gzip", "JdbcMatchRepository.write"))
			assertTrue(hasPhase(immediate, phase), phase);
		JsonObject stored = defer ? tasks.get(1).asObject() : immediate;
		if (defer) assertEquals("CONTROL", stored.getString("kind", null));
		for (String phase : List.of("JdbcRequestConnections.open", "JDBC.executeUpdate", "JDBC.commit",
			"SetupApplication.releaseCompleted", "SetupApplication.liveTranscriptPage"))
			assertTrue(hasPhase(stored, phase), phase);
		assertTrue(tasks.values().stream().map(value -> value.asObject()).anyMatch(task ->
			hasPhase(task, "SetupApplication.reconstruct") || hasPhase(task, "SetupApplication.retainedConcessionOutcome")));
	}

	private boolean hasPhase(JsonObject task, String phase) {
		return task.get("phases").asObject().names().stream().anyMatch(path -> path.endsWith("/" + phase) || path.equals(phase));
	}

	private JsonObject last(JsonArray tasks) { return tasks.get(tasks.size() - 1).asObject(); }

	private int revision(JsonObject reply) {
		return reply.get("state") instanceof JsonObject state ? state.getInt("revision", -1) : -1;
	}

	private String statePhase(JsonObject reply) {
		return reply.get("state") instanceof JsonObject state ? state.getString("phase", "missing") : "missing";
	}

	private void clearRows(String url, String password, String id) throws SQLException {
		try (var connection = DriverManager.getConnection(url, "root", password);
			var recovery = connection.prepareStatement("DELETE FROM ffb_match_recovery WHERE matchid=?");
			var document = connection.prepareStatement("DELETE FROM ffb_prepared_matches WHERE match_id=?")) {
			recovery.setString(1, id);
			document.setString(1, id);
			recovery.executeUpdate();
			document.executeUpdate();
		}
	}

	private static JsonObject request(String type, String name) {
		return new JsonObject().add("version", 2).add("type", type).add("requestId", name);
	}

	private static final class Protocol {
		private final BrowserV2Adapter adapter;
		private final String id;
		private final Map<String, Recipient> recipients = Map.of("home", new Recipient(), "away", new Recipient(),
			"spectator", new Recipient(), "computer", new Recipient());
		private final String serviceToken;

		private Protocol(SetupApplication setup, MatchService matches, String id) throws Exception {
			this.id = id;
			Map<String, AuthenticatedPrincipal> principals = new HashMap<>();
			for (String role : List.of("home", "away", "spectator")) {
				ApplicationScope scope = "spectator".equals(role) ? ApplicationScope.SPECTATOR : ApplicationScope.PLAYER;
				principals.put(role, new AuthenticatedPrincipal(UUID.randomUUID().toString(), EnumSet.of(scope), Long.MAX_VALUE));
			}
			MatchMembershipRepository memberships = mock(MatchMembershipRepository.class);
			for (String role : List.of("home", "away")) {
				String account = principals.get(role).accountId();
				when(memberships.find(id, account)).thenReturn(new MatchMembership(id, account, role));
			}
			when(memberships.isActive(id)).thenReturn(true);
			V2PrincipalDirectory directory = mock(V2PrincipalDirectory.class);
			when(directory.reauthorize(any())).thenAnswer(call -> call.getArgument(0));
			when(directory.authorizePublication(eq(id), any(), anyList())).thenAnswer(call -> {
				List<V2PublicationAuthorization.Target> targets = call.getArgument(2);
				Map<Integer, String> roles = new HashMap<>();
				for (int index = 0; index < targets.size(); index++) {
					var target = targets.get(index);
					if (target.audience() == V2PublicationAuthorization.Audience.COMPUTER) roles.put(index, "away");
					for (var principal : principals.entrySet()) {
						if (principal.getValue().equals(target.principal())) roles.put(index, principal.getKey());
					}
				}
				return new V2PublicationAuthorization.Snapshot(roles);
			});
			byte[] token = new byte[32];
			new SecureRandom().nextBytes(token);
			serviceToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
			String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serviceToken.getBytes(StandardCharsets.US_ASCII)));
			V2PreparationService preparation = mock(V2PreparationService.class);
			when(preparation.computerMembershipRole(id)).thenReturn("away");
			adapter = new BrowserV2Adapter(principals::get, new V2MatchAccess(memberships, directory, Clock.systemUTC()),
				setup, matches, preparation, null, new ComputerOpponentService(digest));
		}

		private void authenticate(StateSyncWorker worker) throws Exception {
			for (String role : List.of("home", "away", "spectator")) {
				JsonObject reply = send(worker, role, request("authenticate", "auth-" + role).add("bearer", role)
					.add("liveTranscript", true));
				assertEquals("ACCEPTED", reply.getString("code", null));
			}
			JsonObject computer = send(worker, "computer", request("authenticateComputer", "auth-computer").add("serviceToken", serviceToken));
			assertEquals("ACCEPTED", computer.getString("code", null));
		}

		private void watch(StateSyncWorker worker) throws Exception {
			send(worker, "spectator", request("watch", "watch").add("matchId", id));
		}

		private JsonObject drainPayloadBytes() {
			JsonObject output = new JsonObject();
			recipients.forEach((role, recipient) -> {
				output.add(role, recipient.payloadBytes);
				recipient.payloadBytes = 0;
			});
			return output;
		}

		private JsonObject load(StateSyncWorker worker, String role, String name) throws Exception {
			return send(worker, role, request("setup", name).add("matchId", id).add("operation", "load"));
		}

		private JsonObject send(StateSyncWorker worker, String role, JsonObject request) throws Exception {
			Recipient recipient = recipients.get(role);
			String name = request.getString("requestId", null);
			String message = request.toString();
			return worker.ingress(message, () -> {
				recipient.expectedReply = name;
				recipient.reply = null;
				adapter.receive(recipient, message);
				if (recipient.reply == null) throw new IllegalStateException("No correlated reply for " + name);
				JsonObject reply = recipient.reply;
				recipient.reply = null;
				recipient.expectedReply = null;
				return reply;
			});
		}
	}

	private static final class Recipient implements BrowserConnection {
		private String expectedReply;
		private JsonObject reply;
		private long payloadBytes;

		@Override public void sendWithOptionalPredecessor(String optional, String mandatory) {
			if (optional != null) send(optional);
			send(mandatory);
		}

		@Override public void send(String message) {
			payloadBytes += new Utf8Size().bytes(message);
			if (expectedReply == null) return;
			JsonObject value = JsonObject.readFrom(message);
			JsonValue requestId = value.get("requestId");
			if (requestId != null && requestId.isString() && expectedReply.equals(requestId.asString())) reply = value;
		}
	}
}
