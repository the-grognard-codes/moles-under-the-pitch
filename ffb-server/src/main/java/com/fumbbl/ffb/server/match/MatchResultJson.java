package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;

/** Participant-only result and single-event reads. No replay import or rules execution. */
public final class MatchResultJson {
    public JsonObject handle(MatchService matches, String owner, JsonObject request) {
        String id = request.getString("requestId", null);
        try {
            if (owner == null) throw new MatchService.Failure("AUTHENTICATION_REQUIRED");
            if (matches == null) throw new MatchService.Failure("PERSISTENCE_FAILED");
            boolean replay = "replay".equals(request.getString("operation", null));
            String[] fields = replay
                ? new String[] { "version", "type", "requestId", "operation", "matchId", "index" }
                : new String[] { "version", "type", "requestId", "operation", "matchId" };
            if (request.size() != fields.length || !new HashSet<>(request.names()).equals(new HashSet<>(Arrays.asList(fields)))
                || request.getInt("version", -1) != 1 || !"matchResult".equals(request.getString("type", null))
                || id == null || !id.matches("[A-Za-z0-9_-]{1,100}")
                || (!replay && !"load".equals(request.getString("operation", null)))) throw new IllegalArgumentException();
            MatchHistory history = matches.resultHistory(owner, request.get("matchId").asString());
            JsonValue event = JsonValue.NULL;
            if (replay) {
                JsonObject entry = history.replayEvent(request.get("index").asInt());
                event = new PublicMatchFields().scalars(entry, "revision", "kind")
                    .add("state", new PublicMatchView().recorded(entry.get("state").asObject(), "home"));
            }
            return response(id, "ACCEPTED", history.resultMetadata(), event);
        } catch (MatchService.Failure failure) { return response(id, failure.code, JsonValue.NULL, JsonValue.NULL); }
        catch (SQLException failure) {
            new MatchPersistenceLog().rejected("result-load", request.getString("matchId", null), id, failure);
            return response(id, "PERSISTENCE_FAILED", JsonValue.NULL, JsonValue.NULL);
        }
        catch (RuntimeException failure) { return response(id, "INVALID_REQUEST", JsonValue.NULL, JsonValue.NULL); }
    }

    private JsonObject response(String id, String code, JsonValue result, JsonValue event) {
        return new JsonObject().add("version", 1).add("type", "matchResult").add("requestId", id)
            .add("code", code).add("result", result).add("event", event);
    }
}
