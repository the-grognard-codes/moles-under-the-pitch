package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.IDialogParameter;
import com.fumbbl.ffb.PlayerChoiceMode;
import com.fumbbl.ffb.TurnMode;
import com.fumbbl.ffb.dialog.DialogApothecaryChoiceParameter;
import com.fumbbl.ffb.dialog.DialogArgueTheCallParameter;
import com.fumbbl.ffb.dialog.DialogBlockRollPropertiesParameter;
import com.fumbbl.ffb.dialog.DialogBriberyAndCorruptionParameter;
import com.fumbbl.ffb.dialog.DialogBribesParameter;
import com.fumbbl.ffb.dialog.DialogConfirmEndActionParameter;
import com.fumbbl.ffb.dialog.DialogDefenderActionParameter;
import com.fumbbl.ffb.dialog.DialogFollowupChoiceParameter;
import com.fumbbl.ffb.dialog.DialogInterceptionParameter;
import com.fumbbl.ffb.dialog.DialogKickSkillParameter;
import com.fumbbl.ffb.dialog.DialogOpponentBlockSelectionPropertiesParameter;
import com.fumbbl.ffb.dialog.DialogPileDriverParameter;
import com.fumbbl.ffb.dialog.DialogPlayerChoiceParameter;
import com.fumbbl.ffb.dialog.DialogReRollBlockForTargetsPropertiesParameter;
import com.fumbbl.ffb.dialog.DialogReRollPropertiesParameter;
import com.fumbbl.ffb.dialog.DialogReceiveChoiceParameter;
import com.fumbbl.ffb.dialog.DialogSkillUseParameter;
import com.fumbbl.ffb.dialog.DialogUseApothecariesParameter;
import com.fumbbl.ffb.dialog.DialogUseApothecaryParameter;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.Player;
import com.fumbbl.ffb.model.Team;
import com.fumbbl.ffb.model.property.NamedProperties;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.server.step.StepId;
import com.fumbbl.ffb.server.step.bb2025.block.StepBlockRoll;

import java.util.Set;

/** Describes a pending native input without enumerating the commands it can accept. */
record PublicMatchDecision(String actor, String kind) {
    private static final Set<String> KINDS = Set.of("coin", "receive", "setup", "kickoff", "touchback",
        "kickoffEvent", "turn", "blockDie", "rerollDie", "reroll", "push", "followUp", "skill", "kickSkill", "apothecary",
        "interception", "argueTheCall", "bribe", "endAction", "secretWeapon", "pileDriver", "hitAndRun", "reaction",
        "reactionMovement", "engine", "none");

    static boolean isKind(String kind) { return KINDS.contains(kind); }

    static PublicMatchDecision read(GameState state) {
        Game game = state.getGame();
        String active = game.isHomePlaying() ? "home" : "away";
        if (game.getFinished() != null) return new PublicMatchDecision(active, "none");
        if (state.getCurrentStep() == null) return new PublicMatchDecision(active, "engine");

        StepId step = state.getCurrentStep().getId();
        IDialogParameter dialog = game.getDialogParameter();
        TurnMode mode = game.getTurnMode();
        if (step == StepId.COIN_CHOICE) return new PublicMatchDecision(active, "coin");
        if (step == StepId.RECEIVE_CHOICE && dialog instanceof DialogReceiveChoiceParameter choice)
            return team(game, choice.getChoosingTeamId(), "receive");
        if (step == StepId.SETUP) return new PublicMatchDecision(active, "setup");
        if (step == StepId.KICKOFF) return new PublicMatchDecision(active, "kickoff");
        if (mode == TurnMode.TOUCHBACK) return team(game, game.getOtherTeam(game.getActingTeam()).getId(), "touchback");

        PublicMatchDecision prompt = switch (dialog) {
            case DialogBlockRollPropertiesParameter choice -> team(game, choice.getChoosingTeamId(),
                state.getCurrentStep() instanceof StepBlockRoll roll && !roll.getRerollDieIndexes().isEmpty()
                    ? "rerollDie" : "blockDie");
            case DialogReRollBlockForTargetsPropertiesParameter choice -> player(game, choice.getPlayerId(), "blockDie");
            case DialogOpponentBlockSelectionPropertiesParameter choice -> team(game, choice.getTeamId(), "blockDie");
            case DialogReRollPropertiesParameter choice -> player(game, choice.getPlayerId(), "reroll");
            case DialogFollowupChoiceParameter ignored -> player(game, game.getActingPlayer().getPlayerId(), "followUp");
            case DialogPileDriverParameter choice -> team(game, choice.getTeamId(), "pileDriver");
            case DialogKickSkillParameter choice -> player(game, choice.getPlayerId(), "kickSkill");
            case DialogSkillUseParameter choice -> player(game, choice.getPlayerId(),
                choice.getSkill().hasSkillProperty(NamedProperties.canReduceKickDistance) ? "kickSkill" : "skill");
            case DialogUseApothecariesParameter choice -> team(game, choice.getTeamId(), "apothecary");
            case DialogUseApothecaryParameter choice -> player(game, choice.getPlayerId(), "apothecary");
            case DialogApothecaryChoiceParameter choice -> player(game, choice.getPlayerId(), "apothecary");
            case DialogBriberyAndCorruptionParameter choice -> team(game, choice.getTeamId(), "reroll");
            case DialogConfirmEndActionParameter choice -> team(game, choice.getTeamId(), "endAction");
            case DialogArgueTheCallParameter choice -> team(game, choice.getTeamId(), "argueTheCall");
            case DialogBribesParameter choice when step == StepId.BRIBES || step == StepId.END_TURN ->
                team(game, choice.getTeamId(), "bribe");
            case DialogInterceptionParameter choice -> {
                Player<?> thrower = game.getPlayerById(choice.getThrowerId());
                yield thrower == null ? null : team(game, game.getOtherTeam(thrower.getTeam()).getId(), "interception");
            }
            case DialogPlayerChoiceParameter choice -> playerChoice(game, step, mode, choice);
            case null, default -> null;
        };
        if (prompt != null) return prompt;

        if (mode == TurnMode.PASS_BLOCK || mode == TurnMode.KICKOFF_RETURN)
            return new PublicMatchDecision(active, "reactionMovement");
        if (mode == TurnMode.DUMP_OFF && dialog instanceof DialogDefenderActionParameter && game.getThrower() != null)
            return player(game, game.getThrower().getId(), "reaction");
        if (mode == TurnMode.TRICKSTER || mode == TurnMode.SAFE_PAIR_OF_HANDS)
            return new PublicMatchDecision(active, "reaction");
        if (mode == TurnMode.HIT_AND_RUN && step == StepId.HIT_AND_RUN)
            return new PublicMatchDecision(active, "hitAndRun");
        if (dialog == null) for (var square : game.getFieldModel().getPushbackSquares()) {
            if (!square.isLocked()) return new PublicMatchDecision(square.isHomeChoice() ? "home" : "away", "push");
        }
        if (mode == TurnMode.HIGH_KICK || mode == TurnMode.QUICK_SNAP || mode == TurnMode.SOLID_DEFENCE)
            return new PublicMatchDecision(active, "kickoffEvent");
        if (mode == TurnMode.SELECT_BLITZ_TARGET && game.getActingPlayer().getPlayer() != null
            || new CoreTurnActions(state).turnActionContext())
            return new PublicMatchDecision(active, "turn");
        return new PublicMatchDecision(active, "engine");
    }

    private static PublicMatchDecision playerChoice(Game game, StepId step, TurnMode mode, DialogPlayerChoiceParameter choice) {
        PlayerChoiceMode choiceMode = choice.getPlayerChoiceMode();
        if (new ReactiveSkillActions().covered(choiceMode)) return team(game, choice.getTeamId(), "reaction");
        if (choiceMode == PlayerChoiceMode.SECRET_WEAPON) return team(game, choice.getTeamId(), "secretWeapon");
        if (step == StepId.APPLY_KICKOFF_RESULT && mode != TurnMode.SOLID_DEFENCE
            && (choiceMode == PlayerChoiceMode.SOLID_DEFENCE || choiceMode == PlayerChoiceMode.CHARGE || choiceMode == PlayerChoiceMode.QUICK_SNAP))
            return team(game, choice.getTeamId(), "kickoffEvent");
        return null;
    }

    private static PublicMatchDecision player(Game game, String id, String kind) {
        Player<?> player = id == null ? null : game.getPlayerById(id);
        return player == null ? new PublicMatchDecision(game.isHomePlaying() ? "home" : "away", "engine")
            : team(game, player.getTeam().getId(), kind);
    }

    private static PublicMatchDecision team(Game game, String id, String kind) {
        Team home = game.getTeamHome();
        if (home.getId().equals(id)) return new PublicMatchDecision("home", kind);
        if (game.getTeamAway().getId().equals(id)) return new PublicMatchDecision("away", kind);
        return new PublicMatchDecision(game.isHomePlaying() ? "home" : "away", "engine");
    }
}
