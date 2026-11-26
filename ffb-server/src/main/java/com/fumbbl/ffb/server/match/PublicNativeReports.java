package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.Map;
import java.util.Set;

/** Public BB2025 report fields, keyed by the native report ID. */
final class PublicNativeReports {
    private final PublicMatchFields fields = new PublicMatchFields();

    private final Map<String, Set<String>> fieldsByReport = new Format4Json().publicNativeReportFields();

    JsonObject report(JsonObject source) {
        String id = source.getString("reportId", "");
        Set<String> allowed = fieldsByReport.get(id);
        if (allowed == null) return null;
        JsonObject report = new JsonObject().add("reportId", id);
        for (JsonObject.Member member : source) {
            String field = member.getName();
            if (!allowed.contains(field)) continue;
            JsonValue value = member.getValue();
            if (field.equals("knockoutRecoveryArray")) {
                fields.objects(source, report, field, recovery ->
                    fields.scalars(recovery, "playerId", "recovering", "roll", "bloodweiserBabes", "bugmansXXXXXXModifier"));
            } else if (field.equals("heatExhaustionArray")) {
                fields.objects(source, report, field, heat -> fields.scalars(heat, "playerId", "exhausted", "roll"));
            } else if (field.equals("statBasedRollModifier")) {
                fields.object(source, report, field, modifier -> fields.scalars(modifier, "reason", "name", "value"));
            } else if (value instanceof JsonArray entries) {
                report.add(field, fields.values(entries));
            } else if (!value.isObject()) {
                report.add(field, value);
            }
        }
        fields.object(source, report, "logActors", actors -> fields.scalars(actors, "version", "actorId", "targetId"));
        fields.object(source, report, "logTest", test -> fields.scalars(test, "version", "rerolled"));
        fields.object(source, report, "logBlock", block -> fields.scalars(block, "version", "attackerId", "defenderId", "chooser"));
        fields.object(source, report, "logRoll", roll -> {
            JsonObject result = fields.scalars(roll, "version", "base", "target", "modifier");
            fields.object(roll, result, "square", fields::square);
            return result;
        });
        fields.object(source, report, "numericDice", dice -> {
            JsonObject result = fields.scalars(dice, "version");
            fields.objects(dice, result, "dice", die -> fields.scalars(die, "sides", "value", "label"));
            return result;
        });
        return report;
    }
}
