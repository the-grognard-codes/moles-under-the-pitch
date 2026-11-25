package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StateSyncProjectionTest {
	@Test void preservesNullRemovalAndPlayerOrderWithoutProjectingPrivateActions() {
		StateSyncProjection projection = new StateSyncProjection();
		JsonObject previous = new JsonObject().add("revision", 50).add("weather", "rain").add("ball", 1)
			.add("players", new JsonArray().add(player("a", 1)).add(player("b", 1)));
		JsonObject next = new JsonObject().add("revision", 51).add("ball", JsonValue.NULL)
			.add("players", new JsonArray().add(player("c", 2)).add(player("a", 3)))
			.add("actions", new JsonArray().add("private"))
			.add("prompt", new JsonObject().add("actor", "away").add("kind", "kick").add("options", new JsonArray()));
		JsonObject publicView = projection.project(next);
		assertNull(publicView.get("actions"));
		assertEquals(new JsonObject().add("kind", "kick").add("actor", "away"), publicView.get("decision"));
		JsonObject patch = projection.patch(projection.project(previous), publicView);
		assertEquals(JsonValue.NULL, patch.get("set").asObject().get("ball"));
		assertEquals(new JsonArray().add("weather"), patch.get("remove"));
		assertEquals(new JsonArray().add("b"), patch.get("removePlayers"));
		assertEquals(new JsonArray().add("c").add("a"), patch.get("order"));
		assertEquals(next.get("players"), patch.get("players"));
	}

	private JsonObject player(String id, int x) { return new JsonObject().add("id", id).add("x", x); }

	@Test void publicEffectsKeepRollsAndMovementWithoutLeakingPrivateReportFields() {
		JsonObject source = new JsonObject()
			.add("animation", new JsonObject().add("animationType", "move").add("private", "fixture"))
			.add("reportList", new JsonObject().add("reports", new JsonArray()
				.add(new JsonObject().add("reportId", "dodge").add("roll", 4).add("private", "fixture"))));
		JsonObject effect = new StateSyncProjection().effects(new JsonArray().add(source)).get(0).asObject();
		assertEquals(new JsonObject().add("animationType", "move"), effect.get("animation"));
		assertEquals(new JsonArray().add(new JsonObject().add("roll", 4).add("reportId", "dodge")), effect.get("dice"));
	}
}
