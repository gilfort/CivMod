package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.PatternTab;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for crafting domains and the Mechanic (#94, vault {@code Systems/Producing Jobs.md}, {@code Jobs/Mechanic.md}):
 * a crafting Specialization works only recipes with an input in its domain tag, intermediates included, four times as
 * fast as the Craftsperson.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MechanicGameTests {

    private MechanicGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mechanic_domain_any_input", MechanicGameTests::domainAnyInput);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Mechanic. */
    static Job mechanic() {
        return StationTypeGameTests.job(CivMod.id("mechanic"));
    }

    /** A level-2 Craftsperson JobBlock with the Mechanic unlocked by a Crafter in its second Station Types slot. */
    static BlockPos workshop(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.CRAFTER), "A Crafter unlocks the Mechanic");
        return pos;
    }

    /**
     * A recipe belongs to the Mechanic's domain ({@code civmod:domains/redstone}) if any input is in the tag: a piston
     * (planks, cobblestone, iron, redstone) and a redstone torch are checked automatically; sticks (planks only) are
     * greyed out with the domain's state line and cannot be checked. The Craftsperson keeps every recipe.
     */
    private static void domainAnyInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job mechanic = mechanic();
        helper.assertTrue(mechanic != null && mechanic.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Mechanic specializes the Craftsperson");
        BlockPos pos = workshop(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(Patterns.setOutput(level, pos, mechanic, 0, Items.PISTON).orElseThrow().checked(), "Piston: one redstone input is enough");
        helper.assertTrue(Patterns.setOutput(level, pos, mechanic, 1, Items.REDSTONE_TORCH).orElseThrow().checked(), "Redstone torch checked");
        Pattern sticks = Patterns.setOutput(level, pos, mechanic, 2, Items.STICK).orElseThrow();
        helper.assertFalse(sticks.checked(), "Sticks are outside the domain: unchecked");
        helper.assertFalse(Patterns.toggleCheck(level, pos, mechanic, 2), "And cannot be checked");
        PatternTab tab = PatternTab.of(level, pos, mechanic);
        helper.assertTrue(tab.filtered().contains(2) && !tab.filtered().contains(0) && tab.unusable(2),
                "Greyed out: " + tab.filtered());
        helper.assertTrue(tab.filteredLine().equals(Component.translatable("civmod.gui.patterns.outside_domain")),
                "Domain state line: " + tab.filteredLine());
        helper.assertTrue(Patterns.setOutput(level, pos, CraftspersonGameTests.craftJob(), 0, Items.STICK).orElseThrow().checked(),
                "The Craftsperson crafts sticks");
        helper.succeed();
    }
}
