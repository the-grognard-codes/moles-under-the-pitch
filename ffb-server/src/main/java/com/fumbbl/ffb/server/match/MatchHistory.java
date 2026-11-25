package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;

import java.sql.SQLException;
import java.util.List;
import java.util.OptionalLong;

/**
 * Bounded reads from one completed match, opened after the caller's access check.
 * Each read may throw {@link SQLException} if the underlying storage is unavailable.
 */
public interface MatchHistory {
    JsonObject resultMetadata() throws SQLException;

    JsonObject finalState() throws SQLException;

    OptionalLong completedAt() throws SQLException;

    /** Returns the recorded terminal stop reason, if present. */
    MatchStopReason stopReason() throws SQLException;

    /** First recorded play for each half, acting coach and turn, in revision order. */
    List<TurnIndexEntry> turnIndex() throws SQLException;

    /**
     * Returns the closest keyframe at or before a valid target revision.
     * @throws IllegalArgumentException if the revision is outside the history
     */
    Keyframe keyframeAtOrBefore(int revision) throws SQLException;

    /**
     * Legacy wire adapter for the result protocol's single-event reply. Range replay
     * uses keyframes and record pages to rebuild boards in the browser.
     * @throws IllegalArgumentException if the revision is outside the history
     */
    JsonObject replayEvent(int revision) throws SQLException;

    /**
     * Contiguous record page starting at {@code from}, capped at 8 records and 512 KiB of record payload.
     * The end cursor is valid and returns an empty page.
     * @throws MatchService.Failure with REPLAY_UNSUPPORTED for event-only artifacts
     * @throws IllegalArgumentException if the cursor or limit is invalid
     */
    JsonObject recordPage(int from, int limit) throws SQLException;

    record Turn(int half, String actor, int number) { }

    record TurnIndexEntry(int revision, Turn turn) { }

    record Keyframe(int revision, JsonObject state) { }
}
