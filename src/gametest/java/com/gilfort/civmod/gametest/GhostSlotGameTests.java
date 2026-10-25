package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * GameTests for Shift + right-click into ghost slots (vault {@code Systems/GUI.md}): the GUI data names the next empty
 * ghost slot of the open tab, which the screen fills with a copy of the clicked inventory item; all full → none.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GhostSlotGameTests {

    private GhostSlotGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "ghost_slot_clerk_rules", GhostSlotGameTests::clerkRules);
            register(helper, "ghost_slot_patterns", GhostSlotGameTests::patterns);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Clerk rules: the first rule slot without a rule; every active slot taken → none. */
    private static void clerkRules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        helper.assertTrue(ClerkTabData.of(level, clerk).nextEmptyGhostSlot() == 0, "No rules: the first slot");
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.BREAD, 4, 0));
        StockRules.setRule(data, clerk, StockRule.minimum(2, Items.TORCH, 4, 0));
        int next = ClerkTabData.of(level, clerk).nextEmptyGhostSlot();
        helper.assertTrue(next == 1, "The gap between the rules: " + next);
        int slots = StockRule.slots(ClerkTabData.of(level, clerk).level());
        // One rule per item: a different item in every slot.
        List<Item> items = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).limit(slots).toList();
        for (int slot = 0; slot < slots; slot++) {
            StockRules.setRule(data, clerk, StockRule.minimum(slot, items.get(slot), 1, 0));
        }
        next = ClerkTabData.of(level, clerk).nextEmptyGhostSlot();
        helper.assertTrue(next == -1, "All " + slots + " slots full: none " + next);
        helper.succeed();
    }

    /**
     * Pattern editor: the library's next empty slot; in an open pattern the output first, then the next empty grid
     * cell; a full grid with an output → none.
     */
    private static void patterns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        CraftspersonGameTests.define(helper, craft, 0, Items.STICK);
        CraftspersonGameTests.define(helper, craft, 1, Items.CHEST);
        PatternsTabData tab = PatternsTabData.of(level, craft, CraftspersonGameTests.craftJob());
        helper.assertTrue(tab.nextEmptyGhostSlot() == 2, "Library: the next empty slot " + tab.nextEmptyGhostSlot());
        helper.assertTrue(tab.nextEmptyInEditor(5) == Pattern.GRID, "A new pattern: its output first " + tab.nextEmptyInEditor(5));
        helper.assertTrue(tab.nextEmptyInEditor(1) == 4, "Chest: the empty centre cell " + tab.nextEmptyInEditor(1));
        Patterns.setOutput(level, craft, CraftspersonGameTests.craftJob(), 3, Items.IRON_BLOCK);
        tab = PatternsTabData.of(level, craft, CraftspersonGameTests.craftJob());
        helper.assertTrue(tab.nextEmptyInEditor(3) == -1, "Iron block: all cells and the output full, none " + tab.nextEmptyInEditor(3)
                + " " + tab.pattern(3));
        for (int slot = 0; slot < Pattern.SLOTS; slot++) {
            Patterns.setOutput(level, craft, CraftspersonGameTests.craftJob(), slot, Items.STICK);
        }
        tab = PatternsTabData.of(level, craft, CraftspersonGameTests.craftJob());
        helper.assertTrue(tab.nextEmptyGhostSlot() == -1, "Library full: none " + tab.nextEmptyGhostSlot());
        helper.succeed();
    }
}
