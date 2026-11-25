package com.fumbbl.ffb.server.factory;

import com.fumbbl.ffb.FactoryType;
import com.fumbbl.ffb.FantasyFootballException;
import com.fumbbl.ffb.RulesCollection;
import com.fumbbl.ffb.factory.IFactorySource;
import com.fumbbl.ffb.RulesCollection.Rules;
import com.fumbbl.ffb.factory.INamedObjectFactory;
import com.fumbbl.ffb.injury.InjuryType;
import com.fumbbl.ffb.model.Game;
import com.fumbbl.ffb.model.InjuryTypeConstants;
import com.fumbbl.ffb.server.IServerJsonOption;
import com.fumbbl.ffb.server.injury.injuryType.InjuryTypeDropDodgeForSpp;
import com.fumbbl.ffb.server.injury.injuryType.InjuryTypeDropJump;
import com.fumbbl.ffb.server.injury.injuryType.InjuryTypeServer;
import com.fumbbl.ffb.util.Scanner;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Kalimar
 */
@FactoryType(FactoryType.Factory.INJURY_TYPE_SERVER)
@RulesCollection(Rules.COMMON)
public class InjuryTypeServerFactory implements INamedObjectFactory {

	private final Map<String, InjuryType> injuryTypes;
	private final Map<Class<? extends InjuryType>, InjuryType> injuryTypeMap;

	private final Set<Class<InjuryTypeServer>> serverTypes = new HashSet<>();

	public InjuryTypeServerFactory() {
		injuryTypes = new HashMap<>();
		injuryTypeMap = new HashMap<>();

		try {
			Field[] fields = InjuryTypeConstants.class.getFields();
			for (Field field : fields) {
				int modifiers = field.getModifiers();
				if (Modifier.isStatic(modifiers) && InjuryType.class.isAssignableFrom(field.getType())) {
					addInjuryType((InjuryType) field.get(null));
				}
			}
		} catch (IllegalArgumentException | IllegalAccessException e) {
			throw new FantasyFootballException("Could not initialize injury type factory", e);
		}
	}

	private void addInjuryType(InjuryType injuryType) {
		injuryTypes.put(injuryType.getName().toLowerCase(Locale.ROOT), injuryType);
		injuryTypeMap.put(injuryType.getClass(), injuryType);
	}

	public InjuryTypeServer<?> forName(String name) {
		return serverType(namedType(name), null);
	}

	public InjuryTypeServer<?> forClass(Class<? extends InjuryType> c) {
		return serverType(injuryTypeMap.get(c), null);
	}

	public static InjuryTypeServer<?> restore(IFactorySource source, JsonObject saved) {
		JsonValue name = saved.get("injuryType");
		if (name == null || name.isNull()) return null;
		FactoryType.Factory type = FactoryType.Factory.INJURY_TYPE_SERVER;
		InjuryTypeServerFactory factory = source.forContext(type.context).getFactory(type);
		return factory.serverType(factory.namedType(name.asString()), IServerJsonOption.INJURY_TYPE_PLAYER_ID.getFrom(source, saved));
	}

	public static void write(JsonObject target, InjuryTypeServer<?> injury) {
		IServerJsonOption.INJURY_TYPE_SERVER.addTo(target, injury);
		if (injury != null && injury.continuationPlayerId() != null) {
			IServerJsonOption.INJURY_TYPE_PLAYER_ID.addTo(target, injury.continuationPlayerId());
		}
	}

	private InjuryType namedType(String name) {
		InjuryType type = name == null ? null : injuryTypes.get(name.toLowerCase(Locale.ROOT));
		if (type == null) throw new IllegalArgumentException("Unknown injury type: " + name);
		return type;
	}

	private InjuryTypeServer<?> serverType(InjuryType injuryType, String playerId) {
		if (injuryType == InjuryTypeConstants.DROP_DODGE_FOR_SPP) return new InjuryTypeDropDodgeForSpp(playerId);
		if (injuryType == InjuryTypeConstants.DROP_JUMP) return new InjuryTypeDropJump(playerId);
		if (playerId != null) throw new IllegalArgumentException("Unexpected injury player");
		if (injuryType == null) return null;

		Class<InjuryTypeServer> implementation = serverTypes.stream()
			.filter(clazz -> !Modifier.isAbstract(clazz.getModifiers()))
			.filter(clazz -> clazz.getGenericSuperclass() instanceof ParameterizedType parent
				&& parent.getActualTypeArguments()[0].equals(injuryType.getClass()))
			.min(Comparator.<Class<InjuryTypeServer>>comparingInt(clazz -> clazz.getSimpleName().length())
				.thenComparing(Class::getName)).orElseThrow(() ->
				new IllegalArgumentException("Missing server injury type: " + injuryType.getName()));
		try {
			return implementation.getConstructor().newInstance();
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
			throw new FantasyFootballException(e);
		}
	}

	@Override
	public void initialize(Game game) {
		serverTypes.addAll(new Scanner<>(InjuryTypeServer.class).getSubclasses());
	}
}
