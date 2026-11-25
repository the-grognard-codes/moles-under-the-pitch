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

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveTrackerMatchTest {
    // Hashes of the per-revision tracker projections on main (0277e63b3).
    @Test void solidDefenceTrackerMatchesLiveRecoveryAndReplay() throws Exception {
        playCompleteMatch(2, "SOLID_DEFENCE", "3df07eeb7e53ec9f69ac84e7315b2921a45a08bf92371d323f4d7822c8656a3d");
    }

    @Test void chargeTrackerMatchesLiveRecoveryAndReplay() throws Exception {
        playCompleteMatch(5, "CHARGE", "76584a92f97d5f956fabe2236b6e789746aad9e0682d0a5d2e00415435f4e09a");
    }

    private void playCompleteMatch(int die, String kickoffEvent, String mainProjectionDigest) throws Exception {
        SetupSessionMovementTest.Fixture fixture = new SetupSessionMovementTest.Fixture(0, true);
        SetupSession session = fixture.session;
        GameState nativeState = nativeState(session);
        nativeState.getDiceRoller().setRecoveryRoll(sides -> Math.min(sides, die));
        JsonObject live = view(session, "home");
        JsonArray projected = new JsonArray();
        Set<String> observedReports = new HashSet<>();
        Set<String> observedEvents = new HashSet<>();
        int trackerRestores = 0;
        boolean scored = false;

        for (int revision = 0; revision < 100; revision++) {
            assertEquals(revision, live.getInt("revision", -1));
            JsonValue tracker = tracker(live);
            projected.add(tracker);
            JsonObject record = session.transcriptPage(revision, 1).get("records").asArray().get(0).asObject();
            assertEquals(tracker, tracker(record.get("state").asObject()), "Live and recorded revision " + revision);
            collectReports(record.get("native").asArray(), observedReports);
            if (tracker.isObject()) {
                if (!tracker.asObject().get("event").isNull()) observedEvents.add(tracker.asObject().get("event").asString());
                SetupSession restored = new SetupSession(fixture.server, fixture.document, session.recoveryArtifact());
                for (String role : new String[] { "home", "away" })
                    assertEquals(tracker, tracker(view(restored, role)), "Restored " + role + " revision " + revision);
                trackerRestores++;
            }
            if ("FULL_TIME".equals(live.getString("phase", null))) break;
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
            live = response.get("state").asObject();
        }

        assertEquals("FULL_TIME", live.getString("phase", null));
        assertTrue(live.getInt("homeScore", 0) + live.getInt("awayScore", 0) > 0);
        assertTrue(trackerRestores >= 6, "Both sides of setup and kick targeting restore across multiple drives");
        assertTrue(observedEvents.contains(kickoffEvent), observedEvents::toString);
        assertEquals(Set.of("kickoffResult", "secretWeaponBan", "knockoutRecovery"), observedReports);

        JsonObject completed = JsonObject.readFrom(session.completedMatch().json());
        JsonArray replay = completed.get("events").asArray();
        JsonArray records = completed.get("transcript").asObject().get("records").asArray();
        CanonicalJson canonical = new CanonicalJson();
        assertEquals(projected.size(), replay.size());
        assertEquals(projected.size(), records.size());
        for (int revision = 0; revision < replay.size(); revision++) {
            String expected = canonical.encode(projected.get(revision), 1024);
            assertEquals(expected, canonical.encode(tracker(replay.get(revision).asObject().get("state").asObject()), 1024),
                "Completed replay event " + revision);
            assertEquals(expected, canonical.encode(tracker(records.get(revision).asObject().get("state").asObject()), 1024),
                "Completed replay transcript revision " + revision);
        }
        assertEquals(mainProjectionDigest, new JsonDigest().sha256(projected), "Tracker projections across the full match");
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

    private GameState nativeState(SetupSession session) throws Exception {
        Field field = SetupSession.class.getDeclaredField("state");
        field.setAccessible(true);
        return (GameState) field.get(session);
    }
}
