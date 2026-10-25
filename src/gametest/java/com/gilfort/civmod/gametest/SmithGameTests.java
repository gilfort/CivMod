package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.settlement.JobPlaces;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Smith and Station Extras (#111, vault {@code Jobs/Smith.md}, {@code Systems/Producing Jobs.md}): a
 * Specialization of the Craftsperson unlocked by a smithing table, crafting metal recipes and netherite upgrades at a
 * smithing table; an anvil near it replaces its base job factor 0.3 by 1.2.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SmithGameTests {

    private SmithGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "smith_unlock", SmithGameTests::unlock);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Smith. */
    static Job smith() {
        return StationTypeGameTests.job(CivMod.id("smith"));
    }

    /** A level-2 Craftsperson JobBlock with the Smith unlocked by a smithing table in its second Station Types slot. */
    static BlockPos smithy(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.SMITHING_TABLE), "A smithing table unlocks the Smith");
        return pos;
    }

    /**
     * The Smith specializes the Craftsperson (job factor 0.3, base EXP 2), is unlocked by a smithing table in a Station
     * Types slot and works {@code crafting} and {@code smithing} recipes at a smithing table.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job smith = smith();
        helper.assertTrue(smith != null && smith.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Smith specializes the Craftsperson");
        helper.assertTrue(smith.unlockItem().equals(Optional.of(Items.SMITHING_TABLE)), "Unlocked by a smithing table: " + smith.unlockItem());
        helper.assertTrue(smith.jobFactor() == 0.3 && smith.baseExp() == 2, "Job factor 0.3, base EXP 2: " + smith.jobFactor() + " / " + smith.baseExp());
        helper.assertTrue(smith.profile(ProducingProfile.class).orElseThrow().recipeTypes().equals(List.of(RecipeType.CRAFTING, RecipeType.SMITHING)),
                "Crafting and smithing recipes");
        BlockPos pos = smithy(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(smith),
                "The job picker offers the Smith");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.SMITHING_TABLE);
        helper.assertTrue(smith.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a smithing table");
        helper.assertFalse(smith.isValidWorkPlace(level, helper.absolutePos(new BlockPos(1, 0, 1))), "Not at the ground");
        helper.succeed();
    }
}
