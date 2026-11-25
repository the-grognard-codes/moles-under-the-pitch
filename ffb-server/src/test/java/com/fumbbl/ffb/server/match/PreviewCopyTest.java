package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.FieldCoordinate;
import com.fumbbl.ffb.PlayerAction;
import com.fumbbl.ffb.PlayerState;
import com.fumbbl.ffb.model.BlockKind;
import com.fumbbl.ffb.model.BlockTarget;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.Player;
import com.fumbbl.ffb.net.commands.ClientCommandSynchronousMultiBlock;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.match.CoreTurnActions.Action;
import com.fumbbl.ffb.util.UtilBox;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class PreviewCopyTest {
    @Test void sixPreviewResultsMatchTheFullLogBaseline() throws Exception {
        SetupSessionMovementTest.Fixture fixture = new SetupSessionMovementTest.Fixture(1, true);
        GameState state = engine(fixture.session);
        seedDice(state);
        JsonObject ready = fixture.advanceToTurn();
        String role = ready.getString("actor", null);
        int revision = ready.getInt("revision", -1);
        Game game = state.getGame();
        assertEquals(22, game.getPlayers().length);
        assertTrue(state.getGameLog().size() > 0);
        Player<?>[] own = game.getActingTeam().getPlayers();
        Player<?>[] other = game.getOtherTeam(game.getActingTeam()).getPlayers();
        Player<?> attacker = own[0], prone = own[1], defender = other[0], second = other[1];
        UtilBox.putAllPlayersIntoBox(game);
        place(game, attacker, 7, 7, PlayerState.STANDING);
        place(game, prone, 9, 7, PlayerState.PRONE);
        place(game, defender, 8, 7, PlayerState.STANDING);
        place(game, second, 7, 6, PlayerState.STANDING);
        game.getActingPlayer().setPlayer(attacker);
        game.getActingPlayer().setPlayerAction(PlayerAction.MULTIPLE_BLOCK);
        BlockPreviewPresentation blocks = new BlockPreviewPresentation(state);
        Action block = new Action("block", "selectBlock", "Block", role, null, attacker.getId());
        Action jump = new Action("jump", "jumpUpBlock", "Jump Up block", role,
            new JumpUpBlockDeclaration(prone.getId(), defender.getId()));
        Action multiple = new Action("multiple", "multiBlock", "Multiple Block", role,
            new ClientCommandSynchronousMultiBlock(List.of(target(game, defender), target(game, second))));
        String before = state.toJsonValue(true, 0).toString();
        JsonObject ordinary = blocks.project(List.of(block), revision);
        JsonObject jumpUp = blocks.project(List.of(jump), revision);
        JsonObject multi = blocks.project(List.of(multiple), revision);
        JsonObject blitz = blocks.blitzPreview(attacker.getId(), defender.getId(), new FieldCoordinate(8, 8), false, true);
        assertEquals(2, ordinary.get("rolls").asArray().size());
        assertEquals(1, jumpUp.get("rolls").asArray().size());
        assertEquals(2, multi.get("multiple").asArray().get(0).asObject().get("context").asObject().get("blocks").asArray().size());
        assertTrue(blitz.get("highlight").isObject());
        assertEquals(before, state.toJsonValue(true, 0).toString());

        game.getActingPlayer().setPlayer(null);
        game.getActingPlayer().setPlayerAction(null);
        place(game, own[2], 10, 7, PlayerState.STANDING);
        place(game, own[3], 14, 8, PlayerState.STANDING);
        game.getTurnData().setPassUsed(false);
        game.getTurnData().setHandOverUsed(false);
        game.getFieldModel().setBallInPlay(true);
        game.getFieldModel().setBallMoving(false);
        game.getFieldModel().setBallCoordinate(new FieldCoordinate(7, 7));
        place(game, defender, 18, 7, PlayerState.STANDING);
        place(game, second, 18, 9, PlayerState.STANDING);
        JsonArray route = new JsonArray().add(new JsonObject().add("x", 8).add("y", 8))
            .add(new JsonObject().add("x", 9).add("y", 8));
        before = state.toJsonValue(true, 0).toString();
        JsonObject pass = fixture.session.movementPreview(role, revision, attacker.getId(), "pass", own[3].getId(), route);
        JsonObject handOff = fixture.session.movementPreview(role, revision, attacker.getId(), "handOff", own[2].getId(), route);
        assertEquals(before, state.toJsonValue(true, 0).toString());

        JsonObject previews = new JsonObject().add("block", ordinary).add("jumpUp", jumpUp).add("multiple", multi)
            .add("blitz", blitz).add("pass", pass).add("handOff", handOff);
        // Full preview payloads captured with the original log-including copies at 0277e63b3.
        assertEquals("095499242d692dd5141c5ed8c6bc6954535f20e3548733db5ede1085c6a752f6",
            new JsonDigest().sha256(previews), previews::toString);

        GameState transferSource = spy(state);
        new PlannedBallTransfer().validate(transferSource, attacker.getId(), own[3].getId(), "pass", role,
            pass.get("route").asObject());
        verify(transferSource).toJsonValue(false, 0);
    }

    @Test void detachedCopyHasNoCommandsAndPreservesTheLiveLog() throws Exception {
        SetupSessionMovementTest.Fixture fixture = new SetupSessionMovementTest.Fixture(1, true);
        GameState live = engine(fixture.session);
        seedDice(live);
        fixture.advanceToTurn();
        int size = live.getGameLog().size();
        long epoch = live.getGameLog().recoveryEpoch();
        assertTrue(size > 0);

        GameState copy = (GameState) blockCopy().invoke(new BlockPreviewPresentation(live));
        assertEquals(0, copy.getGameLog().size());
        assertEquals(size, live.getGameLog().size());
        assertEquals(epoch, live.getGameLog().recoveryEpoch());
    }

    @Test
    @EnabledIfSystemProperty(named = "preview.copy.probe", matches = "true")
    void measureCopyAtRevision50AndBeforeFullTime() throws Exception {
        SetupSessionMovementTest.Fixture fixture = new SetupSessionMovementTest.Fixture(1, true);
        GameState live = engine(fixture.session);
        seedDice(live);
        JsonObject view = fixture.advanceToTurn();
        JsonObject early = null, late = null;
        int earlyRevision = -1, lateRevision = -1;
        for (int sequence = 0; sequence < 1_600 && !fixture.session.isComplete(); sequence++) {
            int revision = fixture.session.revision();
            if (early == null && revision >= 50) {
                early = live.toJsonValue(true, 0);
                earlyRevision = revision;
            }
            if (view.getInt("half", 0) == 2 && view.getInt("homeTurn", 0) >= 7 && view.getInt("awayTurn", 0) >= 7) {
                late = live.toJsonValue(true, 0);
                lateRevision = revision;
            }
            view = advance(fixture, view, sequence);
        }
        assertTrue(fixture.session.isComplete(), "The probe must reach full time");
        assertNotNull(early);
        assertEquals(fixture.session.revision() - 1, lateRevision);
        measure(restore(live, early), earlyRevision);
        measure(restore(live, late), lateRevision);
    }

    private JsonObject advance(SetupSessionMovementTest.Fixture fixture, JsonObject view, int sequence) {
        JsonObject request = fixture.request("action", "probe-" + sequence, view);
        if ("SETUP".equals(view.getString("phase", null))) {
            request.set("operation", "confirm");
        } else if (!view.get("prompt").isNull()) {
            JsonObject prompt = view.get("prompt").asObject();
            request.set("operation", "choice").add("promptId", prompt.get("id"))
                .add("optionId", prompt.get("options").asArray().get(0));
        } else {
            JsonObject action = view.get("actions").asArray().get(0).asObject();
            for (JsonValue value : view.get("actions").asArray()) {
                JsonObject candidate = value.asObject();
                String kind = candidate.getString("kind", "");
                String id = candidate.getString("id", "");
                if (id.endsWith(":decline-event") || id.endsWith(":end-event") || id.endsWith(":confirm-solid-defence")
                    || ("endTurn".equals(kind) && sequence % 24 == 0)) {
                    action = candidate;
                    break;
                }
                if ("select".equals(kind) && !view.get("activePlayerId").equals(candidate.get("target").asObject().get("playerId")))
                    action = candidate;
            }
            request.add("actionId", action.get("id"));
        }
        JsonObject reply = fixture.session.apply(view.getString("actor", null), request);
        assertEquals("ACCEPTED", reply.getString("code", null), request::toString);
        return reply.get("state").asObject();
    }

    private void measure(GameState live, int revision) throws Exception {
        int commands = live.getGameLog().size();
        assertTrue(commands > 0);
        assertFalse(live.getGame().getTurnMode().name().equals("END_GAME"));
        BlockPreviewPresentation preview = new BlockPreviewPresentation(live);
        Method method = blockCopy();
        ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        int samples = 120;
        for (int i = 0; i < samples; i++) method.invoke(preview);
        long[] nanos = new long[samples];
        long[] bytes = new long[samples];
        for (int i = 0; i < samples; i++) {
            long allocated = allocations.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            GameState copy = (GameState) method.invoke(preview);
            nanos[i] = System.nanoTime() - started;
            bytes[i] = allocations.getThreadAllocatedBytes(thread) - allocated;
            assertEquals(22, copy.getGame().getPlayers().length);
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        System.out.printf("PREVIEW_COPY revision=%d commands=%d medianNanos=%d medianBytes=%d p95Nanos=%d%n",
            revision, commands, nanos[samples / 2], bytes[samples / 2], nanos[samples * 95 / 100 - 1]);
    }

    private GameState restore(GameState live, JsonObject snapshot) {
        GameState restored = new GameState(live.getServer());
        restored.initFrom(live.getServer().getFactorySource(), snapshot);
        return restored;
    }

    private GameState engine(SetupSession session) throws Exception {
        Field field = SetupSession.class.getDeclaredField("state");
        field.setAccessible(true);
        return (GameState) field.get(session);
    }

    private Method blockCopy() throws Exception {
        Method method = BlockPreviewPresentation.class.getDeclaredMethod("copy");
        method.setAccessible(true);
        return method;
    }

    private void seedDice(GameState state) {
        Random dice = new Random(652);
        state.getDiceRoller().setRecoveryRoll(sides -> dice.nextInt(sides) + 1);
    }

    private static void place(Game game, Player<?> player, int x, int y, int status) {
        game.getFieldModel().setPlayerCoordinate(player, new FieldCoordinate(x, y));
        game.getFieldModel().setPlayerState(player, new PlayerState(status).changeActive(true));
    }

    private BlockTarget target(Game game, Player<?> player) {
        return new BlockTarget(player.getId(), BlockKind.BLOCK, game.getFieldModel().getPlayerState(player));
    }
}
