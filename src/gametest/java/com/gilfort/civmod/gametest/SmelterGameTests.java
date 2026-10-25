package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Smelter (#110, vault {@code Jobs/Smelter.md}, {@code Systems/Producing Jobs.md}): a Producing Base
 * Job smelting requested items at real furnaces, several in parallel, burning fuel from a priority list and keeping the
 * Remaining Heat of each furnace for its next charge.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SmelterGameTests {

    /** Where the Smelter JobBlock stands. */
    static final BlockPos BLOCK = new BlockPos(1, 1, 5);
    /** Where its first furnace stands. */
    static final BlockPos FURNACE = new BlockPos(3, 1, 5);

    private SmelterGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "smelter_fills_request", SmelterGameTests::fillsRequest);
            register(helper, "smelter_remaining_heat", SmelterGameTests::remainingHeat);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Smelter. */
    static Job smelter() {
        return ModJobs.SMELTER.get();
    }

    /**
     * A Clerk's Min rule on 4 iron ingots is fulfilled from raw iron at a furnace; one coal is fetched for the 4 items,
     * the other coal stays in the Clerk's storage.
     */
    private static void fillsRequest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 4), new ItemStack(Items.COAL, 3)));
        helper.assertTrue(StockRules.setRule(setup.data(), setup.clerk(), StockRule.minimum(0, Items.IRON_INGOT, 4, 0)), "Rule set");
        StockRules.check(level, setup.clerk(), ClerkGameTests.MORNING);
        Settler settler = worker(helper, setup);
        CraftspersonGameTests.catchUp(level, settler, 6000L);

        helper.assertTrue(count(level, setup.clerk(), Items.IRON_INGOT) == 4, "4 iron ingots delivered: " + count(level, setup.clerk(), Items.IRON_INGOT)
                + ", status " + settler.getWork().status(setup.block()) + ", buffer " + settler.getWork().buffer(setup.block()).stacks());
        helper.assertTrue(count(level, setup.clerk(), Items.RAW_IRON) == 0, "The raw iron was smelted");
        helper.assertTrue(count(level, setup.clerk(), Items.COAL) == 2, "One coal burned for 4 items: " + count(level, setup.clerk(), Items.COAL));
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Remaining Heat: 8 single-item charges at one furnace burn one coal; the furnace keeps the heat between the
     * charges, the second coal stays in stock.
     */
    private static void remainingHeat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 8), new ItemStack(Items.COAL, 2)));
        for (int i = 0; i < 8; i++) {
            Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 1, Optional.of(setup.clerk()), Optional.empty());
        }
        Settler settler = worker(helper, setup);
        CraftspersonGameTests.catchUp(level, settler, 12000L);

        helper.assertTrue(count(level, setup.clerk(), Items.IRON_INGOT) == 8, "8 iron ingots: " + count(level, setup.clerk(), Items.IRON_INGOT)
                + ", status " + settler.getWork().status(setup.block()));
        helper.assertTrue(count(level, setup.clerk(), Items.COAL) == 1, "One coal for 8 single-item charges: " + count(level, setup.clerk(), Items.COAL));
        helper.assertTrue(settler.getWork().buffer(setup.block()).stacks().stream().noneMatch(stack -> stack.is(Items.COAL)),
                "No coal left in the buffer: " + settler.getWork().buffer(setup.block()).stacks());
        cleanUp(setup, settler);
        helper.succeed();
    }

    // ---------------------------------------------------------------- setup

    record Setup(BlockPos clerk, BlockPos block, SettlementData data, Settlement settlement) {
    }

    /** A working Clerk whose storage holds the items, a Smelter JobBlock with a furnace and an iron ingot pattern. */
    static Setup setup(GameTestHelper helper, List<ItemStack> stock) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int i = 0; i < stock.size(); i++) {
            chest.setItem(i, stock.get(i).copy());
        }
        BlockPos block = smelterBlock(helper, BLOCK);
        helper.setBlock(FURNACE, Blocks.FURNACE);
        ironPattern(helper, block, 0);
        return new Setup(clerk, block, data, settlement);
    }

    /** Places a Smelter JobBlock. */
    static BlockPos smelterBlock(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, ModBlocks.SMELTER_BLOCK.get());
        return helper.absolutePos(relative);
    }

    /** A checked pattern smelting raw iron into an iron ingot. */
    static void ironPattern(GameTestHelper helper, BlockPos block, int slot) {
        ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> recipe = ResourceKey.create(Registries.RECIPE,
                Identifier.withDefaultNamespace("iron_ingot_from_smelting_raw_iron"));
        Pattern pattern = Patterns.transfer(helper.getLevel(), block, smelter(), slot, Optional.of(recipe), List.of(Items.RAW_IRON)).orElseThrow();
        helper.assertTrue(pattern.output() == Items.IRON_INGOT && (pattern.checked() || Patterns.toggleCheck(helper.getLevel(), block, smelter(), slot)),
                "Iron ingot pattern checked: " + pattern);
    }

    /** A settler working as Smelter at the setup's JobBlock. */
    static Settler worker(GameTestHelper helper, Setup setup) {
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), setup.data(), setup.settlement());
        settler.setCustomName(Component.literal("Ida"));
        settler.getFood().setMorningDay(Math.floorDiv(helper.getLevel().getOverworldClockTime(), 24000L));
        helper.assertTrue(TestAssignments.assign(setup.data(), setup.block(), settler.getUUID(), "Ida", setup.settlement().id(), List.of(), smelter())
                == JobAssignments.AssignResult.OK, "Smelter assigned");
        settler.addJob(new SettlerJob(smelter(), setup.block()));
        return settler;
    }

    /** How many of an item the Clerk's storage holds. */
    static int count(ServerLevel level, BlockPos clerk, Item item) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(item));
    }

    static void cleanUp(Setup setup, Settler settler) {
        TestAssignments.unassign(setup.data(), setup.clerk());
        TestAssignments.unassign(setup.data(), setup.block());
        settler.discard();
    }
}
