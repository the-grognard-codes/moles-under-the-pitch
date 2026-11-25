package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.DiceCategory;
import com.fumbbl.ffb.FactoryType;
import com.fumbbl.ffb.FieldCoordinate;
import com.fumbbl.ffb.PlayerState;
import com.fumbbl.ffb.Weather;
import com.fumbbl.ffb.factory.SkillFactory;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.Player;
import com.fumbbl.ffb.model.RosterPlayer;
import com.fumbbl.ffb.option.GameOptionBoolean;
import com.fumbbl.ffb.option.GameOptionId;
import com.fumbbl.ffb.net.commands.ServerCommand;
import com.fumbbl.ffb.net.commands.ServerCommandModelSync;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.skilluse.SkillUsePreferences;
import com.fumbbl.ffb.server.step.StepId;
import com.fumbbl.ffb.util.UtilBox;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CompactMatchSnapshotTest {
    @Test
    void twoSeededMatchesRestoreAcrossTheMatchWithoutGrowingHistory() throws Exception {
        boolean measure = "true".equals(System.getenv("COMPACT_SNAPSHOT_TIMING"));
        if (measure) StateSyncMoveTiming.install();
        fullMatch("Human", "Orc", 43, measure);
        fullMatch("Orc", "Human", 45, measure);
    }

    private void fullMatch(String home, String away, long seed, boolean measure) throws Exception {
        try (BogrukEngineFixture fixture = fixture(home, away, seed)) {
            SetupSession baseline = fixture.session;
            String snapshot = commit(baseline, 0);
            JsonObject early = null, late = null;
            int reactions = 0, cleanup = 0, captureCount = 0;
            List<JsonObject> replyTimings = new ArrayList<>();
            List<JsonObject> taskTimings = new ArrayList<>();
            for (int sequence = 0; sequence < 1_600 && !baseline.isComplete(); sequence++) {
                JsonObject current = JsonObject.readFrom(snapshot);
                int revision = baseline.revision();
                JsonObject view = actorView(baseline);
                if (revision == 50) early = current;
                if (revision > 50 && "PLAY".equals(view.getString("phase", null))
                    && "REGULAR".equals(view.getString("turnMode", null)) && view.get("reaction") == null)
                    late = current;

                boolean reaction = current.get("reactiveDecision") != null;
                boolean betweenDrives = List.of(StepId.SETUP, StepId.KICKOFF).contains(nativeState(baseline).getCurrentStep().getId());
                boolean firstCleanup = betweenDrives && view.getInt("half", 1) == 2 && cleanup == 0;
                boolean compare = revision < 6 || revision == 50 || reaction && reactions == 0
                    || firstCleanup || revision % 100 == 0;
                if (reaction) reactions++;
                if (firstCleanup) cleanup++;

                String role = view.getString("actor", null);
                JsonObject request = BogrukEngineFixture.recordedDecision(fixture.document.matchId,
                    view, "step-" + sequence, sequence % 24 == 0);
                SetupSession restored = compare ? restoreAndCompare(fixture, baseline, snapshot) : null;
                long now = System.currentTimeMillis();
                if (measure && revision > 50) {
                    StateSyncMoveTiming.begin(revision, "reply");
                    try {
                        view(baseline, role);
                        replyTimings.add(StateSyncMoveTiming.result(revision));
                    } finally {
                        StateSyncMoveTiming.clear();
                    }
                }
                if (measure) StateSyncMoveTiming.begin(revision, "apply-and-snapshot");
                JsonObject response;
                CompactMatchWrite.Capture capture;
                try {
                    response = baseline.apply(role, request, now);
                    assertEquals("ACCEPTED", response.getString("code", null), request::toString);
                    capture = baseline.compactCapture();
                    assertNotNull(capture);
                    assertEquals(revision + 1, capture.revision());
                    captureCount += JsonArray.readFrom(capture.nativeOutcomes()).size();
                    snapshot = commit(baseline, 0);
                    if (measure) taskTimings.add(StateSyncMoveTiming.result(revision + 1));
                } finally {
                    if (measure) StateSyncMoveTiming.clear();
                }
                if (restored != null) {
                    JsonObject nextReply = restored.apply(role, request, now);
                    eraseReplyTime(response.get("state").asObject());
                    eraseReplyTime(nextReply.get("state").asObject());
                    assertEquals(response, nextReply, "next reply at r" + revision);
                    assertSameCapture(capture, restored.compactCapture());
                    assertContinuation(snapshot, commit(restored, 0));
                    assertViews(baseline, restored);
                }
            }
            assertTrue(baseline.isComplete(), "The workload must play to full time");
            assertTrue(captureCount > baseline.revision(), "Use real native commands with model syncs");
            assertTrue(cleanup > 0, "Exercise a new half's native cleanup and setup");
            restoreAndCompare(fixture, baseline, snapshot);
            assertNotNull(early);
            assertNotNull(late);
            assertEquals("REGULAR", actorView(SetupSession.restoreCompactSnapshot(fixture.server, early.toString()))
                .getString("turnMode", null));
            int earlyBytes = bytes(early.toString());
            int lateBytes = bytes(late.toString());
            int terminalBytes = bytes(snapshot);
            System.out.printf("COMPACT_SNAPSHOT seed=%d early=r50:%d late=r%d:%d fullTime=r%d:%d nativeSyncs=%d reactions=%d%n",
                seed, earlyBytes, late.getInt("revision", -1), lateBytes, baseline.revision(), terminalBytes, captureCount, reactions);
            assertTrue(4L * lateBytes <= 5L * earlyBytes, "Matched PLAY/REGULAR snapshot grew more than 25%");
            assertTrue(4L * terminalBytes <= 5L * earlyBytes, "Full-time snapshot grew more than 25%");
            if (measure) {
                timing(seed, "acting-reply", replyTimings);
                timing(seed, "apply-and-snapshot", taskTimings);
            }
        }
    }

    @Test
    void restoresRouteMovementNarrativeAndResolvesTheSameDodgeChoice() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 43)) {
            SetupSession baseline = fixture.session;
            advanceToTurn(fixture);
            GameState nativeState = nativeState(baseline);
            Game game = nativeState.getGame();
            Player<?> mover = game.getActingTeam().getPlayers()[0];
            Player<?> marker = game.getOtherTeam(game.getActingTeam()).getPlayers()[0];
            UtilBox.putAllPlayersIntoBox(game);
            place(game, mover, 7, 7);
            place(game, marker, 6, 7);
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(4, 4));
            game.getFieldModel().setBallMoving(false);

            String role = actorView(baseline).getString("actor", null);
            JsonObject declaration = action(view(baseline, role), "select", mover.getId());
            baseline.apply(role, request(baseline, "action", "select-mover").add("actionId", declaration.get("id")));
            commit(baseline, 0);

            forceRolls(nativeState, 1, 6, 6, 6);
            JsonArray waypoints = new JsonArray().add(new JsonObject().add("x", 8).add("y", 7))
                .add(new JsonObject().add("x", 9).add("y", 7));
            baseline.apply(role, request(baseline, "route", "route-with-dodge").add("playerId", mover.getId())
                .add("waypoints", waypoints));
            String paused = commit(baseline, 0);
            JsonObject snapshot = JsonObject.readFrom(paused);
            assertFalse(snapshot.get("pendingRoute").isNull());
            assertFalse(snapshot.get("unfinishedMovement").isNull());
            assertNotNull(snapshot.get("reactiveDecision"));
            assertEquals(StepId.MOVE_DODGE, nativeState.getCurrentStep().getId());
            SetupSession restored = restoreAndCompare(fixture, baseline, paused);
            JsonObject teamReroll = action(view(baseline, role), "reroll", null);
            assertTrue(teamReroll.getString("id", "").endsWith(":team"));
            JsonObject reroll = request(baseline, "action", "use-team-reroll").add("actionId", teamReroll.get("id"));
            long now = System.currentTimeMillis();
            baseline.apply(role, reroll, now);
            restored.apply(role, reroll, now);
            assertSameCapture(baseline.compactCapture(), restored.compactCapture());
            assertContinuation(commit(baseline, 0), commit(restored, 0));
            assertViews(baseline, restored);
            assertEquals(new FieldCoordinate(9, 7), game.getFieldModel().getPlayerCoordinate(mover));
        }
    }

    @Test
    void restoresAnActivationQueuedBehindAnOptionalSkillChoice() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Orc", "Human", 45)) {
            SetupSession baseline = fixture.session;
            JsonObject ready = advanceToTurn(fixture);
            String role = ready.getString("actor", null);
            GameState state = nativeState(baseline);
            Game game = state.getGame();
            Player<?> first = game.getActingTeam().getPlayers()[0];
            Player<?> next = game.getActingTeam().getPlayers()[1];
            UtilBox.putAllPlayersIntoBox(game);
            place(game, first, 5, 5);
            place(game, next, 5, 8);
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(5, 5));
            game.getFieldModel().setBallMoving(false);
            ((GameOptionBoolean) game.getOptions().getOptionWithDefault(GameOptionId.ENABLE_STALLING_CHECK)).setValue(true);
            SkillFactory skills = game.getFactory(FactoryType.Factory.SKILL);
            ((RosterPlayer) first).addSkill(skills.forName("Steady Footing"));

            JsonObject select = action(view(baseline, role), "select", first.getId());
            baseline.apply(role, request(baseline, "action", "first-activation").add("actionId", select.get("id")));
            commit(baseline, 0);
            baseline.apply(role, movement(baseline, "first-move", first.getId(), 6, 5));
            commit(baseline, 0);
            state.stallingDetected();
            forceRolls(state, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6);
            JsonObject pause = baseline.apply(role, movement(baseline, "next-activation", next.getId(), 6, 8))
                .get("state").asObject();
            String queued = commit(baseline, 0);
            JsonObject snapshot = JsonObject.readFrom(queued);
            assertEquals(next.getId(), snapshot.get("pendingActivation").asObject().getString("playerId", null));
            assertEquals(next.getId(), snapshot.get("pendingRoute").asObject().getString("playerId", null));
            SetupSession restored = restoreAndCompare(fixture, baseline, queued);
            JsonObject useSkill = pause.get("actions").asArray().values().stream().map(JsonValue::asObject)
                .filter(option -> "skill".equals(option.getString("kind", null)) && option.getString("id", "").endsWith(":true"))
                .findFirst().orElseThrow();
            JsonObject use = request(baseline, "action", "use-steady-footing").add("actionId", useSkill.get("id"));
            long now = System.currentTimeMillis();
            baseline.apply(role, use, now);
            restored.apply(role, use, now);
            assertSameCapture(baseline.compactCapture(), restored.compactCapture());
            assertContinuation(commit(baseline, 0), commit(restored, 0));
            assertViews(baseline, restored);
            assertEquals(new FieldCoordinate(6, 8), game.getFieldModel().getPlayerCoordinate(next));
        }
    }

    private JsonObject advanceToTurn(BogrukEngineFixture fixture) throws Exception {
        SetupSession baseline = fixture.session;
        commit(baseline, 0);
        JsonObject ready = actorView(baseline);
        for (int sequence = 0; sequence < 20 && !"REGULAR".equals(ready.getString("turnMode", null)); sequence++) {
            baseline.apply(ready.getString("actor", null), fixture.recordedDecision(ready, "kickoff-" + sequence, sequence));
            commit(baseline, 0);
            ready = actorView(baseline);
        }
        assertEquals("REGULAR", ready.getString("turnMode", null));
        return ready;
    }

    private JsonObject movement(SetupSession session, String id, String player, int x, int y) {
        return request(session, "movement", id).add("playerId", player).add("kind", "move").add("targetPlayerId", JsonValue.NULL)
            .add("waypoints", new JsonArray().add(new JsonObject().add("x", x).add("y", y)));
    }

    private void forceRolls(GameState state, int... values) {
        List<DiceCategory> rolls = new ArrayList<>();
        for (int value : values) {
            DiceCategory roll = new DiceCategory();
            roll.parseCommand(Integer.toString(value), state.getGame(), state.getGame().getTeamHome());
            rolls.add(roll);
        }
        state.getDiceRoller().getTestRolls().put("General", rolls);
    }

    private static JsonObject action(JsonObject view, String kind, String player) {
        return view.get("actions").asArray().values().stream().map(JsonValue::asObject)
            .filter(action -> kind.equals(action.getString("kind", null))
                && (player == null ? action.getString("id", "").endsWith(":team")
                    : player.equals(action.getString("sourcePlayerId", null))))
            .findFirst().orElseThrow();
    }

    private static void place(Game game, Player<?> player, int x, int y) {
        game.getFieldModel().setPlayerCoordinate(player, new FieldCoordinate(x, y));
        game.getFieldModel().setPlayerState(player, new PlayerState(PlayerState.STANDING).changeActive(true));
    }

    @Test
    void stageRestoresToBaseAndSuspensionKeepsClockAndBoundedSaveRequests() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 46)) {
            SetupSession baseline = fixture.session;
            String snapshot = commit(baseline, 7);
            for (int sequence = 0; sequence < 2; sequence++) {
                JsonObject view = actorView(baseline);
                String role = view.getString("actor", null);
                baseline.apply(role, fixture.recordedDecision(view, "pre-" + sequence, sequence));
                snapshot = commit(baseline, 7);
            }
            JsonObject base = actorView(baseline);
            String actor = base.getString("actor", null);
            assertEquals("SETUP", base.getString("phase", null));
            JsonObject player = base.get("players").asArray().values().stream().map(JsonValue::asObject)
                .filter(row -> actor.equals(row.getString("role", null)) && !row.get("x").isNull()).findFirst().orElseThrow();
            JsonObject place = request(baseline, "place", "stage").add("playerId", player.get("id")).add("to", JsonValue.NULL);
            assertEquals("ACCEPTED", baseline.apply(actor, place).getString("code", null));
            assertNotEquals(base, actorView(baseline));
            assertNull(baseline.compactCapture());
            assertEquals(snapshot, commit(baseline, 7), "Transient stage must stay outside the snapshot");
            SetupSession afterCrash = SetupSession.restoreCompactSnapshot(fixture.server, snapshot);
            assertEquals(base, actorView(afterCrash));
            assertEquals(7, afterCrash.compactHistory().chatCount());

            SkillUsePreferences ask = SkillUsePreferences.defaults()
                .with(SkillUsePreferences.UseCase.DODGE_MOVEMENT, SkillUsePreferences.Mode.ALWAYS_ASK);
            afterCrash.configureSkillUsePreferences(ask, SkillUsePreferences.defaults());
            nativeState(afterCrash).setTurnTimeStarted(17_000L);
            JsonObject save = request(afterCrash, "saveRequest", "save");
            JsonObject saveState = afterCrash.decorateSaveResume(afterCrash.saveResume("home", save, 21_000L))
                .get("state").asObject().get("saveResume").asObject();
            JsonObject accept = request(afterCrash, "saveAccept", "save-accept").add("proposalId", saveState.get("proposalId"));
            afterCrash.saveResume("away", accept, 21_300L);
            String suspended = commit(afterCrash, 7);
            SetupSession recovered = restoreAndCompare(fixture, afterCrash, suspended);
            assertEquals(suspended, commit(recovered, 7));
            assertEquals(17_000L, nativeState(recovered).getTurnTimeStarted());
            assertTrue(recovered.saveResume("away", accept, 21_600L).getBoolean("duplicate", false));
            assertEquals(ask.toJson(), nativeState(recovered).getOptionalSkillUse().snapshot().asObject().get("home"));
            JsonObject resume = request(recovered, "resumeRequest", "resume");
            JsonValue resumeId = recovered.decorateSaveResume(recovered.saveResume("away", resume, 22_000L))
                .get("state").asObject().get("saveResume").asObject().get("proposalId");
            afterCrash.saveResume("away", resume, 22_000L);
            JsonObject resumeAccept = request(recovered, "resumeAccept", "resume-accept").add("proposalId", resumeId);
            recovered.saveResume("home", resumeAccept, 22_300L);
            afterCrash.saveResume("home", resumeAccept, 22_300L);
            assertEquals(18_000L, nativeState(recovered).getTurnTimeStarted());
            assertEquals(commit(afterCrash, 7), commit(recovered, 7));
        }
    }

    @Test
    void onlyDurableWritesPruneFrozenCommandsAndUncertainWritesEvict() throws Exception {
        for (CompactMatchWrite.Result result : CompactMatchWrite.Result.values()) {
            try (BogrukEngineFixture fixture = fixture("Human", "Orc", 47)) {
                SetupSession session = fixture.session;
                GameState engine = nativeState(session);
                String initial = commit(session, 0);
                JsonObject before = actorView(session);
                session.apply(before.getString("actor", null), fixture.recordedDecision(before, "capture", 0));
                CompactMatchWrite.Capture capture = session.compactCapture();
                ServerCommand[] commands = engine.getGameLog().getServerCommands();
                assertTrue(commands.length > 0);
                assertFalse(JsonArray.readFrom(capture.nativeOutcomes()).isEmpty());
                ServerCommandModelSync sync = (ServerCommandModelSync) List.of(commands).stream()
                    .filter(ServerCommandModelSync.class::isInstance).findFirst().orElseThrow();
                String frozen = capture.nativeOutcomes();
                view(session, "home");
                view(session, "away");
                sync.setCommandNr(0);
                assertEquals(frozen, session.compactCapture().nativeOutcomes());
                assertSame(capture, session.compactCapture());
                assertEquals(commands.length, engine.getGameLog().size(), "Views and capture leave commands resident");
                assertThrows(IllegalStateException.class, () -> session.apply(before.getString("actor", null),
                    fixture.recordedDecision(before, "uncommitted", 0)));
                AtomicReference<SetupSession> resident = new AtomicReference<>(session);
                CompactMatchWrite.Result actual = session.writeCompactSnapshot(boundary(session, 0), candidate -> {
                    assertSame(session, resident.get());
                    assertEquals(commands.length, engine.getGameLog().size());
                    assertEquals(frozen, candidate.capture().nativeOutcomes());
                    assertFalse(JsonObject.readFrom(candidate.snapshot()).get("native").asObject().names().contains("gameLog"));
                    return result;
                }, () -> resident.set(null));
                assertEquals(result, actual);
                if (result == CompactMatchWrite.Result.DURABLE) {
                    assertSame(session, resident.get());
                    assertEquals(0, engine.getGameLog().size());
                    assertNull(session.compactCapture());
                } else {
                    assertNull(resident.get());
                    assertEquals(commands.length, engine.getGameLog().size());
                    assertThrows(IllegalStateException.class, () -> actorView(session));
                    assertEquals(0, SetupSession.restoreCompactSnapshot(fixture.server, initial).revision());
                }
            }
        }
    }

    @Test
    void rejectsLogHistoryAndMismatchedCommandOrRecordBoundary() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Orc", "Human", 48)) {
            String snapshot = commit(fixture.session, 0);
            JsonObject withLog = JsonObject.readFrom(snapshot);
            withLog.get("native").asObject().add("gameLog", new JsonObject().add("commandArray", new JsonArray()));
            JsonObject withEvents = JsonObject.readFrom(snapshot).add("eventsJson", "[]");
            JsonObject badCommand = JsonObject.readFrom(snapshot).set("lastCapturedCommand", Integer.MAX_VALUE);
            JsonObject badCount = JsonObject.readFrom(snapshot);
            badCount.get("historyBoundary").asObject().set("recordCount", 2);
            for (JsonObject invalid : List.of(withLog, withEvents, badCommand, badCount))
                assertThrows(IllegalArgumentException.class, () -> SetupSession.restoreCompactSnapshot(fixture.server, invalid.toString()));
        }
    }

    private BogrukEngineFixture fixture(String home, String away, long seed) throws Exception {
        BogrukEngineFixture fixture = new BogrukEngineFixture(home, away, seed, true, true);
        fixture.session = SetupSession.startCompact(fixture.server, fixture.document, -2, true, true);
        fixture.seedRecoveryDice(seed);
        nativeState(fixture.session).getGame().getFieldModel().setWeather(Weather.NICE);
        return fixture;
    }

    private static String commit(SetupSession session, int chatCount) throws Exception {
        AtomicReference<String> snapshot = new AtomicReference<>();
        session.writeCompactSnapshot(boundary(session, chatCount), data -> {
            snapshot.set(data.snapshot());
            return CompactMatchWrite.Result.DURABLE;
        }, () -> fail("The test store confirmed durability"));
        assertEquals(0, nativeState(session).getGameLog().size());
        return snapshot.get();
    }

    private static CompactMatchWrite.History boundary(SetupSession session, int chatCount) throws Exception {
        CompactMatchWrite.Capture capture = session.compactCapture();
        CompactMatchWrite.History prior = session.compactHistory();
        String digest = prior == null ? "0".repeat(64) : prior.digest();
        if (capture != null) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(HexFormat.of().parseHex(digest));
            digest = HexFormat.of().formatHex(sha.digest(capture.nativeOutcomes().getBytes(StandardCharsets.UTF_8)));
        }
        return new CompactMatchWrite.History(session.revision() + 1, chatCount, digest);
    }

    private SetupSession restoreAndCompare(BogrukEngineFixture fixture, SetupSession baseline, String snapshot) throws Exception {
        SetupSession restored = SetupSession.restoreCompactSnapshot(fixture.server, snapshot);
        assertEquals(0, nativeState(restored).getGameLog().size());
        assertNull(restored.compactCapture());
        assertEquals(snapshot, commit(restored, restored.compactHistory().chatCount()), "Restore is a pure deserialization");
        assertViews(baseline, restored);
        assertEquals(baseline.computerProjection(), restored.computerProjection());
        return restored;
    }

    private void assertViews(SetupSession expected, SetupSession actual) {
        for (String role : List.of("home", "away")) {
            JsonObject a = expected.decorateSaveResumeState(view(expected, role));
            JsonObject b = actual.decorateSaveResumeState(view(actual, role));
            eraseReplyTime(a);
            eraseReplyTime(b);
            assertEquals(a, b, () -> role + " view at r" + expected.revision());
        }
    }

    private void assertSameCapture(CompactMatchWrite.Capture expected, CompactMatchWrite.Capture actual) {
        assertEquals(expected.revision(), actual.revision());
        assertEquals(expected.lastCapturedCommand(), actual.lastCapturedCommand());
        JsonArray a = JsonArray.readFrom(expected.nativeOutcomes()), b = JsonArray.readFrom(actual.nativeOutcomes());
        eraseWallTime(a);
        eraseWallTime(b);
        orderSetRemovals(a);
        orderSetRemovals(b);
        assertEquals(a, b, () -> "native outcomes at r" + expected.revision());
    }

    private void orderSetRemovals(JsonArray syncs) {
        // Iteration order within a native HashSet clear is immaterial. Keep report, command and other change order exact.
        for (JsonValue value : syncs) {
            JsonObject changes = value.asObject().get("modelChangeList").asObject();
            JsonArray ordered = new JsonArray();
            List<JsonValue> entries = changes.get("modelChangeArray").asArray().values();
            for (int start = 0; start < entries.size();) {
                String id = entries.get(start).asObject().getString("modelChangeId", "");
                int end = start + 1;
                if (List.of("fieldModelRemoveMoveSquare", "fieldModelRemoveTrackNumber").contains(id)) {
                    while (end < entries.size() && id.equals(entries.get(end).asObject().getString("modelChangeId", ""))) end++;
                }
                entries.subList(start, end).stream().sorted(Comparator.comparing(JsonValue::toString)).forEach(ordered::add);
                start = end;
            }
            changes.set("modelChangeArray", ordered);
        }
    }

    private void assertContinuation(String expected, String actual) {
        JsonObject a = JsonObject.readFrom(expected), b = JsonObject.readFrom(actual);
        a.get("historyBoundary").asObject().remove("digest");
        b.get("historyBoundary").asObject().remove("digest");
        eraseWallTime(a);
        eraseWallTime(b);
        assertEquals(a, b, () -> "continuation at r" + a.getInt("revision", -1));
    }

    private static void eraseReplyTime(JsonObject view) {
        if (view.get("clock") != null) view.get("clock").asObject().remove("turnElapsedMs");
        if (view.get("reaction") != null) view.get("reaction").asObject().remove("serverNow");
    }

    private static void eraseWallTime(JsonValue value) {
        if (value.isArray()) {
            for (JsonValue item : value.asArray()) eraseWallTime(item);
        } else if (value.isObject()) {
            JsonObject object = value.asObject();
            for (String key : List.of("gameTime", "turnTime", "finished", "endTime", "turnTimeStarted")) object.remove(key);
            for (JsonObject.Member field : object) eraseWallTime(field.getValue());
        }
    }

    private JsonObject request(SetupSession session, String operation, String id) {
        return new JsonObject().add("operation", operation).add("requestId", id).add("expectedRevision", session.revision());
    }

    private static JsonObject actorView(SetupSession session) {
        JsonObject home = view(session, "home");
        return "away".equals(home.getString("actor", null)) ? view(session, "away") : home;
    }

    private static JsonObject view(SetupSession session, String role) {
        return session.reply("load", "ACCEPTED", false, role).get("state").asObject();
    }

    private static GameState nativeState(SetupSession session) throws ReflectiveOperationException {
        Field field = SetupSession.class.getDeclaredField("state");
        field.setAccessible(true);
        return (GameState) field.get(session);
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void timing(long seed, String operation, List<JsonObject> samples) {
        long[] nanos = samples.stream().mapToLong(sample -> sample.getLong("applyNanos", -1)).sorted().toArray();
        long copyNanos = 0;
        int copies = 0, withCopies = 0;
        for (JsonObject sample : samples) {
            int count = 0;
            for (String type : List.of("blockCopy", "transferCopy")) {
                for (JsonValue copy : sample.get(type).asArray()) {
                    copyNanos += copy.asObject().getLong("nanos", -1);
                    count++;
                }
            }
            copies += count;
            if (count != 0) withCopies++;
        }
        System.out.printf("COMPACT_TIMING seed=%d operation=%s samples=%d medianMs=%.3f p99Ms=%.3f copies=%d withCopies=%d copyTotalMs=%.3f%n",
            seed, operation, nanos.length, nanos[nanos.length / 2] / 1e6, nanos[(int) Math.ceil(nanos.length * .99) - 1] / 1e6,
            copies, withCopies, copyNanos / 1e6);
    }
}
