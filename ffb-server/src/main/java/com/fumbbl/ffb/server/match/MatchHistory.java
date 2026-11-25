package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;

import java.util.List;
import java.util.OptionalLong;

/** Bounded reads from one completed match, opened after the caller's access check. */
public interface MatchHistory {
    JsonObject resultMetadata();

    JsonObject finalState();

    OptionalLong completedAt();

    /** Returns the recorded terminal stop reason, if present. */
    MatchStopReason stopReason();

    /** First recorded play for each half, acting coach and turn, in revision order. */
    List<TurnStart> turnIndex();

    /** Returns the closest keyframe at or before a valid target revision. */
    Keyframe keyframeAtOrBefore(int revision);

    /** Single event in the existing result protocol. */
    JsonObject replayEvent(int revision);

    /**
     * Contiguous record page starting at {@code from}, capped at 8 records and 512 KiB of record payload.
     * The end cursor is valid and returns an empty page.
     * @throws MatchService.Failure with REPLAY_UNSUPPORTED for event-only artifacts
     * @throws IllegalArgumentException if the cursor or limit is invalid
     */
    JsonObject recordPage(int from, int limit);

    JsonObject chatPage(int from, int limit);

    MatchChat.Outcome chatDuplicate(String accountId, String requestId, String content);

    record Turn(int half, String actor, int number) { }

    record TurnStart(int revision, Turn turn) { }

    record Keyframe(int revision, JsonObject state) { }
}
