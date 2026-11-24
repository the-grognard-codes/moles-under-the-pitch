package com.fumbbl.ffb.server.match;

import java.sql.SQLException;

/** Marker-6 membership store. Existing marker-5 fixture memberships are never inferred or rewritten. */
public interface MatchMembershipRepository {
	final class OutcomeUnknown extends SQLException {
		public OutcomeUnknown(SQLException cause) { super("Membership write outcome unknown; retry the same membership", cause); }
	}
	MatchMembership find(String matchId, String accountId) throws SQLException;
	/** Returns null for a missing membership. */
	default Boolean acknowledged(String matchId, String accountId) throws SQLException {
		return find(matchId, accountId) == null ? null : Boolean.FALSE;
	}
	default void acknowledge(String matchId, String accountId, long now) throws SQLException {
		throw new SQLException("Membership acknowledgement storage unavailable");
	}
	boolean hasMatch(String matchId) throws SQLException;
	void insert(MatchMembership membership) throws SQLException;
	default String accountForRole(String matchId, String role) throws SQLException { return null; }
	default java.util.List<String> activeMatches() throws SQLException { return java.util.Collections.emptyList(); }
	default java.util.List<MatchMembership> unfinishedMatches(String accountId, String afterMatchId) throws SQLException {
		return java.util.Collections.emptyList();
	}
	default boolean isActive(String matchId) throws SQLException { return activeMatches().contains(matchId); }
}
