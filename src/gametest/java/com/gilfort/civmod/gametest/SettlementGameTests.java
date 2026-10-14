package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager.Outcome;
import com.gilfort.civmod.settlement.SettlementManager.Placement;
import com.gilfort.civmod.settlement.SettlementManager;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Story 2 (JobBlocks and settlement founding).
 * <p>
 * Settlement rules are tested on fresh {@link SettlementData} instances so parallel tests in the shared test level
 * cannot interfere; one integration test places a real JobBlock.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlementGameTests {

    private SettlementGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "settlement_founding_and_joining", SettlementGameTests::foundingAndJoining);
            register(helper, "settlement_nearest_choice", SettlementGameTests::nearestChoice);
            register(helper, "settlement_unconnected_and_auto_connect", SettlementGameTests::unconnectedAndAutoConnect);
            register(helper, "settlement_removal_and_dissolution", SettlementGameTests::removalAndDissolution);
            register(helper, "settlement_persistence", SettlementGameTests::persistence);
            register(helper, "job_block_world_integration", SettlementGameTests::worldIntegration);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Records listener callbacks. */
    private static final class Recorder implements SettlementManager.Listener {
        final List<BlockPos> autoConnected = new ArrayList<>();

        @Override
        public void blockChanged(BlockPos pos, int settlementId, boolean connected) {
        }

        @Override
        public void autoConnected(BlockPos pos) {
            autoConnected.add(pos);
        }
    }

    private static Placement place(SettlementData data, int x, int level, SettlementManager.Listener listener) {
        return SettlementManager.place(data, new BlockPos(x, 64, 0), ModJobs.LUMBERJACK.get(), level, 0L, RandomSource.create(0L), listener);
    }

    /** A first JobBlock founds a settlement; a block within 10 × level joins it (height is ignored). */
    private static void foundingAndJoining(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Placement first = place(data, 0, 1, SettlementManager.Listener.NONE);
        helper.assertTrue(first.outcome() == Outcome.FOUNDED, "First block should found a settlement");
        Placement second = SettlementManager.place(data, new BlockPos(10, 100, 0), ModJobs.MINER.get(), 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        helper.assertTrue(second.outcome() == Outcome.JOINED && second.settlement() == first.settlement(),
                "Block at horizontal distance 10 should join, regardless of height");
        helper.assertTrue(data.settlements().size() == 1, "Expected exactly one settlement");
        Placement far = place(data, 200, 1, SettlementManager.Listener.NONE);
        helper.assertTrue(far.outcome() == Outcome.FOUNDED && data.settlements().size() == 2, "Block outside all zones founds a second settlement");
        helper.succeed();
    }

    /** A block in reach of two settlements joins the one with the nearest JobBlock. */
    private static void nearestChoice(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement west = place(data, 0, 1, SettlementManager.Listener.NONE).settlement();
        Settlement east = place(data, 31, 3, SettlementManager.Listener.NONE).settlement();
        helper.assertTrue(west != east, "Block at 31 is outside the exclusion radius of 30 and should found a second settlement");
        Placement between = place(data, 9, 1, SettlementManager.Listener.NONE);
        helper.assertTrue(between.outcome() == Outcome.JOINED && between.settlement() == west,
                "Block at 9 is covered by west (radius 10) and east (radius 30) and should join the nearer west settlement");
        helper.succeed();
    }

    /** A block in the exclusion zone waits unconnected and connects automatically once the chain reaches it. */
    private static void unconnectedAndAutoConnect(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Recorder recorder = new Recorder();
        Settlement settlement = place(data, 0, 1, recorder).settlement();
        Placement waiting = place(data, 20, 1, recorder);
        helper.assertTrue(waiting.outcome() == Outcome.UNCONNECTED && waiting.settlement() == settlement,
                "Block at 20 is in the exclusion zone but out of reach");
        helper.assertTrue(waiting.distance() == 20 && waiting.required() == 10, "Unexpected distance info: " + waiting);
        helper.assertTrue(data.settlements().size() == 1, "No second settlement may be founded in the exclusion zone");
        Placement highLevel = place(data, -25, 3, recorder);
        helper.assertTrue(highLevel.outcome() == Outcome.UNCONNECTED && highLevel.required() == 10,
                "A level-3 block outside the border must not connect via its own radius: " + highLevel);

        Placement bridge = place(data, 10, 1, recorder);
        helper.assertTrue(bridge.outcome() == Outcome.JOINED, "Bridge block should join");
        helper.assertTrue(recorder.autoConnected.equals(List.of(new BlockPos(20, 64, 0))), "Waiting block should auto-connect: " + recorder.autoConnected);
        helper.assertTrue(settlement.connectedCount() == 3, "The three blocks at 0, 10 and 20 should be connected");
        helper.succeed();
    }

    /** Removing blocks keeps the settlement; the last removal dissolves it; a lone unconnected block becomes the anchor. */
    private static void removalAndDissolution(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = place(data, 0, 1, SettlementManager.Listener.NONE).settlement();
        place(data, 20, 1, SettlementManager.Listener.NONE);
        SettlementManager.remove(data, new BlockPos(0, 64, 0), SettlementManager.Listener.NONE);
        helper.assertTrue(data.settlements().size() == 1 && settlement.connectedCount() == 1,
                "Remaining unconnected block should become the connected anchor");
        SettlementManager.remove(data, new BlockPos(20, 64, 0), SettlementManager.Listener.NONE);
        helper.assertTrue(data.settlements().isEmpty(), "Removing the last block should dissolve the settlement");
        helper.assertTrue(data.settlementAt(new BlockPos(20, 64, 0)).isEmpty(), "Position index should be cleared");
        helper.succeed();
    }

    /** Settlement data survives a save / load round trip. */
    private static void persistence(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        place(data, 0, 2, SettlementManager.Listener.NONE);
        place(data, 45, 1, SettlementManager.Listener.NONE);
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var json = SettlementData.CODEC.encodeStart(ops, data).getOrThrow();
        SettlementData loaded = SettlementData.CODEC.parse(ops, json).getOrThrow();
        helper.assertTrue(loaded.settlements().size() == 1, "Expected one settlement after loading");
        Settlement settlement = loaded.settlements().iterator().next();
        helper.assertTrue(settlement.blocks().size() == 2 && settlement.connectedCount() == 1, "Blocks or connection state lost");
        helper.assertTrue(loaded.settlementAt(new BlockPos(45, 64, 0)).isPresent(), "Position index not rebuilt after loading");
        helper.assertTrue(settlement.block(new BlockPos(0, 64, 0)).map(block -> block.level() == 2).orElse(false), "Level lost");
        helper.succeed();
    }

    /** A real JobBlock registers itself one tick after placement and drops itself with its level. */
    private static void worldIntegration(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(relative);
        helper.setBlock(relative, ModBlocks.MINER_BLOCK.get());
        ItemStack levelStack = new ItemStack(ModItems.MINER_BLOCK.get());
        levelStack.set(ModDataComponents.JOB_LEVEL.get(), 3);
        helper.getBlockEntity(relative, JobBlockEntity.class).applyComponentsFromItemStack(levelStack);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                SettlementData.get(helper.getLevel()).settlementAt(absolute).isPresent(), "JobBlock was not registered")).thenExecute(() -> {
            // Check the loot table result directly; item entities are unreliable in non-ticking test chunks.
            List<ItemStack> drops = Block.getDrops(helper.getLevel().getBlockState(absolute), helper.getLevel(), absolute,
                    helper.getLevel().getBlockEntity(absolute));
            boolean dropped = drops.stream().anyMatch(stack -> stack.is(ModItems.MINER_BLOCK.get())
                    && stack.getOrDefault(ModDataComponents.JOB_LEVEL.get(), 1) == 3);
            helper.assertTrue(dropped, "JobBlock would not drop itself with level 3: " + drops);
            helper.getLevel().destroyBlock(absolute, false);
            helper.assertTrue(SettlementData.get(helper.getLevel()).settlementAt(absolute).isEmpty(), "Destroyed JobBlock still registered");
        }).thenSucceed();
    }
}
