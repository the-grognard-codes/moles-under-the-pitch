package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.Set;

/** Public read adapter for the legacy transcript. Native recovery retains the original inputs and commands. */
final class PublicMatchRecord {
    private final PublicMatchFields fields = new PublicMatchFields();

    private static final Set<String> OPERATIONS = Set.of("action", "choice", "concede", "confirm",
        "formation", "movement", "place", "route", "sizeLimit");
    private static final Set<String> MODEL_CHANGES = Set.of("fieldModelSetBallCoordinate", "fieldModelSetBallMoving",
        "fieldModelSetBallInPlay", "fieldModelSetPlayerState", "fieldModelSetPlayerCoordinate");

    private final PublicMatchView board = new PublicMatchView();
    private final PublicNativeReports reports = new PublicNativeReports();

    JsonObject record(JsonObject source) {
        JsonObject result = fields.scalars(source, "index", "revision", "kind", "actor", "at");
        fields.object(source, result, "state", state -> board.recorded(state, "home"));
        fields.object(source, result, "decision", this::decision);
        if (source.get("decision") instanceof JsonObject decision && "sizeLimit".equals(decision.getString("operation", null)))
            result.get("state").asObject().set("stopReason", MatchStopReason.MATCH_SIZE_LIMIT.name());
        fields.objects(source, result, "native", this::outcome);
        return result;
    }

    private JsonObject decision(JsonObject source) {
        String operation = source.getString("operation", "");
        JsonObject result = new JsonObject();
        if (OPERATIONS.contains(operation)) result.add("operation", operation);
        if ("choice".equals(operation) && Set.of("heads", "tails", "receive", "kick").contains(source.getString("optionId", "")))
            result.add("optionId", source.get("optionId"));
        if ("sizeLimit".equals(operation) && MatchStopReason.MATCH_SIZE_LIMIT.name().equals(source.getString("reason", null)))
            result.add("reason", MatchStopReason.MATCH_SIZE_LIMIT.name());
        if ("place".equals(operation)) {
            if (source.get("playerId") != null && source.get("playerId").isString()) result.add("playerId", source.get("playerId"));
            fields.object(source, result, "to", fields::square);
        }
        if ("action".equals(operation) && source.getString("actionId", "").endsWith(":event-confirm"))
            fields.values(source, result, "playerIds");
        fields.object(source, result, "logPresentation", log -> {
            JsonObject presentation = fields.scalars(log, "version");
            fields.object(log, presentation, "action", action -> {
                JsonObject accepted = fields.scalars(action, "kind", "label", "playerId");
                fields.object(action, accepted, "target", target -> fields.scalars(target, "x", "y", "playerId"));
                return accepted;
            });
            fields.object(log, presentation, "movement", movement -> coordinates(movement,
                fields.scalars(movement, "commitRevision", "playerId", "complete")));
            return presentation;
        });
        return result;
    }

    private JsonObject outcome(JsonObject source) {
        JsonObject result = fields.scalars(source, "commandNr");
        fields.object(source, result, "modelChangeList", list -> {
            JsonObject changes = new JsonObject();
            fields.objects(list, changes, "modelChangeArray", this::modelChange);
            return changes;
        });
        fields.object(source, result, "reportList", list -> {
            JsonObject projected = new JsonObject();
            fields.objects(list, projected, "reports", reports::report);
            return projected;
        });
        fields.object(source, result, "blockContext", board::blockContext);
        fields.object(source, result, "logOutcomes", log -> {
            JsonObject projected = fields.scalars(log, "version");
            fields.objects(log, projected, "events", event -> coordinates(event, fields.scalars(event, "playerId", "kind", "skill")));
            return projected;
        });
        fields.object(source, result, "matchAnnouncements", log -> {
            JsonObject projected = fields.scalars(log, "version");
            fields.objects(log, projected, "events", this::announcement);
            return projected;
        });
        return result;
    }

    private JsonObject modelChange(JsonObject source) {
        String id = source.getString("modelChangeId", "");
        if (!MODEL_CHANGES.contains(id)) return null;
        JsonObject result = fields.scalars(source, "modelChangeId");
        if (id.startsWith("fieldModelSetPlayer") && source.get("modelChangeKey") != null && source.get("modelChangeKey").isString())
            result.add("modelChangeKey", source.get("modelChangeKey"));
        JsonValue value = source.get("modelChangeValue");
        if (value == null) return result;
        if (id.endsWith("Coordinate") && value.isArray()) fields.values(source, result, "modelChangeValue");
        else if (value.isNull() || id.endsWith("State") && value.isNumber() || value.isBoolean() && (id.endsWith("InPlay") || id.endsWith("Moving")))
            result.add("modelChangeValue", value);
        return result;
    }

    private JsonObject announcement(JsonObject source) {
        JsonObject event = fields.scalars(source, "kind", "team", "turn", "half", "reason", "headline", "code", "teamSpecific");
        fields.object(source, event, "scorer", scorer -> fields.scalars(scorer, "name", "number"));
        fields.object(source, event, "weather", weather -> fields.scalars(weather, "code", "headline", "reason"));
        return event;
    }

    private JsonObject coordinates(JsonObject source, JsonObject result) {
        fields.object(source, result, "from", fields::square);
        fields.object(source, result, "to", fields::square);
        return result;
    }
}
