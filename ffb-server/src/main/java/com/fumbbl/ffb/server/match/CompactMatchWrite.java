package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;

/** Immutable handoff from the engine worker to a future compact snapshot store. */
record CompactMatchWrite(String snapshot, Capture capture) {
    enum Result { DURABLE, FAILED, UNKNOWN }

    /** Native outcomes are serialized at capture, before a later command can change referenced state. */
    record Capture(int revision, int lastCapturedCommand, String nativeOutcomes) { }

    /** Storage owns the chat watermark and the digest of the actual persisted records. */
    record History(int recordCount, int chatCount, String digest) {
        History {
            if (recordCount < 1 || chatCount < 0 || chatCount > MatchChat.MAX_MESSAGES || digest == null || !digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid snapshot history boundary");
        }

        History(JsonObject saved) {
            this(saved.get("recordCount").asInt(), saved.get("chatCount").asInt(), saved.get("digest").asString());
            if (saved.size() != 3) throw new IllegalArgumentException("Invalid snapshot history fields");
        }

        JsonObject json() {
            return new JsonObject().add("recordCount", recordCount).add("chatCount", chatCount).add("digest", digest);
        }
    }
}
