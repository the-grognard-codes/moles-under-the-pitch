package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;

/** Reads chat and reconciles chat retries against the legacy completed artifact. */
final class CompletedMatchChat {
    private final MatchChat chat;

    /** @throws MatchService.Failure with CHAT_UNAVAILABLE if the artifact predates chat */
    CompletedMatchChat(CompletedMatch completion) {
        JsonObject artifact = JsonObject.readFrom(completion.json());
        if (artifact.getInt("formatVersion", -1) != 3) throw new MatchService.Failure("CHAT_UNAVAILABLE");
        chat = new MatchChat(artifact.get("chat").asObject());
    }

    /** @throws MatchService.Failure with INVALID_REQUEST if the cursor or limit is invalid */
    JsonObject page(int from, int limit) {
        return chat.page(from, limit);
    }

    /**
     * @throws MatchService.Failure with MATCH_COMPLETED for a new request, or REQUEST_ID_REUSED
     *     if the original request had different content
     * @throws IllegalArgumentException if the account, request ID or content is invalid
     */
    MatchChat.Outcome duplicate(String accountId, String requestId, String content) {
        return chat.duplicate(accountId, requestId, content);
    }
}
