package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockingDetails;

class TrainingEngineHostTest {

	@Test
	void simulationMocksDoNotRetainCallsButOrdinaryFixturesRemainVerifiable() throws Exception {
		try (BogrukEngineFixture fixture = new BogrukEngineFixture("Human", "Orc", 43, true)) {
			for (int index = 0; index < 20; index++) fixture.reply("home");
			assertTrue(mockingDetails(fixture.server.getDebugLog()).getInvocations().isEmpty());
			assertTrue(mockingDetails(fixture.server.getCommunication()).getInvocations().isEmpty());
			assertTrue(mockingDetails(fixture.server).getInvocations().isEmpty());
		}
		try (BogrukEngineFixture fixture = new BogrukEngineFixture("Human", "Orc", 43)) {
			fixture.reply("home");
			assertTrue(!mockingDetails(fixture.server).getInvocations().isEmpty());
		}
	}

	@Test
	void isolatesFailuresAndReusesCapacityAfterClosingMatches() throws Exception {
		String commands = """
			{"id":"1","match":"bad","operation":"create","homeRoster":"Unsupported"}
			{"id":"2","match":"a","operation":"create","seed":42}
			{"id":"3","match":"b","operation":"create","seed":43}
			{"id":"4","match":"a","operation":"close"}
			{"id":"5","match":"b","operation":"create","seed":43}
			{"id":"6","match":"b","operation":"load"}
			""";
		InputStream previousIn = System.in;
		PrintStream previousOut = System.out;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			System.setIn(new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)));
			System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
			TrainingEngineHost.main(new String[] { "1" });
		} finally {
			System.setIn(previousIn);
			System.setOut(previousOut);
		}
		String[] lines = bytes.toString(StandardCharsets.UTF_8).strip().split("\\R");
		assertEquals(6, lines.length);
		String[] codes = { "HARNESS_FAILURE", "ACCEPTED", "HARNESS_FAILURE", "ACCEPTED", "ACCEPTED", "ACCEPTED" };
		for (int index = 0; index < lines.length; index++) {
			JsonObject response = JsonObject.readFrom(lines[index]);
			assertEquals(Integer.toString(index + 1), response.getString("id", null));
			assertEquals(codes[index], response.get("response").asObject().getString("code", null));
		}
	}

	@Test
	void captureWritesEachTerminalArtifactOnce(@TempDir Path directory) throws Exception {
		try (BogrukEngineFixture fixture = new BogrukEngineFixture("Human", "Orc", 43, true, true)) {
			StateSyncFixtureCapture capture = new StateSyncFixtureCapture(directory);
			capture.start(fixture.session);
			fixture.session.endForSizeLimit(System.currentTimeMillis());
			capture.advance(fixture.session, fixture.document.matchId);
			JsonObject late = JsonObject.readFrom(Files.readString(directory.resolve("late.json"))).get("payload").asObject();
			JsonObject terminal = JsonObject.readFrom(Files.readString(directory.resolve("terminal.json"))).get("payload").asObject();
			assertEquals(0, late.getInt("revision", -1));
			assertEquals(1, terminal.getInt("revision", -1));
			assertTrue(terminal.getBoolean("pendingTerminal", false));
			assertEquals("[]", Files.readString(directory.resolve("move-timing.json")));
		}
	}
}
