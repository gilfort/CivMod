package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.JobCategory;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.ClerkSpots;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobAssignments.PointResult;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Clerk and its storage (#55): access rule, storage limits, food points, Clerk as drop-off and
 * catch-up food.
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
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Clerk is a Logistics Job offered by the Clerk JobBlock, whose 27 slots are its item capability. */
    private static void registryAndBlock(GameTestHelper helper) {
        helper.assertTrue(ModJobs.CLERK.get().category() == JobCategory.LOGISTICS, "Clerk is a Logistics Job");
        helper.assertTrue(ModJobs.CLERK.get().id().equals(CivMod.id("clerk")), "Registered as civmod:clerk");
        helper.assertTrue(ModBlocks.CLERK_BLOCK.get().getJobType() == ModJobs.CLERK.get(), "The Clerk JobBlock offers the Clerk");
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWithStorage(helper);
        var handler = level.getCapability(Capabilities.Item.BLOCK, clerk, null);
        helper.assertTrue(handler != null && handler.size() == 27, "Hoppers see the 27 block slots only");
        helper.assertTrue(SettlementData.get(level).settlementAt(clerk).isPresent(), "The Clerk JobBlock joins a settlement");
        helper.succeed();
    }

    /**
     * Without a working Clerk only the 27 block slots are accessible; a Clerk settler on shift opens the linked storage
     * at its counter.
     */
    private static void accessRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWithStorage(helper);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.BREAD, 5));

        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, true)) == 0, "No settler: linked storage is closed");
        ((Container) level.getBlockEntity(clerk)).setItem(3, new ItemStack(Items.BREAD, 2));
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, true)) == 2, "No settler: the block slots are open");

        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        helper.assertTrue(JobAssignments.assign(data, clerk, CLARA, "Clara", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Clerk assigned");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, true)) == 7, "Working Clerk: linked storage at the counter");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, false)) == 2, "Not at the counter: block slots only");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, EVENING, true)) == 2, "After the shift: block slots only");

        Stock open = ClerkStorage.stock(level, clerk, MORNING, true);
        ItemStack rest = open.insert(new ItemStack(Items.COBBLESTONE, 64 * 27));
        helper.assertTrue(rest.getCount() < 64 * 26, "A working Clerk fills the linked storage as well: rest " + rest.getCount());
        Stock closed = ClerkStorage.stock(level, clerk, EVENING, true);
        helper.assertTrue(closed.insert(new ItemStack(Items.STICK, 64)).getCount() == 64, "Closed Clerk with full block slots takes nothing");
        JobAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /**
     * Storage 2 / 4 / 8 / 12 / 16 by level (extra containers kept but inactive after a downgrade), one Clerk per
     * container, no JobBlock as storage, 8 stand spots on standable blocks, inside the border only; points persist.
     */
    private static void storageLimits(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos clerk = new BlockPos(0, 64, 0);
        SettlementManager.place(data, clerk, ModJobs.CLERK.get(), 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        int[] limits = {2, 4, 8, 12, 16};
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
        SettlementManager.place(data, other, ModJobs.CLERK.get(), 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
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
        SettlementManager.place(data, clerk, ModJobs.CLERK.get(), 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
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

    /** Places a Clerk JobBlock at (1, 1, 1) with a chest at (4, 1, 1) linked as storage. */
    static BlockPos clerkWithStorage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        helper.setBlock(new BlockPos(4, 1, 1), Blocks.CHEST);
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chest = helper.absolutePos(new BlockPos(4, 1, 1));
        helper.assertTrue(JobAssignments.cycleClerkPoint(SettlementData.get(level), clerk, chest, true, false, Direction.NORTH)
                == JobAssignments.PointResult.STORAGE, "Chest linked as storage");
        return clerk;
    }

    private static int count(Stock stock) {
        return stock.count(stack -> stack.is(Items.BREAD));
    }
}
