package com.gilfort.civmod.job;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for paying the heat of a charge at a furnace ({@link Furnaces}, vault {@code Jobs/Smelter.md}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FurnacesGameTests {

    private FurnacesGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> helper.register(ResourceKey.create(Registries.TEST_FUNCTION,
                CivMod.id("furnaces_pay_skips_unburnable_fuel")), (Consumer<GameTestHelper>) FurnacesGameTests::paySkipsUnburnableFuel));
    }

    /**
     * A listed fuel that does not burn (here dirt, ahead of coal) is skipped: the heat is paid with the next listed fuel,
     * the unburnable item stays in the buffer.
     */
    private static void paySkipsUnburnableFuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
        BlockPos furnace = helper.absolutePos(new BlockPos(1, 1, 1));
        JobBuffer buffer = new JobBuffer();
        buffer.add(new ItemStack(Items.DIRT));
        buffer.add(new ItemStack(Items.COAL));

        boolean paid = Furnaces.pay(level, furnace, Furnaces.STANDARD_HEAT, List.of(Items.DIRT, Items.COAL), buffer);

        helper.assertTrue(paid, "Paid with the coal");
        helper.assertTrue(buffer.counts().getOrDefault(Items.DIRT, 0) == 1 && buffer.counts().getOrDefault(Items.COAL, 0) == 0,
                "Dirt kept, coal burnt: " + buffer.stacks());
        helper.assertTrue(Furnaces.heat(level, furnace) == 1600 - Furnaces.STANDARD_HEAT, "Remaining Heat: " + Furnaces.heat(level, furnace));
        helper.succeed();
    }
}
