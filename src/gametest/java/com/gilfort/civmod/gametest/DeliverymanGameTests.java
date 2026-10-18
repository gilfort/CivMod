package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Pickups;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.Warehouses;
import com.gilfort.civmod.settlement.Warehouses.LinkResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Deliveryman collecting harvests (#68): Home Clerks, Warehouse and Served Clerk links, pickup tasks
 * and their thresholds, picking and batching, "No Deliveryman", a Deliveryman emptying a Lumberjack drop-off into the
 * store, and catch-up.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class DeliverymanGameTests {

    private DeliverymanGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "deliveryman_home_clerk", DeliverymanGameTests::homeClerk);
            register(helper, "deliveryman_warehouse_links", DeliverymanGameTests::warehouseLinks);
            register(helper, "deliveryman_pickup_tasks", DeliverymanGameTests::pickupTasks);
            register(helper, "deliveryman_picking", DeliverymanGameTests::picking);
            register(helper, "deliveryman_empties_drop_off", DeliverymanGameTests::emptiesDropOff);
            register(helper, "deliveryman_catch_up", DeliverymanGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Registers a JobBlock without a block in the world (pure settlement rules). */
    private static BlockPos place(GameTestHelper helper, BlockPos relative, Job job) {
        BlockPos pos = helper.absolutePos(relative);
        SettlementManager.place(SettlementData.get(helper.getLevel()), pos, job, 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        return pos;
    }

    private static void remove(GameTestHelper helper, BlockPos... positions) {
        for (BlockPos pos : positions) {
            SettlementManager.remove(SettlementData.get(helper.getLevel()), pos, SettlementManager.Listener.NONE);
        }
    }

    private static SettlementBlock block(GameTestHelper helper, BlockPos pos) {
        return JobBlockSite.blockAt(helper.getLevel(), pos).orElseThrow();
    }

    private static void deliveryman(GameTestHelper helper, BlockPos clerk, UUID settler) {
        SettlementData data = SettlementData.get(helper.getLevel());
        int settlement = data.settlementAt(clerk).orElseThrow().id();
        helper.assertTrue(JobAssignments.assign(data, clerk, settler, "Dora", settlement, List.of(), ModJobs.DELIVERYMAN.get())
                == JobAssignments.AssignResult.OK, "Deliveryman assigned");
    }

    /**
     * Every JobBlock belongs to the nearest Clerk JobBlock; it changes when a Clerk JobBlock is placed or removed, a
     * Clerk JobBlock is its own.
     */
    private static void homeClerk(GameTestHelper helper) {
        BlockPos far = place(helper, new BlockPos(1, 1, 1), ModJobs.CLERK.get());
        BlockPos lumberjack = place(helper, new BlockPos(7, 1, 1), ModJobs.LUMBERJACK.get());
        helper.assertTrue(block(helper, lumberjack).homeClerk().equals(Optional.of(far)), "The only Clerk is the Home Clerk");
        helper.assertTrue(block(helper, far).homeClerk().equals(Optional.of(far)), "A Clerk JobBlock is its own Home Clerk");
        BlockPos near = place(helper, new BlockPos(9, 1, 1), ModJobs.CLERK.get());
        helper.assertTrue(block(helper, lumberjack).homeClerk().equals(Optional.of(near)), "A nearer Clerk placed: it is the Home Clerk");
        BlockPos miner = place(helper, new BlockPos(3, 1, 1), ModJobs.MINER.get());
        helper.assertTrue(block(helper, miner).homeClerk().equals(Optional.of(far)), "A JobBlock added gets its nearest Clerk");
        remove(helper, near);
        helper.assertTrue(block(helper, lumberjack).homeClerk().equals(Optional.of(far)), "The nearer Clerk removed: back to the other one");
        remove(helper, far);
        helper.assertTrue(block(helper, lumberjack).homeClerk().isEmpty(), "No Clerk left: no Home Clerk");
        remove(helper, lumberjack, miner);
        helper.succeed();
    }

    /**
     * A Clerk JobBlock links other Clerks of its settlement as Served Clerks: 2 at level 1, extra ones inactive after a
     * downgrade; rejected: itself, a non-Clerk, a Clerk with own Deliverymen, one served elsewhere, chains.
     */
    private static void warehouseLinks(GameTestHelper helper) {
        SettlementData data = SettlementData.get(helper.getLevel());
        BlockPos warehouse = place(helper, new BlockPos(1, 1, 1), ModJobs.CLERK.get());
        BlockPos a = place(helper, new BlockPos(3, 1, 1), ModJobs.CLERK.get());
        BlockPos b = place(helper, new BlockPos(5, 1, 1), ModJobs.CLERK.get());
        BlockPos c = place(helper, new BlockPos(7, 1, 1), ModJobs.CLERK.get());
        BlockPos staffed = place(helper, new BlockPos(1, 1, 3), ModJobs.CLERK.get());
        BlockPos other = place(helper, new BlockPos(3, 1, 3), ModJobs.CLERK.get());
        BlockPos lumberjack = place(helper, new BlockPos(5, 1, 3), ModJobs.LUMBERJACK.get());
        deliveryman(helper, warehouse, UUID.randomUUID());
        deliveryman(helper, staffed, UUID.randomUUID());
        deliveryman(helper, other, UUID.randomUUID());
        Settlement settlement = data.settlementAt(warehouse).orElseThrow();

        helper.assertTrue(Warehouses.link(data, warehouse, a) == LinkResult.LINKED, "Linked");
        helper.assertTrue(Warehouses.link(data, warehouse, a) == LinkResult.UNLINKED, "Again: unlinked");
        helper.assertTrue(Warehouses.link(data, warehouse, a) == LinkResult.LINKED && Warehouses.link(data, warehouse, b) == LinkResult.LINKED,
                "Two at level 1");
        helper.assertTrue(Warehouses.link(data, warehouse, c) == LinkResult.FULL, "A third one at level 1: full");
        helper.assertTrue(Warehouses.link(data, warehouse, warehouse) == LinkResult.SELF, "Not itself");
        helper.assertTrue(Warehouses.link(data, warehouse, lumberjack) == LinkResult.NOT_A_CLERK, "Only Clerk JobBlocks");
        helper.assertTrue(Warehouses.link(data, warehouse, staffed) == LinkResult.OWN_DELIVERYMEN, "A Clerk with own Deliverymen serves itself");
        helper.assertTrue(Warehouses.link(data, other, a) == LinkResult.TAKEN, "Already served by the first Warehouse");
        helper.assertTrue(Warehouses.link(data, a, c) == LinkResult.CHAIN, "A Served Clerk serves no others");
        helper.assertTrue(Warehouses.link(data, other, warehouse) == LinkResult.OWN_DELIVERYMEN, "A Warehouse cannot be served");
        BlockPos later = place(helper, new BlockPos(7, 1, 3), ModJobs.CLERK.get());
        SettlementManager.setLevel(data, other, 2, SettlementManager.Listener.NONE);
        helper.assertTrue(Warehouses.link(data, other, later) == LinkResult.LINKED, "Linked to the second Warehouse");
        deliveryman(helper, later, UUID.randomUUID());
        helper.assertTrue(Warehouses.servedBy(settlement, later).isEmpty(), "Own Deliverymen: no Served Clerk any more");

        SettlementManager.setLevel(data, warehouse, 2, SettlementManager.Listener.NONE);
        helper.assertTrue(Warehouses.link(data, warehouse, c) == LinkResult.LINKED, "Level 2 serves 4");
        SettlementManager.setLevel(data, warehouse, 1, SettlementManager.Listener.NONE);
        helper.assertTrue(block(helper, warehouse).served().size() == 3 && Warehouses.activeServed(block(helper, warehouse)).equals(List.of(a, b)),
                "After the downgrade the link is kept but inactive");
        helper.assertTrue(Warehouses.warehouseOf(settlement, a).map(SettlementBlock::pos).equals(Optional.of(warehouse)), "a is served");
        helper.assertTrue(Warehouses.warehouseOf(settlement, c).isEmpty(), "The inactive one has no Warehouse");
        helper.assertTrue(Warehouses.warehouseOf(settlement, staffed).map(SettlementBlock::pos).equals(Optional.of(staffed)),
                "A Clerk with own Deliverymen is its own Warehouse");
        helper.assertTrue(Warehouses.handled(settlement, block(helper, warehouse)).containsAll(List.of(warehouse, a, b)),
                "The Warehouse handles itself and its active Served Clerks");

        remove(helper, a);
        helper.assertTrue(!block(helper, warehouse).served().contains(a), "A removed Clerk is no Served Clerk any more");
        remove(helper, warehouse, b, c, staffed, other, lumberjack, later);
        helper.succeed();
    }

    /**
     * A pickup is created when the drop-off holds at least a stack or is half full after a delivery, urgently when it
     * is full, by the end-of-work sweep for any harvest; one task per source; none for a Clerk JobBlock drop-off or for
     * the job's own tools in its JobBlock.
     */
    private static void pickupTasks(GameTestHelper helper) {
        helper.assertTrue(!Pickups.due(63, 1, 27) && Pickups.due(64, 1, 27), "One stack");
        helper.assertTrue(!Pickups.due(13, 13, 27) && Pickups.due(14, 14, 27), "Half the slots");

        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        helper.setBlock(new BlockPos(4, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(new BlockPos(6, 1, 1), Blocks.CHEST);
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos jobBlock = helper.absolutePos(new BlockPos(4, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(6, 1, 1));
        JobAssignments.cyclePoint(data, jobBlock, chestPos, false, true);
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, chestPos, false, true) == JobAssignments.PointResult.DROP_OFF, "Chest drop-off");
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        JobBlockSite site = JobBlockSite.at(level, jobBlock).orElseThrow();
        Container chest = (Container) level.getBlockEntity(chestPos);

        chest.setItem(0, new ItemStack(Items.OAK_LOG, 32));
        Pickups.delivered(level, site, false);
        helper.assertTrue(Deliveries.get(settlement, chestPos).isEmpty(), "Half a stack: no pickup yet");
        chest.setItem(1, new ItemStack(Items.OAK_LOG, 32));
        Pickups.delivered(level, site, false);
        Pickups.delivered(level, site, false);
        helper.assertTrue(settlement.deliveries().stream().filter(task -> task.source().equals(chestPos)).count() == 1, "One task per source");
        helper.assertFalse(Deliveries.get(settlement, chestPos).orElseThrow().urgent(), "Not urgent");
        Pickups.delivered(level, site, true);
        helper.assertTrue(Deliveries.get(settlement, chestPos).orElseThrow().urgent(), "Drop-off full: urgent");
        Deliveries.done(data, settlement, chestPos);

        chest.setItem(0, new ItemStack(Items.STICK, 3));
        chest.setItem(1, ItemStack.EMPTY);
        Pickups.sweep(level, site);
        helper.assertTrue(Deliveries.get(settlement, chestPos).isPresent(), "End-of-work sweep: any harvest");
        JobAssignments.cyclePoint(data, jobBlock, chestPos, false, true);
        helper.assertTrue(Deliveries.get(settlement, chestPos).isEmpty(), "Drop-off changed: its pickup goes");

        // Without a drop-off the JobBlock is drop-off and collect inventory: its axe stays.
        Container own = (Container) level.getBlockEntity(jobBlock);
        for (int i = 0; i < own.getContainerSize(); i++) {
            own.setItem(i, ItemStack.EMPTY);
        }
        own.setItem(0, new ItemStack(Items.STONE_AXE));
        Pickups.sweep(level, JobBlockSite.at(level, jobBlock).orElseThrow());
        helper.assertTrue(Deliveries.get(settlement, jobBlock).isEmpty(), "Only the job's tool in its JobBlock: no pickup");
        // A Clerk JobBlock as drop-off gets no pickups.
        JobAssignments.cyclePoint(data, jobBlock, clerk, false, true);
        JobAssignments.cyclePoint(data, jobBlock, clerk, false, true);
        ((Container) level.getBlockEntity(clerk)).setItem(0, new ItemStack(Items.OAK_LOG, 64));
        Pickups.delivered(level, JobBlockSite.at(level, jobBlock).orElseThrow(), true);
        helper.assertTrue(Deliveries.get(settlement, clerk).isEmpty(), "A Clerk drop-off gets no pickup");
        helper.succeed();
    }

    /**
     * The best task first (urgency, waiting, distance); further sources of the same Home Clerk nearest next while the
     * stacks fit; a taken task is locked until released or the day ends; "No Deliveryman" without a Warehouse.
     */
    private static void picking(GameTestHelper helper) {
        SettlementData data = SettlementData.get(helper.getLevel());
        BlockPos warehouse = place(helper, new BlockPos(1, 1, 1), ModJobs.CLERK.get());
        BlockPos j1 = place(helper, new BlockPos(3, 1, 1), ModJobs.LUMBERJACK.get());
        BlockPos j2 = place(helper, new BlockPos(5, 1, 1), ModJobs.LUMBERJACK.get());
        BlockPos j3 = place(helper, new BlockPos(8, 1, 1), ModJobs.MINER.get());
        Settlement settlement = data.settlementAt(warehouse).orElseThrow();
        SettlementBlock block = block(helper, warehouse);
        BlockPos from = warehouse;
        long now = 10000L;

        DeliveryTask fresh = new DeliveryTask(j1, j1, now, false, Optional.empty());
        DeliveryTask old = new DeliveryTask(j1, j1, now - 2000L, false, Optional.empty());
        DeliveryTask urgent = new DeliveryTask(j1, j1, now, true, Optional.empty());
        DeliveryTask farAway = new DeliveryTask(j1.east(30), j1, now, false, Optional.empty());
        helper.assertTrue(Deliveries.score(urgent, now, from) > Deliveries.score(old, now, from), "Urgent first");
        helper.assertTrue(Deliveries.score(old, now, from) > Deliveries.score(fresh, now, from), "Waited longer first");
        helper.assertTrue(Deliveries.score(fresh, now, from) > Deliveries.score(farAway, now, from), "Nearer first");

        Deliveries.request(data, settlement, j3, j3, now, false);
        Deliveries.request(data, settlement, j1, j1, now, false);
        Deliveries.request(data, settlement, j2, j2, now - 100L, false);
        helper.assertTrue(Deliveries.warehouseOf(settlement, Deliveries.get(settlement, j1).orElseThrow()).filter(Warehouses::hasDeliverymen).isEmpty(),
                "Without Deliverymen: No Deliveryman");
        deliveryman(helper, warehouse, UUID.randomUUID());
        long day = 3L;
        DeliveryTask first = Deliveries.pick(settlement, block, from, now, day, Set.of()).orElseThrow();
        helper.assertTrue(first.source().equals(j2), "The one waiting longest: " + first.source());
        List<DeliveryTask> trip = Deliveries.batch(settlement, block, first, 12, source -> 4, day, Set.of());
        helper.assertTrue(trip.stream().map(DeliveryTask::source).toList().equals(List.of(j2, j1, j3)),
                "Nearest next while the stacks fit: " + trip.stream().map(DeliveryTask::source).toList());
        helper.assertTrue(Deliveries.batch(settlement, block, first, 11, source -> 4, day, Set.of()).size() == 2, "The third one does not fit");
        helper.assertTrue(Deliveries.batch(settlement, block, first, 4, source -> 4, day, Set.of()).size() == 1, "A full load: no more sources");

        UUID dora = UUID.randomUUID();
        Deliveries.take(data, settlement, j2, dora, "Dora", day);
        helper.assertTrue(Deliveries.pick(settlement, block, from, now, day, Set.of()).map(DeliveryTask::source).equals(Optional.of(j1)),
                "A taken task is locked for others");
        helper.assertTrue(Deliveries.pick(settlement, block, from, now, day + 1, Set.of()).map(DeliveryTask::source).equals(Optional.of(j2)),
                "The lock ends with the day");
        helper.assertTrue(Deliveries.pick(settlement, block, from, now, day, Set.of(j1, j3)).isEmpty(), "Skipped sources are left out");
        Deliveries.releaseAll(data, dora);
        helper.assertTrue(Deliveries.get(settlement, j2).orElseThrow().takerOn(day).isEmpty(), "Released");

        // A second Clerk nearer to j3 without a Warehouse: j3's task gets no Deliveryman.
        BlockPos lonely = place(helper, new BlockPos(9, 1, 1), ModJobs.CLERK.get());
        DeliveryTask j3Task = Deliveries.get(settlement, j3).orElseThrow();
        helper.assertTrue(Deliveries.homeClerk(settlement, j3Task).equals(Optional.of(lonely)) && Deliveries.warehouseOf(settlement, j3Task).isEmpty(),
                "No Warehouse serves its Home Clerk: No Deliveryman");
        helper.assertTrue(Deliveries.pick(settlement, block, from, now, day, Set.of(j1, j2)).isEmpty(), "Only tasks of its own Warehouse");
        helper.assertTrue(Warehouses.link(data, warehouse, lonely) == LinkResult.LINKED
                && Deliveries.pick(settlement, block, from, now, day, Set.of(j1, j2)).isPresent(), "Linked: the Warehouse takes it");
        remove(helper, warehouse, j1, j2, j3, lonely);
        helper.succeed();
    }

    /** In the world: a Deliveryman empties a Lumberjack drop-off into the Warehouse's linked storage and gains EXP. */
    private static void emptiesDropOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos warehouse = ClerkGameTests.clerkWithStorage(helper);
        Container own = (Container) level.getBlockEntity(warehouse);
        for (int i = 0; i < own.getContainerSize(); i++) {
            // Full block slots: the delivery has to reach the linked chest.
            own.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        Container store = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        helper.setBlock(new BlockPos(1, 1, 7), ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(new BlockPos(3, 1, 7), Blocks.CHEST);
        BlockPos jobBlock = helper.absolutePos(new BlockPos(1, 1, 7));
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 7));
        SettlementData data = SettlementData.get(level);
        JobAssignments.cyclePoint(data, jobBlock, chestPos, false, true);
        JobAssignments.cyclePoint(data, jobBlock, chestPos, false, true);
        Container dropOff = (Container) level.getBlockEntity(chestPos);
        for (int i = 0; i < 3; i++) {
            dropOff.setItem(i, new ItemStack(Items.OAK_LOG, 64));
        }
        Pickups.delivered(level, JobBlockSite.at(level, jobBlock).orElseThrow(), false);
        Settlement settlement = data.settlementAt(warehouse).orElseThrow();
        helper.assertTrue(Deliveries.get(settlement, chestPos).isPresent(), "A pickup is open");

        Settler dora = ClerkGameTests.settler(helper, new BlockPos(2, 1, 4), data, settlement);
        dora.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 16));
        dora.setNoAi(false);
        deliveryman(helper, warehouse, dora.getUUID());
        dora.addJob(new SettlerJob(ModJobs.DELIVERYMAN.get(), warehouse));
        helper.succeedWhen(() -> {
            helper.assertTrue(logs(store) == 192, "Not delivered yet: " + logs(store) + " (" + dora.getWork().statusText(warehouse).getString() + ")");
            helper.assertTrue(logs(dropOff) == 0 && Deliveries.get(settlement, chestPos).isEmpty(), "The drop-off is empty, its task done");
            helper.assertTrue(dora.getSkills().get(ModJobs.DELIVERYMAN.get()).exp() > 0 || dora.getSkills().get(ModJobs.DELIVERYMAN.get()).level() > 1,
                    "EXP per delivered stack");
            JobAssignments.unassign(data, warehouse);
            dora.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * Catch-up: per simulated hour at most the carry capacity times the trips the distance allows (a far source: one
     * trip of 9 stacks per hour); over a whole day the drop-off is emptied, the job's tool stays in its JobBlock (drop-off
 * and collect inventory).
     */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos warehouse = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(warehouse, ModBlocks.CLERK_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        SettlementData data = SettlementData.get(level);
        SettlementManager.setLevel(data, warehouse, SettlementRules.MAX_LEVEL, SettlementManager.Listener.NONE);
        // 45 blocks away: a trip takes 2 × 45 / 0.2 + 2 stops = 530 ticks, one per simulated hour.
        BlockPos jobBlock = warehouse.east(45);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(47, 0, 2));
        level.setBlock(jobBlock, ModBlocks.LUMBERJACK_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        Container jobInventory = (Container) level.getBlockEntity(jobBlock);
        // The JobBlock is drop-off and collect inventory: the Lumberjack's spare axe stays there.
        jobInventory.setItem(0, new ItemStack(Items.STONE_AXE));
        int logSlots = 0;
        for (int i = 0; i < jobInventory.getContainerSize(); i++) {
            if (jobInventory.getItem(i).isEmpty()) {
                jobInventory.setItem(i, new ItemStack(Items.OAK_LOG, 64));
                logSlots++;
            }
        }
        Settlement settlement = data.settlementAt(warehouse).orElseThrow();
        helper.assertTrue(data.settlementAt(jobBlock).filter(settlement::equals).isPresent(), "Same settlement");
        Pickups.delivered(level, JobBlockSite.at(level, jobBlock).orElseThrow(), true);
        helper.assertTrue(Deliveries.get(settlement, jobBlock).isPresent(), "A pickup is open");

        Settler dora = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        dora.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        deliveryman(helper, warehouse, dora.getUUID());
        dora.addJob(new SettlerJob(ModJobs.DELIVERYMAN.get(), warehouse));
        long missed = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L - 24000L;

        dora.getWork().setCatchUp(missed, missed + 2000L);
        CatchUp.runNow(level, List.of(dora));
        int moved = ClerkStorage.stock(level, warehouse, true).count(stack -> stack.is(Items.OAK_LOG)) / 64;
        helper.assertTrue(moved >= 9 && moved <= 18 && moved % 9 == 0, "06:00–08:00: one trip of 9 stacks per hour at most, moved " + moved);

        dora.getWork().setCatchUp(missed + 2000L, missed + 24000L);
        CatchUp.runNow(level, List.of(dora));
        int all = ClerkStorage.stock(level, warehouse, true).count(stack -> stack.is(Items.OAK_LOG)) / 64;
        helper.assertTrue(all == logSlots, "Over the day the drop-off is emptied: " + all + " / " + logSlots);
        helper.assertTrue(java.util.stream.IntStream.range(0, jobInventory.getContainerSize())
                .anyMatch(i -> jobInventory.getItem(i).is(Items.STONE_AXE)), "The Lumberjack's spare axe stays");
        helper.assertTrue(dora.getSkills().get(ModJobs.DELIVERYMAN.get()).exp() > 0 || dora.getSkills().get(ModJobs.DELIVERYMAN.get()).level() > 1,
                "EXP in catch-up");
        JobAssignments.unassign(data, warehouse);
        dora.discard();
        // The level-5 border must not reach JobBlocks of later tests.
        SettlementManager.setLevel(data, warehouse, SettlementRules.MIN_LEVEL, SettlementManager.Listener.NONE);
        level.setBlock(jobBlock, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(warehouse, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
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
}
