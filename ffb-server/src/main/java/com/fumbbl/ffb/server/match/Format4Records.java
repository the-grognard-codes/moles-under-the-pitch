package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds a record and optional keyframe from the worker's committed public projection. */
final class Format4Records {
    private static final int KEYFRAME_INTERVAL = 32;
    private static final Set<String> VIEW_IDENTITY = Set.of("matchId", "revision", "players");
    static final Set<String> TEAM_FIELDS = Set.of("teamId", "choosingTeamId");
    static final Set<String> TEAM_LIST_FIELDS = Set.of("teamIdsAdditionalAssist", "teamIdsReRolledCheeringFangs");

    private final Format4Json codec = new Format4Json();
    private final PublicMatchView publicView = new PublicMatchView();
    private final PublicMatchRecord publicRecord = new PublicMatchRecord();

    JsonObject view(JsonObject board) {
        JsonObject result = publicView.recordedFacts(board);
        result.remove("homeTeamName").remove("awayTeamName");
        for (JsonValue player : result.get("players").asArray()) player.asObject().remove("name");
        // An out-of-bounds ball has no location section in the recorded view.
        if (result.get("ball") != null && result.get("ball").isNull()) result.remove("ball");
        return result;
    }

    record NativeSide(String teamId, String coach) { }

    record Captured(String record, String keyframe, JsonObject view, long at) { }

    Captured capture(JsonObject previous, JsonObject current, long at, String kind, String side,
        JsonValue decision, JsonArray nativeOutcomes, NativeSide home, NativeSide away) {
        int revision = current.get("revision").asInt();
        String matchId = current.get("matchId").asString();
        JsonValue actor = "system".equals(side) ? JsonValue.NULL
            : new JsonObject().add("side", side).add("teamId", codec.teamId(matchId, side));
        JsonValue accepted = decision.isNull() ? JsonValue.NULL : accepted(decision.asObject());
        JsonArray outcomes = new JsonArray();
        for (JsonValue value : nativeOutcomes) {
            JsonObject outcome = publicRecord.outcome(value.asObject());
            normalizeOutcomes(outcome, current, home, away);
            outcomes.add(outcome);
        }
        JsonObject record = new JsonObject().add("formatVersion", 4).add("matchId", matchId)
            .add("revision", revision).add("kind", kind).add("actor", actor).add("at", at)
            .add("decision", accepted).add("outcomes", outcomes).add("presentation", new JsonArray())
            .add("patch", patch(previous, current));
        String keyframe = revision % KEYFRAME_INTERVAL == 0
            ? codec.encode(codec.validateKeyframe(new JsonObject().add("formatVersion", 4).add("view", current))) : null;
        return new Captured(codec.encode(codec.validateRecord(record)), keyframe, current, at);
    }

    JsonObject patch(JsonObject previous, JsonObject current) {
        JsonObject before = previous == null ? new JsonObject() : previous;
        JsonObject sections = new JsonObject();
        JsonArray removeSections = new JsonArray();
        for (JsonObject.Member field : current) if (!VIEW_IDENTITY.contains(field.getName())
            && !same(before.get(field.getName()), field.getValue()))
            sections.add(field.getName(), field.getValue());
        before.names().stream().filter(field -> !VIEW_IDENTITY.contains(field) && current.get(field) == null)
            .sorted().forEach(removeSections::add);
        Map<String, JsonValue> oldPlayers = players(before);
        Map<String, JsonValue> newPlayers = players(current);
        JsonArray put = new JsonArray();
        JsonArray remove = new JsonArray();
        newPlayers.forEach((id, player) -> { if (!same(player, oldPlayers.get(id))) put.add(player); });
        oldPlayers.keySet().forEach(id -> { if (!newPlayers.containsKey(id)) remove.add(id); });
        JsonObject patch = new JsonObject().add("baseRevision", previous == null ? JsonValue.NULL : previous.get("revision"))
            .add("sections", sections).add("removeSections", removeSections).add("players", put).add("removePlayers", remove);
        if (previous == null || !oldPlayers.keySet().stream().toList().equals(newPlayers.keySet().stream().toList())) {
            JsonArray order = new JsonArray();
            newPlayers.keySet().forEach(order::add);
            patch.add("playerOrder", order);
        }
        return patch;
    }

    // Snapshot JSON sorts object keys. A restored baseline compares by contents and roster order.
    private boolean same(JsonValue first, JsonValue second) {
        if (Objects.equals(first, second)) return true;
        if (first instanceof JsonObject left && second instanceof JsonObject right) {
            if (left.size() != right.size()) return false;
            for (JsonObject.Member field : left) if (!same(field.getValue(), right.get(field.getName()))) return false;
            return true;
        }
        if (first instanceof JsonArray left && second instanceof JsonArray right) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) if (!same(left.get(index), right.get(index))) return false;
            return true;
        }
        return false;
    }

    private Map<String, JsonValue> players(JsonObject view) {
        Map<String, JsonValue> players = new LinkedHashMap<>();
        if (view.get("players") instanceof JsonArray roster) {
            for (JsonValue player : roster) players.put(player.asObject().get("id").asString(), player);
        }
        return players;
    }

    private JsonObject accepted(JsonObject decision) {
        JsonObject result = publicRecord.decision(decision);
        if (result.get("logPresentation") instanceof JsonObject log && log.get("action") instanceof JsonObject action) {
            action.remove("label");
            JsonObject source = decision.get("logPresentation").asObject().get("action").asObject();
            JsonValue rerollSource = source.get("rerollSource");
            if (rerollSource != null) action.add("rerollSource", rerollSource);
        }
        return result;
    }

    private void normalizeOutcomes(JsonObject outcome, JsonObject view, NativeSide home, NativeSide away) {
        String matchId = view.get("matchId").asString();
        if (outcome.get("reportList") instanceof JsonObject list) for (JsonValue value : list.get("reports").asArray()) {
            JsonObject report = value.asObject();
            String coach = report.getString("coach", null);
            if (coach != null) {
                if (coach.equals(home.coach())) report.add("side", "home");
                else if (coach.equals(away.coach())) report.add("side", "away");
            }
            Format4Json.omittedReportFields().forEach(report::remove);
            for (String field : TEAM_FIELDS) if (report.get(field) != null)
                report.set(field, publicTeamId(report.get(field), matchId, home, away));
            for (String field : TEAM_LIST_FIELDS) if (report.get(field) instanceof JsonArray oldIds) {
                JsonArray ids = new JsonArray();
                for (JsonValue id : oldIds) {
                    JsonValue normalized = publicTeamId(id, matchId, home, away);
                    if (!normalized.isNull()) ids.add(normalized);
                }
                report.set(field, ids);
            }
        }
        if (outcome.get("matchAnnouncements") instanceof JsonObject list) for (JsonValue value : list.get("events").asArray()) {
            JsonObject event = value.asObject();
            event.remove("reason").remove("headline");
            if (event.get("weather") instanceof JsonObject weather) weather.remove("headline").remove("reason");
            if (event.get("scorer") instanceof JsonObject scorer) {
                String side = event.getString("team", "");
                int number = scorer.getInt("number", -1);
                for (JsonValue entry : view.get("players").asArray()) {
                    JsonObject player = entry.asObject();
                    if (side.equals(player.getString("role", "")) && number == player.getInt("number", -2)) {
                        event.add("scorerId", player.get("id"));
                        break;
                    }
                }
            }
            event.remove("scorer");
        }
    }

    private JsonValue publicTeamId(JsonValue nativeId, String matchId, NativeSide home, NativeSide away) {
        if (nativeId.isString()) {
            String value = nativeId.asString();
            if (value.equals(home.teamId())) return JsonValue.valueOf(codec.teamId(matchId, "home"));
            if (value.equals(away.teamId())) return JsonValue.valueOf(codec.teamId(matchId, "away"));
        }
        return JsonValue.NULL;
    }
}
