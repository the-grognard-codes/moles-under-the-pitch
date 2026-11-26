package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Public BB2025 report fields, keyed by the native report ID. */
final class PublicNativeReports {
    private final PublicMatchFields fields = new PublicMatchFields();

    private static final Map<String, Set<String>> FIELDS = fields();

    JsonObject report(JsonObject source) {
        String id = source.getString("reportId", "");
        Set<String> allowed = FIELDS.get(id);
        if (allowed == null) return null;
        JsonObject report = new JsonObject().add("reportId", id);
        for (JsonObject.Member member : source) {
            String field = member.getName();
            if (!allowed.contains(field)) continue;
            JsonValue value = member.getValue();
            if (field.equals("knockoutRecoveryArray")) {
                fields.objects(source, report, field, recovery ->
                    fields.scalars(recovery, "playerId", "recovering", "roll", "bloodweiserBabes", "bugmansXXXXXXModifier"));
            } else if (field.equals("heatExhaustionArray")) {
                fields.objects(source, report, field, heat -> fields.scalars(heat, "playerId", "exhausted", "roll"));
            } else if (field.equals("statBasedRollModifier")) {
                fields.object(source, report, field, modifier -> fields.scalars(modifier, "reason", "name", "value"));
            } else if (value instanceof JsonArray entries) {
                report.add(field, fields.values(entries));
            } else if (!value.isObject()) {
                report.add(field, value);
            }
        }
        fields.object(source, report, "logActors", actors -> fields.scalars(actors, "version", "actorId", "targetId"));
        fields.object(source, report, "logTest", test -> fields.scalars(test, "version", "rerolled"));
        fields.object(source, report, "logBlock", block -> fields.scalars(block, "version", "attackerId", "defenderId", "chooser"));
        fields.object(source, report, "logRoll", roll -> {
            JsonObject result = fields.scalars(roll, "version", "base", "target", "modifier");
            fields.object(roll, result, "square", fields::square);
            return result;
        });
        fields.object(source, report, "numericDice", dice -> {
            JsonObject result = fields.scalars(dice, "version");
            fields.objects(dice, result, "dice", die -> fields.scalars(die, "sides", "value", "label"));
            return result;
        });
        return report;
    }

    private static Map<String, Set<String>> fields() {
        Map<String, Set<String>> schema = new HashMap<>();
        allow(schema, "nervesOfSteel",
            "ballAction bomb playerId");
        allow(schema, "alwaysHungryRoll escapeRoll goForItRoll leapRoll rightStuffRoll regenerationRoll jumpUpRoll bloodLustRoll animosityRoll lookIntoMyEyesRoll allYouCanEat catchOfTheDay steadyFootingRoll saboteurRoll",
            "minimumRoll playerId reRolled roll rollModifiers successful");
        allow(schema, "pickUpRoll",
            "minimumRoll playerId reRolled roll rollModifiers secureTheBallUsed successful");
        allow(schema, "argueTheCall",
            "biasedRefs coachBanned friendsWithRef playerId roll staysOnPitch successful");
        allow(schema, "catchRoll",
            "bomb minimumRoll playerId reRolled roll rollModifiers successful");
        allow(schema, "cloudBurster",
            "interceptorId throwerId");
        allow(schema, "confusionRoll",
            "confusionSkill minimumRoll playerId reRolled roll rollModifiers successful");
        allow(schema, "dauntlessRoll",
            "defenderId minimumRoll playerId reRolled roll rollModifiers strength successful");
        allow(schema, "dodgeRoll",
            "minimumRoll playerId reRolled roll rollModifiers statBasedRollModifier successful");
        allow(schema, "foulAppearanceRoll chainsawRoll hypnoticGazeRoll projectileVomit",
            "defenderId minimumRoll playerId reRolled roll rollModifiers successful");
        allow(schema, "interceptionRoll",
            "bomb ignoreAgility minimumRoll playerId reRolled roll rollModifiers successful");
        allow(schema, "passRoll",
            "bomb hailMaryPass minimumRoll passResult passingDistance playerId reRolled roll rollModifiers statBasedRollModifier successful");
        allow(schema, "tentaclesShadowingRoll",
            "defenderId minimumRoll reRolled roll skill successful");
        allow(schema, "skillUse",
            "playerId skill skillUse used");
        allow(schema, "reRoll",
            "playerId reRollSource roll successful");
        allow(schema, "turnEnd",
            "heatExhaustionArray heatRoll knockoutRecoveryArray playerIdTouchdown");
        allow(schema, "playerAction",
            "actingPlayerId playerAction");
        allow(schema, "foul block",
            "defenderId");
        allow(schema, "handOver",
            "catcherId");
        allow(schema, "injury",
            "armorBroken armorModifiers armorRoll attackerId casualtyModifiers casualtyRoll casualtyRollDecay defenderId injury injuryDecay injuryModifiers injuryRoll injuryType seriousInjury seriousInjuryDecay seriousInjuryOld skipInjuryParts");
        allow(schema, "apothecaryRoll",
            "casualtyModifiers casualtyRoll playerId playerState seriousInjury seriousInjuryOld");
        allow(schema, "apothecaryChoice",
            "playerId playerState seriousInjury");
        allow(schema, "throwIn",
            "direction directionRoll distanceRoll");
        allow(schema, "scatterBall",
            "directionArray gustOfWind rolls");
        allow(schema, "blockChoice",
            "blockResult blockRoll defenderId diceIndex nrOfDice showNameInReport suppressExtraEffectHandling");
        allow(schema, "weather",
            "weather weatherRoll");
        allow(schema, "coinThrow",
            "coach coinChoiceHeads coinThrowHeads");
        allow(schema, "receiveChoice",
            "receiveChoice teamId");
        allow(schema, "kickoffResult",
            "kickoffResult kickoffRoll");
        allow(schema, "kickoffScatter",
            "ballCoordinateEnd rollScatterDirection rollScatterDistance scatterDirection scatterDistanceSides");
        allow(schema, "extraReRoll",
            "rollAway rollHome teamId");
        allow(schema, "kickoffTimeout",
            "turnModifier turnNr");
        allow(schema, "solidDefenceRoll quickSnapRoll blitzRoll dwarfenWisdomRoll",
            "nrOfPlayers roll teamId");
        allow(schema, "kickoffSequenceActivationsCount",
            "nrOfPlayers nrOfPlayersAllowed number");
        allow(schema, "kickoffSequenceActivationsExhausted",
            "limitReached");
        allow(schema, "pushback",
            "defenderId pushbackMode");
        allow(schema, "referee",
            "foulingPlayerBanned underScrutiny");
        allow(schema, "kickoffPitchInvasion",
            "amount playerIds rollAway rollHome");
        allow(schema, "dodgySnackRoll",
            "playerId roll");
        allow(schema, "kickoffDodgySnack",
            "playerIds rollAway rollHome");
        allow(schema, "throwTeamMateRoll",
            "kicked minimumRoll passResult passingDistance playerId reRolled roll rollModifiers successful thrownPlayerId");
        allow(schema, "dedicatedFans",
            "conceded dedicatedFansModifierAway dedicatedFansModifierHome rollAway rollHome teamId");
        allow(schema, "scatterPlayer",
            "directionArray endCoordinate isScatter rolls startCoordinate");
        allow(schema, "swoopPlayer",
            "distance endCoordinate outOfBounds scatterDirection startCoordinate");
        allow(schema, "timeoutEnforced",
            "coach");
        allow(schema, "winnings",
            "winningsAway winningsHome");
        allow(schema, "mostValuablePlayers",
            "playerIdsAway playerIdsHome");
        allow(schema, "defectingPlayers",
            "defectingArray playerIds rolls");
        allow(schema, "standUpRoll",
            "modifier playerId reRolled roll successful");
        allow(schema, "bribesRoll throwAtStallingPlayer pickMeUp throwAtPlayer",
            "playerId roll successful");
        allow(schema, "masterChefRoll",
            "masterChefRoll reRollsStolen teamId");
        allow(schema, "startHalf",
            "half");
        allow(schema, "inducement",
            "inducementType teamId value");
        allow(schema, "leader",
            "leaderState teamId");
        allow(schema, "secretWeaponBan",
            "banArray playerIds rolls");
        allow(schema, "raiseDead",
            "nurglesRot playerId positionName");
        allow(schema, "blockRoll",
            "blockRoll choosingTeamId defenderId");
        allow(schema, "penaltyShootout",
            "homeTeam penaltyScoreAway penaltyScoreHome rollAway rollCount rollHome teamId");
        allow(schema, "doubleHiredStarPlayer",
            "starPlayerName");
        allow(schema, "doubleHiredStaff",
            "name");
        allow(schema, "spellEffectRoll",
            "playerId roll specialEffect successful");
        allow(schema, "wizardUse",
            "teamId wizardSpell");
        allow(schema, "passBlock",
            "passBlockAvailable teamId");
        allow(schema, "playCard",
            "card playerId teamId");
        allow(schema, "cardDeactivated",
            "card");
        allow(schema, "bombOutOfBounds kickTeamMateFumble",
            "");
        allow(schema, "freePettyCash",
            "gold teamId");
        allow(schema, "riotousRookies",
            "riotousAmount riotousRoll teamId");
        allow(schema, "swarmingPlayersRoll",
            "swarmingPlayerRoll teamId");
        allow(schema, "fanFactor",
            "dedicatedFans dedicatedFansResult dedicatedFansRoll teamId");
        allow(schema, "selectBlitzTarget selectGazeTarget animalSavagery",
            "attackerId defenderId");
        allow(schema, "placedBallDirection hitAndRun",
            "direction playerId");
        allow(schema, "fumblerooskie",
            "playerId used");
        allow(schema, "brilliantCoachingReRoll",
            "rerollBrilliantCoachingOneDrive teamId");
        allow(schema, "pumpUpTheCrowdReRollLost",
            "rerollPumpUpTheCrowdOneDrive teamId");
        allow(schema, "showStarReRollLost",
            "rerollShowStarOneDrive teamId");
        allow(schema, "blockReRoll",
            "blockRoll blockRollAfterReroll defenderId playerId reRollSource rerolledIndexes");
        allow(schema, "trapDoor",
            "escaped playerId roll");
        allow(schema, "prayerAmount",
            "homeTeam number opponentTeamValue teamValue");
        allow(schema, "prayerRoll",
            "homeTeam roll teamName");
        allow(schema, "prayerEnd",
            "prayer");
        allow(schema, "prayerWasted",
            "name playerId");
        allow(schema, "cheeringFans",
            "rollAway rollHome teamIdsAdditionalAssist teamIdsReRolledCheeringFangs");
        allow(schema, "briberyAndCorruptionReRoll",
            "briberyAncCorruptionAction teamId");
        allow(schema, "playerEvent",
            "message playerId");
        allow(schema, "stallerDetected pumpUpTheCrowdReRoll showStarReRoll",
            "playerId");
        allow(schema, "indomitable",
            "defenderId playerId");
        allow(schema, "oldPro",
            "oldRoll playerId roll selfInflicted");
        allow(schema, "skillWasted",
            "playerId skill");
        allow(schema, "modifiedPassResult",
            "passResult skill");
        allow(schema, "modifiedDodgeResultSuccessful",
            "skill");
        allow(schema, "raidingParty",
            "direction playerId playerIdOtherPlayer");
        allow(schema, "event",
            "message");
        allow(schema, "thrownKeg thenIStartedBlastin",
            "fumble playerId roll successful targetPlayerId");
        allow(schema, "biasedRef",
            "foulingPlayerBanned roll");
        allow(schema, "weatherMageRoll",
            "weatherRoll");
        allow(schema, "weatherMageResult",
            "effect modifier oldWeather weather");
        allow(schema, "balefulHex",
            "minimumRoll playerId reRolled roll rollModifiers successful targetPlayerId");
        allow(schema, "breatheFire",
            "defenderId minimumRoll playerId reRolled roll rollModifiers status strongOpponent successful");
        allow(schema, "prayersAndInducementsBought",
            "gold nrOfInducements nrOfMercenaries nrOfStars teamId teamValue");
        allow(schema, "mascotUsed",
            "minimumRoll reRollUsed roll successful teamId");
        allow(schema, "teamEvent",
            "message teamId");
        allow(schema, "teamCaptainRoll",
            "minimumRoll roll successful teamId");
        allow(schema, "gettingEvenRoll",
            "keyword minimumRoll playerId reRolled roll rollModifiers successful");
        allow(schema, "chompRoll",
            "attackerId defenderId minimumRoll playerId reRolled roll rollModifiers successful");
        allow(schema, "chompRemoved",
            "playerId successful");
        allow(schema, "puntDirectionRoll swoopDirectionRoll",
            "direction directionRoll outOfBounds playerId");
        allow(schema, "puntDistanceRoll",
            "outOfBounds roll");
        return Map.copyOf(schema);
    }

    private static void allow(Map<String, Set<String>> schema, String ids, String fields) {
        Set<String> allowed = fields.isEmpty() ? Set.of() : Set.of(fields.split(" "));
        for (String id : ids.split(" ")) schema.put(id, allowed);
    }
}
