package com.fumbbl.ffb.server.match;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcMatchMembershipRepositoryTest {
	private static final String MATCH = "12345678-1234-1234-1234-123456789abc";
	private static final String HOME = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
	private Connection connection;
	private PreparedStatement statement;
	private JdbcMatchMembershipRepository repository;

	@BeforeEach void setup() throws Exception {
		connection = mock(Connection.class); statement = mock(PreparedStatement.class);
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		repository = new JdbcMatchMembershipRepository(() -> connection);
	}

	@Test void insertsAccountAssociationWithServerAssignedRole() throws Exception {
		when(statement.executeUpdate()).thenReturn(1);
		assertDoesNotThrow(() -> repository.insert(new MatchMembership(MATCH, HOME, "home")));
		verify(statement).setString(1, MATCH); verify(statement).setString(2, HOME); verify(statement).setString(3, "home");
	}

	@Test void failureBeforeAcknowledgementCommitHasADefiniteOutcome() throws Exception {
		when(connection.getAutoCommit()).thenReturn(true);
		SQLException denied = new SQLException("Update refused", "42000");
		when(statement.executeUpdate()).thenThrow(denied);
		assertSame(denied, assertThrows(SQLException.class, () -> repository.acknowledge(MATCH, HOME, 123)));
		verify(connection).setAutoCommit(false);
	}

	@Test void timeoutDuringAcknowledgementCommitIsUncertain() throws Exception {
		when(statement.executeUpdate()).thenReturn(1);
		doThrow(new SQLTimeoutException()).when(connection).commit();
		assertThrows(MatchMembershipRepository.OutcomeUnknown.class, () -> repository.acknowledge(MATCH, HOME, 123));
	}

	@Test void pagesOwnedUnfinishedMembershipsWithoutDependingOnConnections() throws Exception {
		ResultSet rows = mock(ResultSet.class);
		when(statement.executeQuery()).thenReturn(rows);
		when(rows.next()).thenReturn(true, false);
		when(rows.getString(1)).thenReturn(MATCH);
		when(rows.getString(2)).thenReturn("away");
		MatchMembership entry = repository.unfinishedMatches(HOME, null).get(0);
		assertEquals(MATCH, entry.matchId); assertEquals(HOME, entry.accountId); assertEquals("away", entry.role);
		verify(statement).setString(1, HOME); verify(statement).setString(2, "");
	}
}
