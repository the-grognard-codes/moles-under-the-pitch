package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.ReRollSources;
import com.fumbbl.ffb.ReRolledActions;
import com.fumbbl.ffb.net.commands.ClientCommandUseReRoll;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Format4RecordsTest {
    private final Format4Json codec = new Format4Json();
    private final Format4Records records = new Format4Records();

    @Test
    void javaAndBrowserShareRecordPatchKeyframeAndStatusFixtures() throws Exception {
        JsonObject fixtures = fixture();
        JsonObject initial = codec.readRecord(fixtures.get("start").toString());
        JsonObject first = codec.readKeyframe(fixtures.get("initialKeyframe").toString()).get("view").asObject();
        JsonObject turn = codec.readRecord(fixtures.get("turn").toString());
        JsonObject end = codec.validateView(fixtures.get("after").asObject());
        assertEquals(codec.encode(initial.get("patch").asObject()), codec.encode(records.patch(null, first)));
        assertEquals(codec.encode(turn.get("patch").asObject()), codec.encode(records.patch(first, end)));
        assertEquals("SUSPENDED", codec.readStatus(fixtures.get("saved").toString()).get("saveResume").asObject().getString("status", null));
        assertTrue(codec.readStatus(fixtures.get("resumed").toString()).get("reaction").isNull());
    }

    @Test
    void legacyReportFieldsMatchThePreFormat4Allowlist() {
        // Pin the 139 report IDs and exact field sets from fcf43d319, independent of their declaration order.
        Map<String, Set<String>> reportFields = new TreeMap<>(codec.publicNativeReportFields());
        assertEquals(139, reportFields.size());
        String canonical = reportFields.entrySet().stream()
            .map(entry -> entry.getKey() + ":" + String.join(",", entry.getValue().stream().sorted().toList()))
            .collect(Collectors.joining("\n"));
        assertEquals("556a8e70009bacb7be8a05cb3177217eca9c1804a2c6d0ac89fd2f91222fe8a5", new JsonDigest().sha256(canonical),
            "Legacy public report fields changed. Keep their existing contract through cutover.");
    }

    @Test
    void strictDecodersRejectUnknownVersionsAndPrivateDataAtEveryBoundary() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord("{"));
        assertThrows(IllegalArgumentException.class, () -> codec.readKeyframe("[]"));
        JsonObject fixtures = fixture();
        JsonObject start = fixtures.get("start").asObject();
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(start.toString().replace("\"revision\":0", "\"revision\":0,\"revision\":0")));
        for (JsonValue malformed : fixtures.get("invalidNumberEncodings").asArray()) {
            JsonArray pair = malformed.asArray();
            assertThrows(IllegalArgumentException.class,
                () -> codec.readRecord(start.toString().replace(pair.get(0).asString(), pair.get(1).asString())));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(start.set("formatVersion", 5).toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.readKeyframe(fixtures.get("initialKeyframe").asObject().set("formatVersion", 3).toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.readStatus(fixtures.get("saved").asObject().set("formatVersion", 5).toString()));
        JsonObject turn = fixtures.get("turn").asObject();
        turn.get("actor").asObject().add("accountId", "private");
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(turn.toString()));
        turn.get("actor").asObject().remove("accountId");
        JsonObject report = turn.get("outcomes").asArray().get(0).asObject().get("reportList").asObject()
            .get("reports").asArray().get(0).asObject();
        report.add("coach", "private");
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(turn.toString()));
        report.remove("coach");
        turn.get("patch").asObject().get("players").asArray().get(0).asObject().add("name", "private");
        assertThrows(IllegalArgumentException.class, () -> codec.readRecord(turn.toString()));
        JsonObject resumed = fixtures.get("resumed").asObject();
        resumed.get("saveResume").asObject().add("proposalId", "private");
        assertThrows(IllegalArgumentException.class, () -> codec.readStatus(resumed.toString()));
    }

    @Test
    void workerCaptureUsesMatchTeamIdentityAndKeepsNamesAndRequestsOutOfTheRecord() throws Exception {
        JsonObject fixtures = fixture();
        JsonObject before = fixtures.get("initialKeyframe").asObject().get("view").asObject();
        JsonObject after = fixtures.get("after").asObject();
        String match = after.getString("matchId", null);
        JsonObject request = new JsonObject().add("operation", "choice").add("optionId", "heads")
            .add("requestId", "private-request").add("promptId", "private-offer").add("accountId", "private-account");
        JsonObject nativeOutcome = new JsonObject().add("commandNr", 1)
            .add("reportList", new JsonObject().add("reports", new JsonArray()
                .add(new JsonObject().add("reportId", "coinThrow").add("coach", "private-account").add("coinThrowHeads", true))
                .add(new JsonObject().add("reportId", "receiveChoice").add("teamId", "private-source-team"))));
        Format4Records.MatchSides sides = new Format4Records.MatchSides(match,
            new Format4Records.NativeSide("home-team", "home-account"),
            new Format4Records.NativeSide("private-source-team", "private-account"));
        Format4Records.Captured capture = records.capture(new Format4Records.Baseline(before, 10),
            after, 50, "ACTION", "away", request, new JsonArray().add(nativeOutcome), sides);
        assertFalse(capture.encoded().record().contains("private-"));
        JsonObject recorded = codec.readRecord(capture.encoded().record());
        assertEquals(match + ":away", recorded.get("actor").asObject().getString("teamId", null));
        assertEquals("heads", recorded.get("decision").asObject().getString("optionId", null));
        JsonArray publicReports = recorded.get("outcomes").asArray().get(0).asObject().get("reportList").asObject().get("reports").asArray();
        assertTrue(publicReports.get(0).asObject().getBoolean("coinThrowHeads", false));
        assertEquals("away", publicReports.get(0).asObject().getString("side", null));
        assertEquals(match + ":away", publicReports.get(1).asObject().getString("teamId", null));
        request.set("optionId", "tails");
        assertEquals("heads", codec.readRecord(capture.encoded().record()).get("decision").asObject().getString("optionId", null));
    }

    @Test
    void acceptedRerollPolicySurvivesAnIdenticalPublicOutcome() throws Exception {
        JsonObject fixture = fixture();
        JsonObject before = fixture.get("initialKeyframe").asObject().get("view").asObject();
        JsonObject after = fixture.get("after").asObject();
        for (var source : List.of(ReRollSources.MASCOT, ReRollSources.MASCOT_TRR)) {
            JsonObject action = new JsonObject().add("kind", "reroll").add("label", "private-label")
                .add("playerId", JsonValue.NULL).add("target", JsonValue.NULL);
            new AcceptedActionPresentation().addRerollSource(action, new ClientCommandUseReRoll(ReRolledActions.BLOCK, source));
            JsonObject decision = new JsonObject().add("operation", "action").add("logPresentation", new JsonObject()
                .add("version", 1).add("action", action).add("movement", JsonValue.NULL));
            Format4Records.MatchSides sides = new Format4Records.MatchSides(after.getString("matchId", null),
                new Format4Records.NativeSide("home-team", "home-account"), new Format4Records.NativeSide("away-team", "away-account"));
            Format4Records.Captured captured = records.capture(new Format4Records.Baseline(before, 10),
                after, 50, "ACTION", "away", decision, new JsonArray(), sides);
            assertFalse(captured.encoded().record().contains("private-label"));
            JsonObject accepted = codec.readRecord(captured.encoded().record()).get("decision").asObject()
                .get("logPresentation").asObject().get("action").asObject();
            assertEquals(source.getName(), accepted.getString("rerollSource", null));
        }
    }

    @Test
    void actualSaveAndResumeKeepTheKeyframeRevisionAndSnapshotPatchBaseline() throws Exception {
        try (BogrukEngineFixture fixture = new BogrukEngineFixture("Human", "Orc", 43, true, BogrukEngineFixture.Mode.FORMAT4)) {
            SetupSession session = fixture.session;
            CompactMatchWrite.Capture start = session.compactCapture();
            assertNotNull(start.keyframe());
            JsonObject atStart = codec.readKeyframe(start.keyframe()).get("view").asObject();
            String snapshot = commit(session);
            JsonObject initial = JsonObject.readFrom(snapshot);
            assertEquals(atStart, initial.get("recordedView"));
            assertNull(session.compactCapture());

            long time = System.currentTimeMillis();
            session.saveResume("home", saveRequest("saveRequest", null), time);
            JsonObject proposal = session.decorateSaveResumeState(session.reply("proposal", "ACCEPTED", false, "away")
                .get("state").asObject()).get("saveResume").asObject();
            session.saveResume("away", saveRequest("saveAccept", proposal.getString("proposalId", null)), time + 1);
            JsonObject saved = codec.readStatus(session.format4Status(3, time + 1));
            assertEquals("SUSPENDED", saved.get("saveResume").asObject().getString("status", null));
            assertFalse(saved.get("clock").asObject().getBoolean("running", true));
            assertNull(session.compactCapture());
            assertEquals(atStart, JsonObject.readFrom(commit(session)).get("recordedView"));

            session.saveResume("home", saveRequest("resumeRequest", null), time + 2);
            JsonObject resume = session.decorateSaveResumeState(session.reply("resume", "ACCEPTED", false, "away")
                .get("state").asObject()).get("saveResume").asObject();
            session.saveResume("away", saveRequest("resumeAccept", resume.getString("proposalId", null)), time + 3);
            JsonObject resumed = codec.readStatus(session.format4Status(5, time + 3));
            assertEquals("ACTIVE", resumed.get("saveResume").asObject().getString("status", null));
            assertEquals(saved.get("revision"), resumed.get("revision"));
            assertNull(session.compactCapture());
            String resumedSnapshot = commit(session);
            assertEquals(atStart, JsonObject.readFrom(resumedSnapshot).get("recordedView"));

            SetupSession restored = SetupSession.restoreCompactSnapshot(fixture.server, resumedSnapshot);
            String actor = restored.spectatorView().getString("actor", null);
            JsonObject view = restored.reply("load", "ACCEPTED", false, actor).get("state").asObject();
            restored.apply(actor, fixture.recordedDecision(view, "first-decision", 1));
            JsonObject next = codec.readRecord(restored.compactCapture().record());
            assertEquals(0, next.get("patch").asObject().getInt("baseRevision", -1));
            assertEquals(1, next.getInt("revision", -1));
            assertTrue(next.get("at").asLong() >= codec.readRecord(start.record()).get("at").asLong());
            JsonObject after = JsonObject.readFrom(commit(restored)).get("recordedView").asObject();
            assertEquals(codec.encode(records.patch(atStart, after)), codec.encode(next.get("patch").asObject()));
        }
    }

    @Test
    void eachChangedTopLevelFieldIsPatchedAndPlayersAreReplacedWhole() throws Exception {
        JsonObject fixtures = fixture();
        JsonObject original = fixtures.get("initialKeyframe").asObject().get("view").asObject();
        for (String field : original.names()) {
            if (List.of("matchId", "revision", "players").contains(field)) continue;
            JsonObject changed = new JsonObject(original);
            JsonValue replacement = original.get(field).isNull() ? JsonValue.valueOf("changed") : JsonValue.NULL;
            changed.set(field, replacement);
            JsonObject patch = records.patch(original, changed);
            assertEquals(replacement, patch.get("sections").asObject().get(field), field);
            assertTrue(patch.get("removeSections").asArray().isEmpty(), field);
        }
        JsonObject canonical = JsonObject.readFrom(codec.encode(original));
        JsonObject reordered = records.patch(canonical, original);
        assertTrue(reordered.get("sections").asObject().isEmpty());
        assertTrue(reordered.get("players").asArray().isEmpty());

        JsonObject changed = JsonObject.readFrom(original.toString());
        JsonObject player = changed.get("players").asArray().get(1).asObject().set("state", "prone");
        JsonArray players = records.patch(original, changed).get("players").asArray();
        assertEquals(1, players.size());
        assertEquals(player, players.get(0));
    }

    private JsonObject fixture() throws Exception {
        return JsonObject.readFrom(Files.readString(Path.of("..", "browser-client", "test", "fixtures", "match-format-4.json")));
    }

    private JsonObject saveRequest(String operation, String proposal) {
        JsonObject request = new JsonObject().add("operation", operation).add("requestId", operation).add("expectedRevision", 0);
        if (proposal != null) request.add("proposalId", proposal);
        return request;
    }

    private String commit(SetupSession session) {
        CompactMatchWrite.History boundary = new CompactMatchWrite.History(session.revision() + 1, 0, "0".repeat(64));
        String[] snapshot = new String[1];
        assertEquals(CompactMatchWrite.Result.DURABLE, session.writeCompactSnapshot(boundary, candidate -> {
            snapshot[0] = candidate.snapshot();
            return CompactMatchWrite.Result.DURABLE;
        }, () -> { throw new AssertionError("unexpected release"); }));
        return snapshot[0];
    }
}
