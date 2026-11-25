package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.PlayerChoiceMode;
import com.fumbbl.ffb.TurnMode;
import com.fumbbl.ffb.dialog.DialogPlayerChoiceParameter;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.step.IStep;
import com.fumbbl.ffb.server.step.StepId;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.List;
import java.util.function.Supplier;

/** BB2025 start-of-drive guidance derived only from the native sequence and accepted public reports. */
final class DriveTrackerPresentation {
    /** Committed drive facts, sufficient to continue projection without replaying past outcomes. */
    record Summary(int drive, boolean setupSeen, String firstSetupActor, boolean secretWeapons, boolean knockouts,
        String event) {
        Summary(int drive) { this(drive, false, null, false, false, null); }
    }

    private Summary summary = new Summary(-1);

    Summary exportSummary() { return summary; }

    void restoreSummary(Summary saved) { summary = saved; }

    void recorded(int drive, String phase, String actor, Iterable<? extends JsonValue> outcomes) {
        Summary previous = summaryFor(drive);
        boolean firstSetup = !previous.setupSeen() && "SETUP".equals(phase);
        Reports added = new Reports();
        added.readSyncs(outcomes);
        // The earliest recorded revision with a supported event wins.
        summary = new Summary(drive, previous.setupSeen() || firstSetup,
            firstSetup ? actor : previous.firstSetupActor(), previous.secretWeapons() || added.secretWeapons,
            previous.knockouts() || added.knockouts, previous.event() == null ? added.event : previous.event());
    }

    JsonObject project(GameState state, int drive, String phase, Supplier<List<JsonObject>> pendingOutcomes) {
        if ("PRE_MATCH".equals(phase) || "FULL_TIME".equals(phase)) return null;
        boolean setup = hasStep(state, StepId.SETUP);
        boolean kickTarget = hasStep(state, StepId.KICKOFF);
        boolean kickoff = hasStep(state, StepId.END_KICKOFF);
        if ("SETUP".equals(phase) ? !setup : "READY_FOR_KICKOFF".equals(phase) ? !kickTarget
            : !"PLAY".equals(phase) || setup || kickTarget || !kickoff) return null;

        Game game = state.getGame();
        Summary committed = summaryFor(drive);
        String receivingRole = receivingRole(state, committed.firstSetupActor(), setup);
        String currentPhase = setup ? game.isSetupOffense() ? "RECEIVING_SETUP" : "KICKING_SETUP"
            : kickTarget ? "KICK_TARGET" : "KICKOFF";
        // The recorded view also includes the current revision's still-pending outcomes.
        Reports pending = new Reports();
        pending.readSyncs(pendingOutcomes.get());

        // Preserve the current roll in the pre-append view even when an earlier revision supplied a roll.
        String event = pending.event == null ? committed.event() : pending.event;
        if (event == null && game.getDialogParameter() instanceof DialogPlayerChoiceParameter) {
            PlayerChoiceMode choice = ((DialogPlayerChoiceParameter) game.getDialogParameter()).getPlayerChoiceMode();
            if (choice == PlayerChoiceMode.CHARGE || choice == PlayerChoiceMode.SOLID_DEFENCE) event = choice.name();
        }
        TurnMode mode = game.getTurnMode();
        if (event == null && (mode == TurnMode.HIGH_KICK || mode == TurnMode.SOLID_DEFENCE
            || mode == TurnMode.QUICK_SNAP || mode == TurnMode.BLITZ))
            event = mode == TurnMode.BLITZ ? "CHARGE" : mode.name();
        boolean eventActive = "KICKOFF".equals(currentPhase) && event != null
            && (mode == TurnMode.HIGH_KICK || mode == TurnMode.SOLID_DEFENCE
                || mode == TurnMode.QUICK_SNAP || mode == TurnMode.BLITZ
                || game.getDialogParameter() instanceof DialogPlayerChoiceParameter
                    && (((DialogPlayerChoiceParameter) game.getDialogParameter()).getPlayerChoiceMode() == PlayerChoiceMode.CHARGE
                        || ((DialogPlayerChoiceParameter) game.getDialogParameter()).getPlayerChoiceMode() == PlayerChoiceMode.SOLID_DEFENCE));
        return new JsonObject().add("version", 1).add("receivingRole", receivingRole)
            .add("phase", currentPhase).add("secretWeapons", committed.secretWeapons() || pending.secretWeapons)
            .add("knockouts", committed.knockouts() || pending.knockouts)
            .add("event", event == null ? JsonValue.NULL : JsonValue.valueOf(event))
            .add("eventComplete", event != null && !eventActive);
    }

    private boolean hasStep(GameState state, StepId id) {
        IStep current = state.getCurrentStep();
        if (current != null && current.getId() == id) return true;
        for (IStep step : state.getStepStack().toArray()) if (step.getId() == id) return true;
        return false;
    }

    private String receivingRole(GameState state, String firstSetupActor, boolean setup) {
        if ("home".equals(firstSetupActor)) return "away";
        if ("away".equals(firstSetupActor)) return "home";
        Game game = state.getGame();
        boolean homeReceiving = setup && game.isSetupOffense() ? game.isHomePlaying()
            : !game.isHomePlaying();
        if (!setup && (game.getTurnMode() == TurnMode.HIGH_KICK || game.getTurnMode() == TurnMode.QUICK_SNAP
            || game.getTurnMode() == TurnMode.KICKOFF_RETURN)) homeReceiving = game.isHomePlaying();
        return homeReceiving ? "home" : "away";
    }

    private Summary summaryFor(int drive) {
        return summary.drive() == drive ? summary : new Summary(drive);
    }

    private static final class Reports {
        boolean secretWeapons;
        boolean knockouts;
        String event;

        void readSyncs(Iterable<? extends JsonValue> syncs) {
            for (JsonValue value : syncs) readSync(value.asObject());
        }

        void readSync(JsonObject sync) {
            JsonValue reportList = sync.get("reportList");
            if (reportList == null || reportList.isNull()) return;
            for (JsonValue value : reportList.asObject().get("reports").asArray()) {
                JsonObject report = value.asObject();
                String id = report.getString("reportId", null);
                if ("secretWeaponBan".equals(id) && report.get("playerIds") != null
                    && report.get("playerIds").asArray().size() > 0) secretWeapons = true;
                if ("turnEnd".equals(id) && report.get("knockoutRecoveryArray") != null
                    && report.get("knockoutRecoveryArray").asArray().size() > 0) knockouts = true;
                if ("kickoffResult".equals(id)) {
                    String rolled = report.getString("kickoffResult", null);
                    if ("High Kick".equals(rolled)) event = "HIGH_KICK";
                    if ("Solid Defence".equals(rolled)) event = "SOLID_DEFENCE";
                    if ("Quick Snap".equals(rolled)) event = "QUICK_SNAP";
                    if ("Charge".equals(rolled)) event = "CHARGE";
                }
            }
        }
    }
}
