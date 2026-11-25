package com.fumbbl.ffb.server.model;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.fumbbl.ffb.ApothecaryMode;
import com.fumbbl.ffb.FactoryType;
import com.fumbbl.ffb.factory.IFactorySource;
import com.fumbbl.ffb.json.IJsonSerializable;
import com.fumbbl.ffb.json.UtilJson;
import com.fumbbl.ffb.server.IServerJsonOption;
import com.fumbbl.ffb.server.InjuryResult;
import com.fumbbl.ffb.server.factory.InjuryTypeServerFactory;
import com.fumbbl.ffb.server.factory.bb2025.DeferredCommandFactory;
import com.fumbbl.ffb.server.injury.injuryType.InjuryTypeServer;
import com.fumbbl.ffb.server.step.DeferredCommand;
import com.fumbbl.ffb.server.step.DeferredCommandId;
import com.fumbbl.ffb.server.step.bb2025.command.DropPlayerCommand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SteadyFootingContext implements IJsonSerializable {
	private DropPlayerContext dropPlayerContext;
	private InjuryTypeServer<?> injuryType;
	private InjuryResult injuryResult;
	private final List<DeferredCommand> deferredCommands = new ArrayList<>();
	private boolean legacyCommandNames;

	public SteadyFootingContext() {}

	public SteadyFootingContext(DropPlayerContext dropPlayerContext) {
		this(dropPlayerContext, null);
	}

	public SteadyFootingContext(InjuryTypeServer<?> injuryType) {
		this(injuryType, null);
	}

	@SuppressWarnings("unused")
	public SteadyFootingContext(InjuryResult injuryResult) {
		this(injuryResult, null);
	}

	public SteadyFootingContext(DropPlayerContext dropPlayerContext, List<DeferredCommand> deferredCommands) {
		this.dropPlayerContext = dropPlayerContext;
		if (deferredCommands != null) {
			this.deferredCommands.addAll(deferredCommands);
		}
	}

	public SteadyFootingContext(InjuryTypeServer<?> injuryType, List<DeferredCommand> deferredCommands) {
		this.injuryType = injuryType;
		if (deferredCommands != null) {
			this.deferredCommands.addAll(deferredCommands);
		}
	}

	public SteadyFootingContext(InjuryResult injuryResult, List<DeferredCommand> deferredCommands) {
		this.injuryResult = injuryResult;
		if (deferredCommands != null) {
			this.deferredCommands.addAll(deferredCommands);
		}
	}

	public DropPlayerContext getDropPlayerContext() {
		return dropPlayerContext;
	}

	public InjuryTypeServer<?> getInjuryType() {
		return injuryType;
	}

	public InjuryResult getInjuryResult() {
		return injuryResult;
	}

	public ApothecaryMode getApothecaryMode() {
		if (dropPlayerContext != null) {
			return dropPlayerContext.getApothecaryMode();
		}
		if (injuryResult != null) {
			return injuryResult.injuryContext().getApothecaryMode();
		}
		return ApothecaryMode.ATTACKER;
	}

	public List<DeferredCommand> getDeferredCommands() {
		return Collections.unmodifiableList(deferredCommands);
	}

	@Override
	public SteadyFootingContext initFrom(IFactorySource source, JsonValue jsonValue) {

		DeferredCommandFactory factory = source.getFactory(FactoryType.Factory.DEFERRED_COMMAND);

		JsonObject jsonObject = UtilJson.toJsonObject(jsonValue);
		if (IServerJsonOption.DROP_PLAYER_CONTEXT.isDefinedIn(jsonObject)) {
			dropPlayerContext = new DropPlayerContext().initFrom(source, IServerJsonOption.DROP_PLAYER_CONTEXT.getFrom(source, jsonObject));
		}

		if (IServerJsonOption.INJURY_RESULT.isDefinedIn(jsonObject)) {
			injuryResult = new InjuryResult().initFrom(source, IServerJsonOption.INJURY_RESULT.getFrom(source, jsonObject));
		}

		injuryType = InjuryTypeServerFactory.restore(source, jsonObject);
		JsonArray commandArray = IServerJsonOption.DEFERRED_COMMANDS.getFrom(source, jsonObject);
		deferredCommands.clear();
		legacyCommandNames = !commandArray.isEmpty() && commandArray.get(0).isString();
		for (JsonValue saved : commandArray) {
			if (legacyCommandNames) {
				deferredCommands.add(restoreLegacyCommand(factory, saved.asString()));
			} else {
				DeferredCommandId id = (DeferredCommandId) IServerJsonOption.DEFERRED_COMMAND_ID.getFrom(source, saved.asObject());
				if (id == null) throw new IllegalArgumentException("Missing deferred command ID");
				deferredCommands.add(factory.forId(id).initFrom(source, saved));
			}
		}
		return this;
	}

	private DeferredCommand restoreLegacyCommand(DeferredCommandFactory factory, String name) {
		if (DeferredCommandId.DROP_PLAYER.getName().equals(name)) {
			// Both producers' old contexts already identify the player being dropped.
			String playerId = dropPlayerContext != null ? dropPlayerContext.getPlayerId()
				: injuryResult == null ? null : injuryResult.injuryContext().getDefenderId();
			if (playerId == null) throw new IllegalArgumentException("Missing deferred drop player");
			return new DropPlayerCommand(playerId, getApothecaryMode(), true);
		}
		DeferredCommand command = (DeferredCommand) factory.forName(name);
		if (command == null) throw new IllegalArgumentException("Unknown deferred command: " + name);
		return command;
	}

	@Override
	public JsonObject toJsonValue() {
		JsonObject jsonObject = new JsonObject();
		JsonArray commandArray = new JsonArray();
		for (DeferredCommand command : deferredCommands)
			commandArray.add(legacyCommandNames ? JsonValue.valueOf(command.getName()) : command.toJsonValue());
		IServerJsonOption.DEFERRED_COMMANDS.addTo(jsonObject, commandArray);
		if (dropPlayerContext != null) {
			IServerJsonOption.DROP_PLAYER_CONTEXT.addTo(jsonObject, dropPlayerContext.toJsonValue());
		}
		if (injuryResult != null) {
			IServerJsonOption.INJURY_RESULT.addTo(jsonObject, injuryResult.toJsonValue());
		}
		if (injuryType != null) {
			InjuryTypeServerFactory.write(jsonObject, injuryType);
		}
		return jsonObject;
	}
}
