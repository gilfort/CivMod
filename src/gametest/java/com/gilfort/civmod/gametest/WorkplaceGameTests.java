package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Workplace of a job: removing a job leaves nothing behind, and saves from before the Workplace
 * still load.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class WorkplaceGameTests {

    private static final long DAY = 24000L;

    private WorkplaceGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "workplace_forget_leaves_no_trace", WorkplaceGameTests::forgetLeavesNoTrace);
            register(helper, "workplace_old_save_loads", WorkplaceGameTests::oldSaveLoads);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * A job that scanned, chose its work place and got blocked ("No tool") is removed: no status, buffer, scan or work
     * place of the day remains, the buffer lies at the settler, and the job re-added at once is not blocked any more.
     */
    private static void forgetLeavesNoTrace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        SettlerJob job = settler.getJobs().get(0);
        BlockPos jobBlock = job.jobBlock();
        int[] dropped = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertFalse(settler.getWork().found(job).isEmpty(), "Never scanned");
                    helper.assertTrue(hasTrace(settler.getWork().snapshot(), jobBlock), "No work place of the day yet");
                })
                .thenExecute(() -> settler.getInventory().setItem(Settler.BELT_START, ItemStack.EMPTY))
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_TOOL,
                        "Not blocked yet: " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> {
                    dropped[0] = settler.getWork().buffer(jobBlock).count();
                    int itemsBefore = itemsNear(level, settler);
                    settler.removeJob(jobBlock);
                    helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.IDLE, "Status left: " + settler.getWork().status(jobBlock));
                    helper.assertTrue(settler.getWork().buffer(jobBlock).isEmpty(), "Buffer left");
                    helper.assertTrue(settler.getWork().found(job).isEmpty(), "Scan left");
                    helper.assertFalse(hasTrace(settler.getWork().snapshot(), jobBlock), "Saved state left");
                    helper.assertTrue(itemsNear(level, settler) - itemsBefore == dropped[0], "Buffer not dropped at the settler");
                    settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
                    settler.addJob(job);
                })
                // A blocked job waits 600 ticks; a left-over blocked time would keep the re-added job idle that long.
                .thenExecuteAfter(250, () -> helper.assertTrue(settler.getWork().status(jobBlock) != JobStatus.IDLE
                        && settler.getWork().status(jobBlock) != JobStatus.NO_TOOL, "Re-added job still blocked: " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> {
                    settler.discard();
                    level.getEntitiesOfClass(ItemEntity.class, new AABB(settler.blockPosition()).inflate(8)).forEach(ItemEntity::discard);
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * A save written before the Workplace (buffers, work places, scans and pending catch-up, built by hand) loads into a
     * settler, which catches up with the saved scan and then goes on working.
     */
    private static void oldSaveLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        BlockPos workPoint = helper.absolutePos(new BlockPos(4, 1, 1));
        long clock = level.getOverworldClockTime();
        long today = Math.floorDiv(clock, DAY);

        CompoundTag buffer = new CompoundTag();
        buffer.put("job_block", pos(jobBlock));
        ListTag items = new ListTag();
        CompoundTag logs = new CompoundTag();
        logs.putString("id", "minecraft:oak_log");
        logs.putInt("count", 12);
        items.add(logs);
        buffer.put("items", items);
        CompoundTag workPlace = new CompoundTag();
        workPlace.put("job_block", pos(jobBlock));
        workPlace.put("poi", pos(workPoint));
        workPlace.putLong("day", today);
        CompoundTag wood = new CompoundTag();
        CompoundTag logCounts = new CompoundTag();
        logCounts.putInt("minecraft:oak_log", 48);
        wood.put("logs", logCounts);
        wood.put("leaves", new CompoundTag());
        CompoundTag scan = new CompoundTag();
        scan.put("job_block", pos(jobBlock));
        scan.put("center", pos(workPoint));
        scan.putInt("radius", SettlementRules.joinRadius(SettlementRules.MIN_LEVEL));
        scan.putLong("day", today);
        scan.put("wood", wood);
        CompoundTag catchUp = new CompoundTag();
        catchUp.putLong("active_clock", clock - DAY);
        catchUp.putLong("from", clock - DAY);
        catchUp.putLong("to", clock);
        CompoundTag save = new CompoundTag();
        save.put("buffers", list(buffer));
        save.put("work_places", list(workPlace));
        save.put("scans", list(scan));
        save.put("catch_up", catchUp);

        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        settler.getWork().restore(SettlerWork.BuffersSnapshot.CODEC.parse(ops, save).getOrThrow());
        SettlerWork.BuffersSnapshot loaded = settler.getWork().snapshot();
        helper.assertTrue(settler.getWork().buffer(jobBlock).count() == 12, "Buffer lost: " + settler.getWork().buffer(jobBlock).count());
        helper.assertTrue(loaded.workPlaces().equals(List.of(new SettlerWork.WorkPlaceEntry(jobBlock, workPoint, today))),
                "Work place of the day lost: " + loaded.workPlaces());
        helper.assertFalse(settler.getWork().found(settler.getJobs().get(0)).isEmpty(), "Scan lost");
        // The test clock may be below one day, so the missed time is checked as saved rather than through hasPendingCatchUp.
        helper.assertTrue(loaded.catchUp().from() == clock - DAY && loaded.catchUp().to() == clock, "Pending catch-up lost: " + loaded.catchUp());

        Container container = (Container) level.getBlockEntity(jobBlock);
        CatchUp.runNow(level, List.of(settler));
        int total = count(container) + settler.getWork().buffer(jobBlock).count();
        helper.assertTrue(total >= 12 + 40, "About a day of wood expected on top of the saved buffer: " + total);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.WORKING
                        || settler.getWork().status(jobBlock) == JobStatus.DELIVERING, "Not working again: " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> {
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    private static boolean hasTrace(SettlerWork.BuffersSnapshot snapshot, BlockPos jobBlock) {
        return snapshot.entries().stream().anyMatch(entry -> entry.jobBlock().equals(jobBlock))
                || snapshot.workPlaces().stream().anyMatch(entry -> entry.jobBlock().equals(jobBlock))
                || snapshot.scans().stream().anyMatch(entry -> entry.jobBlock().equals(jobBlock));
    }

    private static int itemsNear(ServerLevel level, Settler settler) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(settler.blockPosition()).inflate(3)).stream()
                .mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static int count(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }

    private static IntArrayTag pos(BlockPos pos) {
        return new IntArrayTag(new int[] {pos.getX(), pos.getY(), pos.getZ()});
    }

    private static ListTag list(CompoundTag entry) {
        ListTag list = new ListTag();
        list.add(entry);
        return list;
    }
}
