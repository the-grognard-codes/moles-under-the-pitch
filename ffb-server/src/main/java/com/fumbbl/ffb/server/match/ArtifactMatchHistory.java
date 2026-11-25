package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/** Reads validated formats 1–3 through the bounded match history interface. */
final class ArtifactMatchHistory implements MatchHistory {
    private static final List<String> METADATA_FIELDS = List.of("awayScore", "catalogVersion", "engineVersion",
        "finalRevision", "formatVersion", "homeScore", "matchId", "presetId", "presetVersion", "ruleset");

    private final JsonObject artifact;
    private final JsonObject transcript;
    private final JsonArray frames;

    ArtifactMatchHistory(CompletedMatch completed) {
        // json() also performs full validation when setup deferred it until after recovery reads.
        artifact = JsonObject.readFrom(completed.json());
        transcript = switch (artifact.getInt("formatVersion", -1)) {
            case 1 -> null;
            case 2, 3 -> artifact.get("transcript").asObject();
            default -> throw new MatchService.Failure("REPLAY_UNSUPPORTED");
        };
        // Formats 2 and 3 validate the duplicate events against these records before any read.
        frames = transcript == null ? artifact.get("events").asArray() : transcript.get("records").asArray();
    }

    @Override
    public JsonObject resultMetadata() {
        JsonObject metadata = new JsonObject();
        for (String field : METADATA_FIELDS) metadata.add(field, artifact.get(field));
        return metadata.add("eventCount", frames.size());
    }

    @Override
    public JsonObject finalState() {
        return keyframeAtOrBefore(frames.size() - 1).state();
    }

    @Override
    public OptionalLong completedAt() {
        return transcript == null ? OptionalLong.empty() : OptionalLong.of(frames.get(frames.size() - 1).asObject().get("at").asLong());
    }

    @Override
    public MatchStopReason stopReason() {
        if (transcript == null) return null;
        JsonValue decision = frames.get(frames.size() - 1).asObject().get("decision");
        return MatchTranscript.isSizeLimitDecision(decision) ? MatchStopReason.MATCH_SIZE_LIMIT : null;
    }

    @Override
    public List<TurnIndexEntry> turnIndex() {
        if (transcript == null) return List.of();
        // Same first occurrence per half/coach/turn as the current result page. The
        // validated match state has two halves and turns 0–8, so at most 36 stops.
        List<TurnIndexEntry> entries = new ArrayList<>();
        Set<Turn> seen = new HashSet<>();
        for (int revision = 1; revision < frames.size(); revision++) {
            JsonObject record = frames.get(revision).asObject();
            String actor = record.get("actor").asString();
            JsonObject board = record.get("state").asObject();
            if ("system".equals(actor) || !"PLAY".equals(board.get("phase").asString())) continue;
            int turn = board.get("home".equals(actor) ? "homeTurn" : "awayTurn").asInt();
            Turn key = new Turn(board.get("half").asInt(), actor, turn);
            if (seen.add(key)) entries.add(new TurnIndexEntry(revision, key));
        }
        return List.copyOf(entries);
    }

    @Override
    public Keyframe keyframeAtOrBefore(int revision) {
        JsonObject frame = frame(revision);
        // Legacy history stores full boards at every revision. Detach the one
        // returned board because callers can add role-specific response fields.
        return new Keyframe(revision, JsonObject.readFrom(frame.get("state").toString()));
    }

    @Override
    public JsonObject replayEvent(int revision) {
        return new JsonObject().add("kind", frame(revision).get("kind"))
            .add("revision", revision).add("state", keyframeAtOrBefore(revision).state());
    }

    @Override
    public JsonObject recordPage(int from, int limit) {
        if (transcript == null) throw new MatchService.Failure("REPLAY_UNSUPPORTED");
        return new MatchTranscript(transcript).page(from, limit);
    }

    private JsonObject frame(int revision) {
        if (revision < 0 || revision >= frames.size()) throw new IllegalArgumentException("Invalid replay revision");
        return frames.get(revision).asObject();
    }

}
