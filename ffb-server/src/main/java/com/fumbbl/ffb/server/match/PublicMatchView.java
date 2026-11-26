package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

/** Adapts existing full snapshots to the shared public view and its legacy wire envelope. */
public final class PublicMatchView {
    private final PublicMatchFields fields = new PublicMatchFields();

    public JsonObject live(JsonObject source, String role) {
        JsonObject result = recorded(source, role);
        for (String field : new String[] {"stageSeq", "setupAdjustmentsExhausted"}) {
            JsonValue value = source.get(field);
            if (value != null && (value.isNumber() || value.isBoolean())) result.add(field, value);
        }
        fields.object(source, result, "saveResume", status -> "spectator".equals(role)
            ? fields.scalars(status, "status", "proposer", "expiresAt")
            : fields.scalars(status, "status", "proposer", "expiresAt", "proposalId"));
        fields.object(source, result, "clock", clock -> fields.scalars(clock, "activeRole", "turnElapsedMs", "homeReserveMs", "awayReserveMs"));
        fields.object(source, result, "reaction", reaction -> {
            JsonObject status = fields.scalars(reaction, "actor", "title", "detail", "expiresAt", "serverNow", "allowanceMs");
            if (role.equals(reaction.getString("actor", null)) && reaction.get("id") != null && reaction.get("id").isString())
                status.add("id", reaction.get("id"));
            return status;
        });
        return result;
    }

    JsonObject recorded(JsonObject source, String role) {
        JsonObject result = recordedFacts(source);
        // The full-snapshot decoder still expects the legacy envelope and empty control fields.
        if (source.get("projectionVersion") != null && source.get("projectionVersion").isNumber())
            result.add("projectionVersion", source.get("projectionVersion"));
        return result.add("callerRole", role).add("actions", new JsonArray()).add("prompt", JsonValue.NULL);
    }

    JsonObject recordedFacts(JsonObject source) {
        JsonObject result = fields.scalars(source, "matchId", "revision", "phase", "half", "drive", "turn", "homeTurn", "awayTurn", "turnMode",
            "homeScore", "awayScore", "weather", "actor", "decisionKind", "activePlayerId", "activePlayerAction",
            "homeTeamName", "awayTeamName", "homeRerolls", "awayRerolls", "stopReason");
        for (String side : new String[] {"home", "away"}) {
            fields.object(source, result, side + "TeamArt", art -> {
                JsonObject item = fields.scalars(art, "rosterId", "league");
                fields.object(art, item, "colors", colors -> fields.scalars(colors, "version", "primary", "secondary"));
                return item;
            });
            fields.object(source, result, side + "Resources", data -> fields.scalars(data, "apothecaries", "assistantCoaches", "cheerleaders", "bribes"));
        }
        fields.objects(source, result, "players", this::player);
        fields.object(source, result, "ball", fields::square);
        fields.object(source, result, "ballState", data -> fields.scalars(data, "version", "carrierPlayerId", "inPlay", "moving"));
        fields.object(source, result, "passing", this::passing);
        fields.object(source, result, "kickPreview", data -> {
            JsonObject kick = new JsonObject();
            fields.object(data, kick, "deviation", fields::square);
            fields.object(data, kick, "withKick", fields::square);
            return kick;
        });
        fields.object(source, result, "kickoff", data -> {
            JsonObject kickoff = fields.scalars(data, "version", "event", "actor", "stage", "allowed", "completed", "selected");
            fields.values(data, kickoff, "participantIds");
            return kickoff;
        });
        fields.object(source, result, "driveTracker",
            data -> fields.scalars(data, "version", "receivingRole", "phase", "secretWeapons", "knockouts", "event", "eventComplete"));
        fields.object(source, result, "blockContext", this::blockContext);
        return result;
    }

    JsonObject blockContext(JsonObject source) {
        JsonObject context = fields.scalars(source, "version", "activeTargetPlayerId");
        fields.objects(source, context, "blocks", block -> {
            JsonObject item = fields.scalars(block, "playerId", "targetPlayerId");
            fields.object(block, item, "from", fields::square);
            fields.object(block, item, "to", fields::square);
            fields.objects(block, item, "offensiveAssists", assist -> fields.scalars(assist, "playerId", "x", "y"));
            fields.objects(block, item, "defensiveAssists", assist -> fields.scalars(assist, "playerId", "x", "y"));
            return item;
        });
        return context;
    }

    private JsonObject player(JsonObject source) {
        JsonObject result = fields.scalars(source, "id", "name", "slot", "role", "number", "position", "positionRace", "positionRole",
            "ma", "st", "ag", "pa", "av", "limitedAction", "x", "y", "state", "status", "offPitch");
        fields.values(source, result, "skills");
        fields.values(source, result, "conditions");
        fields.object(source, result, "art", art -> fields.scalars(art, "rosterId", "positionId"));
        fields.object(source, result, "skillMarkers", data -> {
            JsonObject markers = fields.scalars(data, "version");
            fields.objects(data, markers, "entries", entry -> fields.scalars(entry, "id", "category", "origin"));
            fields.values(data, markers, "details");
            return markers;
        });
        return result;
    }

    private JsonObject passing(JsonObject source) {
        JsonObject result = fields.scalars(source, "version", "playerId", "weatherPenalty", "rangeLimited", "action", "hailMary");
        fields.object(source, result, "from", fields::square);
        fields.values(source, result, "ranges");
        if (source.get("targets") instanceof JsonArray targets) {
            JsonArray rows = new JsonArray();
            for (JsonValue row : targets) if (row instanceof JsonArray cells) rows.add(fields.values(cells));
            result.add("targets", rows);
        }
        return result;
    }
}
