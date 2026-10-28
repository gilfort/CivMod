package com.gilfort.civmod.gametest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.job.BlockCountScan;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Gatherer (vault {@code Jobs/Gatherer.md}): a bare-handed Resource Collection Job that produces what
 * the wild plants around it drop; only ripe plants count.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GathererGameTests {

    private static final Identifier SWEET_BERRY_BUSH = Identifier.withDefaultNamespace("sweet_berry_bush");
    private static final Identifier SHORT_GRASS = Identifier.withDefaultNamespace("short_grass");
    private static final int SAMPLES = 4000;

    private GathererGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "gatherer_works_bare_handed", GathererGameTests::worksBareHanded);
            register(helper, "gatherer_unripe_gives_nothing", GathererGameTests::unripeGivesNothing);
            register(helper, "gatherer_seeds_from_grass", GathererGameTests::seedsFromGrass);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A grass floor 9 × 9 with a Gatherer JobBlock at (1, 1, 1). */
    static BlockPos gathererBlock(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.GATHERER_BLOCK.get());
        return relative;
    }

    /** A settler with food in its pockets, nothing on its belt, joined to the JobBlock's settlement. */
    static Settler settler(GameTestHelper helper, BlockPos jobBlock, BlockPos spawnRelative, String name) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(spawnRelative));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal(name));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        return settler;
    }

    static BlockState bush(int age) {
        return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, age);
    }

    /**
     * A Gatherer without any tool collects sweet berries from ripe bushes: it never reports "No tool", the bushes stay
     * ripe (the world is never changed) and the Found tab lists them.
     */
    private static void worksBareHanded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlockRelative = gathererBlock(helper);
        for (int x = 5; x < 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 5), bush(3));
        }
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        Settler settler = settler(helper, jobBlock, new BlockPos(3, 1, 3), "Picker");
        SettlementData data = SettlementData.get(level);
        int settlement = data.settlementAt(jobBlock).orElseThrow().id();
        TestAssignments.assign(data, jobBlock, settler.getUUID(), "Picker", settlement, List.of());
        settler.addJob(new SettlerJob(ModJobs.GATHERER.get(), jobBlock));
        helper.onEachTick(() -> helper.assertTrue(settler.getWork().status(jobBlock) != JobStatus.NO_TOOL, "A Gatherer never lacks a tool"));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).counts().getOrDefault(Items.SWEET_BERRIES, 0) > 0,
                    "No berries yet (status " + settler.getWork().status(jobBlock) + ")");
            for (int x = 5; x < 8; x++) {
                helper.assertTrue(helper.getBlockState(new BlockPos(x, 1, 5)).equals(bush(3)), "The bushes stay ripe");
            }
            helper.assertTrue(FoundTabData.of(level, jobBlock).entry(SWEET_BERRY_BUSH).isPresent(), "Found tab lists the bushes");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** Unripe bushes are not counted: the scan finds nothing and the Gatherer reports "No resources". */
    private static void unripeGivesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlockRelative = gathererBlock(helper);
        helper.setBlock(new BlockPos(5, 1, 5), bush(0));
        helper.setBlock(new BlockPos(6, 1, 5), bush(1));
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        ResourceCollectionProfile profile = ModJobs.GATHERER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        ScanResult unripe = profile.createScan(helper.absolutePos(new BlockPos(5, 1, 5)), 10).runAll(level);
        helper.assertTrue(unripe.isEmpty(), "Unripe bushes are not counted: " + unripe);
        Settler settler = settler(helper, jobBlock, new BlockPos(3, 1, 3), "Picker");
        SettlementData data = SettlementData.get(level);
        TestAssignments.assign(data, jobBlock, settler.getUUID(), "Picker", data.settlementAt(jobBlock).orElseThrow().id(), List.of());
        settler.addJob(new SettlerJob(ModJobs.GATHERER.get(), jobBlock));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_RESOURCES, "Status " + settler.getWork().status(jobBlock));
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() == 0, "Nothing collected");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** Short grass gives wheat seeds bare-handed now and then (vanilla: 12.5 %); ripe bushes give 2–3 berries. */
    private static void seedsFromGrass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceCollectionProfile profile = ModJobs.GATHERER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        BlockPos poi = helper.absolutePos(new BlockPos(1, 1, 1));
        Map<Item, Integer> grass = roll(profile, settler, level, poi, new BlockCountScan.Result(Map.of(SHORT_GRASS, 5)));
        double seeds = grass.getOrDefault(Items.WHEAT_SEEDS, 0) / (double) SAMPLES;
        helper.assertTrue(Math.abs(seeds - 0.125) < 0.03, "About 12.5 % seeds: " + grass);
        Map<Item, Integer> berries = roll(profile, settler, level, poi, new BlockCountScan.Result(Map.of(SWEET_BERRY_BUSH, 5)));
        double perRoll = berries.getOrDefault(Items.SWEET_BERRIES, 0) / (double) SAMPLES;
        helper.assertTrue(perRoll >= 2.0 && perRoll <= 3.0, "2–3 berries per roll of a ripe bush: " + berries);
        settler.discard();
        helper.succeed();
    }

    /** Rolls {@value #SAMPLES} times bare-handed; counts the items produced. */
    private static Map<Item, Integer> roll(ResourceCollectionProfile profile, Settler settler, ServerLevel level, BlockPos poi, ScanResult scan) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            Optional<ResourceCollectionProfile.Produced> produced = profile.produce(new ResourceCollectionProfile.Context(level, settler, scan, poi,
                    ItemStack.EMPTY, Set.of(), 1, Set.of(), type -> profile.dropped(level, poi, type, ItemStack.EMPTY)));
            produced.ifPresent(found -> found.stacks().forEach(stack -> counts.merge(stack.getItem(), stack.getCount(), Integer::sum)));
        }
        return counts;
    }
}
