package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.function.Function;

/** Field selection for the legacy JSON read adapters. */
final class PublicMatchFields {
    JsonObject scalars(JsonObject source, String... fields) {
        JsonObject result = new JsonObject();
        for (String field : fields) {
            JsonValue value = source.get(field);
            if (value != null && !value.isObject() && !value.isArray()) result.add(field, value);
        }
        return result;
    }

    void object(JsonObject source, JsonObject result, String field, Function<JsonObject, JsonObject> project) {
        JsonValue value = source.get(field);
        if (value == null) return;
        if (value.isObject()) result.add(field, project.apply(value.asObject()));
        else if (value.isNull()) result.add(field, JsonValue.NULL);
    }

    void objects(JsonObject source, JsonObject result, String field, Function<JsonObject, JsonObject> project) {
        if (!(source.get(field) instanceof JsonArray values)) return;
        JsonArray projected = new JsonArray();
        for (JsonValue value : values) if (value.isObject()) {
            JsonObject item = project.apply(value.asObject());
            if (item != null) projected.add(item);
        }
        result.add(field, projected);
    }

    void values(JsonObject source, JsonObject result, String field) {
        if (source.get(field) instanceof JsonArray array) result.add(field, values(array));
    }

    JsonArray values(JsonArray array) {
        JsonArray result = new JsonArray();
        for (JsonValue value : array) if (!value.isArray() && !value.isObject()) result.add(value);
        return result;
    }

    JsonObject square(JsonObject value) { return scalars(value, "x", "y"); }
}
