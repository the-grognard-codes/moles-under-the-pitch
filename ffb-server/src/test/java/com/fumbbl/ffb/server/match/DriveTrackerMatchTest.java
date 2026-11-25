package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.FactoryType;
import com.fumbbl.ffb.FieldCoordinate;
import com.fumbbl.ffb.PlayerState;
import com.fumbbl.ffb.factory.SkillFactory;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.Player;
import com.fumbbl.ffb.model.RosterPlayer;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.util.UtilBox;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveTrackerMatchTest {
    @Test void solidDefenceTrackerMatchesLiveRecoveryAndReplay() throws Exception {
        playCompleteMatch(2, "SOLID_DEFENCE");
    }

    @Test void chargeTrackerMatchesLiveRecoveryAndReplay() throws Exception {
        playCompleteMatch(5, "CHARGE");
    }

    private void playCompleteMatch(int die, String kickoffEvent) throws Exception {
        SetupSessionMovementTest.Fixture fixture = new SetupSessionMovementTest.Fixture(0, true);
        SetupSession session = fixture.session;
        GameState nativeState = sessionField(session, "state", GameState.class);
        nativeState.getDiceRoller().setRecoveryRoll(sides -> Math.min(sides, die));
        JsonObject live = view(session, "home");
        JsonArray baseline = baseline(kickoffEvent);
        DriveTrackerPresentation.Summary carried = null;
        SetupSession continuing = null;
        Set<String> observedReports = new HashSet<>();
        boolean scored = false;
        boolean continuedToVisibleTracker = false;

        for (int revision = 0; revision < baseline.size(); revision++) {
            assertEquals(revision, live.getInt("revision", -1));
            JsonValue tracker = tracker(live);
            assertEquals(baseline.get(revision), tracker, "Main projection at revision " + revision);
            JsonObject record = session.transcriptPage(revision, 1).get("records").asArray().get(0).asObject();
            JsonObject recordedView = record.get("state").asObject();
            assertEquals(tracker, tracker(recordedView), "Recorded projection at revision " + revision);
            JsonArray outcomes = record.get("native").asArray();
            collectReports(outcomes, observedReports);

            // Continue a fresh tracker using only the exported value and this revision's native outcomes.
            DriveTrackerPresentation hop = new DriveTrackerPresentation();
            if (carried != null) {
                hop.restoreSummary(carried);
                assertEquals(carried, hop.exportSummary(), "Imported summary before revision " + revision);
            }
            int drive = recordedView.getInt("drive", -1);
            String phase = recordedView.getString("phase", null);
            hop.recorded(drive, phase, recordedView.getString("actor", null), outcomes);
            carried = hop.exportSummary();
            assertEquals(carried, summary(session), "Live summary at revision " + revision);
            JsonObject carriedProjection = hop.project(nativeState, drive, phase, List::of);
            assertEquals(tracker, carriedProjection == null ? JsonValue.NULL : carriedProjection,
                "Summary-only projection at revision " + revision);

            SetupSession restored = new SetupSession(fixture.server, fixture.document, session.recoveryArtifact());
            assertEquals(carried, summary(restored), "Checkpoint summary at revision " + revision);
            for (String role : new String[] { "home", "away" })
                assertEquals(tracker, tracker(view(restored, role)), "Restored " + role + " revision " + revision);
            if (continuing != null) {
                assertEquals(carried, summary(continuing), "Continued summary after touchdown at revision " + revision);
                assertEquals(tracker, tracker(view(continuing, "home")), "Continued projection at revision " + revision);
                if (tracker.isObject()) {
                    continuing = null;
                    continuedToVisibleTracker = true;
                }
            }
            if ("TOUCHDOWN".equals(record.getString("kind", null))) {
                continuing = restored;
                sessionField(continuing, "state", GameState.class).getDiceRoller().setRecoveryRoll(sides -> Math.min(sides, die));
            }
            if ("FULL_TIME".equals(live.getString("phase", null))) break;
            assertTrue(revision + 1 < baseline.size(), "Match ran past its baseline before full time");
            String actor = live.getString("actor", null);
            JsonObject decisionView = view(session, actor);
            JsonObject request = decision(fixture, decisionView, revision);
            if (!scored && "REGULAR".equals(decisionView.getString("turnMode", null))
                && request.getString("actionId", "").endsWith(":end-turn")) {
                prepareScoringDrive(nativeState.getGame());
                scored = true;
            }
            JsonObject response = session.apply(actor, request);
            assertEquals("ACCEPTED", response.getString("code", null), response::toString);
            if (continuing != null) {
                JsonObject recoveredResponse = continuing.apply(actor, request);
                assertEquals("ACCEPTED", recoveredResponse.getString("code", null), recoveredResponse::toString);
            }
            live = response.get("state").asObject();
        }

        assertEquals("FULL_TIME", live.getString("phase", null));
        assertEquals(baseline.size(), live.getInt("revision", -1) + 1);
        assertTrue(live.getInt("homeScore", 0) + live.getInt("awayScore", 0) > 0);
        assertTrue(continuedToVisibleTracker, "The match restored at touchdown continued to a visible tracker");
        assertEquals(Set.of("kickoffResult", "secretWeaponBan", "knockoutRecovery"), observedReports);

        JsonObject completed = JsonObject.readFrom(session.completedMatch().json());
        JsonArray replay = completed.get("events").asArray();
        JsonArray records = completed.get("transcript").asObject().get("records").asArray();
        CanonicalJson canonical = new CanonicalJson();
        assertEquals(baseline.size(), replay.size());
        assertEquals(baseline.size(), records.size());
        for (int revision = 0; revision < replay.size(); revision++) {
            String expected = canonical.encode(baseline.get(revision), 1024);
            assertEquals(expected, canonical.encode(tracker(replay.get(revision).asObject().get("state").asObject()), 1024),
                "Completed replay event " + revision);
            assertEquals(expected, canonical.encode(tracker(records.get(revision).asObject().get("state").asObject()), 1024),
                "Completed replay transcript revision " + revision);
        }
    }

    /** Tracker output at each revision on main (0277e63b3), driven by the same dice and decisions. */
    private JsonArray baseline(String kickoffEvent) throws Exception {
        try (var resource = getClass().getResourceAsStream("/drive-tracker-baseline.json")) {
            assertNotNull(resource);
            return JsonObject.readFrom(new InputStreamReader(resource, StandardCharsets.UTF_8)).get(kickoffEvent).asArray();
        }
    }

    private JsonObject decision(SetupSessionMovementTest.Fixture fixture, JsonObject live, int revision) {
        JsonObject request = fixture.request("action", "tracker-" + revision, live);
        if ("SETUP".equals(live.getString("phase", null))) return request.set("operation", "confirm");
        if (!live.get("prompt").isNull()) {
            JsonObject prompt = live.get("prompt").asObject();
            return request.set("operation", "choice").add("promptId", prompt.get("id"))
                .add("optionId", prompt.get("options").asArray().get(0));
        }
        JsonObject first = live.get("actions").asArray().get(0).asObject();
        for (JsonValue value : live.get("actions").asArray()) {
            JsonObject action = value.asObject();
            String id = action.getString("id", "");
            if (id.endsWith(":decline-event") || id.endsWith(":end-event") || id.endsWith(":confirm-solid-defence")
                || "endTurn".equals(action.getString("kind", null))) return request.add("actionId", id);
        }
        return request.add("actionId", first.get("id"));
    }

    private void prepareScoringDrive(Game game) {
        Player<?> scorer = game.getActingTeam().getPlayers()[0];
        Player<?> knockedOut = game.getActingTeam().getPlayers()[1];
        SkillFactory skills = game.getFactory(FactoryType.Factory.SKILL);
        ((RosterPlayer) scorer).addSkill(skills.forName("Secret Weapon"));
        FieldCoordinate endzone = new FieldCoordinate(game.isHomePlaying() ? 25 : 0, 7);
        game.getFieldModel().setPlayerState(scorer, new PlayerState(PlayerState.STANDING));
        game.getFieldModel().setPlayerCoordinate(scorer, endzone);
        game.getFieldModel().setPlayerState(knockedOut, new PlayerState(PlayerState.KNOCKED_OUT));
        UtilBox.putPlayerIntoBox(game, knockedOut);
        game.getFieldModel().setBallCoordinate(endzone);
        game.getFieldModel().setBallInPlay(true);
        game.getFieldModel().setBallMoving(false);
    }

    private void collectReports(JsonArray syncs, Set<String> observedReports) {
        for (JsonValue value : syncs) {
            JsonValue list = value.asObject().get("reportList");
            if (list == null || list.isNull()) continue;
            for (JsonValue item : list.asObject().get("reports").asArray()) {
                JsonObject report = item.asObject();
                String id = report.getString("reportId", "");
                if ("kickoffResult".equals(id)) observedReports.add(id);
                if ("secretWeaponBan".equals(id) && !report.get("playerIds").asArray().isEmpty()) observedReports.add(id);
                if ("turnEnd".equals(id) && report.get("knockoutRecoveryArray") != null
                    && !report.get("knockoutRecoveryArray").asArray().isEmpty()) observedReports.add("knockoutRecovery");
            }
        }
    }

    private JsonObject view(SetupSession session, String role) {
        return session.reply("load", "ACCEPTED", false, role).get("state").asObject();
    }

    private JsonValue tracker(JsonObject view) {
        JsonValue value = view.get("driveTracker");
        return value == null ? JsonValue.NULL : value;
    }

    private DriveTrackerPresentation.Summary summary(SetupSession session) throws Exception {
        return sessionField(session, "driveTracker", DriveTrackerPresentation.class).exportSummary();
    }

    private <T> T sessionField(SetupSession session, String name, Class<T> type) throws Exception {
        Field field = SetupSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(session));
    }
}
