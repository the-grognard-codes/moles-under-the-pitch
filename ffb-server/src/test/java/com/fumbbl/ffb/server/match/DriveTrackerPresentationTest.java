package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.TurnMode;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.net.commands.ServerCommandModelSync;
import com.fumbbl.ffb.server.GameLog;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.step.IStep;
import com.fumbbl.ffb.server.step.StepId;
import com.fumbbl.ffb.server.step.StepStack;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DriveTrackerPresentationTest {
    private final GameState state = mock(GameState.class);
    private final Game game = mock(Game.class);
    private final StepStack stack = new StepStack(state);
    private final GameLog log = new GameLog(state);
    private final DriveTrackerPresentation presentation = new DriveTrackerPresentation();

    DriveTrackerPresentationTest() {
        when(state.getGame()).thenReturn(game);
        when(state.getStepStack()).thenReturn(stack);
        when(state.getGameLog()).thenReturn(log);
        when(game.getTurnMode()).thenReturn(TurnMode.SETUP);
    }

    @Test void followsBothSetupSidesKickTargetAndKickoffThroughAnInterruption() {
        current(StepId.SETUP);
        when(game.isHomePlaying()).thenReturn(true);
        assertEquals("away", project(1, "SETUP").getString("receivingRole", null));
        assertEquals("KICKING_SETUP", project(1, "SETUP").getString("phase", null));
        when(game.isSetupOffense()).thenReturn(true);
        when(game.isHomePlaying()).thenReturn(false);
        assertEquals("away", project(1, "SETUP").getString("receivingRole", null));
        assertEquals("RECEIVING_SETUP", project(1, "SETUP").getString("phase", null));
        when(game.isHomePlaying()).thenReturn(true);
        current(StepId.KICKOFF);
        assertEquals("KICK_TARGET", project(1, "READY_FOR_KICKOFF").getString("phase", null));
        stack.push(step(StepId.END_KICKOFF));
        current(StepId.APOTHECARY);
        when(game.getTurnMode()).thenReturn(TurnMode.KICKOFF);
        assertEquals("KICKOFF", project(1, "PLAY").getString("phase", null));
        assertEquals("away", project(1, "PLAY").getString("receivingRole", null));
        stack.clear();
        assertNull(project(1, "PLAY"));
    }

    @Test void usesCurrentDriveReportsIncludingFailedRecoveryAndHandledWeaponChecks() {
        current(StepId.SETUP);
        presentation.record(1, "SETUP", "home", new JsonArray());
        JsonArray prior = syncs(report("secretWeaponBan", "playerIds", new JsonArray().add("old")));
        presentation.record(1, "PLAY", "home", prior);
        JsonArray current = syncs(report("turnEnd", "knockoutRecoveryArray", new JsonArray().add(
            new JsonObject().add("playerId", "failed").add("successful", false))),
            report("secretWeaponBan", "playerIds", new JsonArray().add("handled")));
        presentation.record(2, "SETUP", "away", current);
        JsonObject result = project(2, "SETUP");
        assertTrue(result.getBoolean("secretWeapons", false));
        assertTrue(result.getBoolean("knockouts", false));
        assertEquals("home", result.getString("receivingRole", null));
        presentation.record(3, "SETUP", null, new JsonArray());
        assertFalse(project(3, "SETUP").getBoolean("secretWeapons", true));
        assertFalse(project(3, "SETUP").getBoolean("knockouts", true));
        assertFalse(project(2, "SETUP").getBoolean("secretWeapons", true));
    }

    @Test void retainsRolledEventsAndKeepsChargeBlitzWithinKickoff() {
        current(StepId.APPLY_KICKOFF_RESULT);
        stack.push(step(StepId.END_KICKOFF));
        for (String[] event : new String[][] { { "High Kick", "HIGH_KICK", "HIGH_KICK" },
            { "Solid Defence", "SOLID_DEFENCE", "SOLID_DEFENCE" },
            { "Quick Snap", "QUICK_SNAP", "QUICK_SNAP" }, { "Charge", "CHARGE", "BLITZ" } }) {
            when(game.getTurnMode()).thenReturn(TurnMode.valueOf(event[2]));
            DriveTrackerPresentation tracker = new DriveTrackerPresentation();
            tracker.record(1, "SETUP", "home", syncs(report("kickoffResult", "kickoffResult", JsonValue.valueOf(event[0]))));
            JsonObject active = tracker.project(state, 1, "PLAY", List::of);
            assertEquals(event[1], active.getString("event", null));
            assertFalse(active.getBoolean("eventComplete", true));
            when(game.getTurnMode()).thenReturn(TurnMode.KICKOFF);
            assertTrue(tracker.project(state, 1, "PLAY", List::of).getBoolean("eventComplete", false));
        }
    }

    @Test void readsPendingNativeReportsBeforeTheCurrentDecisionIsAppended() {
        current(StepId.SETUP);
        presentation.record(1, "SETUP", "home", new JsonArray());
        JsonObject sync = syncs(report("turnEnd", "knockoutRecoveryArray", new JsonArray().add(new JsonObject())),
            report("kickoffResult", "kickoffResult", JsonValue.valueOf("Charge")))
            .get(0).asObject();
        ServerCommandModelSync command = mock(ServerCommandModelSync.class);
        when(command.getCommandNr()).thenReturn(1);
        when(command.isReplayable()).thenReturn(true);
        when(command.toJsonValue()).thenReturn(sync);
        log.add(command);
        DriveTrackerPresentation.Summary before = presentation.exportSummary();
        List<JsonObject> pending = new NativeOutcomeCapture().since(log, 0).modelSyncs;
        JsonObject preview = presentation.project(state, 1, "SETUP", () -> pending);
        assertTrue(preview.getBoolean("knockouts", false));
        assertEquals("CHARGE", preview.getString("event", null));
        assertEquals(before, presentation.exportSummary());
        assertFalse(project(1, "SETUP").getBoolean("knockouts", true));
        presentation.record(1, "SETUP", "away", pending);
        assertTrue(new NativeOutcomeCapture().since(log, 1).modelSyncs.isEmpty());
        assertEquals(preview, project(1, "SETUP"));
    }

    @Test void continuesFromAnExportedSummaryWithoutRecords() {
        current(StepId.SETUP);
        JsonArray current = syncs(report("secretWeaponBan", "playerIds", new JsonArray().add("handled")),
            report("kickoffResult", "kickoffResult", JsonValue.valueOf("Charge")));
        presentation.record(2, "SETUP", "away", current);
        DriveTrackerPresentation restored = new DriveTrackerPresentation();
        restored.restoreSummary(presentation.exportSummary());
        JsonArray later = syncs(report("kickoffResult", "kickoffResult", JsonValue.valueOf("High Kick")));
        presentation.record(2, "SETUP", "home", later);
        restored.record(2, "SETUP", "home", later);
        JsonObject expected = project(2, "SETUP");
        assertEquals(expected, restored.project(state, 2, "SETUP", List::of));
        assertEquals("home", expected.getString("receivingRole", null));
        assertEquals("CHARGE", expected.getString("event", null));
        assertTrue(expected.getBoolean("secretWeapons", false));
        assertFalse(restored.project(state, 1, "SETUP", List::of).getBoolean("secretWeapons", true));
    }

    @Test void omitsPreMatchFullTimeAndOlderUnknownOptionalHistory() {
        current(StepId.SETUP);
        assertNull(project(1, "PRE_MATCH"));
        assertNull(project(1, "FULL_TIME"));
        JsonObject shown = project(1, "SETUP");
        assertFalse(shown.getBoolean("secretWeapons", true));
        assertFalse(shown.getBoolean("knockouts", true));
        assertTrue(shown.get("event").isNull());
        current(StepId.END_TURN);
        stack.push(step(StepId.SETUP));
        stack.push(step(StepId.END_KICKOFF));
        assertNull(project(1, "PLAY"));
    }

    private JsonObject project(int drive, String phase) {
        return presentation.project(state, drive, phase, List::of);
    }

    private void current(StepId id) {
        IStep current = step(id);
        when(state.getCurrentStep()).thenReturn(current);
    }

    private IStep step(StepId id) {
        IStep step = mock(IStep.class);
        when(step.getId()).thenReturn(id);
        return step;
    }

    private JsonArray syncs(JsonObject... reports) {
        JsonArray list = new JsonArray();
        for (JsonObject report : reports) list.add(report);
        return new JsonArray().add(new JsonObject().add("commandNr", 1)
            .add("reportList", new JsonObject().add("reports", list)));
    }

    private JsonObject report(String id, String name, JsonValue value) {
        return new JsonObject().add("reportId", id).add(name, value);
    }
}
