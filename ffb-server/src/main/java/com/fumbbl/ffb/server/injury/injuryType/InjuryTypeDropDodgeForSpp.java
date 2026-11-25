package com.fumbbl.ffb.server.injury.injuryType;

import com.fumbbl.ffb.FactoryType;
import com.fumbbl.ffb.FieldCoordinate;
import com.fumbbl.ffb.factory.InjuryModifierFactory;
import com.fumbbl.ffb.injury.DropDodgeForSpp;
import com.fumbbl.ffb.injury.context.InjuryContext;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.Player;
import com.fumbbl.ffb.model.property.NamedProperties;
import com.fumbbl.ffb.model.skill.Skill;
import com.fumbbl.ffb.server.DiceInterpreter;
import com.fumbbl.ffb.server.DiceRoller;
import com.fumbbl.ffb.server.GameState;
import com.fumbbl.ffb.util.UtilCards;

import java.util.Objects;
import java.util.Optional;


public class InjuryTypeDropDodgeForSpp extends ModificationAwareInjuryTypeServer<DropDodgeForSpp> {

	private final String armBarPlayerId;

	public InjuryTypeDropDodgeForSpp(Player<?> armBarPlayer) {
		this(armBarPlayer.getId());
	}

	public InjuryTypeDropDodgeForSpp(String armBarPlayerId) {
		super(new DropDodgeForSpp());
		this.armBarPlayerId = Objects.requireNonNull(armBarPlayerId, "Missing Arm Bar player");
	}

	@Override
	public String continuationPlayerId() { return armBarPlayerId; }

	@Override
	protected void armourRoll(Game game, GameState gameState, DiceRoller diceRoller, Player<?> pAttacker,
		Player<?> pDefender, FieldCoordinate pDefenderCoordinate, FieldCoordinate fromCoordinate,
		DiceInterpreter diceInterpreter, InjuryContext injuryContext, boolean roll) {

		injuryContext.setAttackerId(armBarPlayerId);

		if (!injuryContext.isArmorBroken()) {
			if (roll) {
				injuryContext.setArmorRoll(diceRoller.rollArmour());
			}
			if (UtilCards.hasUnusedSkillWithProperty(pDefender, NamedProperties.ignoresArmourModifiersFromSkills)) {
				injuryContext.addArmorModifiers(pDefender.getSkillWithProperty(NamedProperties.ignoresArmourModifiersFromSkills).getArmorModifiers());
			} else {
				Optional.ofNullable(pDefender.getSkillWithProperty(NamedProperties.blocksLikeChainsaw))
					.ifPresent(skill -> skill.getArmorModifiers().forEach(injuryContext::addArmorModifier));
			}
			injuryContext.setArmorBroken(diceInterpreter.isArmourBroken(gameState, injuryContext));
		}

		Skill avOrInjModifierSkill = avOrInjModifierSkill(game, pDefender);

		if (!injuryContext.isArmorBroken() && avOrInjModifierSkill != null) {
			avOrInjModifierSkill.getArmorModifiers().forEach(injuryContext::addArmorModifier);
			injuryContext.setArmorBroken(diceInterpreter.isArmourBroken(gameState, injuryContext));
		}
	}

	@Override
	protected void injuryRoll(Game game, GameState gameState, DiceRoller diceRoller, Player<?> pAttacker,
		Player<?> pDefender, FieldCoordinate pDefenderCoordinate, FieldCoordinate fromCoordinate,
		InjuryContext injuryContext) {

		injuryContext.setAttackerId(armBarPlayerId);

		Skill avOrInjModifierSkill = avOrInjModifierSkill(game, pDefender);

		if (avOrInjModifierSkill != null
			&& avOrInjModifierSkill.getArmorModifiers().stream().anyMatch(injuryContext::hasArmorModifier)) {
			avOrInjModifierSkill = null;
		}

		injuryContext.setInjuryRoll(diceRoller.rollInjury());
		InjuryModifierFactory factory = game.getFactory(FactoryType.Factory.INJURY_MODIFIER);
		factory.findInjuryModifiers(game, injuryContext, pAttacker,
			pDefender, isStab(), isFoul(), isVomitLike()).forEach(injuryModifier -> injuryContext.addInjuryModifier(injuryModifier));
		if (avOrInjModifierSkill != null) {
			avOrInjModifierSkill.getInjuryModifiers().forEach(injuryContext::addInjuryModifier);
		}

		setInjury(pDefender, gameState, diceRoller, injuryContext);
	}

	private Skill avOrInjModifierSkill(Game game, Player<?> pDefender) {
		if (!UtilCards.hasUnusedSkillWithProperty(pDefender, NamedProperties.ignoresArmourModifiersFromSkills)) {
			return game.getPlayerById(armBarPlayerId).getSkillWithProperty(NamedProperties.affectsEitherArmourOrInjuryOnDodge);
		}

		return null;
	}
}
