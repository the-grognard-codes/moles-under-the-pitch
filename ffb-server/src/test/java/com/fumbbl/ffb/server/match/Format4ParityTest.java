package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Format4ParityTest {
    @Test
    void browserReducerMatchesTheIndependentSpectatorViewAtEveryRevision(@TempDir Path scratch) throws Exception {
        Path rows = scratch.resolve("format-4-revisions.ndjson");
        captureCompleteMatch(rows);
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("tools"))) root = root.getParent();
        String node = System.getenv().getOrDefault("FFB_COMPUTER_POLICY_NODE", "node");
        Path output = scratch.resolve("format-4-parity.log");
        Process verifier = new ProcessBuilder(node, root.resolve("tools/validation/format-4-parity-verifier.mjs").toString(),
            rows.toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(verifier.waitFor(30, TimeUnit.SECONDS), "Browser parity verification timed out");
            assertEquals(0, verifier.exitValue(), () -> parityOutput(output));
        } finally {
            if (verifier.isAlive()) {
                verifier.destroyForcibly();
                verifier.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private void captureCompleteMatch(Path rows) throws Exception {
        Format4ParityCapture capture = new Format4ParityCapture();
        try (BogrukEngineFixture fixture = new BogrukEngineFixture("Human", "Orc", 43, true, BogrukEngineFixture.Mode.FORMAT4);
            BufferedWriter output = Files.newBufferedWriter(rows)) {
            SetupSession session = fixture.session;
            writeRow(output, capture.commit(session, System.nanoTime()));
            for (int sequence = 1; sequence <= 256 && !session.isComplete(); sequence++) {
                int revision = session.revision();
                String role = session.spectatorView().getString("actor", null);
                JsonObject view = session.reply("parity-offer", "ACCEPTED", false, role).get("state").asObject();
                JsonObject request = BogrukEngineFixture.recordedDecision(fixture.document.matchId, view, "parity-" + sequence, true);
                assertFalse(session.isStagedRequest(request), request::toString);
                long started = System.nanoTime();
                JsonObject response = session.apply(role, request);
                assertEquals("ACCEPTED", response.getString("code", null), request::toString);
                assertEquals(revision + 1, session.revision(), request::toString);
                writeRow(output, capture.commit(session, started));
            }
            assertTrue(session.isComplete(), "The seeded match must reach full time");
        }
    }

    private void writeRow(BufferedWriter output, JsonObject row) throws IOException {
        output.write(row.toString());
        output.newLine();
    }

    private String parityOutput(Path output) {
        try {
            return Files.readString(output);
        } catch (IOException failure) {
            return "Could not read browser parity output: " + failure.getMessage();
        }
    }
}
