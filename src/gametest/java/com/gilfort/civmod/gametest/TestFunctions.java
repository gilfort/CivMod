package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Registers GameTest functions under the mod's namespace. */
final class TestFunctions {

    private TestFunctions() {
    }

    /** Registers a test function as {@code civmod:<name>}. */
    static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }
}
