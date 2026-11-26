package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict JSON boundary for records, keyframes and status. The browser reads the same field definitions. */
final class Format4Json {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DEPTH = 24;
    private static final Pattern MATCH_ID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final JsonObject CONTRACT = loadContract();
    private static final JsonObject SCHEMAS = CONTRACT.get("schemas").asObject();
    private static final Map<String, Set<String>> REPORT_FIELDS = loadReportFields();
    private static final Set<String> OMITTED_REPORT_FIELDS = strings(CONTRACT.get("omittedReportFields").asArray());

    static Map<String, Set<String>> publicNativeReportFields() { return REPORT_FIELDS; }
    static Set<String> omittedReportFields() { return OMITTED_REPORT_FIELDS; }

    JsonObject readRecord(String json) { return validateRecord(parse(json)); }
    JsonObject readKeyframe(String json) { return validateKeyframe(parse(json)); }
    JsonObject readStatus(String json) { return validateStatus(parse(json)); }

    JsonObject validateRecord(JsonObject record) {
        check(record, SCHEMAS.get("record"), "record");
        int revision = record.get("revision").asInt();
        boolean start = revision == 0;
        JsonObject patch = record.get("patch").asObject();
        JsonValue decision = record.get("decision");
        JsonValue actor = record.get("actor");
        if (start != "START".equals(record.get("kind").asString()) || start != decision.isNull()
            || start != patch.get("baseRevision").isNull()
            || start && (!actor.isNull() || patch.get("playerOrder") == null)
            || !start && (actor.isNull() || patch.get("baseRevision").asInt() != revision - 1))
            throw invalid("record revision");
        if (!actor.isNull()) {
            JsonObject identity = actor.asObject();
            if (!teamId(record.get("matchId").asString(), identity.get("side").asString()).equals(identity.get("teamId").asString()))
                throw invalid("record.actor.teamId");
        }
        if (!decision.isNull()) {
            JsonObject accepted = decision.asObject();
            String operation = accepted.get("operation").asString();
            if (accepted.get("optionId") != null && !"choice".equals(operation)
                || accepted.get("reason") != null && !"sizeLimit".equals(operation)
                || (accepted.get("playerId") != null || accepted.get("to") != null) && !"place".equals(operation)
                || accepted.get("playerIds") != null && !"action".equals(operation))
                throw invalid("record.decision");
            JsonValue log = accepted.get("logPresentation");
            if (log != null && log.asObject().get("movement").isObject()
                && log.asObject().get("movement").asObject().get("commitRevision").asInt() >= revision)
                throw invalid("record.decision.logPresentation.movement");
        }
        checkPatch(patch);
        int last = 0;
        Set<Integer> commands = new HashSet<>();
        for (JsonValue outcome : record.get("outcomes").asArray()) {
            int command = outcome.asObject().get("commandNr").asInt();
            if (command <= last) throw invalid("record.outcomes order");
            last = command;
            commands.add(command);
            checkOutcomeTeams(outcome.asObject(), record.get("matchId").asString());
        }
        int ordinal = 0;
        for (JsonValue value : record.get("presentation").asArray()) {
            JsonObject marker = value.asObject();
            JsonValue command = marker.get("commandNr");
            if (marker.get("ordinal").asInt() != ordinal++ || !command.isNull() && !commands.contains(command.asInt()))
                throw invalid("record.presentation order");
        }
        return record;
    }

    JsonObject validateKeyframe(JsonObject keyframe) {
        check(keyframe, SCHEMAS.get("keyframe"), "keyframe");
        checkView(keyframe.get("view").asObject());
        return keyframe;
    }

    JsonObject validateStatus(JsonObject status) {
        check(status, SCHEMAS.get("status"), "status");
        JsonValue save = status.get("saveResume");
        if (save.isObject()) {
            JsonObject proposal = save.asObject();
            boolean pending = proposal.get("status").asString().endsWith("_PENDING");
            if (pending == proposal.get("proposer").isNull() || pending == proposal.get("expiresAt").isNull())
                throw invalid("status.saveResume");
        }
        if (status.get("clock").asObject().getBoolean("running", false)
            && status.get("clock").asObject().get("activeRole").isNull()) throw invalid("status.clock");
        return status;
    }

    JsonObject validateView(JsonObject view) {
        check(view, SCHEMAS.get("view"), "view");
        checkView(view);
        return view;
    }

    String encode(JsonObject value) {
        return new CanonicalJson().encode(value, MAX_BYTES, MAX_DEPTH, () -> invalid("encoding bounds"));
    }

    private JsonObject parse(String json) {
        return new MatchJson().parse(json, MAX_BYTES, MAX_DEPTH);
    }

    String teamId(String matchId, String side) { return matchId + ":" + side; }

    private void checkOutcomeTeams(JsonObject outcome, String matchId) {
        if (!(outcome.get("reportList") instanceof JsonObject list)) return;
        for (JsonValue value : list.get("reports").asArray()) {
            JsonObject report = value.asObject();
            for (String key : Format4Records.TEAM_FIELDS) if (report.get(key) != null && !report.get(key).isNull())
                checkTeam(report.get(key).asString(), matchId);
            for (String key : Format4Records.TEAM_LIST_FIELDS) if (report.get(key) instanceof JsonArray ids)
                for (JsonValue id : ids) checkTeam(id.asString(), matchId);
        }
    }

    private void checkTeam(String team, String matchId) {
        if (!team.equals(teamId(matchId, "home")) && !team.equals(teamId(matchId, "away")))
            throw invalid("record.outcomes team identity");
    }

    private void checkPatch(JsonObject patch) {
        Set<String> removedSections = unique(patch.get("removeSections").asArray());
        if (removedSections.stream().anyMatch(patch.get("sections").asObject().names()::contains))
            throw invalid("patch section overlap");
        Set<String> removedPlayers = unique(patch.get("removePlayers").asArray());
        if (players(patch.get("players").asArray()).stream().anyMatch(removedPlayers::contains))
            throw invalid("patch player overlap");
        if (patch.get("playerOrder") != null) unique(patch.get("playerOrder").asArray());
    }

    private void checkView(JsonObject view) {
        Set<String> ids = players(view.get("players").asArray());
        JsonValue active = view.get("activePlayerId");
        if (!active.isNull() && !ids.contains(active.asString()) || active.isNull() && view.get("activePlayerAction") != null
            || "FULL_TIME".equals(view.get("phase").asString()) != "none".equals(view.get("decisionKind").asString()))
            throw invalid("view decision or active player");
    }

    private Set<String> players(JsonArray players) {
        Set<String> ids = new HashSet<>();
        for (JsonValue value : players) {
            JsonObject player = value.asObject();
            boolean onPitch = "pitch".equals(player.get("offPitch").asString());
            if (!ids.add(player.get("id").asString()) || onPitch == player.get("x").isNull() || onPitch == player.get("y").isNull()
                || (player.get("positionRace") == null) != (player.get("positionRole") == null))
                throw invalid("player identity or location");
        }
        return ids;
    }

    private Set<String> unique(JsonArray values) {
        Set<String> seen = new HashSet<>();
        for (JsonValue value : values) if (!seen.add(value.asString())) throw invalid("duplicate patch identity");
        return seen;
    }

    private void check(JsonValue value, JsonValue schema, String path) {
        if (accepts(value, schema, path)) return;
        throw invalid(path);
    }

    private boolean accepts(JsonValue value, JsonValue schema, String path) {
        if (schema.isString()) return primitive(value, schema.asString(), path);
        if (schema.isObject()) {
            if (!value.isObject()) return false;
            JsonObject object = value.asObject();
            JsonObject required = schema.asObject().get("required").asObject();
            JsonObject optional = schema.asObject().get("optional").asObject();
            for (String key : required.names()) if (object.get(key) == null) throw invalid(path + "." + key);
            for (JsonObject.Member member : object) {
                String key = member.getName();
                JsonValue expected = required.get(key);
                if (expected == null) expected = optional.get(key);
                if (expected == null) throw invalid(path + "." + key);
                check(member.getValue(), expected, path + "." + key);
            }
            return true;
        }
        JsonArray options = schema.asArray();
        JsonValue first = options.get(0);
        if (first.equals(JsonValue.valueOf("nullable"))) return value.isNull() || accepts(value, options.get(1), path);
        if (first.equals(JsonValue.valueOf("list"))) {
            if (!value.isArray() || value.asArray().size() > options.get(2).asInt()) return false;
            for (JsonValue item : value.asArray()) check(item, options.get(1), path + "[]");
            return true;
        }
        if (first.equals(JsonValue.valueOf("any"))) {
            for (int index = 1; index < options.size(); index++) {
                try { if (accepts(value, options.get(index), path)) return true; }
                catch (IllegalArgumentException mismatch) { /* Try the next shape. */ }
            }
            return false;
        }
        return options.values().contains(value);
    }

    private boolean primitive(JsonValue value, String type, String path) {
        if (type.startsWith("@")) return accepts(value, SCHEMAS.get(type.substring(1)), path);
        return switch (type) {
            case "id" -> text(value, 200);
            case "text" -> text(value, 2_000);
            case "match" -> value.isString() && MATCH_ID.matcher(value.asString()).matches();
            case "matchTeam" -> text(value, 41) && value.asString().length() > 36
                && MATCH_ID.matcher(value.asString().substring(0, 36)).matches()
                && Set.of(":home", ":away").contains(value.asString().substring(36));
            case "nativeSquare" -> value instanceof JsonArray coords && coords.size() == 2
                && coords.values().stream().allMatch(item -> integer(item, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case "int" -> integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case "nat" -> integer(value, 0, Integer.MAX_VALUE);
            case "positive" -> integer(value, 1, Integer.MAX_VALUE);
            case "column" -> integer(value, 0, 25);
            case "row" -> integer(value, 0, 14);
            case "kickColumn" -> integer(value, -6, 31);
            case "kickRow" -> integer(value, -6, 20);
            case "time" -> integer(value, 0, MAX_SAFE_INTEGER);
            case "generation" -> integer(value, 1, MAX_SAFE_INTEGER);
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            case "report" -> report(value, path);
            default -> throw new IllegalStateException("Unknown format-4 schema type " + type);
        };
    }

    private boolean report(JsonValue value, String path) {
        if (!value.isObject()) return false;
        JsonObject report = value.asObject();
        JsonValue id = report.get("reportId");
        if (id == null || !text(id, 200)) return false;
        Set<String> permitted = REPORT_FIELDS.get(id.asString());
        if (permitted == null) return false;
        JsonObject extensions = SCHEMAS.get("report").asObject().get("optional").asObject();
        JsonObject structured = SCHEMAS.get("reportData").asObject().get("optional").asObject();
        for (JsonObject.Member field : report) {
            String name = field.getName();
            if ("reportId".equals(name)) continue;
            JsonValue expected = extensions.get(name);
            if (expected == null) {
                boolean normalizedSide = "side".equals(name) && permitted.contains("coach");
                if (!normalizedSide && (!permitted.contains(name) || OMITTED_REPORT_FIELDS.contains(name))) return false;
                expected = structured.get(name);
            }
            if (expected == null) return false;
            check(field.getValue(), expected, path + "." + name);
        }
        return true;
    }

    private boolean integer(JsonValue value, long min, long max) {
        if (!value.isNumber()) return false;
        try {
            long number = value.asLong();
            return number >= min && number <= max;
        } catch (NumberFormatException invalid) { return false; }
    }

    private boolean text(JsonValue value, int max) {
        return value.isString() && !value.asString().isEmpty() && value.asString().length() <= max;
    }

    private IllegalArgumentException invalid(String path) { return new IllegalArgumentException("Invalid format 4 at " + path); }

    private static Map<String, Set<String>> loadReportFields() {
        Map<String, Set<String>> result = new HashMap<>();
        for (JsonObject.Member report : CONTRACT.get("reportFields").asObject())
            result.put(report.getName(), strings(report.getValue().asArray()));
        return Map.copyOf(result);
    }

    private static Set<String> strings(JsonArray values) {
        Set<String> result = new HashSet<>();
        for (JsonValue value : values) result.add(value.asString());
        return Set.copyOf(result);
    }

    private static JsonObject loadContract() {
        try (InputStream input = Format4Json.class.getResourceAsStream("/match/format-4.json")) {
            if (input == null) throw new IllegalStateException("Missing format-4 schema");
            return JsonObject.readFrom(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) { throw new IllegalStateException("Cannot load format-4 schema", failure); }
    }
}
