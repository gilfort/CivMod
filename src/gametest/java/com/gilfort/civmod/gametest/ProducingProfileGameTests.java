package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.PatternTabData;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Producing profile of the addon API (#88): automatic checkmarks, the Craftsperson as the first
 * profile.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ProducingProfileGameTests {

    private ProducingProfileGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "producing_automatic_checkmarks", ProducingProfileGameTests::automaticCheckmarks);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * A new pattern is checked automatically while the limit allows; at the limit it stays unchecked and the Patterns tab
     * shows the state line until the next toggle; replacing a pattern keeps its checkmark.
     */
    private static void automaticCheckmarks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        Item[] outputs = {Items.STICK, Items.OAK_PLANKS, Items.TORCH, Items.CHEST, Items.STONE_PICKAXE, Items.STONE_AXE};
        for (int slot = 0; slot < outputs.length; slot++) {
            Pattern pattern = Patterns.setOutput(level, pos, CraftspersonGameTests.craftJob(), slot, outputs[slot]).orElseThrow();
            helper.assertTrue(pattern.checked(), "Below the limit of 6: " + outputs[slot] + " checked automatically");
        }
        helper.assertTrue(PatternTabData.of(level, pos, CraftspersonGameTests.craftJob()).notice().isEmpty(), "No state line below the limit");

        Pattern ladder = Patterns.setOutput(level, pos, CraftspersonGameTests.craftJob(), 6, Items.LADDER).orElseThrow();
        helper.assertFalse(ladder.checked(), "At the limit: the new pattern stays unchecked");
        String line = PatternTabData.of(level, pos, CraftspersonGameTests.craftJob()).notice().map(component -> component.getString()).orElse("");
        helper.assertTrue(line.equals("A Craftsperson masters only 6 recipes at once – choose which to check."), "State line: " + line);

        helper.assertFalse(Patterns.toggleCheck(level, pos, CraftspersonGameTests.craftJob(), 0), "Unchecking the sticks frees a checkmark");
        helper.assertTrue(PatternTabData.of(level, pos, CraftspersonGameTests.craftJob()).notice().isEmpty(), "The line disappears on the next toggle");

        helper.assertTrue(Patterns.setOutput(level, pos, CraftspersonGameTests.craftJob(), 1, Items.BOWL).orElseThrow().checked(), "Replacing a checked pattern keeps its checkmark");
        helper.assertFalse(Patterns.setOutput(level, pos, CraftspersonGameTests.craftJob(), 6, Items.STICK).orElseThrow().checked(), "Replacing an unchecked pattern keeps it unchecked");
        helper.assertTrue(Patterns.setOutput(level, pos, CraftspersonGameTests.craftJob(), 8, Items.LADDER).orElseThrow().checked(), "A free checkmark: the next new pattern is checked");
        helper.succeed();
    }
}
