package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/** Measurement-only public projection of an existing transcript state. */
final class StateSyncProjection {
	private final String[] sections = {"matchId", "revision", "phase", "actor", "half", "turn", "turnMode",
		"homeTurn", "awayTurn", "homeScore", "awayScore", "homeRerolls", "awayRerolls", "drive",
		"activePlayerId", "activePlayerAction", "ball", "ballState", "weather", "homeResources", "awayResources",
		"kickoff", "driveTracker", "blockContext", "players", "homeTeamName", "awayTeamName", "homeTeamArt", "awayTeamArt"};

	JsonObject project(JsonObject state) {
		JsonObject output = new JsonObject();
		for (String field : sections) if (state.get(field) != null) output.add(field, state.get(field));
		JsonValue prompt = state.get("prompt");
		if (prompt != null && prompt.isObject()) {
			JsonObject decision = new JsonObject();
			for (String key : new String[]{"kind", "actor"}) {
				JsonValue value = prompt.asObject().get(key);
				if (value != null) decision.add(key, value);
			}
			output.add("decision", decision);
		}
		if (state.get("passing") instanceof JsonObject passing) output.add("passing", select(passing, "ranges", "rangeLimited"));
		if (state.get("kickPreview") instanceof JsonObject kick) output.add("kick", select(kick, "deviation", "withKick"));
		return output;
	}

	JsonArray effects(JsonArray outcomes) {
		JsonArray effects = new JsonArray();
		for (JsonValue value : outcomes) {
			JsonObject command = value.asObject(), effect = new JsonObject();
			if (command.get("animation") instanceof JsonObject animation)
				effect.add("animation", select(animation, "animationType", "startCoordinate", "endCoordinate", "withBall"));
			if (command.get("logOutcomes") instanceof JsonObject log && log.get("events") instanceof JsonArray events) {
				JsonArray movement = new JsonArray();
				for (JsonValue event : events) movement.add(select(event.asObject(), "kind", "playerId", "from", "to", "skill"));
				if (!movement.isEmpty()) effect.add("events", movement);
			}
			if (command.get("reportList") instanceof JsonObject list && list.get("reports") instanceof JsonArray reports) {
				JsonArray rolls = new JsonArray();
				for (JsonValue report : reports) {
					JsonObject dice = select(report.asObject(), "roll", "rolls", "numericDice", "blockRoll", "blockResult",
						"armorRoll", "casualtyRoll", "injuryRoll", "directionRoll", "distanceRoll", "kickoffRoll", "weatherRoll");
					if (!dice.isEmpty()) {
						dice.add("reportId", report.asObject().get("reportId"));
						rolls.add(dice);
					}
				}
				if (!rolls.isEmpty()) effect.add("dice", rolls);
			}
			if (!effect.isEmpty()) effects.add(effect);
		}
		return effects;
	}

	private JsonObject select(JsonObject source, String... keys) {
		JsonObject result = new JsonObject();
		for (String key : keys) if (source.get(key) != null) result.add(key, source.get(key));
		return result;
	}

	JsonObject patch(JsonObject previous, JsonObject next) {
		JsonObject set = new JsonObject();
		JsonArray remove = new JsonArray();
		for (String key : previous.names()) if (next.get(key) == null) remove.add(key);
		for (JsonObject.Member field : next) {
			if (!"players".equals(field.getName()) && !field.getValue().equals(previous.get(field.getName())))
				set.add(field.getName(), field.getValue());
		}
		Map<String, JsonValue> before = players(previous), after = players(next);
		JsonArray changed = new JsonArray(), removed = new JsonArray();
		after.forEach((id, player) -> { if (!player.equals(before.get(id))) changed.add(player); });
		before.keySet().forEach(id -> { if (!after.containsKey(id)) removed.add(id); });
		JsonObject patch = new JsonObject().add("base", previous.get("revision")).add("set", set).add("remove", remove)
			.add("players", changed).add("removePlayers", removed);
		if (!before.keySet().stream().toList().equals(after.keySet().stream().toList())) {
			JsonArray order = new JsonArray();
			after.keySet().forEach(order::add);
			patch.add("order", order);
		}
		return patch;
	}

	private Map<String, JsonValue> players(JsonObject state) {
		Map<String, JsonValue> result = new LinkedHashMap<>();
		for (JsonValue player : state.get("players").asArray()) {
			String id = player.asObject().getString("id", null);
			if (id == null || result.put(id, player) != null) throw new IllegalArgumentException("Invalid player ID");
		}
		return result;
	}
}
