package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Fuels;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for what a settler delivers when its work does not (#181, vault {@code Systems/NPC Daily Schedule.md}): after
 * work every job in priority order, and a collecting job with a missing tool.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class DeliveryGameTests {

    private static final int SIZE = 8;
    private static final int FISH = 50;
    /** Longer than a decision: a status that is reset shows within it. */
    private static final int HOLD_TICKS = 300;

    private DeliveryGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "delivery_evening_every_job", DeliveryGameTests::eveningEveryJob);
            register(helper, "delivery_evening_full_drop_off", DeliveryGameTests::eveningFullDropOff);
            register(helper, "delivery_missing_tool_delivers_first", DeliveryGameTests::missingToolDeliversFirst);
            register(helper, "delivery_missing_tool_from_buffer", DeliveryGameTests::missingToolFromBuffer);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static void ground(GameTestHelper helper) {
        for (int x = 0; x <= SIZE; x++) {
            for (int z = 0; z <= SIZE; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static BlockPos jobBlock(GameTestHelper helper, BlockPos relative, Block block) {
        TestSettlements.setBlock(helper, relative, block);
        return helper.absolutePos(relative);
    }

    private static Settler settler(GameTestHelper helper, BlockPos firstJobBlock) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(firstJobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal("Dana"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        return settler;
    }

    private static void takeJob(GameTestHelper helper, Settler settler, BlockPos jobBlock, Job job) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Dana", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(job, jobBlock));
    }

    private static int count(GameTestHelper helper, BlockPos pos, Item item) {
        return ((Container) helper.getLevel().getBlockEntity(pos)).countItem(item);
    }

    /** Smelter first with a fuel rest, Fisherman second with fish: the fish are delivered, the fuel rest stays. */
    private static void eveningEveryJob(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        ground(helper);
        BlockPos smelter = jobBlock(helper, new BlockPos(1, 1, 1), ModBlocks.SMELTER_BLOCK.get());
        BlockPos fisherman = jobBlock(helper, new BlockPos(5, 1, 1), ModBlocks.FISHERMAN_BLOCK.get());
        Settler settler = settler(helper, smelter);
        takeJob(helper, settler, smelter, ModJobs.SMELTER.get());
        takeJob(helper, settler, fisherman, ModJobs.FISHERMAN.get());
        settler.getWork().decideNow(level);
        Item fuel = Fuels.of(level, smelter).getFirst();
        settler.getWork().buffer(smelter).add(new ItemStack(fuel, 3));
        settler.getWork().buffer(fisherman).add(new ItemStack(Items.COD, FISH));
        helper.succeedWhen(() -> {
            helper.assertTrue(count(helper, fisherman, Items.COD) == FISH, "Fish delivered, got " + count(helper, fisherman, Items.COD)
                    + " (Smelter " + settler.getWork().status(smelter) + ", Fisherman " + settler.getWork().status(fisherman) + ")");
            helper.assertTrue(settler.getWork().buffer(smelter).count() == 3 && count(helper, smelter, fuel) == 0, "The fuel rest stays in the buffer");
            helper.assertTrue(settler.getWork().status(smelter) == JobStatus.IDLE && settler.getWork().status(fisherman) == JobStatus.IDLE,
                    "Nothing left: jobs show idle");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A full drop-off shows, stays that way all evening and does not stop the next job. */
    private static void eveningFullDropOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        ground(helper);
        BlockPos smelter = jobBlock(helper, new BlockPos(1, 1, 1), ModBlocks.SMELTER_BLOCK.get());
        BlockPos fisherman = jobBlock(helper, new BlockPos(5, 1, 1), ModBlocks.FISHERMAN_BLOCK.get());
        Container full = (Container) level.getBlockEntity(smelter);
        for (int slot = 0; slot < full.getContainerSize(); slot++) {
            full.setItem(slot, new ItemStack(Items.STONE, Items.STONE.getDefaultMaxStackSize()));
        }
        Settler settler = settler(helper, smelter);
        takeJob(helper, settler, smelter, ModJobs.SMELTER.get());
        takeJob(helper, settler, fisherman, ModJobs.FISHERMAN.get());
        settler.getWork().decideNow(level);
        settler.getWork().buffer(smelter).add(new ItemStack(Items.IRON_INGOT, 5));
        settler.getWork().buffer(fisherman).add(new ItemStack(Items.COD, FISH));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(count(helper, fisherman, Items.COD) == FISH && settler.getWork().status(smelter) == JobStatus.DROP_OFF_FULL,
                        "Fish delivered, Smelter shows its full drop-off: " + count(helper, fisherman, Items.COD) + ", " + settler.getWork().status(smelter)))
                .thenExecuteFor(HOLD_TICKS, () -> helper.assertTrue(settler.getWork().status(smelter) == JobStatus.DROP_OFF_FULL,
                        "Stays full, no new attempt: " + settler.getWork().status(smelter)))
                .thenExecute(() -> {
                    helper.assertTrue(settler.getWork().buffer(smelter).count() == 5, "The ingots wait in the buffer");
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** A broken rod with fish in the buffer and a rod in the collect inventory: the fish are delivered, then the rod is fetched. */
    private static void missingToolDeliversFirst(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = brokenRod(helper);
        BlockPos fisherman = settler.getJobs().getFirst().jobBlock();
        ((Container) level.getBlockEntity(fisherman)).setItem(0, new ItemStack(Items.FISHING_ROD));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getInventory().countItem(Items.FISHING_ROD) == 1,
                    "Rod fetched, got " + settler.getInventory().countItem(Items.FISHING_ROD) + " (" + settler.getWork().status(fisherman) + ")");
            helper.assertTrue(count(helper, fisherman, Items.COD) == FISH && settler.getWork().buffer(fisherman).isEmpty(), "Fish delivered first");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A broken rod with fish and a rod it fished up in the buffer: the rod goes straight onto the belt, the fish stay. */
    private static void missingToolFromBuffer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = brokenRod(helper);
        BlockPos fisherman = settler.getJobs().getFirst().jobBlock();
        settler.getWork().buffer(fisherman).add(new ItemStack(Items.FISHING_ROD));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getInventory().countItem(Items.FISHING_ROD) == 1,
                    "Rod taken from the buffer, got " + settler.getInventory().countItem(Items.FISHING_ROD) + " (" + settler.getWork().status(fisherman) + ")");
            helper.assertTrue(settler.getWork().buffer(fisherman).count() >= FISH && count(helper, fisherman, Items.COD) == 0,
                    "The fish stay in the buffer: " + settler.getWork().buffer(fisherman).count());
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A Fisherman during work without a rod and with fish in its buffer. */
    private static Settler brokenRod(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ground(helper);
        BlockPos fisherman = jobBlock(helper, new BlockPos(1, 1, 1), ModBlocks.FISHERMAN_BLOCK.get());
        Settler settler = settler(helper, fisherman);
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        takeJob(helper, settler, fisherman, ModJobs.FISHERMAN.get());
        // The first decision creates the Workplace that holds the buffer.
        settler.getWork().decideNow(level);
        settler.getWork().buffer(fisherman).add(new ItemStack(Items.COD, FISH));
        return settler;
    }
}
