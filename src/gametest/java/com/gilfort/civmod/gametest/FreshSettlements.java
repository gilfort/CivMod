package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.settlement.SettlementData;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * A test environment that starts a batch without settlements (type {@code civmod:fresh_settlements}). Every {@code civmod} test
 * environment includes it. The settlements that tests found stay in the shared GameTest world otherwise, and every block change
 * of the next test structures (hundreds of thousands per test) would meet more and more of them over the run. Vanilla places the
 * structures of a batch before it sets up the environment, so the structures of one batch still meet the settlements of the batch
 * before, never more.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FreshSettlements implements TestEnvironmentDefinition<Void> {

    private static final FreshSettlements INSTANCE = new FreshSettlements();
    private static final MapCodec<FreshSettlements> CODEC = MapCodec.unit(INSTANCE);

    private FreshSettlements() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, CivMod.id("fresh_settlements"), () -> CODEC);
        event.register(Registries.TEST_FUNCTION, helper -> helper.register(
                ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("fresh_settlements_in_every_environment")),
                (Consumer<GameTestHelper>) FreshSettlements::inEveryEnvironment));
    }

    /** A new test environment that forgets this type would bring the pile-up back for its batches. */
    private static void inEveryEnvironment(GameTestHelper helper) {
        List<String> without = helper.getLevel().registryAccess().lookupOrThrow(Registries.TEST_ENVIRONMENT).listElements()
                .filter(environment -> environment.key().identifier().getNamespace().equals(CivMod.MOD_ID))
                .filter(environment -> !includesFresh(environment.value()))
                .map(environment -> environment.key().identifier().toString()).toList();
        helper.assertTrue(without.isEmpty(), "Test environments without civmod:fresh_settlements: " + without);
        helper.succeed();
    }

    private static boolean includesFresh(TestEnvironmentDefinition<?> environment) {
        return environment instanceof TestEnvironmentDefinition.AllOf(List<Holder<TestEnvironmentDefinition<?>>> definitions)
                && definitions.stream().anyMatch(definition -> definition.value() instanceof FreshSettlements);
    }

    @Override
    public Void setup(ServerLevel level) {
        SettlementData.get(level).clear();
        return null;
    }

    @Override
    public void teardown(ServerLevel level, Void saveData) {
        // Nothing to give back: the next batch clears again.
    }

    @Override
    public MapCodec<FreshSettlements> codec() {
        return CODEC;
    }
}
