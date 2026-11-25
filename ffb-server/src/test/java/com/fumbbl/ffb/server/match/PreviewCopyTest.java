package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.FieldCoordinate;
import com.fumbbl.ffb.FieldCoordinateBounds;
import com.fumbbl.ffb.PlayerAction;
import com.fumbbl.ffb.PlayerState;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.Player;
import com.fumbbl.ffb.model.RosterPlayer;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.match.CoreTurnActions.Action;
import com.fumbbl.ffb.server.util.ServerUtilBlock;
import com.fumbbl.ffb.util.UtilBox;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class PreviewCopyTest {
    @Test void eachBlockPreviewMatchesItsFullLogCopy() throws Exception {
        Pitch pitch = new Pitch();
        GameState state = pitch.fixture.state;
        Game game = state.getGame();
        GameState legacy = fullLogCopiesFrom(state);
        int revision = pitch.fixture.match.session.revision();
        String role = pitch.role();

        List<Action> actions = new CoreTurnActions(state).actions();
        Action ordinary = findAction(actions, "selectBlock", pitch.carrier().getId());
        Action jumpUp = findAction(actions, "jumpUpBlock", pitch.other[0].getId());
        JsonObject before = state.toJsonValue(true, 0);
        JsonObject block = compare("block", legacy, state, view -> view.project(List.of(ordinary), revision));
        assertEquals(2, block.get("rolls").asArray().size());
        JsonObject againstDefender = block.get("rolls").asArray().values().stream().map(JsonValue::asObject)
            .filter(roll -> pitch.other[0].getId().equals(roll.getString("targetPlayerId", null))).findFirst().orElseThrow();
        assertTrue(againstDefender.get("highlight").asObject().get("offensiveAssists").asArray().values().stream()
            .anyMatch(assist -> pitch.own[2].getId().equals(assist.asObject().getString("playerId", null))),
            "Guard must assist despite the adjacent marker");

        JsonObject jump = compare("Jump Up", legacy, state, view -> view.project(List.of(jumpUp), revision));
        assertEquals(1, jump.get("rolls").asArray().size());
        assertEquals(pitch.own[1].getId(), jump.get("rolls").asArray().get(0).asObject().getString("playerId", null));
        JsonObject blitz = compare("blitz", legacy, state, view -> view.blitzPreview(pitch.carrier().getId(),
            pitch.other[0].getId(), new FieldCoordinate(8, 8), false, true));
        assertTrue(blitz.get("highlight").isObject());
        assertEquals(before, state.toJsonValue(true, 0));

        game.getActingPlayer().setPlayer(pitch.carrier());
        game.getActingPlayer().setPlayerAction(PlayerAction.MULTIPLE_BLOCK);
        ServerUtilBlock.updateDiceDecorations(state);
        List<Action> pairs = new ArrayList<>();
        new MultipleBlockActions().targets(game, pitch.carrier(), role, pairs);
        assertEquals(2, pairs.size(), "The two defenders can be blocked in either order");
        legacy = fullLogCopiesFrom(state);
        before = state.toJsonValue(true, 0);
        JsonObject multiple = compare("Multiple Block", legacy, state, view -> view.project(pairs, revision));
        assertEquals(2, multiple.get("multiple").asArray().size());
        assertEquals(before, state.toJsonValue(true, 0));
    }

    @Test void plannedPassAndHandOffValidationMatchTheirFullLogCopies() throws Exception {
        Pitch pitch = new Pitch();
        GameState state = pitch.fixture.state;
        pitch.giveBallToCarrier();
        GameState legacy = fullLogCopiesFrom(state);
        JsonObject before = state.toJsonValue(true, 0);
        JsonObject route = pitch.route();
        Player<?> passTarget = pitch.own[3], handOffTarget = pitch.own[4];
        PlannedBallTransfer transfer = new PlannedBallTransfer();

        for (GameState source : List.of(legacy, state)) {
            assertDoesNotThrow(() -> transfer.validate(source, pitch.carrier().getId(), passTarget.getId(), "pass", pitch.role(), route));
            assertDoesNotThrow(() -> transfer.validate(source, pitch.carrier().getId(), handOffTarget.getId(), "handOff", pitch.role(), route));
            // Possession is valid. The native copy must reject the non-adjacent recipient.
            assertEquals("INVALID_TARGET", assertThrows(MatchService.Failure.class,
                () -> transfer.validate(source, pitch.carrier().getId(), passTarget.getId(), "handOff", pitch.role(), route)).code);
            // A square target is checked against the native pass actions from the planned endpoint.
            assertEquals("INVALID_TARGET", assertThrows(MatchService.Failure.class,
                () -> transfer.validate(source, pitch.carrier().getId(), null, "pass", pitch.role(), route,
                    new FieldCoordinate(25, 14))).code);
        }
        assertEquals(before, state.toJsonValue(true, 0));
    }

    @Test void blockCopyKeepsSkillBehaviourAndLeavesTheLiveLogIntact() throws Exception {
        Pitch pitch = new Pitch();
        GameState live = pitch.fixture.state;
        int size = live.getGameLog().size();
        long epoch = live.getGameLog().recoveryEpoch();

        GameState copy = (GameState) PreviewCopyFixture.blockCopy().invoke(new BlockPreviewPresentation(live));
        assertEquals(0, copy.getGameLog().size());
        assertNotNull(copy.getGame().getRules().getSkillFactory().forName("Jump Up").getSkillBehaviour());
        assertEquals(size, live.getGameLog().size());
        assertEquals(epoch, live.getGameLog().recoveryEpoch());
    }

    @Test void plannedTransferCopiesWithoutTheLogAndLeavesTheLiveLogIntact() throws Exception {
        Pitch pitch = new Pitch();
        GameState live = pitch.fixture.state;
        pitch.giveBallToCarrier();
        int size = live.getGameLog().size();
        long epoch = live.getGameLog().recoveryEpoch();

        GameState source = spy(live);
        List<JsonObject> snapshots = new ArrayList<>();
        doAnswer(call -> {
            JsonObject snapshot = (JsonObject) call.callRealMethod();
            snapshots.add(snapshot);
            return snapshot;
        }).when(source).toJsonValue(false, 0);

        new PlannedBallTransfer().validate(source, pitch.carrier().getId(), pitch.own[3].getId(), "pass", pitch.role(), pitch.route());
        verify(source).toJsonValue(false, 0);
        assertEquals(1, snapshots.size());
        JsonObject snapshot = snapshots.getFirst();
        assertNull(snapshot.get("gameLog"), "The JSON used by the actual transfer copy must omit the log");
        assertEquals(0, pitch.fixture.restore(snapshot).getGameLog().size());
        assertEquals(size, live.getGameLog().size());
        assertEquals(epoch, live.getGameLog().recoveryEpoch());
    }

    private static GameState fullLogCopiesFrom(GameState state) {
        GameState source = spy(state);
        // Restore the parent's serializer argument through the test boundary. The native
        // preview code still performs the copy, registers skills and calculates the result.
        doReturn(state.toJsonValue(true, 0)).when(source).toJsonValue(false, 0);
        return source;
    }

    private static JsonObject compare(String kind, GameState legacy, GameState live,
                                      Function<BlockPreviewPresentation, JsonObject> project) {
        JsonObject expected = project.apply(new BlockPreviewPresentation(legacy));
        assertNotNull(expected, kind);
        JsonObject actual = project.apply(new BlockPreviewPresentation(live));
        assertEquals(expected, actual, kind);
        return actual;
    }

    private static Action findAction(List<Action> actions, String kind, String playerId) {
        return actions.stream().filter(action -> kind.equals(action.kind) && playerId.equals(action.targetPlayerId))
            .findFirst().orElseThrow(() -> new AssertionError("Missing " + kind + " for " + playerId));
    }

    private static final class Pitch {
        final PreviewCopyFixture fixture = new PreviewCopyFixture();
        final Game game = fixture.state.getGame();
        final Player<?>[] own = game.getActingTeam().getPlayers();
        final Player<?>[] other = game.getOtherTeam(game.getActingTeam()).getPlayers();

        Pitch() throws Exception {
            assertTrue(fixture.state.getGameLog().size() > 0);
            game.getActingPlayer().setPlayer(null);
            game.getActingPlayer().setPlayerAction(null);
            UtilBox.putAllPlayersIntoBox(game);
            place(own[0], 7, 7, PlayerState.STANDING);
            place(own[1], 9, 7, PlayerState.PRONE);
            place(own[2], 9, 6, PlayerState.STANDING);
            place(own[3], 14, 8, PlayerState.STANDING);
            place(own[4], 10, 7, PlayerState.STANDING);
            place(other[0], 8, 7, PlayerState.STANDING);
            place(other[1], 7, 6, PlayerState.STANDING);
            place(other[2], 10, 6, PlayerState.STANDING);
            for (int i = 5; i < own.length; i++) place(own[i], 3, i, PlayerState.STANDING);
            for (int i = 3; i < other.length; i++) place(other[i], 20, i, PlayerState.STANDING);
            assertEquals(22, Arrays.stream(game.getPlayers()).map(game.getFieldModel()::getPlayerCoordinate)
                .filter(FieldCoordinateBounds.FIELD::isInBounds).distinct().count());

            skill(own[0], "Multiple Block");
            skill(own[1], "Jump Up");
            skill(own[2], "Guard");
        }

        Player<?> carrier() { return own[0]; }

        String role() { return fixture.turn.getString("actor", null); }

        void giveBallToCarrier() {
            game.getFieldModel().setBallInPlay(true);
            game.getFieldModel().setBallMoving(false);
            game.getFieldModel().setBallCoordinate(game.getFieldModel().getPlayerCoordinate(carrier()));
        }

        JsonObject route() {
            return new RoutePlanner(fixture.state, carrier(), false)
                .previewWaypoints(List.of(new MovementWaypoint(new FieldCoordinate(8, 8), false),
                    new MovementWaypoint(new FieldCoordinate(9, 8), false)));
        }

        private void place(Player<?> player, int x, int y, int base) {
            game.getFieldModel().setPlayerCoordinate(player, new FieldCoordinate(x, y));
            game.getFieldModel().setPlayerState(player, new PlayerState(base).changeActive(true));
        }

        private void skill(Player<?> player, String name) {
            ((RosterPlayer) player).addSkill(game.getRules().getSkillFactory().forName(name));
        }
    }
}
