package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class MatchTranscriptTest {
    @Test void legacyHistoryPublicReadRetainsRevealedDiceAndMovementAndExcludesPrivateFields() {
        MatchTranscript transcript = new MatchTranscript();
        transcript.append(0, "START", "system", 100L, JsonValue.NULL, new JsonArray(), state(0), 0);
        JsonObject before = JsonObject.readFrom("""
            {"revision":1,"decisionKind":"reroll","actor":"away","actions":[{"id":"private"}],
             "prompt":{"id":"private"},"playerActionMenu":[{}],"saveResume":{"status":"ACTIVE"},
             "players":[{"id":"player","number":4,"art":{"rosterId":"human","positionId":"lineman","private":"owner"}}]}
            """);
        JsonObject decision = JsonObject.readFrom("""
            {"operation":"action","actionId":"1:event-confirm","requestId":"private","computerDecision":{},
             "playerIds":["player"],"waypoints":[{"x":2,"y":2}],
             "logPresentation":{"version":1,"action":{"kind":"kickoffChoice","label":"Confirm","playerId":null,"target":null},
               "movement":{"commitRevision":0,"playerId":"player","from":{"x":1,"y":2,"private":true},"to":{"x":2,"y":2},"complete":true}}}
            """);
        JsonObject nativeSync = JsonObject.readFrom("""
            {"commandNr":7,"gameTime":1000,"turnTime":190,"modelChangeList":{"modelChangeArray":[
              {"modelChangeId":"fieldModelSetPlayerCoordinate","modelChangeKey":"player","modelChangeValue":[2,2],"private":true},
              {"modelChangeId":"gameSetDialogParameter","modelChangeValue":{"id":"private"}}]},
             "reportList":{"reports":[{"reportId":"blockReRoll","blockRoll":[2],"blockRollAfterReroll":[2,2],"rerolledIndexes":[0],"private":true},
               {"reportId":"dodgeRoll","playerId":"player","roll":4,"minimumRoll":3,
                "numericDice":{"version":1,"dice":[{"sides":6,"value":4,"label":"Dodge","private":true}]}},
               {"reportId":"futurePrivateReport","playerId":"player"}]}}
            """);
        transcript.append(1, "ACTION", "away", 101L, decision, new JsonArray().add(nativeSync), before, 7);
        MatchTranscript restored = new MatchTranscript(transcript.json());
        JsonObject record = restored.page(1, 1).get("records").asArray().get(0).asObject();
        JsonObject board = record.get("state").asObject(), accepted = record.get("decision").asObject();
        JsonObject outcome = record.get("native").asArray().get(0).asObject();
        assertEquals("reroll", board.getString("decisionKind", ""));
        assertEquals(new JsonArray(), board.get("actions"));
        assertEquals(JsonValue.NULL, board.get("prompt"));
        assertNull(board.get("playerActionMenu"));
        assertNull(board.get("saveResume"));
        for (String field : new String[] {"requestId", "actionId", "computerDecision", "waypoints"})
            assertNull(accepted.get(field));
        assertEquals(new JsonArray().add("player"), accepted.get("playerIds"));
        assertEquals(new JsonObject().add("x", 1).add("y", 2),
            accepted.get("logPresentation").asObject().get("movement").asObject().get("from"));
        assertEquals(7, outcome.getInt("commandNr", -1));
        assertNull(outcome.get("turnTime"));
        JsonArray changes = outcome.get("modelChangeList").asObject().get("modelChangeArray").asArray();
        assertEquals(1, changes.size());
        assertEquals(new JsonArray().add(2).add(2), changes.get(0).asObject().get("modelChangeValue"));
        JsonArray reports = outcome.get("reportList").asObject().get("reports").asArray();
        assertEquals(2, reports.size());
        assertEquals(new JsonArray().add(2).add(2), reports.get(0).asObject().get("blockRollAfterReroll"));
        assertEquals(4, reports.get(1).asObject().getInt("roll", -1));
        assertFalse(record.toString().contains("private"));
        assertEquals(transcript.json(), restored.json());
    }

    @Test void restoresAcceptedDecisionAndRawNativeOutcomeInRevisionOrder() {
        MatchTranscript transcript = new MatchTranscript();
        transcript.append(0, "START", "system", 100L, JsonValue.NULL, new JsonArray(), state(0), 0);
        JsonObject report = new JsonObject().add("commandNr", 7).add("reportList", new JsonObject()
            .add("reports", new JsonArray().add(new JsonObject().add("roll", 4).add("minimumRoll", 3))));
        transcript.append(1, "ACTION", "home", 101L, new JsonObject().add("actionId", "move"),
            new JsonArray().add(report), state(1), 7);

        MatchTranscript restored = new MatchTranscript(JsonObject.readFrom(transcript.json().toString()));
        assertEquals(2, restored.size());
        assertEquals(7, restored.nativeCursor());
        assertEquals(1, restored.page(1, 1).get("records").asArray().size());
        assertEquals(2, restored.page(1, 1).getInt("next", -1));
        JsonObject accepted = restored.json().get("records").asArray().get(1).asObject();
        assertEquals("home", accepted.getString("actor", null));
        assertEquals(4, accepted.get("native").asArray().get(0).asObject().get("reportList").asObject()
            .get("reports").asArray().get(0).asObject().getInt("roll", -1));
        assertThrows(IllegalArgumentException.class, () -> restored.append(1, "ACTION", "home", 102L,
            new JsonObject(), new JsonArray(), state(1), 7));
        assertThrows(IllegalArgumentException.class, () -> restored.page(0, 9));
    }

    @Test void rejectsMissingRevisionAndUnsupportedSavedShape() {
        MatchTranscript transcript = new MatchTranscript();
        assertThrows(IllegalArgumentException.class, () -> transcript.append(1, "ACTION", "home", 100L,
            new JsonObject(), new JsonArray(), state(1), 0));
        transcript.append(0, "START", "system", 100L, JsonValue.NULL, new JsonArray(), state(0), 0);
        JsonObject saved = transcript.json();
        saved.get("records").asArray().get(0).asObject().set("revision", 2);
        assertThrows(IllegalArgumentException.class, () -> new MatchTranscript(saved));
    }

    @Test void rejectsMalformedNativeReportsWhenLoadingStoredRecords() {
        MatchTranscript transcript = new MatchTranscript();
        transcript.append(0, "START", "system", 100L, JsonValue.NULL,
            new JsonArray().add(new JsonObject().add("commandNr", 1).add("reportList", JsonValue.NULL)), state(0), 1);
        JsonObject saved = transcript.json();
        JsonObject sync = saved.get("records").asArray().get(0).asObject().get("native").asArray().get(0).asObject();
        assertEquals(1, new MatchTranscript(saved).size());
        for (String malformed : new String[] {
            "5", "{\"reports\":7}", "{\"reports\":[3]}",
            "{\"reports\":[{\"reportId\":\"secretWeaponBan\",\"playerIds\":false}]}",
            "{\"reports\":[{\"reportId\":\"turnEnd\",\"knockoutRecoveryArray\":{}}]}",
            "{\"reports\":[{\"reportId\":\"kickoffResult\",\"kickoffResult\":[]}]}"
        }) {
            sync.set("reportList", Json.parse(malformed));
            assertThrows(IllegalArgumentException.class, () -> new MatchTranscript(saved), malformed);
        }
    }

    @Test void unfinishedMovementRemainsAnIsolatedLatestRecordViewAcrossRestore() {
        MatchTranscript transcript = new MatchTranscript();
        transcript.append(0, "START", "system", 100L, JsonValue.NULL, new JsonArray(), state(0), 0);
        JsonObject movement = new JsonObject().add("complete", false).add("steps", new JsonArray().add(1));
        JsonObject decision = new JsonObject().add("logPresentation", new JsonObject().add("movement", movement));
        transcript.append(1, "ACTION", "home", 101L, decision, new JsonArray(), state(1), 0);
        movement.set("complete", true);
        assertEquals(false, transcript.pendingMovement().getBoolean("complete", true));
        transcript.pendingMovement().set("complete", true);
        MatchTranscript restored = new MatchTranscript(transcript.json());
        assertEquals(false, restored.pendingMovement().getBoolean("complete", true));
        restored.append(2, "ACTION", "home", 102L, new JsonObject(), new JsonArray(), state(2), 0);
        assertNull(restored.pendingMovement());
    }

    private JsonObject state(int revision) { return new JsonObject().add("revision", revision); }
}
