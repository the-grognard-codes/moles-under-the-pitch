package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.BlockDiceCategory;
import com.fumbbl.ffb.DiceCategory;
import com.fumbbl.ffb.FactoryType;
import com.fumbbl.ffb.FieldCoordinate;
import com.fumbbl.ffb.PlayerState;
import com.fumbbl.ffb.Weather;
import com.fumbbl.ffb.factory.SkillFactory;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.Player;
import com.fumbbl.ffb.model.RosterPlayer;
import com.fumbbl.ffb.net.commands.ServerCommand;
import com.fumbbl.ffb.net.commands.ServerCommandModelSync;
import com.fumbbl.ffb.option.GameOptionBoolean;
import com.fumbbl.ffb.option.GameOptionId;
import com.fumbbl.ffb.report.ReportList;
import com.fumbbl.ffb.report.ReportSecretWeaponBan;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.model.SteadyFootingContext;
import com.fumbbl.ffb.server.skilluse.SkillUsePreferences;
import com.fumbbl.ffb.server.step.StepId;
import com.fumbbl.ffb.server.step.StepParameter;
import com.fumbbl.ffb.server.step.StepParameterKey;
import com.fumbbl.ffb.server.step.bb2025.pass.StepHailMaryPass;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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
        fullMatch("Human", "Orc", 103, measure);
        fullMatch("Orc", "Human", 107, measure);
    }

    private void fullMatch(String home, String away, long seed, boolean measure) throws Exception {
        try (BogrukEngineFixture fixture = fixture(home, away, seed)) {
            SetupSession baseline = fixture.session;
            String snapshot = commit(baseline, 0);
            JsonObject early = null, late = null;
            int captureCount = 0, passDependencyBoundaries = 0, reactions = 0;
            Map<String, Integer> reports = new HashMap<>();
            Random choices = new Random(seed);
            int decisionsInTurn = 0;
            String turn = "";
            List<JsonObject> replyTimings = new ArrayList<>();
            List<JsonObject> taskTimings = new ArrayList<>();
            for (int sequence = 0; sequence < 1_600 && !baseline.isComplete(); sequence++) {
                JsonObject current = JsonObject.readFrom(snapshot);
                int revision = baseline.revision();
                JsonObject view = actorView(baseline);
                if (revision == 50) early = current;
                if (revision > 50 && "PLAY".equals(view.getString("phase", null))
                    && "REGULAR".equals(view.getString("turnMode", null)) && current.get("reactiveDecision") == null)
                    late = current;

                String period = view.get("half") + ":" + view.get("homeTurn") + ":" + view.get("awayTurn");
                if (!turn.equals(period)) { decisionsInTurn = 0; turn = period; }
                String role = view.getString("actor", null);
                JsonObject request = choose(fixture, baseline, view, sequence, decisionsInTurn++, choices,
                    reports.getOrDefault("passRoll", 0) == 0);
                if (current.get("reactiveDecision") != null) reactions++;
                boolean awaitingPassFix = waitingForPassRecoveryFix(baseline, current);
                if (awaitingPassFix) {
                    passDependencyBoundaries++;
                    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> SetupSession.restoreCompactSnapshot(fixture.server, current.toString()));
                    assertEquals("Compact native state did not round-trip", error.getCause().getMessage());
                }
                SetupSession restored = awaitingPassFix ? null : restoreAndCompare(fixture, baseline, snapshot);
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
                    JsonArray outcomes = JsonArray.readFrom(capture.nativeOutcomes());
                    captureCount += outcomes.size();
                    countReports(outcomes, reports);
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
            assertTrue(reactions > 0, () -> "Missing a native reaction for seed " + seed);
            for (String report : List.of("blockRoll", "passRoll", "injury"))
                assertTrue(reports.getOrDefault(report, 0) > 0, () -> "Missing " + report + " for seed " + seed + ": " + reports);
            restoreAndCompare(fixture, baseline, snapshot);
            assertNotNull(early);
            assertNotNull(late);
            assertEquals("regular", early.get("native").asObject().get("game").asObject().getString("turnMode", null),
                "Compare the same native turn mode at revision 50 and late play");
            int earlyBytes = bytes(early.toString());
            int lateBytes = bytes(late.toString());
            int terminalBytes = bytes(snapshot);
            System.out.printf("COMPACT_SNAPSHOT seed=%d early=r50:%d late=r%d:%d fullTime=r%d:%d nativeSyncs=%d reactions=%d passDependencyBoundaries=%d reports=%s%n",
                seed, earlyBytes, late.getInt("revision", -1), lateBytes, baseline.revision(), terminalBytes, captureCount,
                reactions, passDependencyBoundaries, new java.util.TreeMap<>(reports));
            assertTrue(4L * lateBytes <= 5L * earlyBytes, "Matched PLAY/REGULAR snapshot grew more than 25%");
            assertTrue(4L * terminalBytes <= 5L * earlyBytes, "Full-time snapshot grew more than 25%");
            if (measure) {
                timing(seed, "acting-reply", replyTimings);
                timing(seed, "apply-and-snapshot", taskTimings);
            }
        }
    }

    private JsonObject choose(BogrukEngineFixture fixture, SetupSession session, JsonObject view,
        int sequence, int decisionsInTurn, Random random, boolean seekPass) {
        String id = "step-" + sequence;
        if (!view.get("prompt").isNull() || "SETUP".equals(view.getString("phase", null)))
            return fixture.recordedDecision(view, id, sequence);

        List<JsonObject> choices = new ArrayList<>();
        if (seekPass && decisionsInTurn < 20 && "REGULAR".equals(view.getString("turnMode", ""))) {
            JsonObject possession = seekPass(view, random);
            if (possession != null) return request(session, "action", id).add("actionId", possession.get("id"));
        }
        for (JsonValue value : view.get("actions").asArray()) {
            JsonObject action = value.asObject();
            JsonObject request = request(session, "action", id).add("actionId", action.get("id"));
            if (session.isStagedRequest(request)) continue;
            String kind = action.getString("kind", "");
            if ("endTurn".equals(kind) && decisionsInTurn >= 20) return request;
            int weight = switch (kind) {
                case "block", "pass", "move", "jump", "pickup", "reroll" -> 10;
                case "selectBlock", "declarePass", "kick" -> 4;
                default -> 1;
            };
            for (int i = 0; i < weight; i++) choices.add(request);
        }
        if (choices.isEmpty()) return fixture.recordedDecision(view, id, sequence);
        return choices.get(random.nextInt(choices.size()));
    }

    private JsonObject seekPass(JsonObject view, Random random) {
        List<JsonObject> passes = offeredActions(view, "pass").toList();
        if (!passes.isEmpty()) return passes.get(random.nextInt(passes.size()));
        JsonValue ball = view.get("ball");
        JsonObject state = view.get("ballState").asObject();
        JsonValue carrier = state.get("carrierPlayerId");
        if (carrier.isString()) {
            return offeredActions(view, "declarePass")
                .filter(option -> carrier.equals(option.get("sourcePlayerId"))).findFirst().orElse(null);
        }
        if (!ball.isObject() || !state.getBoolean("inPlay", false)) return null;
        JsonObject position = ball.asObject();
        List<JsonObject> players = view.get("players").asArray().values().stream().map(JsonValue::asObject).toList();
        JsonValue active = view.get("activePlayerId");
        JsonObject mover = players.stream().filter(player -> active.equals(player.get("id"))).findFirst().orElse(null);
        if (mover != null && mover.get("x").isNumber()) {
            int current = distance(mover, position);
            JsonObject toward = offeredActions(view, "move").filter(option -> option.get("target").isObject())
                .filter(option -> distance(option.get("target").asObject(), position) < current)
                .min(Comparator.comparingInt(option -> distance(option.get("target").asObject(), position))).orElse(null);
            if (toward != null) return toward;
        }
        return offeredActions(view, "select")
            .filter(option -> option.get("sourcePlayerId") != null && !active.equals(option.get("sourcePlayerId")))
            .min(Comparator.comparingInt(option -> players.stream()
                .filter(player -> player.get("id").equals(option.get("sourcePlayerId")) && player.get("x").isNumber())
                .mapToInt(player -> distance(player, position)).findFirst().orElse(Integer.MAX_VALUE))).orElse(null);
    }

    private int distance(JsonObject from, JsonObject to) {
        return Math.max(Math.abs(from.getInt("x", 0) - to.getInt("x", 0)),
            Math.abs(from.getInt("y", 0) - to.getInt("y", 0)));
    }

    private void countReports(JsonArray outcomes, Map<String, Integer> counts) {
        for (JsonValue value : outcomes) {
            JsonValue list = value.asObject().get("reportList");
            if (list == null || list.isNull()) continue;
            for (JsonValue report : list.asObject().get("reports").asArray())
                counts.merge(report.asObject().getString("reportId", ""), 1, Integer::sum);
        }
    }

    private boolean waitingForPassRecoveryFix(SetupSession baseline, JsonObject snapshot) throws Exception {
        // #691 owns the shared native pass codec. Exercise these exact boundaries as soon as it lands.
        JsonObject state = snapshot.get("native").asObject();
        List<JsonValue> steps = new ArrayList<>(state.get("stepStack").asObject().get("steps").asArray().values());
        if (state.get("currentStep") != null) steps.add(state.get("currentStep"));
        for (JsonValue value : steps) {
            JsonObject step = value.asObject();
            if (!StepId.HAIL_MARY_PASS.getName().equals(step.getString("stepId", "")) || !step.get("passResult").isNull()) continue;
            GameState engine = nativeState(baseline);
            return !new StepHailMaryPass(engine).initFrom(engine.getGame().getRules(), step)
                .toJsonValue().get("passResult").isNull();
        }
        return false;
    }

    @Test
    void restoresRouteMovementNarrativeAndResolvesTheSameDodgeChoice() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 43)) {
            SetupSession baseline = fixture.session;
            advanceToTurn(fixture);
            GameState nativeState = nativeState(baseline);
            Game game = nativeState.getGame();
            Player<?> mover = game.getActingTeam().getPlayers()[1];
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

            game.getTurnData().setReRollsBrilliantCoachingOneDrive(0);
            game.getTurnData().setReRollShowStarOneDrive(1);
            int teamRerolls = game.getTurnData().getReRolls();
            forceRolls(nativeState, 1, 6, 6, 6);
            JsonArray waypoints = new JsonArray().add(new JsonObject().add("x", 8).add("y", 7))
                .add(new JsonObject().add("x", 9).add("y", 7));
            baseline.apply(role, request(baseline, "route", "route-with-dodge").add("playerId", mover.getId())
                .add("waypoints", waypoints));
            game.getTurnDataHome().setSingleUseReRolls(1);
            game.getTurnDataAway().setSingleUseReRolls(2);
            String paused = commit(baseline, 0);
            JsonObject snapshot = JsonObject.readFrom(paused);
            assertFalse(snapshot.get("pendingRoute").isNull());
            assertFalse(snapshot.get("unfinishedMovement").isNull());
            assertNotNull(snapshot.get("reactiveDecision"));
            assertEquals(StepId.MOVE_DODGE, nativeState.getCurrentStep().getId());
            SetupSession restored = restoreAndCompare(fixture, baseline, paused);
            JsonObject teamReroll = action(view(baseline, role), "reroll", null);
            assertTrue(teamReroll.getString("label", "").contains("Star of the Show"));
            Game restoredGame = nativeState(restored).getGame();
            assertEquals(1, restoredGame.getTurnDataHome().getSingleUseReRolls());
            assertEquals(2, restoredGame.getTurnDataAway().getSingleUseReRolls());
            JsonObject reroll = request(baseline, "action", "use-team-reroll").add("actionId", teamReroll.get("id"));
            long now = System.currentTimeMillis();
            baseline.apply(role, reroll, now);
            restored.apply(role, reroll, now);
            assertSameCapture(baseline.compactCapture(), restored.compactCapture());
            assertContinuation(commit(baseline, 0), commit(restored, 0));
            assertViews(baseline, restored);
            assertEquals(new FieldCoordinate(9, 7), game.getFieldModel().getPlayerCoordinate(mover));
            assertEquals(0, restoredGame.getTurnData().getReRollShowStarOneDrive());
            assertEquals(teamRerolls - 1, restoredGame.getTurnData().getReRolls());
        }
    }

    @Test
    void aSuccessfulRerollOfAnOrdinaryRushDoesNotRollTheBallAndChainStep() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 49)) {
            SetupSession baseline = fixture.session;
            advanceToTurn(fixture);
            GameState state = nativeState(baseline);
            Game game = state.getGame();
            Player<?> mover = game.getActingTeam().getPlayers()[1];
            UtilBox.putAllPlayersIntoBox(game);
            place(game, mover, 7, 7);
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(4, 4));
            game.getFieldModel().setBallMoving(false);
            String role = actorView(baseline).getString("actor", null);
            JsonObject select = action(view(baseline, role), "select", mover.getId());
            baseline.apply(role, request(baseline, "action", "select-rusher").add("actionId", select.get("id")));
            commit(baseline, 0);

            game.getActingPlayer().setCurrentMove(mover.getMovementWithModifiers());
            forceRolls(state, 1, 6, 1);
            baseline.apply(role, movement(baseline, "rush", mover.getId(), 8, 7));
            String paused = commit(baseline, 0);
            assertEquals(StepId.GO_FOR_IT, state.getCurrentStep().getId());
            SetupSession restored = restoreAndCompare(fixture, baseline, paused);

            JsonObject rerollAction = action(view(baseline, role), "reroll", null);
            JsonObject reroll = request(baseline, "action", "reroll-rush").add("actionId", rerollAction.get("id"));
            long now = System.currentTimeMillis();
            baseline.apply(role, reroll, now);
            restored.apply(role, reroll, now);
            assertSameCapture(baseline.compactCapture(), restored.compactCapture());
            String expected = commit(baseline, 0), actual = commit(restored, 0);
            assertContinuation(expected, actual);
            assertViews(baseline, restored);
            assertEquals(new JsonArray().add(1), JsonObject.readFrom(actual).get("testRolls").asObject().get("General"));
            assertEquals(new FieldCoordinate(8, 7), game.getFieldModel().getPlayerCoordinate(mover));
        }
    }

    @Test
    void restoresAnOrderedChainPushBeforeSelectingTheSecondDestination() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 50)) {
            SetupSession baseline = fixture.session;
            advanceToTurn(fixture);
            GameState state = nativeState(baseline);
            Game game = state.getGame();
            Player<?> attacker = game.getActingTeam().getPlayers()[1];
            Player<?>[] opposition = game.getOtherTeam(game.getActingTeam()).getPlayers();
            UtilBox.putAllPlayersIntoBox(game);
            place(game, attacker, 10, 7);
            place(game, opposition[0], 11, 7);
            place(game, opposition[1], 12, 6);
            place(game, opposition[2], 12, 7);
            place(game, opposition[3], 12, 8);
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(4, 4));
            game.getFieldModel().setBallMoving(false);
            BlockDiceCategory die = new BlockDiceCategory();
            die.parseCommand("push", game, game.getActingTeam());
            state.getDiceRoller().addTestRoll(die);

            String role = actorView(baseline).getString("actor", null);
            JsonObject select = action(view(baseline, role), "selectBlock", attacker.getId());
            baseline.apply(role, request(baseline, "action", "select-block").add("actionId", select.get("id")));
            commit(baseline, 0);
            for (String kind : List.of("block", "blockDie")) {
                JsonObject offered = offeredActions(view(baseline, role), kind).findFirst().orElseThrow();
                baseline.apply(role, request(baseline, "action", kind).add("actionId", offered.get("id")));
                commit(baseline, 0);
            }
            JsonObject firstPush = pushAt(view(baseline, role), 12, 7);
            baseline.apply(role, request(baseline, "action", "first-push").add("actionId", firstPush.get("id")));
            String betweenPushes = commit(baseline, 0);
            assertEquals(StepId.PUSHBACK, state.getCurrentStep().getId());
            SetupSession restored = restoreAndCompare(fixture, baseline, betweenPushes);

            JsonObject secondPush = pushAt(view(baseline, role), 13, 7);
            JsonObject request = request(baseline, "action", "second-push").add("actionId", secondPush.get("id"));
            long now = System.currentTimeMillis();
            baseline.apply(role, request, now);
            restored.apply(role, request, now);
            assertSameCapture(baseline.compactCapture(), restored.compactCapture());
            assertContinuation(commit(baseline, 0), commit(restored, 0));
            assertViews(baseline, restored);
            Game restoredGame = nativeState(restored).getGame();
            assertEquals(new FieldCoordinate(12, 7), restoredGame.getFieldModel().getPlayerCoordinate(
                restoredGame.getPlayerById(opposition[0].getId())));
            assertEquals(new FieldCoordinate(13, 7), restoredGame.getFieldModel().getPlayerCoordinate(
                restoredGame.getPlayerById(opposition[2].getId())));
        }
    }

    private JsonObject pushAt(JsonObject view, int x, int y) {
        return offeredActions(view, "push").filter(choice -> {
            JsonObject target = choice.get("target").asObject();
            return target.getInt("x", -1) == x && target.getInt("y", -1) == y;
        }).findFirst().orElseThrow();
    }

    @Test
    void steadyFootingRestoresAFailedRushAndADeferredBlockKnockdown() throws Exception {
        for (boolean block : List.of(false, true)) {
            try (BogrukEngineFixture fixture = fixture("Human", "Orc", 43)) {
                SetupSession baseline = fixture.session;
                advanceToTurn(fixture);
                GameState state = nativeState(baseline);
                Game game = state.getGame();
                Player<?> player = game.getActingTeam().getPlayers()[1];
                Player<?> defender = game.getOtherTeam(game.getActingTeam()).getPlayers()[1];
                UtilBox.putAllPlayersIntoBox(game);
                place(game, player, 10, 7);
                if (block) place(game, defender, 11, 7);
                game.getFieldModel().setBallCoordinate(new FieldCoordinate(4, 4));
                game.getFieldModel().setBallMoving(false);
                grant(game, player, "Steady Footing");
                String role = actorView(baseline).getString("actor", null);

                JsonObject select = action(view(baseline, role), block ? "selectBlock" : "select", player.getId());
                baseline.apply(role, request(baseline, "action", "select-player").add("actionId", select.get("id")));
                commit(baseline, 0);
                if (block) {
                    BlockDiceCategory die = new BlockDiceCategory();
                    die.parseCommand("skull", game, game.getActingTeam());
                    state.getDiceRoller().addTestRoll(die);
                    forceRolls(state, 2, 2, 2, 2);
                    applyChoice(baseline, role, "block", "");
                    commit(baseline, 0);
                    applyChoice(baseline, role, "blockDie", "");
                } else {
                    game.getActingPlayer().setCurrentMove(player.getMovementWithModifiers());
                    forceRolls(state, 1, 2, 2, 2, 2);
                    baseline.apply(role, movement(baseline, "falling-rush", player.getId(), 11, 7));
                    commit(baseline, 0);
                    applyChoice(baseline, role, "reroll", ":none");
                }
                String pending = commit(baseline, 0);
                assertEquals(StepId.STEADY_FOOTING, state.getCurrentStep().getId());
                JsonObject step = JsonObject.readFrom(pending).get("native").asObject().get("currentStep").asObject();
                JsonObject context = step.get("steadyFootingContext").asObject();
                if (block) {
                    JsonObject deferredDrop = context.get("deferredCommands").asArray().values().stream()
                        .map(JsonValue::asObject).filter(command -> player.getId().equals(command.getString("playerId", "")))
                        .findFirst().orElseThrow();
                    JsonObject oldContext = JsonObject.readFrom(context.toString()).set("deferredCommands",
                        new JsonArray().add(deferredDrop.get("deferredCommandId")));
                    SteadyFootingContext recovered = new SteadyFootingContext().initFrom(game.getRules(), oldContext);
                    JsonObject recoveredDrop = recovered.getDeferredCommands().get(0).toJsonValue();
                    for (JsonObject.Member field : deferredDrop) {
                        assertEquals(field.getValue(), recoveredDrop.get(field.getName()), field.getName());
                    }
                    assertEquals(oldContext.get("deferredCommands"), recovered.toJsonValue().get("deferredCommands"));
                } else assertNotNull(context.get("injuryType"));

                SetupSession restored = restoreAndCompare(fixture, baseline, pending);
                JsonObject decline = offeredActions(view(baseline, role), "skill")
                    .filter(option -> option.getString("id", "").endsWith(":false")).findFirst().orElseThrow();
                JsonObject input = request(baseline, "action", "decline-steady-footing").add("actionId", decline.get("id"));
                compareDecision(baseline, restored, role, input, 0);
                Game continued = nativeState(restored).getGame();
                assertEquals(PlayerState.PRONE, continued.getFieldModel().getPlayerState(continued.getPlayerById(player.getId())).getBase());
            }
        }
    }

    @Test
    void preservesTheMarkedJumpOriginAcrossABoneHeadReroll() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 44)) {
            SetupSession baseline = fixture.session;
            String role = advanceToTurn(fixture).getString("actor", null);
            GameState state = nativeState(baseline);
            Game game = state.getGame();
            Player<?> mover = game.getActingTeam().getPlayers()[1];
            Player<?>[] opponents = game.getOtherTeam(game.getActingTeam()).getPlayers();
            UtilBox.putAllPlayersIntoBox(game);
            place(game, mover, 7, 7);
            place(game, opponents[0], 6, 6);
            place(game, opponents[1], 6, 7);
            place(game, opponents[2], 8, 7);
            game.getFieldModel().setPlayerState(mover, new PlayerState(PlayerState.PRONE).changeActive(true));
            game.getFieldModel().setPlayerState(opponents[2], new PlayerState(PlayerState.PRONE));
            grant(game, mover, "Bone Head");
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(4, 4));
            game.getFieldModel().setBallMoving(false);
            game.getFieldModel().setBallInPlay(true);

            JsonObject select = action(view(baseline, role), "declareHandOff", mover.getId());
            baseline.apply(role, request(baseline, "action", "select-prone-mover").add("actionId", select.get("id")));
            commit(baseline, 0);
            applyChoice(baseline, role, "jumpMode", "");
            commit(baseline, 0);
            forceRolls(state, 1);
            JsonObject jump = offeredActions(view(baseline, role), "jump").filter(choice -> {
                JsonObject target = choice.get("target").asObject();
                return target.getInt("x", 0) == 9 && target.getInt("y", 0) == 7;
            }).findFirst().orElseThrow();
            baseline.apply(role, request(baseline, "action", "jump").add("actionId", jump.get("id")));
            String pending = commit(baseline, 0);
            assertEquals(StepId.BONE_HEAD, state.getCurrentStep().getId());
            JsonArray steps = JsonObject.readFrom(pending).get("native").asObject().get("stepStack").asObject().get("steps").asArray();
            assertTrue(steps.values().stream().map(JsonValue::asObject)
                .anyMatch(step -> StepId.END_SELECTING.getName().equals(step.getString("stepId", ""))
                    && new JsonArray().add(7).add(7).equals(step.get("moveStart"))));
            forceRolls(state, 6, 3);
            pending = commit(baseline, 0);
            SetupSession restored = restoreAndCompare(fixture, baseline, pending);
            JsonObject reroll = offeredActions(view(baseline, role), "reroll")
                .filter(choice -> choice.getString("id", "").contains("team")).findFirst().orElseThrow();
            compareDecision(baseline, restored, role, request(baseline, "action", "reroll-bone-head").add("actionId", reroll.get("id")), 0);
            assertEquals(StepId.JUMP, nativeState(restored).getCurrentStep().getId());
        }
    }

    @Test
    void opposingReactionTimesOutAndContinuesAfterRestore() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 43)) {
            SetupSession baseline = fixture.session;
            String role = advanceToTurn(fixture).getString("actor", null);
            GameState state = nativeState(baseline);
            Game game = state.getGame();
            Player<?> blocker = game.getActingTeam().getPlayers()[1];
            Player<?> defender = game.getOtherTeam(game.getActingTeam()).getPlayers()[1];
            UtilBox.putAllPlayersIntoBox(game);
            place(game, blocker, 9, 7);
            place(game, defender, 10, 7);
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(4, 4));
            game.getFieldModel().setBallMoving(false);
            grant(game, defender, "Stand Firm");
            JsonObject select = action(view(baseline, role), "selectBlock", blocker.getId());
            baseline.apply(role, request(baseline, "action", "select-blocker").add("actionId", select.get("id")));
            commit(baseline, 0);
            BlockDiceCategory die = new BlockDiceCategory();
            die.parseCommand("pushback", game, game.getActingTeam());
            state.getDiceRoller().addTestRoll(die);
            applyChoice(baseline, role, "block", "");
            commit(baseline, 0);
            applyChoice(baseline, role, "blockDie", "");
            String pending = commit(baseline, 0);

            JsonObject reaction = baseline.decorateSaveResumeState(actorView(baseline)).get("reaction").asObject();
            assertNotEquals(role, reaction.getString("actor", null));
            assertTrue(reaction.getString("title", "").contains("Stand Firm"));
            SetupSession restored = restoreAndCompare(fixture, baseline, pending);
            long deadline = reaction.getLong("expiresAt", -1);
            MatchService.Failure stale = assertThrows(MatchService.Failure.class,
                () -> restored.apply(role, request(restored, "action", "late-action"), deadline));
            assertEquals("STALE_REVISION", stale.code);
            assertNull(restored.compactCapture());
            assertTrue(baseline.expireReactiveDecision(deadline));
            assertTrue(restored.expireReactiveDecision(deadline));
            assertSameCapture(baseline.compactCapture(), restored.compactCapture());
            assertContinuation(commit(baseline, 0), commit(restored, 0));
            assertViews(baseline, restored);

            JsonObject view = actorView(baseline);
            JsonObject next = fixture.recordedDecision(view, "after-timeout", 1);
            compareDecision(baseline, restored, view.getString("actor", null), next, 0);
        }
    }

    @Test
    void restoresCleanupBeforeResolvingASecretWeaponDecision() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 43)) {
            SetupSession baseline = fixture.session;
            String role = advanceToTurn(fixture).getString("actor", null);
            Game game = nativeState(baseline).getGame();
            Player<?> weapon = game.getActingTeam().getPlayers()[1];
            game.getOptions().addOption(new GameOptionBoolean(GameOptionId.ARGUE_THE_CALL).setValue(true));
            game.getGameResult().getPlayerResult(weapon).setHasUsedSecretWeapon(true);
            game.getTurnDataHome().setTurnNr(8);
            game.getTurnDataAway().setTurnNr(8);
            applyChoice(baseline, role, "endTurn", "");
            String pending = commit(baseline, 0);
            assertEquals(StepId.END_TURN, nativeState(baseline).getCurrentStep().getId());
            SetupSession restored = restoreAndCompare(fixture, baseline, pending);
            JsonObject cleanup = actorView(baseline);
            JsonObject next = offeredActions(cleanup, "secretWeapon").findFirst()
                .orElseGet(() -> offeredActions(cleanup, "argueTheCall").findFirst().orElseThrow());
            compareDecision(baseline, restored, cleanup.getString("actor", null),
                request(baseline, "action", "resolve-cleanup").add("actionId", next.get("id")), 0);
        }
    }

    @Test
    void driveTrackerReadsPendingNativeReportsWithoutConsumingThem() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 43)) {
            SetupSession session = fixture.session;
            commit(session, 0);
            for (int sequence = 0; sequence < 2; sequence++) {
                JsonObject current = actorView(session);
                session.apply(current.getString("actor", null), fixture.recordedDecision(current, "setup-" + sequence, sequence));
                commit(session, 0);
            }
            JsonObject base = actorView(session);
            assertEquals("SETUP", base.getString("phase", ""));
            assertFalse(base.get("driveTracker").asObject().getBoolean("secretWeapons", true));
            GameState state = nativeState(session);
            ReportSecretWeaponBan report = new ReportSecretWeaponBan();
            report.add(state.getGame().getTeamHome().getPlayers()[0].getId(), 1, true);
            ReportList reports = new ReportList();
            reports.add(report);
            fixture.server.getCommunication().sendModelSync(state, state.fetchChanges(), reports, null, null, 0, 0);
            int pending = state.getGameLog().size();

            for (String coach : List.of("home", "away"))
                assertTrue(view(session, coach).get("driveTracker").asObject().getBoolean("secretWeapons", false));
            assertEquals(pending, state.getGameLog().size());
            assertNull(session.compactCapture());
            applyChoice(session, base.getString("actor", null), "confirm", "");
            assertTrue(session.compactCapture().nativeOutcomes().contains("secretWeaponBan"));
            String captured = commit(session, 0);
            assertTrue(actorView(SetupSession.restoreCompactSnapshot(fixture.server, captured))
                .get("driveTracker").asObject().getBoolean("secretWeapons", false));
        }
    }

    private void grant(Game game, Player<?> player, String name) {
        SkillFactory skills = game.getFactory(FactoryType.Factory.SKILL);
        ((RosterPlayer) player).addSkill(skills.forName(name));
    }

    @Test
    void restoresDeferredHalftimeFromCompactSnapshotAndCapturesItOnce() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 47)) {
            SetupSession session = fixture.session;
            String role = advanceToTurn(fixture).getString("actor", null);
            Game game = nativeState(session).getGame();
            game.getTurnDataHome().setTurnNr(8);
            game.getTurnDataAway().setTurnNr(8);
            Player<?> scorer = game.getActingTeam().getPlayers()[0];
            UtilBox.putAllPlayersIntoBox(game);
            int endZone = game.isHomePlaying() ? 25 : 0;
            place(game, scorer, endZone, 7);
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(endZone, 7));
            game.getFieldModel().setBallInPlay(true);
            game.getFieldModel().setBallMoving(false);
            grant(game, scorer, "Star of the Show");

            applyChoice(session, role, "endTurn", "");
            assertEquals(List.of("touchdown"), announcementKinds(session.compactCapture()));
            String paused = commit(session, 0);
            SetupSession restored = restoreAndCompare(fixture, session, paused);
            JsonObject decline = offeredActions(view(restored, role), "skill")
                .filter(option -> option.getString("id", "").endsWith(":false")).findFirst().orElseThrow();
            JsonObject request = request(restored, "action", "decline-star-at-halftime").add("actionId", decline.get("id"));
            restored.apply(role, request);
            assertEquals(StepId.SETUP, nativeState(restored).getCurrentStep().getId());
            assertEquals(List.of("halfTime"), announcementKinds(restored.compactCapture()));

            String accepted = commit(restored, 0);
            SetupSession afterAcceptance = SetupSession.restoreCompactSnapshot(fixture.server, accepted);
            MatchService.Failure stale = assertThrows(MatchService.Failure.class, () -> afterAcceptance.apply(role, request));
            assertEquals("STALE_REVISION", stale.code);
            assertNull(afterAcceptance.compactCapture());
            assertEquals(accepted, commit(afterAcceptance, 0));

            JsonObject oldSnapshot = JsonObject.readFrom(paused);
            // Older writers already placed halftime in history, outside the snapshot.
            oldSnapshot.get("matchAnnouncements").asObject().remove("pendingHalfTime");
            SetupSession olderSave = SetupSession.restoreCompactSnapshot(fixture.server, oldSnapshot.toString());
            olderSave.apply(role, request);
            assertEquals(StepId.SETUP, nativeState(olderSave).getCurrentStep().getId());
            assertTrue(announcementKinds(olderSave.compactCapture()).isEmpty());
        }
    }

    private List<String> announcementKinds(CompactMatchWrite.Capture capture) {
        return RecordedMatchAnnouncements.events(JsonArray.readFrom(capture.nativeOutcomes())).stream()
            .map(event -> event.getString("kind", null)).toList();
    }

    private void applyChoice(SetupSession session, String role, String kind, String suffix) {
        JsonObject current = view(session, role);
        if ("confirm".equals(kind)) {
            session.apply(role, request(session, kind, kind + "-" + session.revision()));
            return;
        }
        JsonObject choice = offeredActions(current, kind)
            .filter(option -> option.getString("id", "").endsWith(suffix)).findFirst().orElseThrow();
        session.apply(role, request(session, "action", kind + "-" + session.revision()).add("actionId", choice.get("id")));
    }

    private void compareDecision(SetupSession baseline, SetupSession restored, String role, JsonObject input, int chatCount)
        throws Exception {
        long now = System.currentTimeMillis();
        baseline.apply(role, input, now);
        restored.apply(role, input, now);
        assertSameCapture(baseline.compactCapture(), restored.compactCapture());
        assertContinuation(commit(baseline, chatCount), commit(restored, chatCount));
        assertViews(baseline, restored);
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

    private static Stream<JsonObject> offeredActions(JsonObject view, String kind) {
        return view.get("actions").asArray().values().stream().map(JsonValue::asObject)
            .filter(action -> kind.equals(action.getString("kind", null)));
    }

    private static JsonObject action(JsonObject view, String kind, String player) {
        return offeredActions(view, kind)
            .filter(action -> player == null ? action.getString("id", "").endsWith(":team")
                : player.equals(action.getString("sourcePlayerId", null)))
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
    void resumesDuringPlayAndConsumesTheSameNextRushRoll() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 43)) {
            SetupSession baseline = fixture.session;
            String role = advanceToTurn(fixture).getString("actor", null);
            GameState engine = nativeState(baseline);
            Game game = engine.getGame();
            Player<?> mover = game.getActingTeam().getPlayers()[1];
            UtilBox.putAllPlayersIntoBox(game);
            place(game, mover, 7, 7);
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(4, 4));
            game.getFieldModel().setBallMoving(false);
            JsonObject select = action(view(baseline, role), "select", mover.getId());
            baseline.apply(role, request(baseline, "action", "select-rusher").add("actionId", select.get("id")));
            commit(baseline, 0);
            game.getActingPlayer().setCurrentMove(mover.getMovementWithModifiers());
            forceRolls(engine, 6);
            long now = System.currentTimeMillis();

            JsonObject save = request(baseline, "saveRequest", "save-in-play");
            JsonValue saveId = proposalId(baseline, "home", save, now);
            baseline.saveResume("away", request(baseline, "saveAccept", "accept-save").add("proposalId", saveId), now + 1);
            SetupSession restored = restoreAndCompare(fixture, baseline, commit(baseline, 0));

            JsonObject resume = request(baseline, "resumeRequest", "resume-play");
            JsonValue resumeId = proposalId(baseline, "home", resume, now + 2);
            assertEquals(resumeId, proposalId(restored, "home", resume, now + 2));
            JsonObject accept = request(baseline, "resumeAccept", "accept-resume").add("proposalId", resumeId);
            baseline.saveResume("away", accept, now + 3);
            restored.saveResume("away", accept, now + 3);
            assertEquals(commit(baseline, 0), commit(restored, 0));

            compareDecision(baseline, restored, role, movement(baseline, "rush-after-resume", mover.getId(), 8, 7), 0);
            assertEquals(new FieldCoordinate(8, 7), game.getFieldModel().getPlayerCoordinate(mover));
            assertTrue(engine.getDiceRoller().getTestRolls().get("General").isEmpty());
        }
    }

    private JsonValue proposalId(SetupSession session, String role, JsonObject request, long now) {
        return session.decorateSaveResume(session.saveResume(role, request, now))
            .get("state").asObject().get("saveResume").asObject().get("proposalId");
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
    void invalidWriteBoundaryEvictsAndReentrantWriteLeavesTheOuterWriterInCharge() throws Exception {
        try (BogrukEngineFixture fixture = fixture("Human", "Orc", 47)) {
            SetupSession session = fixture.session;
            var boundary = boundary(session, 0);
            assertEquals(CompactMatchWrite.Result.DURABLE, session.writeCompactSnapshot(boundary, candidate -> {
                assertThrows(IllegalStateException.class, () -> session.writeCompactSnapshot(boundary,
                    ignored -> fail("Reentrant writer ran"), () -> fail("Reentrant write released the session")));
                return CompactMatchWrite.Result.DURABLE;
            }, () -> fail("Durable writer released the session")));

            var wrong = new CompactMatchWrite.History(2, 0, boundary.digest());
            AtomicReference<SetupSession> resident = new AtomicReference<>(session);
            assertThrows(IllegalStateException.class, () -> session.writeCompactSnapshot(wrong,
                candidate -> fail("Invalid writer ran"), () -> resident.set(null)));
            assertNull(resident.get());
            assertThrows(IllegalStateException.class, session::browseSummary);
            assertThrows(IllegalStateException.class, session::computerProjection);
            assertThrows(IllegalStateException.class, () -> session.expireSaveResumeProposal(0));
            assertThrows(IllegalStateException.class, () -> session.recordPlayerActivity("home", 0));
            assertThrows(IllegalStateException.class, () -> session.routePreview("home", 0, new JsonArray()));
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
            JsonObject missingMatch = JsonObject.readFrom(snapshot).remove("matchId");
            JsonObject missingFrozen = JsonObject.readFrom(snapshot).remove("frozen");
            for (JsonObject invalid : List.of(withLog, withEvents, badCommand, badCount, missingMatch, missingFrozen)) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> SetupSession.restoreCompactSnapshot(fixture.server, invalid.toString()));
                assertEquals("Invalid compact match snapshot", error.getMessage());
            }
        }
    }

    private BogrukEngineFixture fixture(String home, String away, long seed) throws Exception {
        BogrukEngineFixture fixture = new BogrukEngineFixture(home, away, seed, true, BogrukEngineFixture.Mode.MEASURED);
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
        }, () -> { });
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
        normalizeSets(a);
        normalizeSets(b);
        assertEquals(a, b, () -> "native outcomes at r" + expected.revision());
    }

    private void normalizeSets(JsonArray syncs) {
        // Keep command, report and change order exact except for consecutive removals from native sets.
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
            JsonValue reportList = value.asObject().get("reportList");
            if (reportList == null || reportList.isNull()) continue;
            for (JsonValue entry : reportList.asObject().get("reports").asArray()) {
                JsonObject report = entry.asObject();
                if (!"injury".equals(report.getString("reportId", ""))) continue;
                JsonValue modifiers = report.get("injuryModifiers");
                if (modifiers == null) continue;
                // The injury report copies this list from a native HashSet.
                JsonArray sorted = new JsonArray();
                modifiers.asArray().values().stream().sorted(Comparator.comparing(JsonValue::asString)).forEach(sorted::add);
                report.set("injuryModifiers", sorted);
            }
        }
    }

    private void assertContinuation(String expected, String actual) {
        JsonObject a = JsonObject.readFrom(expected), b = JsonObject.readFrom(actual);
        a.get("historyBoundary").asObject().remove("digest");
        b.get("historyBoundary").asObject().remove("digest");
        a.remove("lastRecordTime");
        b.remove("lastRecordTime");
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
            if ("gameSetFinished".equals(object.getString("modelChangeId", "")) && object.get("modelChangeValue").isString())
                object.set("modelChangeValue", "<wall clock>");
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
