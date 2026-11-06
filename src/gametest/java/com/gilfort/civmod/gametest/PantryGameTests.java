package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobAssignments.PointResult;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * GameTests for the Pantry and the Tavern Host (#175): the food-only storage that grows with its level, the food points
 * (the Clerk is none any more), counter service with the meal bonus, self-service, catch-up and the Tavern Host's shift.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class PantryGameTests {

    private static final UUID HOLLY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private PantryGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "pantry_registry_and_block", PantryGameTests::registryAndBlock);
            register(helper, "pantry_slots_and_food_only", PantryGameTests::slotsAndFoodOnly);
            register(helper, "pantry_food_points", PantryGameTests::foodPoints);
            register(helper, "pantry_stand_spots", PantryGameTests::standSpots);
            register(helper, "pantry_shift", PantryGameTests::shift);
            register(helper, "pantry_catch_up_food", PantryGameTests::catchUpFood);
            register(helper, "pantry_catch_up_wait_for_food", PantryGameTests::catchUpWaitForFood);
            register(helper, "pantry_counter_service", PantryGameTests::counterService);
            register(helper, "pantry_self_service", PantryGameTests::selfService);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Tavern Host is a Service Job offered by the Pantry; the Pantry joins a settlement. */
    private static void registryAndBlock(GameTestHelper helper) {
        helper.assertTrue(ModJobs.TAVERN_HOST.get().category() == JobCategory.SERVICE, "Tavern Host is a Service Job");
        helper.assertTrue(ModJobs.TAVERN_HOST.get().id().equals(CivMod.id("tavern_host")), "Registered as civmod:tavern_host");
        helper.assertTrue(ModJobs.TAVERN_HOST.get().parent().isEmpty(), "Outside the Clerk tree");
        helper.assertTrue(ModBlocks.PANTRY_BLOCK.get().getJobType() == ModJobs.TAVERN_HOST.get(), "The Pantry offers the Tavern Host");
        BlockPos pantry = pantry(helper);
        helper.assertTrue(SettlementData.get(helper.getLevel()).settlementAt(pantry).isPresent(), "The Pantry joins a settlement");
        helper.succeed();
    }

    /** 27 / 36 / 45 / 54 / 63 slots by level, food only by hand and by hopper, no linked containers. */
    private static void slotsAndFoodOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = pantry(helper);
        JobBlockEntity pantry = (JobBlockEntity) level.getBlockEntity(pos);
        int[] expected = {27, 36, 45, 54, 63};
        for (int jobLevel = 1; jobLevel <= 5; jobLevel++) {
            helper.assertTrue(SettlementRules.storageSlots(ModJobs.TAVERN_HOST.get(), jobLevel) == expected[jobLevel - 1],
                    "Slots at level " + jobLevel);
            pantry.setJobLevel(jobLevel);
            var handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
            helper.assertTrue(pantry.getContainerSize() == expected[jobLevel - 1] && handler != null && handler.size() == expected[jobLevel - 1],
                    "Pantry level " + jobLevel + " has " + expected[jobLevel - 1] + " slots, hoppers see them");
        }
        helper.assertTrue(SettlementRules.storageSlots(ModJobs.CLERK.get(), 5) == 0, "A Clerk JobBlock has no slots");
        helper.assertTrue(SettlementRules.storageSlots(ModJobs.CRAFTSPERSON.get(), 5) == 27, "Other JobBlocks keep 27 slots");

        helper.assertTrue(pantry.canPlaceItem(0, new ItemStack(Items.BREAD)), "Bread by hand");
        helper.assertFalse(pantry.canPlaceItem(0, new ItemStack(Items.COBBLESTONE)), "No cobblestone by hand");
        var handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(handler.insert(ItemResource.of(Items.BREAD), 8, transaction) == 8, "Bread by hopper");
            helper.assertTrue(handler.insert(ItemResource.of(Items.COBBLESTONE), 8, transaction) == 0, "No cobblestone by hopper");
        }
        BlockPos chest = helper.absolutePos(new BlockPos(5, 1, 1));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        SettlementData data = SettlementData.get(level);
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, pos, chest, true, false, Direction.NORTH) == PointResult.INVALID,
                "No linked containers");
        helper.succeed();
    }

    /**
     * Food points: the nearest Pantry with food, then the next; a Tavern Host's own Pantry first, also after its shift;
     * served at the counter only while the Tavern Host works; a Clerk with food is no food point.
     */
    private static void foodPoints(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = pantry(helper);
        TestSettlements.setBlock(helper, new BlockPos(7, 1, 7), ModBlocks.PANTRY_BLOCK.get());
        BlockPos far = helper.absolutePos(new BlockPos(7, 1, 7));
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 7), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 7));
        BlockPos from = helper.absolutePos(new BlockPos(0, 1, 0));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(near).orElseThrow();
        helper.assertTrue(data.settlementAt(far).filter(s -> s == settlement).isPresent(), "Both Pantries in one settlement");
        helper.assertTrue(food(level, settlement, from, ClerkGameTests.MORNING, Optional.empty(), pos -> true).isEmpty(), "No food anywhere");

        helper.setBlock(new BlockPos(4, 1, 7), net.minecraft.world.level.block.Blocks.CHEST);
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, clerk, helper.absolutePos(new BlockPos(4, 1, 7)), true, false,
                net.minecraft.core.Direction.NORTH) == JobAssignments.PointResult.STORAGE, "Chest linked to the Clerk");
        ((Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 7)))).setItem(0, new ItemStack(Items.BREAD, 3));
        helper.assertTrue(food(level, settlement, from, ClerkGameTests.MORNING, Optional.empty(), pos -> true).isEmpty(),
                "A Clerk is no food point any more");

        ((Container) level.getBlockEntity(far)).setItem(0, new ItemStack(Items.BREAD, 3));
        helper.assertTrue(food(level, settlement, from, ClerkGameTests.MORNING, Optional.empty(), pos -> true).equals(Optional.of(far)),
                "The near Pantry is empty: the far one");
        ((Container) level.getBlockEntity(near)).setItem(0, new ItemStack(Items.COOKED_BEEF, 3));
        Optional<ClerkStorage.FoodPoint> selfService = ClerkStorage.nearestFood(level, settlement, from, ClerkGameTests.MORNING,
                Optional.empty(), pos -> true);
        helper.assertTrue(selfService.map(ClerkStorage.FoodPoint::clerk).equals(Optional.of(near)), "Both have food: the near one");
        helper.assertFalse(selfService.orElseThrow().visit().counter(), "No Tavern Host: self-service at the block");

        helper.assertTrue(TestAssignments.assign(data, near, HOLLY, "Holly", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Tavern Host assigned");
        Optional<ClerkStorage.FoodPoint> working = ClerkStorage.nearestFood(level, settlement, from, ClerkGameTests.MORNING, Optional.empty(),
                pos -> true);
        helper.assertTrue(working.orElseThrow().visit().counter() && working.orElseThrow().visit().reach() == ClerkStorage.COUNTER_REACH,
                "Working Tavern Host: served at the counter");
        helper.assertTrue(food(level, settlement, from, ClerkGameTests.MORNING, Optional.empty(), pos -> !pos.equals(near)).isPresent(),
                "Counter not reachable: the block alone is a food point");
        Optional<ClerkStorage.FoodPoint> evening = ClerkStorage.nearestFood(level, settlement, from, ClerkGameTests.EVENING, Optional.empty(),
                pos -> true);
        helper.assertTrue(evening.isPresent() && !evening.get().visit().counter(), "After the shift: self-service");
        helper.assertTrue(food(level, settlement, helper.absolutePos(new BlockPos(8, 1, 8)), ClerkGameTests.EVENING, Optional.of(near),
                pos -> true).equals(Optional.of(near)), "The Tavern Host eats from its own Pantry first");
        TestAssignments.unassign(data, near);
        helper.succeed();
    }

    /** A Pantry takes stand spots (set with the scroll) like a Clerk; the Tavern Host stands on today's one. */
    private static void standSpots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = pantry(helper);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.STONE);
        BlockPos spot = helper.absolutePos(new BlockPos(4, 1, 4));
        SettlementData data = SettlementData.get(level);
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, pos, spot, false, true, Direction.SOUTH) == PointResult.STAND_SPOT,
                "Stand spot set");
        helper.assertTrue(data.settlementAt(pos).orElseThrow().block(pos).orElseThrow().standSpots().size() == 1, "One stand spot");
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, pos, spot, false, true, Direction.SOUTH) == PointResult.REMOVED,
                "Stand spot removed");
        helper.succeed();
    }

    /** The Tavern Host works 05:30–18:30 like the Clerk and wakes early. */
    private static void shift(GameTestHelper helper) {
        Settler host = ModEntityTypes.SETTLER.get().create(helper.getLevel(), EntitySpawnReason.EVENT);
        long day = 4L;
        helper.assertTrue(host.getWork().dayTimes(day).workEnd() < ClerkStorage.SHIFT_END, "Without the job: off before 18:30");
        host.addJob(new SettlerJob(ModJobs.TAVERN_HOST.get(), helper.absolutePos(new BlockPos(1, 1, 1))));
        helper.assertTrue(host.getWork().dayTimes(day).workEnd() == ClerkStorage.SHIFT_END, "With the Tavern Host job: 18:30");
        long wake = host.getWork().dayTimes(day).wake();
        helper.assertTrue(wake < 0L && wake == com.gilfort.civmod.job.Schedule.dayTimes(host.getUUID(), day, true).wake(),
                "Up 05:30–06:00 like a Clerk, got " + wake);
        helper.assertTrue(SettlerWork.WORK_END < ClerkStorage.SHIFT_END, "Other jobs end earlier");
        host.discard();
        helper.succeed();
    }

    /**
     * Catch-up: a hungry settler takes food from a Pantry (self-service); with a Tavern Host on shift every hand-out gives
     * it EXP.
     */
    private static void catchUpFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantryPos = pantry(helper);
        Container pantry = (Container) level.getBlockEntity(pantryPos);
        pantry.setItem(0, new ItemStack(Items.BREAD, 32));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pantryPos).orElseThrow();
        Settler customer = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        customer.getFood().setLevel(4);
        customer.getFood().setSaturation(0.0F);
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;

        customer.getWork().setCatchUp(dayStart - 24000L, dayStart);
        CatchUp.runNow(level, List.of(customer));
        int bread = pantry.getItem(0).getCount();
        helper.assertTrue(bread < 32, "Self-service: food taken from the Pantry, bread left " + bread);
        helper.assertFalse(customer.getFood().isForaging(), "Fed, no foraging");

        Settler host = ClerkGameTests.settler(helper, new BlockPos(3, 1, 3), data, settlement);
        TestAssignments.assign(data, pantryPos, host.getUUID(), "Host", settlement.id(), List.of());
        host.addJob(new SettlerJob(ModJobs.TAVERN_HOST.get(), pantryPos));
        customer.getFood().setLevel(4);
        customer.getFood().setSaturation(0.0F);
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            customer.getInventory().setItem(i, ItemStack.EMPTY);
        }
        customer.getWork().setCatchUp(dayStart - 24000L, dayStart);
        CatchUp.runNow(level, List.of(customer));
        helper.assertTrue(pantry.getItem(0).getCount() < bread, "Served at the counter, bread left " + pantry.getItem(0).getCount());
        SettlerSkills.Skill skill = host.getSkills().get(ModJobs.TAVERN_HOST.get());
        helper.assertTrue(skill.exp() + 1000 * skill.level() > 1000, "Hand-outs give the Tavern Host EXP: " + skill);

        TestAssignments.unassign(data, pantryPos);
        host.removeJob(pantryPos);
        customer.discard();
        host.discard();
        helper.succeed();
    }

    /** Catch-up: a hungry settler without food waits at the (empty) Pantry for 2 hours, then it forages. */
    private static void catchUpWaitForFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantryPos = pantry(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pantryPos).orElseThrow();
        Settler customer = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        customer.getFood().setLevel(4);
        customer.getFood().setSaturation(0.0F);
        long missedStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L - 24000L;
        long missed = Math.floorDiv(missedStart, 24000L);

        customer.getWork().setCatchUp(missedStart, missedStart + 1500L);
        CatchUp.runNow(level, List.of(customer));
        helper.assertFalse(customer.getFood().isForaging(), "07:30: still waiting at the Pantry");
        helper.assertTrue(customer.getFood().waitDay() == missed, "The wait began: " + customer.getFood().waitDay());

        customer.getWork().setCatchUp(missedStart + 1500L, missedStart + 3500L);
        CatchUp.runNow(level, List.of(customer));
        helper.assertTrue(customer.getFood().forageDay() == missed, "09:30: foraging after 2 hours without food");
        customer.discard();
        helper.succeed();
    }

    /**
     * In the world: a hungry settler is served at a working Tavern Host's counter; the meal carries the bonus (skill 10 =
     * +10 %: a beef of 8 gives 9) and the Tavern Host gains EXP.
     */
    private static void counterService(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        floor(helper);
        BlockPos pantryPos = pantry(helper);
        Container pantry = (Container) level.getBlockEntity(pantryPos);
        pantry.setItem(0, new ItemStack(Items.COOKED_BEEF, 1));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pantryPos).orElseThrow();
        Settler host = ClerkGameTests.settler(helper, new BlockPos(1, 1, 3), data, settlement);
        host.getSkills().set(ModJobs.TAVERN_HOST.get(), new SettlerSkills.Skill(10, 0));
        TestAssignments.assign(data, pantryPos, host.getUUID(), "Host", settlement.id(), List.of());
        host.addJob(new SettlerJob(ModJobs.TAVERN_HOST.get(), pantryPos));
        Settler customer = ClerkGameTests.settler(helper, new BlockPos(7, 1, 7), data, settlement);
        customer.setNoAi(false);
        customer.getFood().setLevel(2);
        customer.getFood().setSaturation(0.0F);
        helper.succeedWhen(() -> {
            helper.assertTrue(pantry.getItem(0).isEmpty(), "Not served yet (" + customer.statusText().getString() + ")");
            helper.assertTrue(customer.getFood().level() >= 11, "Meal with bonus: 2 + 9, got " + customer.getFood().level());
            helper.assertTrue(host.getSkills().get(ModJobs.TAVERN_HOST.get()).exp() > 0, "The Tavern Host gains EXP per hand-out");
            TestAssignments.unassign(data, pantryPos);
            customer.discard();
            host.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** In the world: without a Tavern Host the Pantry is self-service, the meal carries no bonus (a beef of 8 gives 8). */
    private static void selfService(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        floor(helper);
        BlockPos pantryPos = pantry(helper);
        Container pantry = (Container) level.getBlockEntity(pantryPos);
        pantry.setItem(0, new ItemStack(Items.COOKED_BEEF, 1));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pantryPos).orElseThrow();
        Settler customer = ClerkGameTests.settler(helper, new BlockPos(7, 1, 7), data, settlement);
        customer.setNoAi(false);
        customer.getFood().setLevel(2);
        customer.getFood().setSaturation(0.0F);
        helper.succeedWhen(() -> {
            helper.assertTrue(pantry.getItem(0).isEmpty(), "Not eaten yet (" + customer.statusText().getString() + ")");
            helper.assertTrue(customer.getFood().level() >= 9 && customer.getFood().level() <= 10, "No bonus: 2 + 8, got " + customer.getFood().level());
            customer.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static Optional<BlockPos> food(ServerLevel level, Settlement settlement, BlockPos from, long dayTime, Optional<BlockPos> own,
            Predicate<BlockPos> reachable) {
        return ClerkStorage.nearestFood(level, settlement, from, dayTime, own, reachable).map(ClerkStorage.FoodPoint::clerk);
    }

    /** Places a Pantry at (1, 1, 1). */
    private static BlockPos pantry(GameTestHelper helper) {
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.PANTRY_BLOCK.get());
        return helper.absolutePos(new BlockPos(1, 1, 1));
    }
}
