package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.ArrayList;
import java.util.List;

final class RecordedMatchAnnouncements {
    private RecordedMatchAnnouncements() { }

    static List<JsonObject> events(JsonArray outcomes) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonValue value : outcomes) {
            JsonValue announcements = value.asObject().get("matchAnnouncements");
            if (announcements == null) continue;
            for (JsonValue event : announcements.asObject().get("events").asArray()) result.add(event.asObject());
        }
        return result;
    }
}
