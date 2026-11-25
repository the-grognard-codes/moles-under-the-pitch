package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.TurnMode;
import com.fumbbl.ffb.server.GameState;

import com.eclipsesource.json.JsonObject;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCopyWorkloadTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "PREVIEW_COPY_WORKLOAD", matches = "true")
    void measureCopyAtRevision50AndBeforeFullTime() throws Exception {
        PreviewCopyFixture fixture = new PreviewCopyFixture();
        SetupSession session = fixture.match.session;
        JsonObject view = fixture.turn;
        JsonObject early = null, late = null;
        int lateRevision = -1;
        for (int sequence = 0; sequence < 1_600 && !session.isComplete(); sequence++) {
            int revision = session.revision();
            if (revision == 50) early = fixture.state.toJsonValue(true, 0);
            if (view.getInt("half", 0) == 2 && view.getInt("homeTurn", 0) >= 7 && view.getInt("awayTurn", 0) >= 7) {
                late = fixture.state.toJsonValue(true, 0);
                lateRevision = revision;
            }
            JsonObject request = BogrukEngineFixture.recordedDecision(fixture.match.document.matchId,
                view, "probe-" + sequence, sequence % 24 == 0);
            JsonObject reply = session.apply(view.getString("actor", null), request);
            assertEquals("ACCEPTED", reply.getString("code", null), request::toString);
            view = reply.get("state").asObject();
        }
        assertTrue(session.isComplete(), "The probe must reach full time");
        assertNotNull(early);
        assertNotNull(late);
        assertEquals(session.revision() - 1, lateRevision);
        assertEquals(378, lateRevision, "Use the same full-match workload in each run");
        measure(fixture.restore(early), 50, 143);
        measure(fixture.restore(late), lateRevision, 918);
    }

    private static void measure(GameState live, int revision, int expectedCommands) throws ReflectiveOperationException {
        int commands = live.getGameLog().size();
        assertEquals(expectedCommands, commands);
        assertNotEquals(TurnMode.END_GAME, live.getGame().getTurnMode());
        BlockPreviewPresentation preview = new BlockPreviewPresentation(live);
        Method method = PreviewCopyFixture.blockCopy();
        ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        int samples = 120;
        for (int i = 0; i < samples; i++) method.invoke(preview);
        long[] nanos = new long[samples];
        long[] bytes = new long[samples];
        for (int i = 0; i < samples; i++) {
            long allocated = allocations.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            GameState copy = (GameState) method.invoke(preview);
            nanos[i] = System.nanoTime() - started;
            bytes[i] = allocations.getThreadAllocatedBytes(thread) - allocated;
            assertEquals(22, copy.getGame().getPlayers().length);
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        System.out.printf("PREVIEW_COPY revision=%d commands=%d medianNanos=%d medianBytes=%d p95Nanos=%d%n",
            revision, commands, nanos[samples / 2], bytes[samples / 2], nanos[samples * 95 / 100 - 1]);
    }
}
