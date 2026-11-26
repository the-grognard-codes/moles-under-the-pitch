package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;

/** Captures a durable revision and an independent spectator projection for browser parity checks. */
final class Format4ParityCapture {
    private final Format4Json codec = new Format4Json();

    JsonObject commit(SetupSession session, long workerStart) {
        CompactMatchWrite.Capture capture = session.compactCapture();
        CompactMatchWrite.History history = new CompactMatchWrite.History(session.revision() + 1, 0, "0".repeat(64));
        CompactMatchWrite.Result result = session.writeCompactSnapshot(history, candidate -> CompactMatchWrite.Result.DURABLE,
            () -> { throw new IllegalStateException("Format-4 parity write failed"); });
        if (result != CompactMatchWrite.Result.DURABLE) throw new IllegalStateException("Format-4 parity write was not durable");
        long workerNanos = System.nanoTime() - workerStart;

        codec.readRecord(capture.record());
        if (capture.keyframe() != null) codec.readKeyframe(capture.keyframe());
        return new JsonObject().add("record", capture.record()).add("keyframe", capture.keyframe())
            .add("expected", session.spectatorView()).add("workerNanos", workerNanos);
    }
}
