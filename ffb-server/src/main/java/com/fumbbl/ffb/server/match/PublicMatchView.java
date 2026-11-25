package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import static com.fumbbl.ffb.server.match.PublicMatchFields.object;
import static com.fumbbl.ffb.server.match.PublicMatchFields.objects;
import static com.fumbbl.ffb.server.match.PublicMatchFields.scalars;
import static com.fumbbl.ffb.server.match.PublicMatchFields.values;

/** Adapts existing full snapshots to the shared public view and its legacy wire envelope. */
public final class PublicMatchView {
    public JsonObject live(JsonObject source, String role) {
        JsonObject result = recorded(source, role);
        for (String field : new String[] {"stageSeq", "setupAdjustmentsExhausted"}) {
            JsonValue value = source.get(field);
            if (value != null && (value.isNumber() || value.isBoolean())) result.add(field, value);
        }
        object(source, result, "saveResume", status -> "spectator".equals(role)
            ? scalars(status, "status", "proposer", "expiresAt")
            : scalars(status, "status", "proposer", "expiresAt", "proposalId"));
        object(source, result, "clock", clock -> scalars(clock, "activeRole", "turnElapsedMs", "homeReserveMs", "awayReserveMs"));
        object(source, result, "reaction", reaction -> {
            JsonObject status = scalars(reaction, "actor", "title", "detail", "expiresAt", "serverNow", "allowanceMs");
            if (role.equals(reaction.getString("actor", null)) && reaction.get("id") != null && reaction.get("id").isString())
                status.add("id", reaction.get("id"));
            return status;
        });
        return result;
    }

    JsonObject recorded(JsonObject source, String role) {
        JsonObject result = scalars(source, "matchId", "revision", "phase", "half", "drive", "turn", "homeTurn", "awayTurn", "turnMode",
            "homeScore", "awayScore", "weather", "actor", "decisionKind", "activePlayerId", "activePlayerAction",
            "homeTeamName", "awayTeamName", "homeRerolls", "awayRerolls", "stopReason");
        for (String side : new String[] {"home", "away"}) {
            object(source, result, side + "TeamArt", art -> {
                JsonObject item = scalars(art, "rosterId", "league");
                object(art, item, "colors", colors -> scalars(colors, "version", "primary", "secondary"));
                return item;
            });
            object(source, result, side + "Resources", data -> scalars(data, "apothecaries", "assistantCoaches", "cheerleaders", "bribes"));
        }
        objects(source, result, "players", this::player);
        object(source, result, "ball", PublicMatchFields::square);
        object(source, result, "ballState", data -> scalars(data, "version", "carrierPlayerId", "inPlay", "moving"));
        object(source, result, "passing", this::passing);
        object(source, result, "kickPreview", data -> {
            JsonObject kick = new JsonObject();
            object(data, kick, "deviation", PublicMatchFields::square);
            object(data, kick, "withKick", PublicMatchFields::square);
            return kick;
        });
        object(source, result, "kickoff", data -> {
            JsonObject kickoff = scalars(data, "version", "event", "actor", "stage", "allowed", "completed", "selected");
            values(data, kickoff, "participantIds");
            return kickoff;
        });
        object(source, result, "driveTracker",
            data -> scalars(data, "version", "receivingRole", "phase", "secretWeapons", "knockouts", "event", "eventComplete"));
        object(source, result, "blockContext", this::blockContext);
        // The full-snapshot decoder still expects the legacy envelope and empty control fields.
        if (source.get("projectionVersion") != null && source.get("projectionVersion").isNumber())
            result.add("projectionVersion", source.get("projectionVersion"));
        return result.add("callerRole", role).add("actions", new JsonArray()).add("prompt", JsonValue.NULL);
    }

    JsonObject blockContext(JsonObject source) {
        JsonObject context = scalars(source, "version", "activeTargetPlayerId");
        objects(source, context, "blocks", block -> {
            JsonObject item = scalars(block, "playerId", "targetPlayerId");
            object(block, item, "from", PublicMatchFields::square);
            object(block, item, "to", PublicMatchFields::square);
            objects(block, item, "offensiveAssists", assist -> scalars(assist, "playerId", "x", "y"));
            objects(block, item, "defensiveAssists", assist -> scalars(assist, "playerId", "x", "y"));
            return item;
        });
        return context;
    }

    private JsonObject player(JsonObject source) {
        JsonObject result = scalars(source, "id", "name", "slot", "role", "number", "position", "positionRace", "positionRole",
            "ma", "st", "ag", "pa", "av", "limitedAction", "x", "y", "state", "status", "offPitch");
        values(source, result, "skills");
        values(source, result, "conditions");
        object(source, result, "art", art -> scalars(art, "rosterId", "positionId"));
        object(source, result, "skillMarkers", data -> {
            JsonObject markers = scalars(data, "version");
            objects(data, markers, "entries", entry -> scalars(entry, "id", "category", "origin"));
            values(data, markers, "details");
            return markers;
        });
        return result;
    }

    private JsonObject passing(JsonObject source) {
        JsonObject result = scalars(source, "version", "playerId", "weatherPenalty", "rangeLimited", "action", "hailMary");
        object(source, result, "from", PublicMatchFields::square);
        values(source, result, "ranges");
        if (source.get("targets") instanceof JsonArray targets) {
            JsonArray rows = new JsonArray();
            for (JsonValue row : targets) if (row instanceof JsonArray cells) rows.add(values(cells));
            result.add("targets", rows);
        }
        return result;
    }
}
