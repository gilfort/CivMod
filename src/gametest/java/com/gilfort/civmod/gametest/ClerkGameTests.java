package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.ClerkSpots;
import com.gilfort.civmod.settlement.JobAssignments.PointResult;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Clerk and its storage (#55): access rule, storage limits, stand spot, Clerk as drop-off and
 * shift. Food points moved to the Pantry ({@link PantryGameTests}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClerkGameTests {

    /** Day time 08:00, inside the Clerk's shift. */
    static final long MORNING = 2000L;
    /** Day time 19:00, after the Clerk's shift. */
    static final long EVENING = 13000L;
    private static final UUID CLARA = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private ClerkGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "clerk_registry_and_block", ClerkGameTests::registryAndBlock);
            register(helper, "clerk_access_rule", ClerkGameTests::accessRule);
            register(helper, "clerk_storage_limits", ClerkGameTests::storageLimits);
            register(helper, "clerk_spot_of_day", ClerkGameTests::spotOfDay);
            register(helper, "clerk_as_drop_off", ClerkGameTests::asDropOff);
            register(helper, "clerk_shift", ClerkGameTests::shift);
            register(helper, "clerk_old_save", ClerkGameTests::oldSave);
            register(helper, "clerk_no_storage_hint", ClerkGameTests::noStorageHint);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Clerk is a Logistics Job offered by the Clerk JobBlock, which has no slots of its own (#176). */
    private static void registryAndBlock(GameTestHelper helper) {
        helper.assertTrue(ModJobs.CLERK.get().category() == JobCategory.LOGISTICS, "Clerk is a Logistics Job");
        helper.assertTrue(ModJobs.CLERK.get().id().equals(CivMod.id("clerk")), "Registered as civmod:clerk");
        helper.assertTrue(ModBlocks.CLERK_BLOCK.get().getJobType() == ModJobs.CLERK.get(), "The Clerk JobBlock offers the Clerk");
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWithStorage(helper);
        var handler = level.getCapability(Capabilities.Item.BLOCK, clerk, null);
        helper.assertTrue(handler != null && handler.size() == 0, "Hoppers on the block see no slot");
        helper.assertTrue(((Container) level.getBlockEntity(clerk)).getContainerSize() == 0, "The block has no slots");
        helper.assertTrue(SettlementData.get(level).settlementAt(clerk).isPresent(), "The Clerk JobBlock joins a settlement");
        helper.succeed();
    }

    /**
     * The block has no slots: without a working Clerk nobody takes anything from the linked storage; a Clerk settler on
     * shift opens it at its counter. Putting items in works at any time.
     */
    private static void accessRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWithStorage(helper);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.BREAD, 5));

        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, true)) == 0, "No settler: linked storage is closed");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, false)) == 0, "No settler: the block has nothing");
        helper.assertTrue(ClerkStorage.dropOff(level, clerk).insert(new ItemStack(Items.BREAD, 2)).isEmpty(),
                "No settler: a drop-off still stores into the linked storage");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, true)) == 7, "Staff reach the whole storage at any time");

        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        helper.assertTrue(TestAssignments.assign(data, clerk, CLARA, "Clara", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Clerk assigned");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, true)) == 7, "Working Clerk: linked storage at the counter");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, false)) == 0, "Not at the counter: nothing");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, EVENING, true)) == 0, "After the shift: nothing");
        helper.assertTrue(ClerkStorage.stock(level, clerk, EVENING, true).insert(new ItemStack(Items.STICK, 64)).getCount() == 64,
                "Closed Clerk: nothing to hand out");

        Stock open = ClerkStorage.stock(level, clerk, MORNING, true);
        ItemStack rest = open.insert(new ItemStack(Items.COBBLESTONE, 64 * 27));
        helper.assertTrue(rest.getCount() < 64 * 27, "A working Clerk fills the linked storage: rest " + rest.getCount());
        helper.assertTrue(ClerkStorage.dropOff(level, clerk).insert(new ItemStack(Items.STICK, 64)).getCount() == 64,
                "A full linked storage takes nothing more: Storage full");
        TestAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /**
     * Storage 2 / 4 / 6 / 10 / 16 by level (extra containers kept but inactive after a downgrade), one Clerk per
     * container, no JobBlock as storage, 8 stand spots on standable blocks, inside the border only; points persist.
     */
    private static void storageLimits(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos clerk = new BlockPos(0, 64, 0);
        TestSettlements.place(data, clerk, ModJobs.CLERK.get(), 1);
        int[] limits = {2, 4, 6, 10, 16};
        int linked = 0;
        for (int level = 1; level <= 5; level++) {
            SettlementManager.setLevel(data, clerk, level, SettlementManager.Listener.NONE);
            while (linked < limits[level - 1]) {
                PointResult result = JobAssignments.cycleClerkPoint(data, clerk, new BlockPos(1 + linked, 64, 3), true, false, Direction.NORTH);
                helper.assertTrue(result == PointResult.STORAGE, "Container " + linked + " at level " + level + ": " + result);
                linked++;
            }
            helper.assertTrue(JobAssignments.cycleClerkPoint(data, clerk, new BlockPos(1, 64, 4), true, false, Direction.NORTH)
                    == PointResult.STORAGE_FULL, "Level " + level + " links " + limits[level - 1]);
        }
        SettlementBlock block = data.settlementAt(clerk).flatMap(s -> s.block(clerk)).orElseThrow();
        SettlementManager.setLevel(data, clerk, 2, SettlementManager.Listener.NONE);
        helper.assertTrue(block.storage().size() == 16 && ClerkStorage.activeStorage(block).size() == 4,
                "Downgrade keeps all 16, 4 active: " + ClerkStorage.activeStorage(block).size());
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, clerk, new BlockPos(1, 64, 3), true, false, Direction.NORTH)
                == PointResult.REMOVED && block.storage().size() == 15, "Clicking a linked container removes it");

        BlockPos other = new BlockPos(6, 64, 6);
        TestSettlements.place(data, other, ModJobs.CLERK.get(), 1);
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, other, new BlockPos(2, 64, 3), true, false, Direction.NORTH) == PointResult.TAKEN,
                "A container belongs to one Clerk");
        helper.assertTrue(JobAssignments.storageOwner(data, new BlockPos(2, 64, 3)).map(SettlementBlock::pos).orElseThrow().equals(clerk),
                "The owner is named");
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, other, clerk, true, false, Direction.NORTH) == PointResult.INVALID,
                "A JobBlock is no storage");
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, other, new BlockPos(500, 64, 0), true, false, Direction.NORTH) == PointResult.OUTSIDE,
                "Outside the border");

        for (int i = 0; i < 8; i++) {
            helper.assertTrue(JobAssignments.cycleClerkPoint(data, other, new BlockPos(4 + i, 64, 8), false, true, Direction.EAST)
                    == PointResult.STAND_SPOT, "Stand spot " + i);
        }
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, other, new BlockPos(4, 64, 9), false, true, Direction.EAST)
                == PointResult.TOO_MANY_SPOTS, "At most 8 stand spots");
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, other, new BlockPos(4, 64, 9), false, false, Direction.EAST)
                == PointResult.INVALID, "Not standable");
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, other, new BlockPos(4, 64, 8), false, true, Direction.EAST)
                == PointResult.REMOVED, "Clicking a stand spot removes it");

        Settlement settlement = data.settlementAt(other).orElseThrow();
        var ops = JsonOps.INSTANCE;
        Settlement loaded = Settlement.CODEC.parse(ops, Settlement.CODEC.encodeStart(ops, settlement).getOrThrow()).getOrThrow();
        SettlementBlock reloaded = loaded.block(other).orElseThrow();
        helper.assertTrue(reloaded.standSpots().size() == 7 && reloaded.standSpots().get(0).facing() == Direction.EAST,
                "Stand spots survive saving");
        helper.assertTrue(loaded.block(clerk).orElseThrow().storage().equals(block.storage()), "Storage survives saving");
        helper.succeed();
    }

    /** One stand spot per half day (morning and from 12:00), never the same twice in a row with several spots. */
    private static void spotOfDay(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos clerk = new BlockPos(0, 64, 0);
        TestSettlements.place(data, clerk, ModJobs.CLERK.get(), 1);
        RandomSource random = RandomSource.create(7L);
        helper.assertTrue(ClerkSpots.today(data, clerk, 3, MORNING, random).isEmpty(), "No spots: none");
        for (int i = 0; i < 3; i++) {
            JobAssignments.cycleClerkPoint(data, clerk, new BlockPos(2 + i, 64, 2), false, true, Direction.SOUTH);
        }
        BlockPos previous = null;
        for (long day = 3; day < 40; day++) {
            BlockPos morning = ClerkSpots.today(data, clerk, day, MORNING, random).orElseThrow().pos();
            helper.assertTrue(morning.equals(ClerkSpots.today(data, clerk, day, MORNING + 3000, random).orElseThrow().pos()),
                    "Same spot all morning");
            BlockPos afternoon = ClerkSpots.today(data, clerk, day, 6500, random).orElseThrow().pos();
            helper.assertFalse(morning.equals(previous) || afternoon.equals(morning), "Never twice in a row (day " + day + ")");
            previous = afternoon;
        }
        SettlementBlock block = data.settlementAt(clerk).flatMap(s -> s.block(clerk)).orElseThrow();
        helper.assertTrue(ClerkStorage.spotOfDay(block).map(SettlementBlock.StandSpot::pos).orElseThrow().equals(previous),
                "Customers see the picked spot");
        helper.succeed();
    }

    /**
     * A Clerk JobBlock as drop-off and collect point of a Lumberjack (catch-up): deliveries and tools follow the access
     * rule – without a Clerk settler only the full block slots (nothing fits, no tool), with one on shift the linked
     * storage.
     */
    private static void asDropOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || LumberjackGameTests.foundNothing(settler, settler.getJobs().get(0))) {
                return;
            }
            done[0] = true;
            TestSettlements.setBlock(helper, new BlockPos(1, 1, 6), ModBlocks.CLERK_BLOCK.get());
            helper.setBlock(new BlockPos(3, 1, 6), Blocks.CHEST);
            BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 6));
            BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 6));
            SettlementData data = SettlementData.get(level);
            helper.assertTrue(JobAssignments.cycleClerkPoint(data, clerk, chestPos, true, false, Direction.NORTH) == PointResult.STORAGE,
                    "Chest linked");
            helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, clerk, false, true) == PointResult.COLLECT, "A Clerk JobBlock is a container");
            helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, clerk, false, true) == PointResult.DROP_OFF, "Clerk as drop-off");
            Container chest = (Container) level.getBlockEntity(chestPos);
            long clock = level.getOverworldClockTime();
            long dayStart = Math.floorDiv(clock, 24000L) * 24000L;

            settler.getWork().setCatchUp(dayStart - 24000L, dayStart);
            CatchUp.runNow(level, List.of(settler));
            helper.assertTrue(logs(chest) > 0, "No Clerk settler: deliveries still go into the linked storage, got " + logs(chest));

            Settlement settlement = data.settlementAt(clerk).orElseThrow();
            TestAssignments.assign(data, clerk, CLARA, "Clara", settlement.id(), List.of());

            helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, clerk, false, true) == PointResult.REMOVED
                    && JobAssignments.cyclePoint(data, jobBlock, clerk, false, true) == PointResult.COLLECT, "Clerk as collect point");
            settler.getInventory().setItem(Settler.BELT_START, ItemStack.EMPTY);
            chest.setItem(26, new ItemStack(Items.STONE_AXE));
            com.gilfort.civmod.job.ToolSearchThrottleGameTests.forgetSearches(settler);
            settler.getWork().setCatchUp(dayStart - 24000L, dayStart);
            CatchUp.runNow(level, List.of(settler));
            helper.assertTrue(settler.getInventory().getItem(Settler.BELT_START).is(Items.STONE_AXE) && chest.getItem(26).isEmpty(),
                    "Tool from the linked storage of a working Clerk: " + settler.getInventory().getItem(Settler.BELT_START));
            TestAssignments.unassign(data, clerk);
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** The Clerk works until 18:00, others until 17:30–18:00; service time 2 s at level 1 down to 1 s at level 100. */
    private static void shift(GameTestHelper helper) {
        long sixPm = 12000L;
        helper.assertTrue(ClerkStorage.SHIFT_END == sixPm, "The Clerk's shift ends at 18:00: " + ClerkStorage.SHIFT_END);
        helper.assertTrue(Schedule.next(snapshot(sixPm - 1, ClerkStorage.SHIFT_END)).intent() == Schedule.Intent.WORK, "17:59: the Clerk works");
        helper.assertTrue(Schedule.next(snapshot(sixPm - 1, SettlerWork.WORK_END)).afterWork(), "17:59: others are off");
        helper.assertTrue(Schedule.next(snapshot(sixPm, ClerkStorage.SHIFT_END)).afterWork(), "18:00: the Clerk is off");
        helper.assertTrue(ClerkStorage.serviceTicks(1) == 40 && ClerkStorage.serviceTicks(100) == 20,
                "Service 40 → 20 ticks: " + ClerkStorage.serviceTicks(1) + ", " + ClerkStorage.serviceTicks(100));
        helper.assertTrue(ClerkStorage.serviceTicks(50) > 20 && ClerkStorage.serviceTicks(50) < 40, "Faster with skill");

        Settler clerk = ModEntityTypes.SETTLER.get().create(helper.getLevel(), EntitySpawnReason.EVENT);
        long day = 4L;
        long ownEnd = clerk.getWork().dayTimes(day).workEnd();
        helper.assertTrue(ownEnd >= SettlerWork.WORK_END && ownEnd < SettlerWork.WORK_END + Schedule.WINDOW_TICKS,
                "Without the Clerk job: 17:30–18:00, got " + ownEnd);
        clerk.addJob(new SettlerJob(ModJobs.CLERK.get(), helper.absolutePos(new BlockPos(1, 1, 1))));
        helper.assertTrue(clerk.getWork().dayTimes(day).workEnd() == ClerkStorage.SHIFT_END, "With the Clerk job: 18:00");
        // Clerks wake up early (05:30–06:00): they have a stall to take care of.
        long clerkWake = clerk.getWork().dayTimes(day).wake();
        helper.assertTrue(clerkWake >= -Schedule.WINDOW_TICKS && clerkWake < 0L, "With the Clerk job: up 05:30–06:00, got " + clerkWake);
        helper.assertTrue(clerkWake == Schedule.dayTimes(clerk.getUUID(), day, true).wake(), "Its offset in the early waking window");
        clerk.discard();

        // The shift opens at the Clerk's own waking time (its daily offset in 05:30–06:00), before any other settler wakes.
        UUID late = lateRiser(day);
        long wake = Schedule.dayTimes(late, day, true).wake();
        long dayBefore = day - 1;
        long clockDay = 24000L;
        helper.assertTrue(ClerkStorage.inShift(late, day, 0L, ModJobs.CLERK.get()), "06:00: every Clerk's shift is open");
        helper.assertFalse(ClerkStorage.inShift(late, day, wake - 1, ModJobs.CLERK.get()), "Closed just before its waking time");
        helper.assertTrue(ClerkStorage.inShift(late, day, wake, ModJobs.CLERK.get()), "Open from its waking time");
        helper.assertFalse(ClerkStorage.inShift(late, dayBefore, clockDay + wake - 1, ModJobs.CLERK.get()), "On the clock's day before: closed just before");
        helper.assertTrue(ClerkStorage.inShift(late, dayBefore, clockDay + wake, ModJobs.CLERK.get()), "On the clock's day before: open from its waking time");
        helper.assertFalse(ClerkStorage.inShift(late, dayBefore, 14000L, ModJobs.CLERK.get()), "Closed in the evening");
        helper.assertTrue(ClerkStorage.inShift(late, day, ClerkStorage.SHIFT_END - 1, ModJobs.CLERK.get()), "Open until 18:00");
        helper.assertFalse(ClerkStorage.inShift(late, day, ClerkStorage.SHIFT_END, ModJobs.CLERK.get()), "Closed at 18:00");
        helper.succeed();
    }

    /** A Clerk that wakes after 05:30 on the day. */
    private static UUID lateRiser(long day) {
        for (long i = 1; ; i++) {
            UUID candidate = new UUID(0xC1E2L, i);
            if (Schedule.dayTimes(candidate, day, true).wake() > -Schedule.WINDOW_TICKS) {
                return candidate;
            }
        }
    }

    private static Schedule.Snapshot snapshot(long dayTime, long workEnd) {
        return new Schedule.Snapshot(3L, dayTime, 20, 10, 6, true, () -> true, () -> true, false, false, SettlerFood.NO_DAY, false,
                Schedule.Activity.IDLE, () -> false, true, false, Schedule.NOT_WAITING, false, () -> false,
                new Schedule.DayTimes(0L, workEnd, SettlerWork.SLEEP_TIME));
    }

    static Settler settler(GameTestHelper helper, BlockPos relative, SettlementData data, Settlement settlement) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            settler.getInventory().setItem(i, ItemStack.EMPTY);
        }
        settler.getFood().setAppetite(1.0F);
        return settler;
    }

    private static int logs(Container container) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(Items.OAK_LOG)) {
                count += container.getItem(i).getCount();
            }
        }
        return count;
    }
    /** Places a Clerk JobBlock at (1, 1, 1) with a chest at (4, 1, 1) linked as storage. */
    static BlockPos clerkWithStorage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        helper.setBlock(new BlockPos(4, 1, 1), Blocks.CHEST);
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chest = helper.absolutePos(new BlockPos(4, 1, 1));
        JobAssignments.PointResult linked = JobAssignments.cycleClerkPoint(SettlementData.get(level), clerk, chest, true, false, Direction.NORTH);
        helper.assertTrue(linked == JobAssignments.PointResult.STORAGE, "Chest linked as storage: " + linked);
        return clerk;
    }

    /** An old save with 27 stacks in a Clerk JobBlock loads without a crash; the items are gone, the block has no slots. */
    private static void oldSave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        TestSettlements.setBlock(helper, new BlockPos(3, 1, 1), ModBlocks.CRAFTSPERSON_BLOCK.get());
        JobBlockEntity oldBlock = (JobBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(3, 1, 1)));
        for (int slot = 0; slot < oldBlock.getContainerSize(); slot++) {
            oldBlock.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        // 27 stacks in a Clerk JobBlock's tag, as a save of an earlier version wrote them.
        CompoundTag saved = oldBlock.saveWithoutMetadata(level.registryAccess());
        JobBlockEntity clerk = (JobBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(1, 1, 1)));
        clerk.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        helper.assertTrue(clerk.getContainerSize() == 0 && clerk.isEmpty(), "The block has no slots: " + clerk.getContainerSize());
        helper.succeed();
    }

    /** A Clerk JobBlock without a linked container asks for one in its header; a linked chest takes the request away. */
    private static void noStorageHint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWithStorage(helper);
        helper.assertFalse(JobBlockInfo.of(level, clerk, ModJobs.CLERK.get(), 1).storageUnlinked(), "A linked chest: no hint");
        SettlementData data = SettlementData.get(level);
        JobAssignments.cycleClerkPoint(data, clerk, helper.absolutePos(new BlockPos(4, 1, 1)), true, false, Direction.NORTH);
        helper.assertTrue(JobBlockInfo.of(level, clerk, ModJobs.CLERK.get(), 1).storageUnlinked(), "The chest is unlinked: link a container");
        helper.assertFalse(JobBlockInfo.of(level, helper.absolutePos(new BlockPos(1, 1, 1)), ModJobs.CRAFTSPERSON.get(), 1).storageUnlinked(),
                "Other JobBlocks never ask");
        helper.succeed();
    }

    /**
     * Places a chest and links it as storage to a Clerk JobBlock (the block has no slots of its own).
     *
     * @param helper the test helper
     * @param clerk  the Clerk JobBlock (relative position)
     * @param chest  where the chest goes (relative position)
     * @return the chest
     */
    static Container linkChest(GameTestHelper helper, BlockPos clerk, BlockPos chest) {
        helper.setBlock(chest, Blocks.CHEST);
        PointResult linked = JobAssignments.cycleClerkPoint(SettlementData.get(helper.getLevel()), helper.absolutePos(clerk), helper.absolutePos(chest),
                true, false, Direction.NORTH);
        helper.assertTrue(linked == PointResult.STORAGE, "Chest linked as storage: " + linked);
        return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chest));
    }

    /**
     * @param level the level
     * @param clerk a Clerk JobBlock with a linked container
     * @return its first linked container (the block has no slots of its own)
     */
    static Container store(ServerLevel level, BlockPos clerk) {
        SettlementBlock block = JobBlockSite.blockAt(level, clerk).orElseThrow();
        return (Container) level.getBlockEntity(block.storage().getFirst());
    }

    private static int count(Stock stock) {
        return stock.count(stack -> stack.is(Items.BREAD));
    }
}
