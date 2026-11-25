package com.fumbbl.ffb.server.match;

import com.fumbbl.ffb.Weather;
import com.fumbbl.ffb.server.GameState;

import com.eclipsesource.json.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

final class PreviewCopyFixture {
    final SetupSessionMovementTest.Fixture match = new SetupSessionMovementTest.Fixture(1, true);
    final GameState state;
    final JsonObject turn;

    PreviewCopyFixture() throws ReflectiveOperationException {
        Field field = SetupSession.class.getDeclaredField("state");
        field.setAccessible(true);
        state = (GameState) field.get(match.session);
        Random dice = new Random(652);
        state.getDiceRoller().setRecoveryRoll(sides -> dice.nextInt(sides) + 1);
        // SetupSession rolls the starting weather before the fixture can seed the dice.
        state.getGame().getFieldModel().setWeather(Weather.NICE);
        turn = match.advanceToTurn();
    }

    static Method blockCopy() throws ReflectiveOperationException {
        Method method = BlockPreviewPresentation.class.getDeclaredMethod("copy");
        method.setAccessible(true);
        return method;
    }

    GameState restore(JsonObject snapshot) {
        GameState restored = new GameState(state.getServer());
        restored.initFrom(state.getServer().getFactorySource(), snapshot);
        return restored;
    }
}
