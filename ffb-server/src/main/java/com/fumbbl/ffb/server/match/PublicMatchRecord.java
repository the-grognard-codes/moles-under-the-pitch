package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.Set;

import static com.fumbbl.ffb.server.match.PublicMatchFields.object;
import static com.fumbbl.ffb.server.match.PublicMatchFields.objects;
import static com.fumbbl.ffb.server.match.PublicMatchFields.scalars;
import static com.fumbbl.ffb.server.match.PublicMatchFields.values;

/** Public read adapter for the legacy transcript. Native recovery retains the original inputs and commands. */
final class PublicMatchRecord {
    private static final Set<String> OPERATIONS = Set.of("action", "choice", "concede", "confirm",
        "formation", "movement", "place", "route", "sizeLimit");
    private static final Set<String> MODEL_CHANGES = Set.of("fieldModelSetBallCoordinate", "fieldModelSetBallMoving",
        "fieldModelSetBallInPlay", "fieldModelSetPlayerState", "fieldModelSetPlayerCoordinate");

    private final PublicMatchView board = new PublicMatchView();
    private final PublicNativeReports reports = new PublicNativeReports();

    JsonObject record(JsonObject source) {
        JsonObject result = scalars(source, "index", "revision", "kind", "actor", "at");
        object(source, result, "state", state -> board.recorded(state, "home"));
        object(source, result, "decision", this::decision);
        if (source.get("decision") instanceof JsonObject decision && "sizeLimit".equals(decision.getString("operation", null)))
            result.get("state").asObject().set("stopReason", MatchStopReason.MATCH_SIZE_LIMIT.name());
        objects(source, result, "native", this::outcome);
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
            object(source, result, "to", PublicMatchFields::square);
        }
        if ("action".equals(operation) && source.getString("actionId", "").endsWith(":event-confirm"))
            values(source, result, "playerIds");
        object(source, result, "logPresentation", log -> {
            JsonObject presentation = scalars(log, "version");
            object(log, presentation, "action", action -> {
                JsonObject accepted = scalars(action, "kind", "label", "playerId");
                object(action, accepted, "target", target -> scalars(target, "x", "y", "playerId"));
                return accepted;
            });
            object(log, presentation, "movement", movement -> coordinates(movement,
                scalars(movement, "commitRevision", "playerId", "complete")));
            return presentation;
        });
        return result;
    }

    private JsonObject outcome(JsonObject source) {
        JsonObject result = scalars(source, "commandNr");
        object(source, result, "modelChangeList", list -> {
            JsonObject changes = new JsonObject();
            objects(list, changes, "modelChangeArray", this::modelChange);
            return changes;
        });
        object(source, result, "reportList", list -> {
            JsonObject projected = new JsonObject();
            objects(list, projected, "reports", reports::report);
            return projected;
        });
        object(source, result, "blockContext", board::blockContext);
        object(source, result, "logOutcomes", log -> {
            JsonObject projected = scalars(log, "version");
            objects(log, projected, "events", event -> coordinates(event, scalars(event, "playerId", "kind", "skill")));
            return projected;
        });
        object(source, result, "matchAnnouncements", log -> {
            JsonObject projected = scalars(log, "version");
            objects(log, projected, "events", this::announcement);
            return projected;
        });
        return result;
    }

    private JsonObject modelChange(JsonObject source) {
        String id = source.getString("modelChangeId", "");
        if (!MODEL_CHANGES.contains(id)) return null;
        JsonObject result = scalars(source, "modelChangeId");
        if (id.startsWith("fieldModelSetPlayer") && source.get("modelChangeKey") != null && source.get("modelChangeKey").isString())
            result.add("modelChangeKey", source.get("modelChangeKey"));
        JsonValue value = source.get("modelChangeValue");
        if (value == null) return result;
        if (id.endsWith("Coordinate") && value.isArray()) values(source, result, "modelChangeValue");
        else if (value.isNull() || id.endsWith("State") && value.isNumber() || value.isBoolean() && (id.endsWith("InPlay") || id.endsWith("Moving")))
            result.add("modelChangeValue", value);
        return result;
    }

    private JsonObject announcement(JsonObject source) {
        JsonObject event = scalars(source, "kind", "team", "turn", "half", "reason", "headline", "code", "teamSpecific");
        object(source, event, "scorer", scorer -> scalars(scorer, "name", "number"));
        object(source, event, "weather", weather -> scalars(weather, "code", "headline", "reason"));
        return event;
    }

    private JsonObject coordinates(JsonObject source, JsonObject result) {
        object(source, result, "from", PublicMatchFields::square);
        object(source, result, "to", PublicMatchFields::square);
        return result;
    }
}
