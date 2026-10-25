package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.Fatigue;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.WorkPlaceLock;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Fuels;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.OccupiedPlaces;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
            register(helper, "smelter_missing_fuel", SmelterGameTests::missingFuel);
            register(helper, "smelter_fuel_priority", SmelterGameTests::fuelPriority);
            register(helper, "smelter_parallel_furnaces", SmelterGameTests::parallelFurnaces);
            register(helper, "smelter_player_furnace", SmelterGameTests::playerFurnace);
            register(helper, "smelter_broken_furnace", SmelterGameTests::brokenFurnace);
            register(helper, "smelter_speed_and_exp", SmelterGameTests::speedAndExp);
            register(helper, "smelter_two_smelters", SmelterGameTests::twoSmelters);
            register(helper, "smelter_live_lit_furnaces", SmelterGameTests::liveLitFurnaces);
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

    /** No listed fuel in stock: nothing is smelted, the job says "Missing: Fuel", the request names the first fuel. */
    private static void missingFuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 4), new ItemStack(Items.OAK_LOG, 4)));
        Request request = Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 2, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup);
        CraftspersonGameTests.catchUp(level, settler, 3000L);

        helper.assertTrue(count(level, setup.clerk(), Items.IRON_INGOT) == 0 && count(level, setup.clerk(), Items.RAW_IRON) == 4,
                "Nothing smelted without fuel (oak logs are not listed)");
        helper.assertTrue(settler.getWork().status(setup.block()) == JobStatus.MISSING_FUEL, "Missing: Fuel " + settler.getWork().status(setup.block()));
        List<Item> missing = Requests.get(setup.settlement(), request.id()).orElseThrow().missing().stream().map(Request.Missing::item).toList();
        helper.assertTrue(missing.equals(List.of(Items.COAL)), "The request names the first listed fuel: " + missing);
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * The fuel list's order is the priority: with charcoal moved before coal (◀ on charcoal), charcoal is burnt and the
     * coal stays in stock.
     */
    private static void fuelPriority(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 4), new ItemStack(Items.COAL, 1), new ItemStack(Items.CHARCOAL, 1)));
        helper.assertTrue(Fuels.of(level, setup.block()).equals(List.of(Items.COAL, Items.CHARCOAL)), "Default: coal, then charcoal");
        helper.assertTrue(Fuels.move(level, setup.block(), 1, -1), "Charcoal moved left");
        helper.assertTrue(Fuels.of(level, setup.block()).equals(List.of(Items.CHARCOAL, Items.COAL)), "Charcoal first: " + Fuels.of(level, setup.block()));
        Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 4, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup);
        CraftspersonGameTests.catchUp(level, settler, 3000L);

        helper.assertTrue(count(level, setup.clerk(), Items.IRON_INGOT) == 4, "4 ingots: " + count(level, setup.clerk(), Items.IRON_INGOT));
        helper.assertTrue(count(level, setup.clerk(), Items.CHARCOAL) == 0 && count(level, setup.clerk(), Items.COAL) == 1,
                "Charcoal burnt, coal kept: charcoal " + count(level, setup.clerk(), Items.CHARCOAL) + ", coal " + count(level, setup.clerk(), Items.COAL));
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A level-3 JobBlock smelts at 3 furnaces in parallel ({@code parallel_stations} [1, 2, 3, 4, 5]): 6 ingots take the
     * time of 2 per furnace (one alone would make 3 in that time); each furnace burns its own coal, all three are
     * occupied by the Smelter.
     */
    private static void parallelFurnaces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(JobBalancing.of(smelter()).parallelStations().equals(Optional.of(List.of(1, 2, 3, 4, 5))),
                "parallel_stations from the job file: " + JobBalancing.of(smelter()).parallelStations());
        helper.assertTrue(JobBalancing.of(CraftspersonGameTests.craftJob()).parallelStations().isEmpty()
                && CraftspersonGameTests.profile().parallelStations(5) == 1, "Other Producing Jobs: 1 station per level");
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 6), new ItemStack(Items.COAL, 4)));
        StationTypeGameTests.setLevel(helper, BLOCK, 3);
        helper.setBlock(new BlockPos(3, 1, 7), Blocks.FURNACE);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.FURNACE);
        Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 6, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup);
        // Level 1: 314 ticks per item and furnace; 2 rounds of charges fit, 6 single charges would not.
        catchUpWork(level, settler, 0L, 800.0);

        helper.assertTrue(count(level, setup.clerk(), Items.IRON_INGOT) == 6, "6 ingots from 3 furnaces: " + count(level, setup.clerk(), Items.IRON_INGOT)
                + ", status " + settler.getWork().status(setup.block()));
        helper.assertTrue(count(level, setup.clerk(), Items.COAL) == 1, "One coal per furnace: " + count(level, setup.clerk(), Items.COAL));
        // The catch-up worked the previous day.
        long day = DayClock.day(level) - 1;
        for (BlockPos furnace : List.of(FURNACE, new BlockPos(3, 1, 7), new BlockPos(3, 1, 3))) {
            helper.assertTrue(OccupiedPlaces.occupant(setup.data(), helper.absolutePos(furnace), day).map(OccupiedPlaces.Occupation::settler)
                    .equals(Optional.of(settler.getUUID())), "Occupied by the Smelter: " + furnace);
        }
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A furnace holding a player's items is never used: with only that furnace the Smelter has no work place; an empty
     * furnace placed next is used and the player's items stay untouched.
     */
    private static void playerFurnace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 1), new ItemStack(Items.COAL, 1)));
        Container own = (Container) level.getBlockEntity(helper.absolutePos(FURNACE));
        own.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 1, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup);
        CraftspersonGameTests.catchUp(level, settler, 1000L);
        helper.assertTrue(settler.getWork().status(setup.block()) == JobStatus.NO_WORK_PLACE && count(level, setup.clerk(), Items.IRON_INGOT) == 0,
                "The player's furnace is no work place: " + settler.getWork().status(setup.block()));

        helper.setBlock(new BlockPos(3, 1, 3), Blocks.FURNACE);
        CraftspersonGameTests.catchUp(level, settler, 1000L);
        helper.assertTrue(count(level, setup.clerk(), Items.IRON_INGOT) == 1, "Smelted at the empty furnace: " + settler.getWork().status(setup.block()));
        helper.assertTrue(own.getItem(0).is(Items.COBBLESTONE) && own.getItem(0).getCount() == 5 && own.getItem(2).isEmpty(),
                "The player's furnace is untouched");
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A furnace broken during a charge: the charge's inputs are back in the buffer, its Remaining Heat is lost; at a
     * new furnace the item needs a second coal.
     */
    private static void brokenFurnace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 1), new ItemStack(Items.COAL, 2)));
        Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 1, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup);
        long time = catchUpWork(level, settler, 0L, 150.0);
        helper.assertTrue(count(level, setup.clerk(), Items.COAL) == 1 && count(level, setup.clerk(), Items.RAW_IRON) == 0,
                "Charge running: one coal and the raw iron fetched");

        helper.setBlock(FURNACE, Blocks.AIR);
        helper.assertTrue(settler.getWork().buffer(setup.block()).counts().getOrDefault(Items.RAW_IRON, 0) == 1,
                "The charge's input is in the buffer: " + settler.getWork().buffer(setup.block()).stacks());
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.FURNACE);
        catchUpWork(level, settler, time, 1000.0);
        helper.assertTrue(count(level, setup.clerk(), Items.IRON_INGOT) == 1, "The raw iron was still in the buffer and smelted at the new furnace: "
                + settler.getWork().status(setup.block()) + ", buffer " + settler.getWork().buffer(setup.block()).stacks());
        helper.assertTrue(count(level, setup.clerk(), Items.COAL) == 0, "The heat was lost with the furnace: a second coal");
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Speed per furnace: level 1 needs 200 ÷ (2^(2.75 × (1 − 20) / 80) × 1.0) ≈ 314 ticks per iron ingot, so 2 are done
     * after 700 ticks; EXP 1 per item × JobBlock level 1.
     */
    private static void speedAndExp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 10), new ItemStack(Items.COAL, 2)));
        Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 10, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup);
        catchUpWork(level, settler, 0L, 700.0);

        int made = settler.getWork().buffer(setup.block()).counts().getOrDefault(Items.IRON_INGOT, 0);
        helper.assertTrue(made == 2, "2 ingots after 700 ticks at level 1: " + made);
        int exp = settler.getSkills().get(smelter()).exp();
        helper.assertTrue(exp == 2, "EXP 1 per item: " + exp);
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Two Smelters at one level-3 JobBlock (3 furnaces each wanted, 4 furnaces there) never share a furnace: each
     * furnace is occupied by one of them, both get furnaces and both smelt.
     */
    private static void twoSmelters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.RAW_IRON, 12), new ItemStack(Items.COAL, 8)));
        StationTypeGameTests.setLevel(helper, BLOCK, 3);
        List<BlockPos> furnaces = List.of(FURNACE, new BlockPos(3, 1, 7), new BlockPos(3, 1, 3), new BlockPos(5, 1, 5));
        furnaces.forEach(furnace -> helper.setBlock(furnace, Blocks.FURNACE));
        for (int i = 0; i < 4; i++) {
            Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 3, Optional.of(setup.clerk()), Optional.empty());
        }
        Settler first = worker(helper, setup);
        Settler second = ClerkGameTests.settler(helper, new BlockPos(2, 1, 8), setup.data(), setup.settlement());
        second.setCustomName(Component.literal("Ole"));
        second.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        helper.assertTrue(TestAssignments.assign(setup.data(), setup.block(), second.getUUID(), "Ole", setup.settlement().id(), List.of(), smelter())
                == JobAssignments.AssignResult.OK, "Second Smelter assigned");
        second.addJob(new SettlerJob(smelter(), setup.block()));
        long dayStart = (Math.floorDiv(level.getOverworldClockTime(), 24000L) - 1) * 24000L;
        first.getWork().setCatchUp(dayStart + 2000L, dayStart + 4000L);
        second.getWork().setCatchUp(dayStart + 2000L, dayStart + 4000L);
        CatchUp.runNow(level, List.of(first, second));

        long day = DayClock.day(level) - 1;
        List<java.util.UUID> occupants = furnaces.stream().map(furnace -> OccupiedPlaces.occupant(setup.data(), helper.absolutePos(furnace), day)
                .map(OccupiedPlaces.Occupation::settler).orElse(null)).toList();
        helper.assertTrue(occupants.contains(first.getUUID()) && occupants.contains(second.getUUID()), "Each Smelter has its own furnaces: " + occupants);
        helper.assertTrue(first.getSkills().get(smelter()).exp() > 0 && second.getSkills().get(smelter()).exp() > 0, "Both smelted");
        helper.assertTrue(count(level, setup.clerk(), Items.IRON_INGOT) == 12, "12 ingots: " + count(level, setup.clerk(), Items.IRON_INGOT));
        TestAssignments.unassign(setup.data(), setup.block());
        second.discard();
        cleanUp(setup, first);
        helper.succeed();
    }

    /**
     * Live play at a level-2 JobBlock with two furnaces: while the charges run both furnaces show their lit state and a
     * player cannot open either ("In use by Ida"), also the one the Smelter does not stand at.
     */
    private static void liveLitFurnaces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Setup setup = setup(helper, List.of());
        // No Clerk at work: the inputs lie in the Clerk block's own slots, open to everybody (like the Craftsperson's live round).
        TestAssignments.unassign(setup.data(), setup.clerk());
        Container slots = (Container) level.getBlockEntity(setup.clerk());
        slots.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        slots.setItem(1, new ItemStack(Items.COAL, 2));
        StationTypeGameTests.setLevel(helper, BLOCK, 2);
        BlockPos second = new BlockPos(3, 1, 3);
        helper.setBlock(second, Blocks.FURNACE);
        Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 4, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup);
        settler.setNoAi(false);
        helper.succeedWhen(() -> {
            for (BlockPos furnace : List.of(FURNACE, second)) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(furnace)).getValue(BlockStateProperties.LIT), "Lit: " + furnace
                        + ", status " + settler.getWork().status(setup.block()) + ", at " + helper.relativePos(settler.blockPosition()));
                helper.assertTrue(WorkPlaceLock.refusal(level, helper.absolutePos(furnace)).isPresent(), "In use: " + furnace);
            }
            cleanUp(setup, settler);
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
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

    /**
     * Catches up the previous workday from a time of day for a span whose work time (the span × the average work share
     * × the catch-up multiplier) is the given ticks.
     *
     * @param from the time of day to start at, from 08:00 (every settler is up then)
     * @return the time of day the span ended at, for a following catch-up
     */
    static long catchUpWork(ServerLevel level, Settler settler, long from, double workTicks) {
        long dayStart = (Math.floorDiv(level.getOverworldClockTime(), 24000L) - 1) * 24000L;
        long start = Math.max(from, 2000L);
        long span = (long) Math.ceil(workTicks / (Fatigue.expectedWorkShare() * CivModConfig.CATCH_UP_MULTIPLIER.get()));
        settler.getWork().setCatchUp(dayStart + start, dayStart + start + span);
        CatchUp.runNow(level, List.of(settler));
        return start + span;
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
