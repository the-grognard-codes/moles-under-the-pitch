package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonObject;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class V2MatchAccessTest {
	private final String match = "12345678-1234-1234-1234-123456789abc";
	private final String account = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
	private final MatchMembershipRepository memberships = mock(MatchMembershipRepository.class);
	private final V2PrincipalDirectory directory = mock(V2PrincipalDirectory.class);
	private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
	private final V2MatchAccess access = new V2MatchAccess(memberships, directory, clock);

	@Test void authenticatedViewerCanBrowseAndWatchWithoutConsentButCannotPlay() throws Exception {
		AuthenticatedPrincipal viewer = principal(2000, ApplicationScope.PLAYER, ApplicationScope.SPECTATOR);
		when(directory.reauthorize(viewer)).thenReturn(viewer);
		when(memberships.activeMatches()).thenReturn(Arrays.asList(match));
		when(memberships.isActive(match)).thenReturn(true);
		assertEquals(Arrays.asList(match), access.browse(viewer));
		access.spectatorSnapshot(viewer, match);
		assertThrows(MatchService.Failure.class, () -> access.playerRole(viewer, match));
		when(memberships.find(match, account)).thenReturn(new MatchMembership(match, account, "away"));
		assertEquals("away", access.playerRole(viewer, match));
	}

	@Test void currentRevocationOrRemovedScopeDeniesBeforeAnyMatchRead() throws Exception {
		AuthenticatedPrincipal cached = principal(2000, ApplicationScope.PLAYER, ApplicationScope.SPECTATOR);
		when(directory.reauthorize(cached)).thenReturn(principal(2000, ApplicationScope.PLAYER));
		assertThrows(MatchService.Failure.class, () -> access.browse(cached));
		when(directory.reauthorize(cached)).thenThrow(new V2PrincipalAuthenticator.Rejected());
		assertThrows(MatchService.Failure.class, () -> access.playerRole(cached, match));
		verifyNoInteractions(memberships);
	}

	@Test void expiredAndMissingIdentityDenyBeforeDirectoryAndMatchReads() {
		assertThrows(MatchService.Failure.class, () -> access.browse(null));
		assertThrows(MatchService.Failure.class, () -> access.browse(principal(1000, ApplicationScope.SPECTATOR)));
		verifyNoInteractions(directory, memberships);
	}

	@Test void currentMatchesUseOnlyTheReauthorizedAccountAndRequirePlayerScope() throws Exception {
		AuthenticatedPrincipal player = principal(2000, ApplicationScope.PLAYER);
		when(directory.reauthorize(player)).thenReturn(player);
		MatchMembership membership = new MatchMembership(match, account, "home");
		when(memberships.unfinishedMatches(account, null)).thenReturn(Arrays.asList(membership));
		assertEquals(Arrays.asList(membership), access.currentMatches(player, null));
		when(directory.reauthorize(player)).thenReturn(principal(2000, ApplicationScope.SPECTATOR));
		assertThrows(MatchService.Failure.class, () -> access.currentMatches(player, match));
	}

	@Test void currentMatchesDenyExpiredIdentityBeforeReadingOwnedInventory() {
		assertThrows(MatchService.Failure.class, () -> access.currentMatches(null, null));
		assertThrows(MatchService.Failure.class, () -> access.currentMatches(principal(1000, ApplicationScope.PLAYER), null));
		verifyNoInteractions(directory, memberships);
	}

	@Test void inactiveAndCopiedReferenceMatchesStayUnavailable() throws Exception {
		AuthenticatedPrincipal viewer = principal(2000, ApplicationScope.SPECTATOR);
		when(directory.reauthorize(viewer)).thenReturn(viewer);
		assertThrows(MatchService.Failure.class, () -> access.spectatorSnapshot(viewer, match));
	}

	@Test void registeredCompletedMatchKeepsPublicTranscriptAccessWithoutLiveWatchAccess() throws Exception {
		AuthenticatedPrincipal viewer = principal(2000, ApplicationScope.SPECTATOR);
		when(directory.reauthorize(viewer)).thenReturn(viewer);
		when(memberships.hasMatch(match)).thenReturn(true);
		access.spectatorTranscript(viewer, match);
		assertThrows(MatchService.Failure.class, () -> access.spectatorSnapshot(viewer, match));
	}

	@Test void acknowledgementRequiresMembershipAndChecksExpiryOnlyUntilFirstSuccess() throws Exception {
		AuthenticatedPrincipal player = principal(2000, ApplicationScope.PLAYER);
		when(directory.reauthorize(player)).thenReturn(player);
		SetupApplication setup = mock(SetupApplication.class);
		when(memberships.acknowledged(match, account)).thenReturn(null, true, false);
		assertEquals("NOT_FOUND", assertThrows(MatchService.Failure.class, () -> access.acknowledgeExpired(player, match, setup)).code);
		access.acknowledgeExpired(player, match, setup);
		verifyNoInteractions(setup);
		MatchDocument doc = new BogrukEngineFixture("human", "orc", 42).document;
		Map<String, MatchDocument> documents = Map.of(match, doc);
		when(setup.browseDocuments(eq(List.of(match)), any())).thenReturn(documents);
		when(setup.currentMatchStates(eq(List.of(match)), eq(documents), eq(1000L), any()))
			.thenReturn(Map.of(match, new JsonObject()), Map.of(match, new JsonObject().add("expired", true)));
		assertEquals("MATCH_NOT_EXPIRED", assertThrows(MatchService.Failure.class, () -> access.acknowledgeExpired(player, match, setup)).code);
		access.acknowledgeExpired(player, match, setup);
		verify(memberships).acknowledge(match, account, 1000);
		doThrow(new MatchMembershipRepository.OutcomeUnknown(new SQLException())).when(memberships).acknowledge(match, account, 1000);
		assertEquals("ACKNOWLEDGEMENT_OUTCOME_UNKNOWN", assertThrows(MatchService.Failure.class, () -> access.acknowledgeExpired(player, match, setup)).code);
		doThrow(new SQLException("failed")).when(memberships).acknowledge(match, account, 1000);
		assertThrows(SQLException.class, () -> access.acknowledgeExpired(player, match, setup));
	}

	private AuthenticatedPrincipal principal(long expiry, ApplicationScope... scopes) {
		return new AuthenticatedPrincipal(account, EnumSet.copyOf(Arrays.asList(scopes)), expiry);
	}
}
