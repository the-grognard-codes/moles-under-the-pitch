package com.fumbbl.ffb.test;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.fumbbl.ffb.server.match.SetupSession;
import com.fumbbl.ffb.server.match.MatchService;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipientProjectionTest {
	@Test void nativeHomeAwayAndSpectatorShareOnlyTheExplicitPublicState() throws Exception {
		SetupSession session = new SetupSessionTest().session(11);
		JsonObject home = session.reply("contract", "ACCEPTED", false, "home").get("state").asObject();
		JsonObject away = session.reply("contract", "ACCEPTED", false, "away").get("state").asObject();
		JsonObject spectator = session.spectatorView();
		for (JsonObject view : Arrays.asList(home, away, spectator)) {
			keys(view, "projectionVersion", "matchId", "revision", "callerRole", "phase", "actor", "decisionKind", "prompt", "players", "weather", "homeRerolls", "awayRerolls",
				"actions", "turn", "turnMode", "ball", "activePlayerId", "half", "homeTurn", "awayTurn", "homeScore", "awayScore", "drive",
				"homeTeamName", "awayTeamName", "homeTeamArt", "awayTeamArt", "homeResources", "awayResources", "ballState");
			keys(view.get("ballState").asObject(), "version", "carrierPlayerId", "inPlay", "moving");
			assertEquals(4, view.getInt("projectionVersion", 0));
			assertEquals("Home", view.getString("homeTeamName", null));
			assertEquals("Away", view.getString("awayTeamName", null));
			for (String field : Arrays.asList("homeTeamArt", "awayTeamArt")) {
				keys(view.get(field).asObject(), "rosterId", "league", "colors");
				JsonObject colors = view.get(field).asObject().get("colors").asObject();
				keys(colors, "version", "primary", "secondary");
				assertEquals(1, colors.getInt("version", 0));
				assertEquals("navy", colors.getString("primary", null));
				assertEquals("ivory", colors.getString("secondary", null));
				assertEquals("human", view.get(field).asObject().getString("rosterId", null));
				assertEquals("Old World Classic", view.get(field).asObject().getString("league", null));
			}
			keys(view.get("homeResources").asObject(), "apothecaries", "assistantCoaches", "cheerleaders");
			for (JsonValue value : view.get("players").asArray()) {
				JsonObject player = value.asObject();
				keys(player, "id", "name", "slot", "role", "state", "status", "x", "y", "art", "number", "position", "positionRace", "positionRole", "ma", "st", "ag", "pa", "av", "skills", "skillMarkers", "conditions", "offPitch");
				assertEquals(0, player.get("conditions").asArray().size());
				assertEquals(player.get("x").isNull() ? "reserve" : "pitch", player.getString("offPitch", null));
				assertEquals(6, player.getInt("ma", -1));
				keys(player.get("art").asObject(), "rosterId", "positionId");
				assertEquals("human", player.get("art").asObject().getString("rosterId", null));
				assertEquals("lineman", player.get("art").asObject().getString("positionId", null));
			}
			assertEquals("coin", view.getString("decisionKind", null));
			if (view.getString("actor", "").equals(view.getString("callerRole", null)))
				keys(view.get("prompt").asObject(), "id", "actor", "kind", "options");
			else assertEquals(JsonValue.NULL, view.get("prompt"));
			for (JsonValue value : view.get("actions").asArray()) assertEquals(true, value.asObject().get("sourcePlayerId") != null);
		}
		String actor = home.getString("actor", null);
		JsonObject acting = "home".equals(actor) ? home : away;
		for (String field : spectator.names())
			if (!"callerRole".equals(field) && !"prompt".equals(field)) {
				assertEquals(home.get(field), spectator.get(field), field);
				assertEquals(away.get(field), spectator.get(field), field);
			}
		MatchService.Failure rejected = assertThrows(MatchService.Failure.class, () -> session.apply("home".equals(actor) ? "away" : "home", new JsonObject().add("requestId", "wrong-recipient")
			.add("operation", "choice").add("expectedRevision", home.get("revision")).add("promptId", acting.get("prompt").asObject().get("id")).add("optionId", "heads")));
		assertEquals("WRONG_ACTOR", rejected.code);
		assertEquals(home, session.reply("after", "ACCEPTED", false, "home").get("state"));
	}
	private void keys(JsonObject object, String... fields) {
		assertEquals(new HashSet<>(Arrays.asList(fields)), new HashSet<>(object.names())); assertEquals(fields.length, object.size());
	}
}
