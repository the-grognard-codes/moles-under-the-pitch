package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

/** Adapts stored transcript records until checkpoints carry the drive summary. */
final class DriveTrackerTranscriptRestore {
    void restore(DriveTrackerPresentation tracker, JsonArray records) {
        for (JsonValue value : records) {
            JsonObject record = value.asObject();
            JsonObject state = record.get("state").asObject();
            tracker.record(state.getInt("drive", -1), state.getString("phase", null), state.getString("actor", null),
                record.get("native").asArray());
        }
    }
}
