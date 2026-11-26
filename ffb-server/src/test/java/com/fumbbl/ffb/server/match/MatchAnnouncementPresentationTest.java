package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.BlockDiceCategory;
import com.fumbbl.ffb.DiceCategory;
import com.fumbbl.ffb.FactoryType;
import com.fumbbl.ffb.FieldCoordinate;
import com.fumbbl.ffb.PlayerAction;
import com.fumbbl.ffb.PlayerState;
import com.fumbbl.ffb.TurnMode;
import com.fumbbl.ffb.Weather;
import com.fumbbl.ffb.dialog.DialogSkillUseParameter;
import com.fumbbl.ffb.factory.InducementTypeFactory;
import com.fumbbl.ffb.inducement.Inducement;
import com.fumbbl.ffb.kickoff.bb2025.KickoffResult;
import com.fumbbl.ffb.mechanics.PassResult;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.Player;
import com.fumbbl.ffb.model.RosterPlayer;
import com.fumbbl.ffb.model.Team;
import com.fumbbl.ffb.model.TurnData;
import com.fumbbl.ffb.option.GameOptionBoolean;
import com.fumbbl.ffb.option.GameOptionId;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.factory.SequenceGeneratorFactory;
import com.fumbbl.ffb.server.step.StepId;
import com.fumbbl.ffb.server.step.StepParameter;
import com.fumbbl.ffb.server.step.StepParameterKey;
import com.fumbbl.ffb.server.step.generator.SequenceGenerator;
import com.fumbbl.ffb.server.step.generator.ThrowTeamMate;
import com.fumbbl.ffb.util.UtilBox;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import static com.fumbbl.ffb.server.match.RecordedMatchAnnouncements.events;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchAnnouncementPresentationTest {
    private static final int STARTING_TEAM_RE_ROLLS = 2;

    private enum Scorer { HOME, AWAY }
    private enum ScoringTurn { OWN, OPPONENTS }
    private enum Boundary { MID_HALF, HALFTIME }
    private enum StarResponse { ACCEPT, DECLINE }

    @Test void acceptingStarAfterOwnTurnScoreKeepsOneTouchdown() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.AWAY, ScoringTurn.OWN, Boundary.MID_HALF, StarResponse.ACCEPT);
    }

    @Test void decliningStarAfterOffTurnScoreKeepsOneTouchdown() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.HOME, ScoringTurn.OPPONENTS, Boundary.MID_HALF, StarResponse.DECLINE);
    }

    @Test void lastTurnScorePrecedesStarAndAdvancesOneDrive() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.AWAY, ScoringTurn.OWN, Boundary.HALFTIME, StarResponse.DECLINE);
    }

    @Test void decliningStarAfterOffTurnScoreAtHalftimeAddsNoReRoll() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.AWAY, ScoringTurn.OPPONENTS, Boundary.HALFTIME, StarResponse.DECLINE);
    }

    @Test void acceptingStarAfterHomeOwnTurnScoreAtHalftimeAddsOneReRoll() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.HOME, ScoringTurn.OWN, Boundary.HALFTIME, StarResponse.ACCEPT);
    }

    @Test void offTurnStarCreditsHome() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.HOME, ScoringTurn.OPPONENTS, Boundary.MID_HALF, StarResponse.ACCEPT);
    }

    @Test void offTurnStarCreditsAway() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.AWAY, ScoringTurn.OPPONENTS, Boundary.MID_HALF, StarResponse.ACCEPT);
    }

    @Test void offTurnLastTurnScorePrecedesStarAndHalftimeFollowsIt() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.HOME, ScoringTurn.OPPONENTS, Boundary.HALFTIME, StarResponse.ACCEPT);
    }

    @Test void offTurnStarCreditsAwayAtHalftime() throws Exception {
        verifyStarAfterRecoveredTouchdown(Scorer.AWAY, ScoringTurn.OPPONENTS, Boundary.HALFTIME, StarResponse.ACCEPT);
    }

    private void verifyStarAfterRecoveredTouchdown(Scorer scoringSide, ScoringTurn scoringTurnOwner,
        Boundary boundary, StarResponse response) throws Exception {
        boolean scorerHome = scoringSide == Scorer.HOME;
        boolean offTurn = scoringTurnOwner == ScoringTurn.OPPONENTS;
        boolean lastTurn = boundary == Boundary.HALFTIME;
        boolean useSkill = response == StarResponse.ACCEPT;
        SetupSessionMovementTest.Fixture fixture = fixture(STARTING_TEAM_RE_ROLLS);
        JsonObject ready = advanceToFirstTurn(fixture, scorerHome != offTurn);
        Game game = state(fixture.session).getGame();
        assertStarReRolls(game, scorerHome, STARTING_TEAM_RE_ROLLS, 0);
        Team endingTeam = game.getActingTeam();
        Team scoringTeam = offTurn ? game.getOtherTeam(endingTeam) : endingTeam;
        Player<?> scorer = scoringTeam.getPlayers()[0];
        Player<?> endingPlayer = endingTeam.getPlayers()[1];
        int previousTurnsPlayed = game.getGameResult().getPlayerResult(endingPlayer).getTurnsPlayed();
        String scoringRole = scorerHome ? "home" : "away";
        var starOfTheShow = game.getRules().getSkillFactory().forName("Star of the Show");
        ((RosterPlayer) scorer).addSkill(starOfTheShow);
        prepareScorer(game, scorer);
        if (lastTurn) {
            game.getTurnDataHome().setTurnNr(scorerHome && offTurn ? 7 : 8);
            game.getTurnDataAway().setTurnNr(!scorerHome && offTurn ? 7 : 8);
            // Spend a re-roll on each side so the assertion requires a halftime refill before the award.
            game.getTurnDataHome().setReRolls(STARTING_TEAM_RE_ROLLS - 1);
            game.getTurnDataAway().setReRolls(STARTING_TEAM_RE_ROLLS - 1);
        }
        if (offTurn) state(fixture.session).setTurnTimeStarted(System.currentTimeMillis() - 240_000);
        int scoringTurn = (scorerHome ? game.getTurnDataHome() : game.getTurnDataAway()).getTurnNr() + (offTurn ? 1 : 0);
        JsonObject paused = applyAction(fixture, ready, "score-with-star", action(ready, "endTurn").getString("id", null));
        assertEquals(scoringRole, paused.getString("actor", null));
        assertEquals(ready.getInt("drive", -1), paused.getInt("drive", -1));
        assertEquals(scorerHome ? 1 : 0, paused.getInt("homeScore", -1));
        assertEquals(scorerHome ? 0 : 1, paused.getInt("awayScore", -1));
        assertTrue(game.getDialogParameter() instanceof DialogSkillUseParameter);
        assertEquals(scorer.getId(), ((DialogSkillUseParameter) game.getDialogParameter()).getPlayerId());
        if (offTurn) {
            JsonObject clock = fixture.session.decorateSaveResumeState(paused).get("clock").asObject();
            assertEquals(ready.get("actor"), clock.get("activeRole"));
            assertEquals(600_000L, clock.get(scorerHome ? "homeReserveMs" : "awayReserveMs").asLong());
            JsonObject bank = fixture.session.recoveryCheckpointPayload().get("clock").asObject();
            assertEquals(0L, bank.get("homeUsedMs").asLong());
            assertEquals(0L, bank.get("awayUsedMs").asLong());
        }

        int revision = paused.getInt("revision", -1);
        JsonArray scoringOutcomes = recordedNativeOutcomes(fixture.session, revision);
        int announcement = announcementIndex(scoringOutcomes, "touchdown");
        int score = changeIndex(scoringOutcomes, "teamResultSetScore", null);
        int offer = changeIndex(scoringOutcomes, "gameSetDialogParameter", "skillUse");
        assertTrue(score >= 0 && score <= announcement && announcement < offer, scoringOutcomes.toString());
        assertEquals(-1, announcementIndex(scoringOutcomes, "halfTime"));
        JsonArray publicOutcomes = nativeOutcomes(fixture.session, revision);
        assertEquals(announcement, announcementIndex(publicOutcomes, "touchdown"));
        assertEquals(-1, announcementIndex(publicOutcomes, "halfTime"));
        assertEquals(-1, changeIndex(publicOutcomes, "gameSetDialogParameter", "skillUse"));
        assertEquals(-1, changeIndex(publicOutcomes, "teamResultSetScore", null));
        assertEquals("skill", paused.getString("decisionKind", null));
        JsonObject touchdown = events(publicOutcomes).stream()
            .filter(event -> "touchdown".equals(event.getString("kind", null))).findFirst().orElseThrow();
        assertEquals(scoringRole, touchdown.getString("team", null));
        assertEquals(scoringTurn, touchdown.getInt("turn", -1));

        SetupSession resumed = new SetupSession(fixture.server, fixture.document, fixture.session.recoveryArtifact());
        JsonObject recipient = resumed.reply("load", "ACCEPTED", false, scoringRole).get("state").asObject();
        assertEquals(paused.get("revision"), recipient.get("revision"));
        JsonObject request = fixture.request("action", "resolve-star", recipient).add("actionId",
            actionWithSuffix(recipient, "skill:" + useSkill));
        JsonObject after = resumed.apply(scoringRole, request).get("state").asObject();
        assertEquals(ready.getInt("drive", -1) + 1, after.getInt("drive", -1));
        assertEquals(lastTurn ? 2 : 1, after.getInt("half", -1));
        if (lastTurn) assertHalftimeAtSetup(resumed, after);
        Game continued = state(resumed).getGame();
        Player<?> restoredScorer = continued.getPlayerById(scorer.getId());
        assertEquals(useSkill, !restoredScorer.hasUnused(starOfTheShow));
        assertEquals(useSkill, nativeOutcomes(resumed, after.getInt("revision", -1)).values().stream()
            .flatMap(sync -> sync.asObject().get("reportList").asObject().get("reports").asArray().values().stream())
            .anyMatch(report -> "showStarReRoll".equals(report.asObject().getString("reportId", null))));
        assertEquals(previousTurnsPlayed + 1,
            continued.getGameResult().getPlayerResult(continued.getPlayerById(endingPlayer.getId())).getTurnsPlayed());
        if (!lastTurn) assertEquals(scoringTurn, (scorerHome ? continued.getTurnDataHome() : continued.getTurnDataAway()).getTurnNr());
        if (offTurn) {
            JsonObject bank = resumed.recoveryCheckpointPayload().get("clock").asObject();
            long endingUsed = bank.get(scorerHome ? "awayUsedMs" : "homeUsedMs").asLong();
            assertTrue(endingUsed >= 120_000 && endingUsed < 240_000, bank.toString());
            assertEquals(0L, bank.get(scorerHome ? "homeUsedMs" : "awayUsedMs").asLong());
        }

        JsonObject recordCount = resumed.transcriptPage(0, 1);
        assertTrue(resumed.apply(scoringRole, request).getBoolean("duplicate", false));
        assertEquals(recordCount.get("total"), resumed.transcriptPage(0, 1).get("total"));
        assertStarReRolls(continued, scorerHome, STARTING_TEAM_RE_ROLLS, useSkill ? 1 : 0);
        assertEquals(1, continued.getGameResult().getPlayerResult(restoredScorer).getTouchdowns());
        assertEquals(scorerHome ? 1 : 0, continued.getGameResult().getTeamResultHome().getScore());
        assertEquals(scorerHome ? 0 : 1, continued.getGameResult().getTeamResultAway().getScore());
        assertEquals(1, countEvents(resumed, "touchdown"));
        assertEquals(lastTurn ? 1 : 0, countEvents(resumed, "halfTime"));
    }

    @Test void preChangeCheckpointResumesTheUnscoredTouchdownExactlyOnce() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        // Captured from 4db2c4aee with the fixture's own-turn scorer and an open Star of the Show offer.
        SetupSession resumed = restoreCheckpoint(fixture, "/pre-603-star-checkpoint.json.gz");
        Game game = state(resumed).getGame();
        assertTrue(game.getDialogParameter() instanceof DialogSkillUseParameter);
        String scorerId = ((DialogSkillUseParameter) game.getDialogParameter()).getPlayerId();
        boolean scorerHome = game.getTeamHome().hasPlayer(game.getPlayerById(scorerId));
        String role = scorerHome ? "home" : "away";
        String scoreKey = scorerHome ? "homeScore" : "awayScore";
        JsonObject offered = resumed.reply("load", "ACCEPTED", false, role).get("state").asObject();
        assertEquals(0, offered.getInt(scoreKey, -1));
        JsonObject request = fixture.request("action", "legacy-decline-star", offered)
            .add("actionId", actionWithSuffix(offered, "skill:false"));
        JsonObject continued = resumed.apply(role, request).get("state").asObject();
        assertEquals(1, continued.getInt(scoreKey, -1));
        assertEquals(offered.getInt("drive", -1) + 1, continued.getInt("drive", -1));
        assertTrue(resumed.apply(role, request).getBoolean("duplicate", false));
        assertEquals(continued.getInt("revision", -1) + 1, resumed.transcriptPage(0, 1).getInt("total", -1));
        assertEquals(1, game.getGameResult().getPlayerResult(game.getPlayerById(scorerId)).getTouchdowns());
        assertEquals(1, countEvents(resumed, "touchdown"));
    }

    @Test void concessionDuringFirstHalfCleanupSkipsStraightToFullTime() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        JsonObject paused = pauseAtFirstHalfStarOfTheShow(fixture);
        SetupSession resumed = new SetupSession(fixture.server, fixture.document, fixture.session.recoveryArtifact());
        String role = paused.getString("actor", null);

        JsonObject reply = resumed.apply(role, fixture.request("concede", "concede-during-first-half-cleanup", paused));

        assertEquals("ACCEPTED", reply.getString("code", null));
        assertTrue(resumed.isComplete());
        List<JsonObject> events = events(nativeOutcomes(resumed, resumed.revision()));
        assertEquals(List.of("fullTime"), events.stream().map(event -> event.getString("kind", null)).toList());
        assertEquals("MATCH CONCEDED", events.getFirst().getString("headline", null));
        assertEquals(1, countEvents(resumed, "touchdown"));
        assertEquals(0, countEvents(resumed, "halfTime"));
        assertNull(resumed.recoveryCheckpointPayload().get("matchAnnouncements").asObject().get("pendingHalfTime"));
    }

    @Test void recoveryArtifactWithAlreadyRecordedHalftimeFinishesCleanupWithoutAnotherEvent() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        JsonObject paused = pauseAtFirstHalfStarOfTheShow(fixture);
        JsonObject payload = fixture.session.orderedRecoveryPayload();
        // Older writers put halftime in the touchdown sync instead of the cleanup continuation.
        JsonObject checkpointAnnouncements = payload.get("matchAnnouncements").asObject();
        JsonValue halftime = checkpointAnnouncements.get("pendingHalfTime");
        assertNotNull(halftime);
        checkpointAnnouncements.remove("pendingHalfTime");
        JsonArray records = payload.get("transcript").asObject().get("records").asArray();
        JsonArray score = records.get(paused.getInt("revision", -1)).asObject().get("native").asArray();
        score.get(announcementIndex(score, "touchdown")).asObject().get("matchAnnouncements").asObject()
            .get("events").asArray().add(halftime);
        String artifact = new RecoveryArtifactJson().encodeCheckpoint(payload).envelope();

        SetupSession resumed = new SetupSession(fixture.server, fixture.document, artifact);
        assertEquals(1, countEvents(resumed, "halfTime"));
        String role = paused.getString("actor", null);
        JsonObject offer = resumed.reply("load", "ACCEPTED", false, role).get("state").asObject();
        JsonObject request = fixture.request("action", "decline-star-after-legacy-halftime", offer)
            .add("actionId", actionWithSuffix(offer, "skill:false"));
        JsonObject setup = resumed.apply(role, request).get("state").asObject();

        assertEquals(2, setup.getInt("half", -1));
        assertEquals(StepId.SETUP, state(resumed).getCurrentStep().getId());
        assertTrue(resumed.apply(role, request).getBoolean("duplicate", false));
        assertEquals(1, countEvents(resumed, "halfTime"));
    }

    private JsonObject pauseAtFirstHalfStarOfTheShow(SetupSessionMovementTest.Fixture fixture) throws Exception {
        JsonObject ready = fixture.advanceToTurn();
        Game game = state(fixture.session).getGame();
        setLastTurnOfFirstHalf(game);
        UtilBox.putAllPlayersIntoBox(game);
        Player<?> scorer = game.getActingTeam().getPlayers()[0];
        ((RosterPlayer) scorer).addSkill(game.getRules().getSkillFactory().forName("Star of the Show"));
        prepareScorer(game, scorer);
        JsonObject paused = applyAction(fixture, ready, "score-with-star", action(ready, "endTurn").getString("id", null));
        assertTrue(game.getDialogParameter() instanceof DialogSkillUseParameter);
        return paused;
    }

    @Test void offTurnStarCreditsHomeAfterRestoredSendOff() throws Exception {
        offTurnStarCreditsTheScorerAfterRestoredSendOff(true, false);
    }

    @Test void offTurnStarCreditsAwayAfterRestoredSendOff() throws Exception {
        offTurnStarCreditsTheScorerAfterRestoredSendOff(false, false);
    }

    @Test void offTurnStarCreditsHomeAfterRestoredSendOffAtHalftime() throws Exception {
        offTurnStarCreditsTheScorerAfterRestoredSendOff(true, true);
    }

    @Test void offTurnStarCreditsAwayAfterRestoredSendOffAtHalftime() throws Exception {
        offTurnStarCreditsTheScorerAfterRestoredSendOff(false, true);
    }

    private void offTurnStarCreditsTheScorerAfterRestoredSendOff(boolean scorerHome, boolean lastTurn) throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        JsonObject ready = advanceToFirstTurn(fixture, !scorerHome);
        Game game = state(fixture.session).getGame();
        Team scoringTeam = game.getOtherTeam(game.getActingTeam());
        String role = scorerHome ? "home" : "away";
        Player<?> scorer = scoringTeam.getPlayers()[0];
        Player<?> weapon = scoringTeam.getPlayers()[1];
        game.getOptions().addOption(new GameOptionBoolean(GameOptionId.ARGUE_THE_CALL).setValue(true));
        InducementTypeFactory inducements = game.getFactory(FactoryType.Factory.INDUCEMENT_TYPE);
        TurnData scoringTurn = scorerHome ? game.getTurnDataHome() : game.getTurnDataAway();
        scoringTurn.getInducementSet().addInducement(new Inducement(inducements.forName("bribes"), 1));
        UtilBox.putAllPlayersIntoBox(game);
        ((RosterPlayer) scorer).addSkill(game.getRules().getSkillFactory().forName("Star of the Show"));
        ((RosterPlayer) weapon).addSkill(game.getRules().getSkillFactory().forName("Secret Weapon"));
        place(game, weapon, 8, 6, PlayerState.STANDING);
        prepareScorer(game, scorer);
        if (lastTurn) {
            game.getTurnDataHome().setTurnNr(scorerHome ? 7 : 8);
            game.getTurnDataAway().setTurnNr(scorerHome ? 8 : 7);
        }

        JsonObject scored = applyAction(fixture, ready, "score-with-weapon", action(ready, "endTurn").getString("id", null));
        JsonArray scoringOutcomes = recordedNativeOutcomes(fixture.session, scored.getInt("revision", -1));
        int touchdown = announcementIndex(scoringOutcomes, "touchdown");
        assertTrue(touchdown >= 0 && touchdown < changeIndex(scoringOutcomes, "gameSetDialogParameter", "skillUse"));
        assertEquals(-1, announcementIndex(scoringOutcomes, "halfTime"));
        assertEquals(ready.get("drive"), scored.get("drive"));

        SetupSession starSession = new SetupSession(fixture.server, fixture.document, fixture.session.recoveryArtifact());
        JsonObject star = starSession.reply("load", "ACCEPTED", false, role).get("state").asObject();
        JsonObject accept = starAcceptanceRequest(fixture, star);
        JsonObject sendOff = starSession.apply(role, accept).get("state").asObject();
        assertDecisionOffered(starSession, sendOff, "argueTheCall");
        assertEquals(0, countEvents(starSession, "halfTime"));
        assertEquals(ready.getInt("drive", -1) + 1, sendOff.getInt("drive", -1));
        assertStarReRolls(state(starSession).getGame(), scorerHome, 0);

        SetupSession sendOffSession = new SetupSession(fixture.server, fixture.document, starSession.recoveryArtifact());
        JsonObject setup = retryStarThenDeclineSendOff(fixture, sendOffSession, role, accept);
        assertEquals(lastTurn ? 2 : 1, setup.getInt("half", -1));
        if (lastTurn) assertHalftimeAtSetup(sendOffSession, setup);
        // Halftime currently advances the drive again after the send-off. The review tracks this separately.
        assertEquals(sendOff.getInt("drive", -1) + (lastTurn ? 1 : 0), setup.getInt("drive", -1));
        assertEquals(scorerHome ? 1 : 0, setup.getInt("homeScore", -1));
        assertEquals(scorerHome ? 0 : 1, setup.getInt("awayScore", -1));
        Game continued = state(sendOffSession).getGame();
        assertStarReRolls(continued, scorerHome, 1);
        assertEquals(1, continued.getGameResult().getPlayerResult(continued.getPlayerById(scorer.getId())).getTouchdowns());
        assertEquals(PlayerState.BANNED, continued.getFieldModel().getPlayerState(continued.getPlayerById(weapon.getId())).getBase());
        assertEquals(1, countEvents(sendOffSession, "touchdown"));
        assertEquals(lastTurn ? 1 : 0, countEvents(sendOffSession, "halfTime"));
    }

    @Test void preChangeStarOfferAndSendOffCheckpointsCreditTheScorerOnce() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        // Both checkpoints were captured on b485326b4 with a home scorer on away's last turn of the first half.
        SetupSession offerSession = restoreCheckpoint(fixture, "/pre-690-star-offer.json.gz");
        JsonObject offer = offerSession.reply("load", "ACCEPTED", false, "home").get("state").asObject();
        JsonObject accept = starAcceptanceRequest(fixture, offer);
        JsonObject sendOff = offerSession.apply("home", accept).get("state").asObject();
        assertDecisionOffered(offerSession, sendOff, "argueTheCall");
        assertStarReRolls(state(offerSession).getGame(), true, 0);
        retryStarThenDeclineSendOff(fixture, offerSession, "home", accept);
        assertStarReRolls(state(offerSession).getGame(), true, 1);

        SetupSession sendOffSession = restoreCheckpoint(fixture, "/pre-690-star-send-off.json.gz");
        assertStarReRolls(state(sendOffSession).getGame(), true, 0);
        retryStarThenDeclineSendOff(fixture, sendOffSession, "home", accept);
        assertStarReRolls(state(sendOffSession).getGame(), true, 1);
    }

    @Test void preChangeStarOfferWithoutSendOffCreditsAwayOnce() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture(STARTING_TEAM_RE_ROLLS);
        // Captured on fcf43d319 with an away scorer during home's turn and no Secret Weapons.
        SetupSession resumed = restoreCheckpoint(fixture, "/pre-690-star-offer-unpaused.json.gz");
        Game game = state(resumed).getGame();
        assertStarReRolls(game, false, STARTING_TEAM_RE_ROLLS, 0);
        JsonObject offer = resumed.reply("load", "ACCEPTED", false, "away").get("state").asObject();
        JsonObject accept = fixture.request("action", "accept-unpaused-star", offer)
            .add("actionId", actionWithSuffix(offer, "skill:true"));
        JsonObject setup = resumed.apply("away", accept).get("state").asObject();
        assertEquals("SETUP", setup.getString("phase", null));
        assertEquals(offer.getInt("drive", -1) + 1, setup.getInt("drive", -1));
        assertTrue(resumed.apply("away", accept).getBoolean("duplicate", false));
        assertStarReRolls(game, false, STARTING_TEAM_RE_ROLLS, 1);
    }

    private JsonObject starAcceptanceRequest(SetupSessionMovementTest.Fixture fixture, JsonObject offer) {
        return fixture.request("action", "accept-star-before-send-off", offer)
            .add("actionId", actionWithSuffix(offer, "skill:true"));
    }

    private SetupSession restoreCheckpoint(SetupSessionMovementTest.Fixture fixture, String resourceName) throws IOException {
        try (InputStream resource = getClass().getResourceAsStream(resourceName)) {
            assertNotNull(resource, resourceName);
            try (GZIPInputStream compressed = new GZIPInputStream(resource)) {
                String checkpoint = new String(compressed.readAllBytes(), StandardCharsets.UTF_8);
                return new SetupSession(fixture.server, fixture.document, checkpoint);
            }
        }
    }

    private JsonObject retryStarThenDeclineSendOff(SetupSessionMovementTest.Fixture fixture, SetupSession sendOffSession,
        String role, JsonObject accept) {
        assertTrue(sendOffSession.apply(role, accept).getBoolean("duplicate", false));
        JsonObject argue = sendOffSession.reply("load", "ACCEPTED", false, role).get("state").asObject();
        JsonObject declineArgue = fixture.request("action", "decline-argue-after-restore", argue)
            .add("actionId", actionWithSuffix(argue, "argue:no"));
        JsonObject bribe = sendOffSession.apply(role, declineArgue).get("state").asObject();
        assertDecisionOffered(sendOffSession, bribe, "bribes");
        assertEquals(-1, announcementIndex(recordedNativeOutcomes(sendOffSession, bribe.getInt("revision", -1)), "halfTime"));
        JsonObject decline = fixture.request("action", "decline-bribe", bribe)
            .add("actionId", actionWithSuffix(bribe, "bribe:no"));
        JsonObject setup = sendOffSession.apply(role, decline).get("state").asObject();
        assertEquals("SETUP", setup.getString("phase", null));
        assertTrue(sendOffSession.apply(role, decline).getBoolean("duplicate", false));
        assertTrue(sendOffSession.apply(role, accept).getBoolean("duplicate", false));
        assertEquals(setup.getInt("revision", -1) + 1, sendOffSession.transcriptPage(0, 1).getInt("total", -1));
        return setup;
    }

    private void assertStarReRolls(Game game, boolean scorerHome, int expected) {
        assertStarReRolls(game, scorerHome, 0, expected);
    }

    private void assertStarReRolls(Game game, boolean scorerHome, int teamReRolls, int expected) {
        assertEquals(teamReRolls + (scorerHome ? expected : 0), game.getTurnDataHome().getReRolls(), "home team re-rolls");
        assertEquals(teamReRolls + (scorerHome ? 0 : expected), game.getTurnDataAway().getReRolls(), "away team re-rolls");
        assertEquals(scorerHome ? expected : 0, game.getTurnDataHome().getReRollShowStarOneDrive(), "home drive re-rolls");
        assertEquals(scorerHome ? 0 : expected, game.getTurnDataAway().getReRollShowStarOneDrive(), "away drive re-rolls");
    }

    private JsonObject advanceToFirstTurn(SetupSessionMovementTest.Fixture fixture, boolean homeActsFirst) {
        JsonObject ready = fixture.advanceToTurn(homeActsFirst ? "kick" : "receive");
        assertEquals(homeActsFirst ? "home" : "away", ready.getString("actor", null));
        return ready;
    }

    @Test void secretWeaponChoicesFollowTheTouchdown() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        openFirstSecretWeaponBribe(fixture, fixture.advanceToTurn(), TurnEnding.TOUCHDOWN);
    }

    @Test void secretWeaponChoicesFollowTheLastTurnTouchdown() throws Exception {
        assertHalftimeAfterRestoringSecretWeaponChain(TurnEnding.TOUCHDOWN);
    }

    @Test void secretWeaponChoicesFollowTheLastTurnTurnover() throws Exception {
        assertHalftimeAfterRestoringSecretWeaponChain(TurnEnding.TURNOVER);
    }

    @Test void voluntaryLastTurnWithSecretWeaponsAnnouncesOnlyHalftimeAfterCleanup() throws Exception {
        assertHalftimeAfterRestoringSecretWeaponChain(TurnEnding.VOLUNTARY);
    }

    private enum TurnEnding { TOUCHDOWN, TURNOVER, VOLUNTARY }

    private JsonObject openFirstSecretWeaponBribe(SetupSessionMovementTest.Fixture fixture, JsonObject ready, TurnEnding ending)
        throws Exception {
        GameState nativeState = state(fixture.session);
        Game game = nativeState.getGame();
        Team team = game.getActingTeam();
        game.getOptions().addOption(new GameOptionBoolean(GameOptionId.ARGUE_THE_CALL).setValue(true));
        game.getFieldModel().setWeather(Weather.NICE);
        UtilBox.putAllPlayersIntoBox(game);
        place(game, team.getPlayers()[1], 5, 5, PlayerState.STANDING);
        place(game, team.getPlayers()[2], 5, 6, PlayerState.STANDING);
        for (int index = 1; index <= 2; index++) {
            ((RosterPlayer) team.getPlayers()[index]).addSkill(game.getRules().getSkillFactory().forName("Secret Weapon"));
        }
        TurnData turnData = game.getTurnData();
        InducementTypeFactory inducements = game.getFactory(FactoryType.Factory.INDUCEMENT_TYPE);
        turnData.getInducementSet().addInducement(new Inducement(inducements.forName("bribes"), 1));
        turnData.getInducementSet().addInducement(new Inducement(inducements.forName("briberyAndCorruption"), 1));
        Inducement referee = new Inducement(inducements.forName("biasedRef"), 1);
        referee.setValue(1);
        turnData.getInducementSet().addInducement(referee);
        JsonObject current = switch (ending) {
            case TURNOVER -> failPickupAndDeclineReroll(fixture, ready);
            case VOLUNTARY -> {
                game.getFieldModel().setBallCoordinate(null);
                game.getFieldModel().setBallInPlay(false);
                yield applyAction(fixture, ready, "end-drive", action(ready, "endTurn").getString("id", null));
            }
            case TOUCHDOWN -> {
                prepareScorer(game, team.getPlayers()[0]);
                yield applyAction(fixture, ready, "end-drive", action(ready, "endTurn").getString("id", null));
            }
        };
        current = fixture.session.reply("load", "ACCEPTED", false, current.getString("actor", null)).get("state").asObject();
        JsonArray outcomes = recordedNativeOutcomes(fixture.session, current.getInt("revision", -1));
        int selection = changeIndex(outcomes, "gameSetDialogParameter", "playerChoice");
        assertTrue(selection >= 0, outcomes.toString());
        assertEquals(-1, announcementIndex(outcomes, "halfTime"));
        if (ending == TurnEnding.VOLUNTARY) {
            assertEquals(-1, announcementIndex(outcomes, "touchdown"));
            assertEquals(-1, announcementIndex(outcomes, "turnover"));
        } else {
            String boundary = ending == TurnEnding.TURNOVER ? "turnover" : "touchdown";
            int event = announcementIndex(outcomes, boundary);
            assertTrue(event >= 0 && event < selection, outcomes.toString());
        }

        current = applyAction(fixture, current, "choose-weapon", action(current, "secretWeapon").getString("id", null));
        assertDecisionOffered(fixture.session, current, "argueTheCall");
        forceGeneral(nativeState, 1);
        current = applyAction(fixture, current, "argue", actionWithSuffix(current, "argue:" + team.getPlayers()[1].getId()));
        assertDecisionOffered(fixture.session, current, "briberyAndCorruptionReRoll");
        current = applyAction(fixture, current, "decline-argue-reroll", actionWithSuffix(current, "argue-reroll:none"));
        assertDecisionOffered(fixture.session, current, "bribes");
        return current;
    }

    private JsonObject failPickupAndDeclineReroll(SetupSessionMovementTest.Fixture fixture, JsonObject ready) throws Exception {
        GameState nativeState = state(fixture.session);
        Game game = nativeState.getGame();
        Player<?> active = game.getActingTeam().getPlayers()[0];
        place(game, active, 7, 7, PlayerState.STANDING);
        game.getFieldModel().setBallCoordinate(new FieldCoordinate(8, 7));
        game.getFieldModel().setBallInPlay(true);
        game.getFieldModel().setBallMoving(true);
        forceGeneral(nativeState, 1, 6, 6, 6);
        JsonObject reroll = fixture.session.apply(ready.getString("actor", null), fixture.request("movement", "failed-pickup", ready)
            .add("playerId", active.getId()).add("kind", "move").add("targetPlayerId", JsonValue.NULL)
            .add("waypoints", new JsonArray().add(new JsonObject().add("x", 8).add("y", 7))))
            .get("state").asObject();
        assertEquals(-1, announcementIndex(nativeOutcomes(fixture.session, reroll.getInt("revision", -1)), "turnover"));
        return applyAction(fixture, reroll, "decline-pickup-reroll", actionWithSuffix(reroll, "none"));
    }

    private void assertHalftimeAfterRestoringSecretWeaponChain(TurnEnding ending) throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        JsonObject ready = fixture.advanceToTurn();
        setLastTurnOfFirstHalf(state(fixture.session).getGame());
        JsonObject current = openFirstSecretWeaponBribe(fixture, ready, ending);
        SetupSession resumed = new SetupSession(fixture.server, fixture.document, fixture.session.recoveryArtifact());
        String role = current.getString("actor", null);
        JsonObject bribe = resumed.reply("load", "ACCEPTED", false, role).get("state").asObject();
        JsonObject argue = resumed.apply(role, fixture.request("action", "decline-first-bribe", bribe)
            .add("actionId", actionWithSuffix(bribe, "bribe:no"))).get("state").asObject();
        assertDecisionOffered(resumed, argue, "argueTheCall");
        JsonObject secondBribe = resumed.apply(role, fixture.request("action", "decline-second-argue", argue)
            .add("actionId", actionWithSuffix(argue, "argue:no"))).get("state").asObject();
        assertDecisionOffered(resumed, secondBribe, "bribes");
        JsonObject request = fixture.request("action", "decline-final-bribe", secondBribe)
            .add("actionId", actionWithSuffix(secondBribe, "bribe:no"));
        JsonObject setup = resumed.apply(role, request).get("state").asObject();
        assertHalftimeAtSetup(resumed, setup);

        SetupSession afterAcceptance = new SetupSession(fixture.server, fixture.document, resumed.recoveryArtifact());
        assertTrue(afterAcceptance.apply(role, request).getBoolean("duplicate", false));
        assertEquals(setup.getInt("revision", -1) + 1, afterAcceptance.transcriptPage(0, 1).getInt("total", -1));
        assertEquals(1, countEvents(afterAcceptance, "halfTime"));
    }

    @Test void voluntaryLastTurnWithNoCleanupChoiceAnnouncesHalftimeInTheSameRevision() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        JsonObject ready = fixture.advanceToTurn();
        setLastTurnOfFirstHalf(state(fixture.session).getGame());

        JsonObject setup = applyAction(fixture, ready, "end-first-half", action(ready, "endTurn").getString("id", null));

        assertEquals(ready.getInt("revision", -1) + 1, setup.getInt("revision", -1));
        assertHalftimeAtSetup(fixture.session, setup);
        assertEquals(List.of("halfTime"), events(nativeOutcomes(fixture.session, setup.getInt("revision", -1))).stream()
            .map(event -> event.getString("kind", null)).toList());
    }

    @Test void lastTurnTurnoverWithNoCleanupChoicePrecedesHalftime() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        JsonObject ready = fixture.advanceToTurn();
        Game game = state(fixture.session).getGame();
        setLastTurnOfFirstHalf(game);
        game.getFieldModel().setWeather(Weather.NICE);
        UtilBox.putAllPlayersIntoBox(game);

        JsonObject setup = failPickupAndDeclineReroll(fixture, ready);

        assertHalftimeAtSetup(fixture.session, setup);
        JsonArray outcomes = nativeOutcomes(fixture.session, setup.getInt("revision", -1));
        int turnover = announcementIndex(outcomes, "turnover");
        assertTrue(turnover >= 0 && turnover < announcementIndex(outcomes, "halfTime"), outcomes.toString());
        assertEquals(List.of("turnover", "halfTime"), events(outcomes).stream()
            .map(event -> event.getString("kind", null)).toList());
    }

    @Test void voluntaryEndAndRecoveryProduceOnlyTheNextAuthoritativeTurnStart() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        JsonObject ready = fixture.advanceToTurn();
        List<JsonObject> initial = allEvents(fixture.session);
        assertEquals(List.of("weather", "kickoff", "turnStart"), initial.stream().map(e -> e.getString("kind", null)).toList());
        assertEquals("turnStart", initial.getLast().getString("kind", null));
        Game game = state(fixture.session).getGame();
        assertEquals(game.getTurnData().getTurnNr(), initial.getLast().getInt("turn", -1));
        SetupSession recovered = new SetupSession(fixture.server, fixture.document, fixture.session.recoveryArtifact());
        int from = ready.getInt("revision", -1) + 1;
        JsonObject request = fixture.request("action", "finish-turn", ready).add("actionId", action(ready, "endTurn").get("id"));
        JsonObject after = recovered.apply(ready.getString("actor", null), request).get("state").asObject();
        List<JsonObject> events = events(recovered.transcriptPage(from, 1).get("records").asArray().get(0).asObject().get("native").asArray());
        assertEquals(1, events.size(), events.toString());
        assertEquals("turnStart", events.getFirst().getString("kind", null));
        Game actual = state(recovered).getGame();
        assertEquals(actual.isHomePlaying() ? "home" : "away", events.getFirst().getString("team", null));
        assertEquals(actual.getTurnData().getTurnNr(), events.getFirst().getInt("turn", -1));
        assertTrue(recovered.apply(ready.getString("actor", null), request).getBoolean("duplicate", false));
        assertEquals(after.getInt("revision", -1) + 1, recovered.transcriptPage(0, 1).getInt("total", -1));
    }

    @Test void ownAndOffTurnScoresTakePrecedenceAndPrecedeNewSetup() throws Exception {
        for (boolean offTurn : new boolean[] {false, true}) {
            SetupSessionMovementTest.Fixture fixture = fixture();
            JsonObject ready = fixture.advanceToTurn();
            Game game = state(fixture.session).getGame();
            boolean scorerHome = offTurn != game.isHomePlaying();
            Player<?> scorer = (scorerHome ? game.getTeamHome() : game.getTeamAway()).getPlayers()[0];
            UtilBox.putAllPlayersIntoBox(game);
            FieldCoordinate endzone = new FieldCoordinate(scorerHome ? 25 : 0, 7);
            game.getFieldModel().setPlayerCoordinate(scorer, endzone);
            game.getFieldModel().setPlayerState(scorer, new PlayerState(PlayerState.STANDING));
            game.getFieldModel().setBallCoordinate(endzone);
            game.getFieldModel().setBallInPlay(true);
            game.getFieldModel().setBallMoving(false);
            int revision = ready.getInt("revision", -1);
            fixture.session.apply(ready.getString("actor", null), fixture.request("action", "score", ready)
                .add("actionId", action(ready, "endTurn").get("id")));
            JsonArray outcomes = fixture.session.transcriptPage(revision + 1, 1).get("records").asArray().get(0).asObject().get("native").asArray();
            List<JsonObject> events = events(outcomes);
            assertEquals(1, events.size(), outcomes.toString());
            assertEquals("touchdown", events.getFirst().getString("kind", null));
            assertEquals(scorerHome ? "home" : "away", events.getFirst().getString("team", null));
            int announcement = -1;
            for (int index = 0; index < outcomes.size(); index++) {
                JsonObject sync = outcomes.get(index).asObject();
                if (sync.get("matchAnnouncements") != null) announcement = index;
                for (JsonValue change : sync.get("modelChangeList").asObject().get("modelChangeArray").asArray())
                    if ("gameSetTurnMode".equals(change.asObject().getString("modelChangeId", null))
                        && "setup".equals(change.asObject().getString("modelChangeValue", null)))
                        assertTrue(announcement >= 0 && announcement < index, "Touchdown must precede the first setup transition");
            }
            assertTrue(announcement >= 0);
        }
    }

    @Test void friendlyThrowCollisionUsesConfirmedVictimAndRetainsResolutionOrder() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        fixture.advanceToTurn();
        GameState state = state(fixture.session);
        Game game = state.getGame();
        String team = game.isHomePlaying() ? "home" : "away";
        Player<?> victim = game.getActingTeam().getPlayers()[2];
        int cursor = (int) state.getLastCommandNr();
        JsonObject initial = beginCommittedThrow(fixture.session, true, false, false);
        List<JsonObject> outcomes = capture(fixture.session, cursor);
        List<JsonObject> events = events(array(outcomes));
        assertEquals(List.of("turnover", "turnStart"), events.stream().map(e -> e.getString("kind", null)).toList());
        assertEquals(team, events.getFirst().getString("team", null));
        assertTrue(events.getFirst().getString("reason", "").contains(victim.getName()));
        assertTrue(events.getFirst().getString("reason", "").contains("thrown player"));
        assertTrue(game.getFieldModel().getPlayerState(victim).isProneOrStunned());
        assertResolutionBeforeAnnouncement(outcomes);
        Files.writeString(Path.of("target/match-announcement-ttm-native.json"), new JsonObject()
            .add("initialState", initial).add("native", array(outcomes)).add("state", fixture.session.spectatorView()).toString());
    }

    @Test void thrownPlayerExitingThePitchHasAConfirmedCauseWithAndWithoutTheBall() throws Exception {
        for (boolean ball : new boolean[] {false, true}) {
            SetupSessionMovementTest.Fixture fixture = fixture();
            fixture.advanceToTurn();
            GameState state = state(fixture.session);
            Player<?> thrown = state.getGame().getActingTeam().getPlayers()[1];
            int cursor = (int) state.getLastCommandNr();
            beginCommittedThrow(fixture.session, false, true, ball);
            List<JsonObject> outcomes = capture(fixture.session, cursor);
            List<JsonObject> events = events(array(outcomes));
            assertEquals(List.of("turnover", "turnStart"), events.stream().map(e -> e.getString("kind", null)).toList());
            assertTrue(events.getFirst().getString("reason", "").contains(thrown.getName()));
            assertTrue(events.getFirst().getString("reason", "").contains("left the pitch"));
            assertResolutionBeforeAnnouncement(outcomes);
        }
    }

    @Test void failedLandingWithoutTheBallDoesNotAnnounceATurnover() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        fixture.advanceToTurn();
        GameState state = state(fixture.session);
        boolean home = state.getGame().isHomePlaying();
        int cursor = (int) state.getLastCommandNr();
        beginCommittedThrow(fixture.session, false, false, false);
        List<JsonObject> outcomes = capture(fixture.session, cursor);
        assertTrue(events(array(outcomes)).isEmpty());
        assertEquals(home, state.getGame().isHomePlaying());
        assertTrue(outcomes.stream().anyMatch(sync -> sync.toString().contains("rightStuffRoll")));
    }

    @Test void pendingConfirmedCauseSurvivesCheckpointAndBonusModesDoNotStartNumberedTurns() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        fixture.advanceToTurn();
        Game game = state(fixture.session).getGame();
        MatchAnnouncementPresentation presentation = new MatchAnnouncementPresentation();
        presentation.reason(game, "Team-mate Test was hit by thrown player Flyer.");
        presentation.cause(game, StepId.HANDLE_DROP_PLAYER_CONTEXT, true);
        MatchAnnouncementPresentation restored = new MatchAnnouncementPresentation();
        restored.restore(JsonObject.readFrom(presentation.checkpoint().toString()));
        restored.end(game, null, false);
        game.setHomePlaying(!game.isHomePlaying());
        game.getTurnData().setTurnNr(6);
        restored.playable(game);
        JsonObject sync = new JsonObject().add("commandNr", 42);
        restored.decorate(List.of(sync));
        List<JsonObject> events = events(new JsonArray().add(sync));
        assertEquals(2, events.size());
        assertEquals("Team-mate Test was hit by thrown player Flyer.", events.getFirst().getString("reason", null));
        assertEquals(6, events.getLast().getInt("turn", -1));
        for (TurnMode mode : new TurnMode[] {TurnMode.SETUP, TurnMode.KICKOFF, TurnMode.BLITZ, TurnMode.QUICK_SNAP}) {
            game.setTurnMode(mode);
            restored.end(game, null, false);
            restored.playable(game);
        }
        game.setTurnMode(TurnMode.REGULAR);
        restored.end(game, null, true);
        restored.halfEnd();
        JsonObject quiet = new JsonObject().add("commandNr", 43);
        restored.decorate(List.of(quiet));
        assertEquals("halfTime", events(new JsonArray().add(quiet)).getFirst().getString("kind", null));
    }

    @Test void rerolledPickupSuccessAndDeclinedPickupFailureRemainDistinctAcrossRecovery() throws Exception {
        for (boolean reroll : new boolean[] {true, false}) {
            SetupSessionMovementTest.Fixture fixture = new SetupSessionMovementTest.Fixture(1, true);
            state(fixture.session).getDiceRoller().setRecoveryRoll(sides -> 1);
            JsonObject ready = fixture.advanceToTurn();
            GameState nativeState = state(fixture.session);
            Game game = nativeState.getGame();
            Player<?> runner = game.getActingTeam().getPlayers()[0];
            UtilBox.putAllPlayersIntoBox(game);
            place(game, runner, 7, 7, PlayerState.STANDING);
            game.getFieldModel().setBallCoordinate(new FieldCoordinate(8, 7));
            game.getFieldModel().setBallMoving(true);
            game.getFieldModel().setBallInPlay(true);
            forceGeneral(nativeState, 1, 6, 6, 6, 6, 6);
            String role = ready.getString("actor", null);
            ready = fixture.session.reply("load", "ACCEPTED", false, role).get("state").asObject();
            JsonObject paused = fixture.session.apply(role, fixture.request("movement", "pickup", ready)
                .add("playerId", runner.getId()).add("kind", "move").add("targetPlayerId", JsonValue.NULL)
                .add("waypoints", new JsonArray().add(new JsonObject().add("x", 8).add("y", 7))))
                .get("state").asObject();
            assertTrue(allEvents(fixture.session).stream().noneMatch(e -> "turnover".equals(e.getString("kind", null))));
            SetupSession recovered = new SetupSession(fixture.server, fixture.document, fixture.session.recoveryArtifact());
            JsonObject choice = null;
            for (JsonValue value : paused.get("actions").asArray()) {
                JsonObject offered = value.asObject();
                if ("reroll".equals(offered.getString("kind", null)) && offered.getString("id", "").endsWith(reroll ? ":team" : ":none")) choice = offered;
            }
            assertTrue(choice != null, paused.toString());
            recovered.apply(role, fixture.request("action", "pickup-response", paused).add("actionId", choice.get("id")));
            List<JsonObject> turnovers = allEvents(recovered).stream().filter(e -> "turnover".equals(e.getString("kind", null))).toList();
            assertEquals(reroll ? 0 : 1, turnovers.size());
            if (!reroll) assertEquals(runner.getName() + " failed to pick up the ball.", turnovers.getFirst().getString("reason", null));
        }
    }

    @Test void nativeAttackerDownNamesTheBlockingPlayer() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        JsonObject ready = fixture.advanceToTurn();
        GameState nativeState = state(fixture.session);
        Game game = nativeState.getGame();
        Player<?> attacker = game.getActingTeam().getPlayers()[0];
        Player<?> defender = game.getOtherTeam(game.getActingTeam()).getPlayers()[0];
        UtilBox.putAllPlayersIntoBox(game);
        place(game, attacker, 7, 7, PlayerState.STANDING);
        place(game, defender, 8, 7, PlayerState.STANDING);
        game.getFieldModel().setBallCoordinate(new FieldCoordinate(20, 14));
        game.getFieldModel().setBallMoving(true);
        List<DiceCategory> dice = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            BlockDiceCategory die = new BlockDiceCategory(); die.parseCommand("skull", game, game.getTeamHome()); dice.add(die);
        }
        nativeState.getDiceRoller().getTestRolls().put("Block", dice);
        String role = ready.getString("actor", null);
        ready = fixture.session.reply("load", "ACCEPTED", false, role).get("state").asObject();
        JsonObject blocked = fixture.session.apply(role, fixture.request("movement", "block", ready)
            .add("playerId", attacker.getId()).add("kind", "blitz").add("targetPlayerId", defender.getId())
            .add("waypoints", new JsonArray())).get("state").asObject();
        fixture.session.apply(blocked.getString("actor", null), fixture.request("action", "skull", blocked)
            .add("actionId", action(blocked, "blockDie").get("id")));
        List<JsonObject> turnovers = allEvents(fixture.session).stream().filter(e -> "turnover".equals(e.getString("kind", null))).toList();
        assertEquals(1, turnovers.size());
        assertEquals(attacker.getName() + " was knocked down performing a block.", turnovers.getFirst().getString("reason", null));
    }

    @Test void revertedEndTurnClearsCauseAndConfirmedFinalTurnStillAnnounces() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture(); fixture.advanceToTurn();
        Game game = state(fixture.session).getGame();
        MatchAnnouncementPresentation presentation = new MatchAnnouncementPresentation();
        presentation.cause(game, StepId.MOVE_DODGE, true);
        presentation.cause(game, StepId.STEADY_FOOTING, false);
        presentation.end(game, null, false);
        JsonObject noTurnover = new JsonObject().add("commandNr", 1);
        presentation.decorate(List.of(noTurnover));
        assertTrue(events(new JsonArray().add(noTurnover)).isEmpty());
        presentation.cause(game, StepId.PICK_UP, true);
        presentation.end(game, null, true);
        JsonObject finalTurn = new JsonObject().add("commandNr", 2);
        presentation.decorate(List.of(finalTurn));
        List<JsonObject> events = events(new JsonArray().add(finalTurn));
        assertEquals(1, events.size());
        assertEquals("turnover", events.getFirst().getString("kind", null));
        assertEquals("A rules-triggered turnover ended the turn.", events.getFirst().getString("reason", null));
    }

    @Test void everyKickoffWaitsForPlayableTurnAndSurvivesRecovery() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        fixture.advanceToTurn();
        Game game = state(fixture.session).getGame();
        JsonArray contracts = new JsonArray();
        for (KickoffResult result : KickoffResult.values()) {
            MatchAnnouncementPresentation presentation = new MatchAnnouncementPresentation();
            game.setTurnMode(TurnMode.KICKOFF);
            presentation.kickoff(game, result);
            game.getFieldModel().setWeather(Weather.POURING_RAIN);
            if (result == KickoffResult.WEATHER_CHANGE) presentation.weather(game);
            for (TurnMode mode : new TurnMode[] {TurnMode.SETUP, TurnMode.KICKOFF, TurnMode.QUICK_SNAP, TurnMode.BLITZ}) {
                game.setTurnMode(mode);
                presentation.playable(game);
                JsonObject quiet = new JsonObject().add("commandNr", 1);
                presentation.decorate(List.of(quiet));
                assertTrue(events(new JsonArray().add(quiet)).isEmpty());
            }
            MatchAnnouncementPresentation recovered = new MatchAnnouncementPresentation();
            recovered.restore(JsonObject.readFrom(presentation.checkpoint().toString()));
            game.setTurnMode(TurnMode.REGULAR);
            recovered.playable(game);
            recovered.playable(game);
            JsonObject sync = new JsonObject().add("commandNr", 2);
            recovered.decorate(List.of(sync));
            List<JsonObject> actual = events(new JsonArray().add(sync));
            assertEquals(result == KickoffResult.WEATHER_CHANGE
                ? List.of("kickoff", "weather", "turnStart") : List.of("kickoff", "turnStart"),
                actual.stream().map(e -> e.getString("kind", null)).toList());
            assertEquals(result.getName(), actual.getFirst().getString("headline", null));
            assertEquals(result.getDescription(), actual.getFirst().getString("reason", null));
            contracts.add(actual.getFirst());
        }
        MatchAnnouncementPresentation presentation = new MatchAnnouncementPresentation();
        for (Weather weather : Weather.values()) {
            game.getFieldModel().setWeather(weather);
            presentation.weather(game);
            presentation.weather(game);
            JsonObject sync = new JsonObject().add("commandNr", 3);
            presentation.decorate(List.of(sync));
            List<JsonObject> actual = events(new JsonArray().add(sync));
            if (weather == Weather.INTRO) assertTrue(actual.isEmpty());
            else {
                assertEquals(1, actual.size());
                assertEquals(weather.getName(), actual.getFirst().getString("headline", null));
                contracts.add(actual.getFirst());
            }
        }
        Files.writeString(Path.of("target/match-splash-events.json"), contracts.toString());
        Path browserFixture = Path.of("../browser-client/test/fixtures/match-splash-events.json");
        assertEquals(contracts, JsonArray.readFrom(Files.readString(browserFixture)),
            "Refresh the committed browser fixture from ffb-server/target/match-splash-events.json");
    }

    @Test void kickoffAttributionUsesTheKickingAndReceivingSides() throws Exception {
        for (KickoffResult result : List.of(KickoffResult.QUICK_SNAP, KickoffResult.HIGH_KICK, KickoffResult.CHARGE)) {
            List<String> kickers = new ArrayList<>();
            for (String receiveOption : List.of("receive", "kick")) {
                SetupSessionMovementTest.Fixture fixture = fixture();
                GameState engine = state(fixture.session);
                int firstDie = result == KickoffResult.HIGH_KICK ? 2 : 4;
                int secondDie = result == KickoffResult.CHARGE ? 6 : result == KickoffResult.QUICK_SNAP ? 5 : 3;
                JsonObject turn = fixture.advanceToTurn(receiveOption, view -> {
                    JsonArray actions = view.get("actions").asArray();
                    JsonObject first = actions.get(0).asObject();
                    if ("kickoff".equals(first.getString("kind", null))) {
                        String kicker = first.getString("actor", null);
                        kickers.add(kicker);
                        String target = "home".equals(kicker) ? ":kick-19-7" : ":kick-6-7";
                        forceGeneral(engine, 1, 1, firstDie, secondDie, 1);
                        for (JsonValue offered : actions) {
                            JsonObject candidate = offered.asObject();
                            if (candidate.getString("id", "").endsWith(target)) return candidate;
                        }
                        throw new AssertionError("Missing kickoff target " + target);
                    }
                    for (JsonValue offered : actions) {
                        JsonObject candidate = offered.asObject();
                        String id = candidate.getString("id", "");
                        if (id.endsWith(":decline-event") || id.endsWith(":end-event")) return candidate;
                    }
                    return first;
                });
                JsonObject kickoff = allEvents(fixture.session).stream().filter(event -> "kickoff".equals(event.getString("kind", null)))
                    .findFirst().orElseThrow();
                assertEquals(result.name(), kickoff.getString("code", null));
                String kicking = kickers.getLast();
                String receiving = "home".equals(kicking) ? "away" : "home";
                assertEquals(receiving, turn.getString("actor", null));
                assertEquals(result == KickoffResult.CHARGE ? kicking : receiving, kickoff.getString("team", null), result.name());
                assertTrue(kickoff.getBoolean("teamSpecific", false));
            }
            assertEquals(List.of("away", "home"), kickers.stream().sorted().toList());
        }
    }

    @Test void unchangedKickoffWeatherStillCarriesTheEngineLabel() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        fixture.advanceToTurn();
        Game game = state(fixture.session).getGame();
        MatchAnnouncementPresentation presentation = new MatchAnnouncementPresentation();
        game.getFieldModel().setWeather(Weather.NICE);
        presentation.weather(game);
        presentation.recorded();
        game.setTurnMode(TurnMode.KICKOFF);
        presentation.kickoff(game, KickoffResult.WEATHER_CHANGE);
        presentation.weather(game);
        game.setTurnMode(TurnMode.REGULAR);
        presentation.playable(game);
        JsonObject sync = new JsonObject().add("commandNr", 1);
        presentation.decorate(List.of(sync));
        List<JsonObject> facts = events(new JsonArray().add(sync));
        assertEquals(List.of("kickoff", "turnStart"), facts.stream().map(e -> e.getString("kind", null)).toList());
        assertEquals(Weather.NICE.getName(), facts.getFirst().get("weather").asObject().getString("headline", null));
    }

    @Test void restoresAFullMatchCheckpointWithLegacyAnnouncementFields() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        fixture.advanceToTurn();
        JsonObject payload = fixture.session.orderedRecoveryPayload();
        JsonObject announcements = payload.get("matchAnnouncements").asObject();
        JsonValue lastTurn = announcements.get("lastTurn");
        announcements.remove("lastWeather").remove("pendingKickoff").remove("deferredWeather");
        String artifact = new RecoveryArtifactJson().encodeCheckpoint(payload).envelope();

        SetupSession restored = new SetupSession(fixture.server, fixture.document, artifact);
        assertEquals(fixture.session.revision(), restored.revision());
        JsonObject currentAnnouncements = restored.orderedRecoveryPayload().get("matchAnnouncements").asObject();
        assertEquals(lastTurn, currentAnnouncements.get("lastTurn"));
        assertEquals(JsonValue.NULL, currentAnnouncements.get("lastWeather"));
        assertEquals(JsonValue.NULL, currentAnnouncements.get("pendingKickoff"));
        assertEquals(JsonValue.NULL, currentAnnouncements.get("deferredWeather"));
    }

    @Test void rejectsCheckpointsMissingRequiredFields() {
        MatchAnnouncementPresentation presentation = new MatchAnnouncementPresentation();
        JsonObject incomplete = presentation.checkpoint().remove("lastWeather");
        assertThrows(IllegalArgumentException.class, () -> presentation.restore(incomplete));
        JsonObject malformed = presentation.checkpoint().remove("lastWeather").add("wrongField", JsonValue.NULL);
        assertThrows(IllegalArgumentException.class, () -> presentation.restore(malformed));
    }

    @Test void halfEndingTouchdownPrecedesHalfTimeAndConcessionIsLast() throws Exception {
        SetupSessionMovementTest.Fixture fixture = fixture();
        fixture.advanceToTurn();
        Game game = state(fixture.session).getGame();
        MatchAnnouncementPresentation presentation = new MatchAnnouncementPresentation();
        game.setHalf(1);
        presentation.end(game, game.getTeamHome().getPlayers()[0], true);
        JsonObject sync = new JsonObject().add("commandNr", 4);
        presentation.decorate(List.of(sync));
        assertEquals(List.of("touchdown"), events(new JsonArray().add(sync)).stream().map(e -> e.getString("kind", null)).toList());
        presentation.halfEnd();
        presentation.halfEnd();
        JsonObject half = new JsonObject().add("commandNr", 5);
        presentation.decorate(List.of(half));
        assertEquals(List.of("halfTime"), events(new JsonArray().add(half)).stream().map(e -> e.getString("kind", null)).toList());
        game.setHalf(2);
        presentation.end(game, game.getTeamAway().getPlayers()[0], true);
        presentation.halfEnd();
        game.getGameResult().getTeamResultHome().setConceded(true);
        presentation.finished(game);
        JsonObject end = new JsonObject().add("commandNr", 6);
        presentation.decorate(List.of(end));
        List<JsonObject> actual = events(new JsonArray().add(end));
        assertEquals(List.of("touchdown", "fullTime"), actual.stream().map(e -> e.getString("kind", null)).toList());
        assertEquals("MATCH CONCEDED", actual.getLast().getString("headline", null));
        assertEquals("away", actual.getLast().getString("team", null));
        Player<?> scorer = game.getTeamAway().getPlayers()[0];
        assertEquals(new JsonObject().add("name", scorer.getName()).add("number", scorer.getNr()), actual.getFirst().get("scorer"));

        game.getGameResult().getTeamResultHome().setConceded(false);
        presentation.finished(game);
        JsonObject finish = new JsonObject().add("commandNr", 7);
        presentation.decorate(List.of(finish));
        assertEquals("END OF MATCH", events(new JsonArray().add(finish)).getFirst().getString("headline", null));
    }
    private void forceGeneral(GameState state, int... values) {
        List<DiceCategory> dice = new ArrayList<>();
        for (int value : values) {
            DiceCategory die = new DiceCategory(); die.parseCommand(Integer.toString(value), state.getGame(), state.getGame().getTeamHome()); dice.add(die);
        }
        state.getDiceRoller().getTestRolls().put("General", dice);
    }

    private void prepareScorer(Game game, Player<?> scorer) {
        boolean home = game.getTeamHome().hasPlayer(scorer);
        FieldCoordinate endzone = new FieldCoordinate(home ? 25 : 0, 7);
        game.getFieldModel().setPlayerCoordinate(scorer, endzone);
        game.getFieldModel().setPlayerState(scorer, new PlayerState(PlayerState.STANDING));
        game.getFieldModel().setBallCoordinate(endzone);
        game.getFieldModel().setBallInPlay(true);
        game.getFieldModel().setBallMoving(false);
    }

    private void setLastTurnOfFirstHalf(Game game) {
        game.getTurnDataHome().setTurnNr(8);
        game.getTurnDataAway().setTurnNr(8);
    }

    private JsonObject applyAction(SetupSessionMovementTest.Fixture fixture, JsonObject view, String requestId, String actionId) {
        return fixture.session.apply(view.getString("actor", null), fixture.request("action", requestId, view).add("actionId", actionId))
            .get("state").asObject();
    }

    private String actionWithSuffix(JsonObject view, String suffix) {
        for (JsonValue value : view.get("actions").asArray()) {
            String id = value.asObject().getString("id", "");
            if (id.endsWith(":" + suffix)) return id;
        }
        throw new AssertionError("Missing action " + suffix + " in " + view.get("actions"));
    }

    private void assertDecisionOffered(SetupSession session, JsonObject view, String dialogId) {
        JsonArray outcomes = recordedNativeOutcomes(session, view.getInt("revision", -1));
        assertTrue(changeIndex(outcomes, "gameSetDialogParameter", dialogId) >= 0, outcomes.toString());
        assertEquals(-1, announcementIndex(outcomes, "touchdown"));
        assertEquals(-1, announcementIndex(outcomes, "turnover"));
        assertEquals(-1, announcementIndex(outcomes, "halfTime"));
    }

    private void assertHalftimeAtSetup(SetupSession session, JsonObject view) throws Exception {
        assertEquals(2, view.getInt("half", -1));
        assertEquals(StepId.SETUP, state(session).getCurrentStep().getId());
        int revision = view.getInt("revision", -1);
        JsonArray recorded = recordedNativeOutcomes(session, revision);
        int halfChange = changeIndex(recorded, "gameSetHalf", null);
        assertTrue(halfChange >= 0, recorded.toString());
        assertEquals(halfChange, announcementIndex(recorded, "halfTime"));
        JsonArray publicOutcomes = nativeOutcomes(session, revision);
        assertEquals(halfChange, announcementIndex(publicOutcomes, "halfTime"));
        JsonObject halftime = events(publicOutcomes).stream().filter(event -> "halfTime".equals(event.getString("kind", null)))
            .findFirst().orElseThrow();
        assertEquals(1, halftime.getInt("half", -1));
        assertEquals(8, halftime.getInt("turn", -1));
    }

    private JsonArray recordedNativeOutcomes(SetupSession session, int revision) {
        return session.recoveryCheckpointPayload().get("transcript").asObject().get("records").asArray()
            .get(revision).asObject().get("native").asArray();
    }

    private JsonArray nativeOutcomes(SetupSession session, int revision) {
        return session.transcriptPage(revision, 1).get("records").asArray().get(0).asObject().get("native").asArray();
    }

    private int announcementIndex(JsonArray outcomes, String kind) {
        for (int index = 0; index < outcomes.size(); index++) {
            if (events(new JsonArray().add(outcomes.get(index))).stream().anyMatch(event -> kind.equals(event.getString("kind", null))))
                return index;
        }
        return -1;
    }

    private int changeIndex(JsonArray outcomes, String changeId, String dialogId) {
        for (int index = 0; index < outcomes.size(); index++) {
            for (JsonValue value : outcomes.get(index).asObject().get("modelChangeList").asObject().get("modelChangeArray").asArray()) {
                JsonObject change = value.asObject();
                if (!changeId.equals(change.getString("modelChangeId", null))) continue;
                JsonValue data = change.get("modelChangeValue");
                if (dialogId == null || data.isObject() && dialogId.equals(data.asObject().getString("dialogId", null))) return index;
            }
        }
        return -1;
    }

    private SetupSessionMovementTest.Fixture fixture() throws Exception {
        return fixture(0);
    }

    private SetupSessionMovementTest.Fixture fixture(int teamReRolls) throws Exception {
        SetupSessionMovementTest.Fixture fixture = new SetupSessionMovementTest.Fixture(teamReRolls, true);
        state(fixture.session).getDiceRoller().setRecoveryRoll(sides -> 1);
        return fixture;
    }

    /** Begin after the throw has been committed; execute the real generated scatter/landing/end-action rules. */
    private JsonObject beginCommittedThrow(SetupSession session, boolean collision, boolean exits, boolean ball) throws Exception {
        GameState state = state(session);
        Game game = state.getGame();
        Player<?> thrower = game.getActingTeam().getPlayers()[0];
        Player<?> thrown = game.getActingTeam().getPlayers()[1];
        Player<?> victim = game.getActingTeam().getPlayers()[2];
        UtilBox.putAllPlayersIntoBox(game);
        int x = 7;
        int y = exits ? 0 : 7;
        place(game, thrower, x, y, PlayerState.MOVING);
        place(game, thrown, x + 1, y, PlayerState.PICKED_UP);
        if (collision) place(game, victim, 9, 7, PlayerState.STANDING);
        game.getActingPlayer().setPlayerId(thrower.getId());
        game.getActingPlayer().setPlayerAction(PlayerAction.THROW_TEAM_MATE);
        game.setThrowerId(thrower.getId());
        game.setDefenderId(thrown.getId());
        game.setPassCoordinate(new FieldCoordinate(9, 7));
        game.getFieldModel().setBallCoordinate(ball ? new FieldCoordinate(x + 1, y) : new FieldCoordinate(20, 14));
        game.getFieldModel().setBallInPlay(true);
        game.getFieldModel().setBallMoving(!ball);
        state.pushCurrentStepOnStack();
        SequenceGeneratorFactory factory = game.getFactory(FactoryType.Factory.SEQUENCE_GENERATOR);
        ((ThrowTeamMate) factory.forName(SequenceGenerator.Type.ThrowTeamMate.name()))
            .pushSequence(new ThrowTeamMate.SequenceParams(state));
        while (state.getStepStack().peek().getId() != StepId.DISPATCH_SCATTER_PLAYER) state.getStepStack().pop();
        state.getStepStack().publishStepParameter(StepParameter.from(StepParameterKey.THROWN_PLAYER_ID, thrown.getId()));
        state.getStepStack().publishStepParameter(StepParameter.from(StepParameterKey.THROWN_PLAYER_STATE, new PlayerState(PlayerState.PICKED_UP)));
        state.getStepStack().publishStepParameter(StepParameter.from(StepParameterKey.THROWN_PLAYER_HAS_BALL, ball));
        state.getStepStack().publishStepParameter(StepParameter.from(StepParameterKey.OLD_DEFENDER_STATE, new PlayerState(PlayerState.STANDING)));
        state.getStepStack().publishStepParameter(StepParameter.from(StepParameterKey.PASS_RESULT, PassResult.FUMBLE));
        state.getStepStack().publishStepParameter(StepParameter.from(StepParameterKey.USING_BULLSEYE, collision));
        if (collision) state.getStepStack().publishStepParameter(StepParameter.from(StepParameterKey.PASS_RESULT, PassResult.ACCURATE));
        JsonObject initial = session.spectatorView();
        state.startNextStep();
        assertEquals(null, game.getDialogParameter(), "Synthetic throw must resolve without a pending choice");
        return initial;
    }

    private List<JsonObject> capture(SetupSession session, int cursor) throws Exception {
        List<JsonObject> outcomes = new NativeOutcomeCapture().since(state(session).getGameLog(), cursor).modelSyncs;
        Field field = SetupSession.class.getDeclaredField("announcements"); field.setAccessible(true);
        ((MatchAnnouncementPresentation) field.get(session)).decorate(outcomes);
        return outcomes;
    }

    private void assertResolutionBeforeAnnouncement(List<JsonObject> outcomes) {
        int lastResolution = -1, announcement = -1;
        for (int index = 0; index < outcomes.size(); index++) {
            JsonObject sync = outcomes.get(index);
            if (sync.get("matchAnnouncements") != null && announcement < 0) announcement = index;
            for (JsonValue report : sync.get("reportList").asObject().get("reports").asArray()) {
                String id = report.asObject().getString("reportId", "");
                if (id.contains("injury") || id.contains("Injury") || id.equals("scatterPlayer") || id.equals("rightStuffRoll")) lastResolution = index;
            }
        }
        assertTrue(lastResolution >= 0 && announcement >= lastResolution, outcomes.toString());
    }

    private void place(Game game, Player<?> player, int x, int y, int status) {
        game.getFieldModel().setPlayerCoordinate(player, new FieldCoordinate(x, y));
        game.getFieldModel().setPlayerState(player, new PlayerState(status).changeActive(true));
    }

    private GameState state(SetupSession session) throws Exception {
        Field field = SetupSession.class.getDeclaredField("state"); field.setAccessible(true); return (GameState) field.get(session);
    }

    private JsonObject action(JsonObject view, String kind) {
        for (JsonValue value : view.get("actions").asArray()) if (kind.equals(value.asObject().getString("kind", null))) return value.asObject();
        throw new AssertionError("Missing " + kind);
    }

    private long countEvents(SetupSession session, String kind) {
        return allEvents(session).stream().filter(event -> kind.equals(event.getString("kind", null))).count();
    }

    private List<JsonObject> allEvents(SetupSession session) {
        List<JsonObject> result = new ArrayList<>();
        for (int from = 0; ; ) {
            JsonObject page = session.transcriptPage(from, 8);
            for (JsonValue record : page.get("records").asArray()) result.addAll(events(record.asObject().get("native").asArray()));
            from = page.getInt("next", -1);
            if (from == page.getInt("total", -2)) return result;
        }
    }

    private JsonArray array(List<JsonObject> values) {
        JsonArray result = new JsonArray(); values.forEach(result::add); return result;
    }

}
