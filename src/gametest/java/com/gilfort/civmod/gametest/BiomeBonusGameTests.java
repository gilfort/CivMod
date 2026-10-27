package com.gilfort.civmod.gametest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.job.BiomeBonuses;
import com.gilfort.civmod.job.FishTable;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for biome bonuses (vault {@code Systems/JobBlock.md}, spec #104): the datapack files of the test pack
 * ({@code civmod_biome_bonuses/test_*.json}) and the combining rules of the resolved bonus.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BiomeBonusGameTests {

    private static final double EPSILON = 1.0E-9;
    private static final int SAMPLES = 20000;
    /**
     * A relative x or z one biome cell before the test area: the {@link #cellStart} boundary of 4 puts the JobBlock at
     * x 1, which reads the cell at x -4..-1 when the area starts on a cell boundary; the same holds for a work place at
     * z 0 or 1.
     */
    static final int BEFORE_AREA = -4;

    private BiomeBonusGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "biome_bonus_combining", BiomeBonusGameTests::combining);
            register(helper, "biome_bonus_matching", BiomeBonusGameTests::matching);
            register(helper, "biome_bonus_invalid_files", BiomeBonusGameTests::invalidFiles);
            register(helper, "biome_bonus_double_drop", BiomeBonusGameTests::doubleDrop);
            register(helper, "biome_bonus_defaults", BiomeBonusGameTests::defaults);
            register(helper, "biome_bonus_positions", BiomeBonusGameTests::positions);
            register(helper, "biome_bonus_fisherman_treasure", BiomeBonusGameTests::fishermanTreasure);
            register(helper, "biome_bonus_miner_work_place", BiomeBonusGameTests::minerWorkPlace);
            register(helper, "biome_bonus_rechosen_work_place", BiomeBonusGameTests::rechosenWorkPlace);
            register(helper, "biome_bonus_lumberjack_double_drop", BiomeBonusGameTests::lumberjackDoubleDrop);
            register(helper, "biome_bonus_fisherman_double_drop", BiomeBonusGameTests::fishermanDoubleDrop);
            register(helper, "biome_bonus_craftsperson_station", BiomeBonusGameTests::craftspersonStation);
        });
    }

    /**
     * Sets the biome of a box (relative corners). {@code Level#getBiome} reads the 4×4×4 cell a position lies in or the
     * neighbour cell on its nearer side, so a position 1 block inside the box can read the cell before it: the world's
     * own biome. A box whose edge is closer than 2 blocks to a checked position starts at {@link #BEFORE_AREA}.
     */
    static void fillBiome(GameTestHelper helper, BlockPos from, BlockPos to, ResourceKey<Biome> key) {
        var result = FillBiomeCommand.fill(helper.getLevel(), helper.absolutePos(from), helper.absolutePos(to), biome(helper, key));
        logFill(helper, from, to, key);
        helper.assertTrue(result.left().isPresent(), "Biome not set (unloaded chunks?): " + result.right().map(Exception::getMessage).orElse(""));
    }

    /**
     * Logs a biome fill with its absolute box, so a test that finds a foreign biome in its area can be matched with the
     * fill that put it there, or shown to read an unfilled cell (CI runs the biome tests side by side).
     */
    static void logFill(GameTestHelper helper, BlockPos from, BlockPos to, ResourceKey<Biome> key) {
        CivMod.LOGGER.info("[biome fill] test at {}: {} .. {} -> {}", helper.absolutePos(BlockPos.ZERO), helper.absolutePos(from),
                helper.absolutePos(to), key.identifier());
    }

    /**
     * The first relative coordinate at or after {@code relative} where a biome cell starts: a block reads the cell it
     * lies in or a neighbour, so a block at least 3 before a cell boundary reads only cells before it, and one at least
     * 5 after it only cells after it.
     */
    static int cellStart(GameTestHelper helper, int relative) {
        int absolute = helper.absolutePos(new BlockPos(relative, 0, 0)).getX();
        return relative + Math.floorMod(-absolute, 4);
    }

    /**
     * CivMod's default bonuses: the dark forest stacks the forest speed with the dark oak double drop; cave biomes and
     * tags count; no default file has a drawback.
     */
    private static void defaults(GameTestHelper helper) {
        BiomeBonus darkForest = BiomeBonuses.resolve(ModJobs.LUMBERJACK.get(), biome(helper, Biomes.DARK_FOREST));
        helper.assertTrue(near(darkForest.speed().value(), 1.1) && near(darkForest.doubleDropChance(new ItemStack(Items.DARK_OAK_LOG)), 0.15)
                && darkForest.rareChance().sources().isEmpty(), "Dark forest: forest speed and dark oak double drop, no rare group: " + darkForest);
        helper.assertTrue(near(BiomeBonuses.resolve(ModJobs.MINER.get(), biome(helper, Biomes.JAGGED_PEAKS)).rareChance().value(), 1.1),
                "Miner in jagged peaks (#c:is_mountain): ore chance ×1.1");
        helper.assertTrue(near(BiomeBonuses.resolve(ModJobs.MINER.get(), biome(helper, Biomes.DEEP_DARK)).rareChance().value(), 1.2),
                "Miner in the deep dark: ore chance ×1.2");
        helper.assertTrue(near(BiomeBonuses.resolve(ModJobs.MINER.get(), biome(helper, Biomes.DRIPSTONE_CAVES)).doubleDropChance(new ItemStack(Items.RAW_COPPER)), 0.5),
                "Miner in dripstone caves: raw copper +0.5");
        helper.assertTrue(near(BiomeBonuses.resolve(ModJobs.FISHERMAN.get(), biome(helper, Biomes.FROZEN_RIVER)).doubleDropChance(new ItemStack(Items.SALMON)), 0.5)
                && near(BiomeBonuses.resolve(ModJobs.FISHERMAN.get(), biome(helper, Biomes.FROZEN_RIVER)).speed().value(), 1.1),
                "Fisherman at a frozen river: cold salmon and river speed");
        for (Map.Entry<Identifier, BiomeBonuses.BonusFile> entry : BiomeBonuses.files().entrySet()) {
            if (entry.getKey().getPath().startsWith("test_")) {
                continue;
            }
            BiomeBonuses.BonusFile file = entry.getValue();
            helper.assertTrue(file.speed().orElse(1.0) >= 1.0 && file.rareChance().orElse(1.0) >= 1.0
                    && file.doubleDrop().stream().allMatch(drop -> drop.chance() >= 0.0), "Default file without drawbacks: " + entry.getKey());
        }
        helper.succeed();
    }

    /**
     * The biome position per Job Category: a Resource Collection Job reads its work place (none without one), a
     * Logistics Job its JobBlock, a Mission Job nothing even with a matching file.
     */
    private static void positions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(32, 0, 32));
        fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(12, 8, 12), Biomes.MUSHROOM_FIELDS);
        fillBiome(helper, new BlockPos(20, -4, 20), new BlockPos(32, 8, 32), Biomes.PLAINS);
        BlockPos mushroom = helper.absolutePos(new BlockPos(6, 1, 6));
        BlockPos plains = helper.absolutePos(new BlockPos(26, 1, 26));

        helper.assertTrue(BiomeBonuses.resolve(ModJobs.ADVENTURER.get(), biome(helper, Biomes.MUSHROOM_FIELDS)).speed().value() == 2.0,
                "The Adventurer's test file matches mushroom fields");
        BiomeBonus adventurer = BiomeBonuses.today(level, ModJobs.ADVENTURER.get(), mushroom, mushroom);
        helper.assertTrue(adventurer.isEmpty() && adventurer.biome().isEmpty(), "A Mission Job has no biome bonus: " + adventurer);

        BiomeBonus clerk = BiomeBonuses.today(level, ModJobs.CLERK.get(), mushroom, plains);
        helper.assertTrue(near(clerk.speed().value(), 1.3), "A Logistics Job reads the JobBlock's biome: " + clerk);

        BiomeBonus remote = BiomeBonuses.today(level, ModJobs.MINER.get(), plains, mushroom);
        helper.assertTrue(near(remote.rareChance().value(), 6.0), "The Miner reads its work place, not the JobBlock: " + remote);
        BiomeBonus home = BiomeBonuses.today(level, ModJobs.MINER.get(), mushroom, plains);
        helper.assertTrue(home.isEmpty() && home.biome().equals(Optional.of(Biomes.PLAINS)),
                "A JobBlock in mushroom fields does not count: " + home);
        helper.assertTrue(BiomeBonuses.today(level, ModJobs.MINER.get(), mushroom, null) == BiomeBonus.NONE, "No work place, no bonus");
        fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(12, 8, 12), Biomes.PLAINS);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
    }

    /** {@code rare_chance} multiplies the Fisherman's treasure chance up to 1.0: 25 % × 4 → every catch is treasure. */
    private static void fishermanTreasure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level,
                EntitySpawnReason.EVENT);
        BlockPos poi = helper.absolutePos(new BlockPos(1, 1, 1));
        settler.snapTo(poi.getX() + 0.5, poi.getY(), poi.getZ() + 0.5, 0.0F, 0.0F);
        ResourceCollectionProfile profile = ModJobs.FISHERMAN.get()
                .profile(ResourceCollectionProfile.class).orElseThrow();
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        Set<Item> fish = new HashSet<>();
        FishTable.get().fish().forEach(entry -> fish.add(entry.item()));
        int fishCaught = 0;
        for (int i = 0; i < 200; i++) {
            var produced = profile.produce(new ResourceCollectionProfile.Context(level, settler, null, poi, rod,
                    Set.of(), 100, Set.of(), type -> profile.dropped(level, poi, type, rod), 4.0)).orElseThrow();
            fishCaught += (int) produced.stacks().stream().filter(stack -> fish.contains(stack.getItem())).count();
        }
        helper.assertTrue(fishCaught == 0, "Treasure chance capped at 100 %: " + fishCaught + " fish caught");
        helper.assertTrue(profile.rareGroup().isPresent() && ModJobs.LUMBERJACK.get()
                .profile(ResourceCollectionProfile.class).orElseThrow().rareGroup().isEmpty(),
                "The Fisherman has a rare group, the Lumberjack none");
        settler.discard();
        helper.succeed();
    }

    /**
     * A Miner whose work point lies in mushroom fields (its JobBlock in plains) gets the double drop of that biome:
     * every cobblestone and granite comes twice. A biome change at the work place does not change today's bonus, nor
     * does a save and reload on the same day; the next day's choice reads the new biome.
     */
    private static void minerWorkPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        // Plains up to the cell boundary after the JobBlock, mushroom fields beyond: the work point reads mushroom fields.
        int boundary = cellStart(helper, 4);
        fillBiome(helper, new BlockPos(BEFORE_AREA, -4, 0), new BlockPos(boundary - 1, 8, 24), Biomes.PLAINS);
        fillBiome(helper, new BlockPos(boundary, -4, 0), new BlockPos(24, 8, 24), Biomes.MUSHROOM_FIELDS);
        // 3 before the boundary the JobBlock reads plains, 5 after it the work point mushroom fields (8 apart: inside the border).
        BlockPos jobBlockRelative = new BlockPos(boundary - 3, 1, 3);
        BlockPos poiRelative = new BlockPos(boundary + 5, 1, 3);
        helper.setBlock(jobBlockRelative, ModBlocks.MINER_BLOCK.get());
        helper.setBlock(poiRelative, Blocks.GRANITE);
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        BlockPos poi = helper.absolutePos(poiRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(boundary - 3, 1, 6), data, settlement);
        settler.setNoAi(false);
        settler.setCustomName(Component.literal("Digger"));
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_PICKAXE));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, poi, true, false)
                == JobAssignments.PointResult.WORK, "Work point set");
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Digger", settlement.id(),
                List.of()) == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), jobBlock));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().buffer(jobBlock).count() >= 6,
                        "Not enough mined yet (status " + settler.getWork().status(jobBlock) + ", buffer " + settler.getWork().buffer(jobBlock).stacks() + ")"))
                .thenExecute(() -> {
                    BiomeBonus atJobBlock = BiomeBonuses.at(level, ModJobs.MINER.get(), jobBlock);
                    helper.assertTrue(atJobBlock.isEmpty(), "The JobBlock " + jobBlock + " stands in plains: " + atJobBlock);
                    Map<Item, Integer> counts = settler.getWork().buffer(jobBlock).counts();
                    helper.assertTrue(counts.values().stream().allMatch(count -> count % 2 == 0), "Every stone drop comes twice: " + counts);
                    BiomeBonus today = CivModApi.biomeBonus(settler, jobBlock);
                    helper.assertTrue(today.biome().equals(Optional.of(Biomes.MUSHROOM_FIELDS)) && near(today.rareChance().value(), 6.0),
                            "Today's bonus is the work place's: " + today);
                    helper.assertTrue(today.doubleDrops().stream().allMatch(drop -> drop.source().equals(CivMod.id("test_miner_a"))),
                            "With its source file: " + today.doubleDrops());
                    // The biome at the work place changes: today's bonus stays.
                    fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(24, 8, 24), Biomes.PLAINS);
                })
                .thenExecuteAfter(40, () -> helper.assertTrue(CivModApi.biomeBonus(settler, jobBlock).biome()
                        .equals(Optional.of(Biomes.MUSHROOM_FIELDS)), "The bonus stays fixed for the day"))
                .thenExecute(() -> {
                    // Saved and loaded again on the same day: the day's biome is saved with the work place.
                    settler.getWork().restore(settler.getWork().snapshot());
                    helper.assertTrue(CivModApi.biomeBonus(settler, jobBlock).biome().equals(Optional.of(Biomes.MUSHROOM_FIELDS)),
                            "A reload on the same day keeps the day's biome: " + CivModApi.biomeBonus(settler, jobBlock));
                })
                .thenExecute(() -> {
                    // The work place and its biome were chosen yesterday: the bonus stays until the next decision
                    // chooses the work place for today.
                    SettlerWork.BuffersSnapshot saved = settler.getWork().snapshot();
                    settler.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), saved.workPlaces().stream()
                            .map(entry -> new SettlerWork.WorkPlaceEntry(entry.jobBlock(), entry.poi(), entry.day() - 1, entry.biome())).toList(),
                            saved.scans(), saved.catchUp()));
                    helper.assertTrue(CivModApi.biomeBonus(settler, jobBlock).biome().equals(Optional.of(Biomes.MUSHROOM_FIELDS)),
                            "Yesterday's biome until today's choice: " + CivModApi.biomeBonus(settler, jobBlock));
                })
                .thenWaitUntil(() -> {
                    BiomeBonus next = CivModApi.biomeBonus(settler, jobBlock);
                    helper.assertTrue(next.biome().equals(Optional.of(Biomes.PLAINS)) && next.isEmpty(),
                            "The next day's work place reads the new biome: " + next);
                })
                .thenExecute(() -> {
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * A work place chosen anew on the same day (the old one is no longer a work place) reads the new place's biome: the
     * Miner's work point in mushroom fields is dug away, the next work point stands in plains.
     */
    private static void rechosenWorkPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        int boundary = cellStart(helper, 4);
        fillBiome(helper, new BlockPos(BEFORE_AREA, -4, 0), new BlockPos(boundary - 1, 8, 24), Biomes.PLAINS);
        fillBiome(helper, new BlockPos(boundary, -4, 0), new BlockPos(24, 8, 24), Biomes.MUSHROOM_FIELDS);
        BlockPos jobBlockRelative = new BlockPos(boundary - 3, 1, 3);
        BlockPos mushroomRelative = new BlockPos(boundary + 5, 1, 3);
        BlockPos plainsRelative = new BlockPos(boundary - 3, 1, 9);
        helper.setBlock(jobBlockRelative, ModBlocks.MINER_BLOCK.get());
        helper.setBlock(mushroomRelative, Blocks.GRANITE);
        helper.setBlock(plainsRelative, Blocks.GRANITE);
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(boundary - 3, 1, 6), data, settlement);
        settler.setNoAi(false);
        settler.setCustomName(Component.literal("Digger"));
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_PICKAXE));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(mushroomRelative), true, false)
                == JobAssignments.PointResult.WORK, "Work point set");
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Digger", settlement.id(),
                List.of()) == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), jobBlock));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(CivModApi.biomeBonus(settler, jobBlock).biome().equals(Optional.of(Biomes.MUSHROOM_FIELDS)),
                        "The work place in mushroom fields is not chosen yet (status " + settler.getWork().status(jobBlock) + ")"))
                .thenExecute(() -> {
                    helper.setBlock(mushroomRelative, Blocks.AIR);
                    helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(plainsRelative), true, false)
                            == JobAssignments.PointResult.WORK, "Second work point set");
                })
                .thenWaitUntil(() -> {
                    BiomeBonus today = CivModApi.biomeBonus(settler, jobBlock);
                    BlockPos plains = helper.absolutePos(plainsRelative);
                    helper.assertTrue(today.biome().equals(Optional.of(Biomes.PLAINS)) && today.isEmpty(),
                            "The work place chosen anew reads its own biome: " + today + "; work places " + settler.getWork().snapshot().workPlaces()
                                    + ", second point " + plains + " in " + level.getBiome(plains).unwrapKey().map(ResourceKey::identifier)
                                    + ", settler at " + settler.blockPosition());
                })
                .thenExecute(() -> {
                    settler.discard();
                    fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(24, 8, 24), Biomes.PLAINS);
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** A Lumberjack in mushroom fields (test file: logs +1.0) gets every log twice. */
    private static void lumberjackDoubleDrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(14, 0, 14));
        // The work point and the trees stand at z 0–3.
        fillBiome(helper, new BlockPos(0, -4, BEFORE_AREA), new BlockPos(12, 8, 12), Biomes.MUSHROOM_FIELDS);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        helper.succeedWhen(() -> {
            Map<Item, Integer> counts = settler.getWork().buffer(jobBlock).counts();
            helper.assertTrue(counts.getOrDefault(Items.OAK_LOG, 0) >= 4,
                    "Not enough wood yet (status " + settler.getWork().status(jobBlock) + "): " + counts);
            helper.assertTrue(counts.get(Items.OAK_LOG) % 2 == 0,
                    "Every log comes twice: " + counts + " (" + CivModApi.biomeBonus(settler, jobBlock) + ")");
            settler.discard();
            fillBiome(helper, new BlockPos(0, -4, BEFORE_AREA), new BlockPos(12, 8, 12), Biomes.PLAINS);
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A Fisherman at water in mushroom fields (test file: fishes +1.0) catches every fish twice. */
    private static void fishermanDoubleDrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(14, 0, 14));
        FishermanGameTests.pond(helper);
        fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(14, 8, 14), Biomes.MUSHROOM_FIELDS);
        BlockPos jobBlockRelative = new BlockPos(1, 3, 1);
        helper.setBlock(jobBlockRelative, ModBlocks.FISHERMAN_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 3, 2), data, settlement);
        settler.setNoAi(false);
        settler.setCustomName(Component.literal("Angler"));
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.FISHING_ROD));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(6, 2, 6)), true, false)
                == JobAssignments.PointResult.WORK, "Work point set");
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Angler", settlement.id(),
                List.of()) == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.FISHERMAN.get(), jobBlock));
        helper.succeedWhen(() -> {
            Map<Item, Integer> counts = settler.getWork().buffer(jobBlock).counts();
            helper.assertTrue(counts.values().stream().mapToInt(Integer::intValue).sum() >= 4,
                    "Not enough fish yet (status " + settler.getWork().status(jobBlock) + "): " + counts);
            helper.assertTrue(counts.values().stream().allMatch(count -> count % 2 == 0),
                    "Every fish comes twice: " + counts + " (" + CivModApi.biomeBonus(settler, jobBlock) + ")");
            settler.discard();
            fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(14, 8, 14), Biomes.PLAINS);
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * A Craftsperson whose station of the day stands in mushroom fields (its JobBlock in plains) works with that
     * biome's speed; the station is chosen while it crafts (catch-up of the previous workday).
     */
    private static void craftspersonStation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(28, 0, 16));
        int boundary = cellStart(helper, 4);
        // The JobBlock stands at x 1.
        fillBiome(helper, new BlockPos(BEFORE_AREA, -4, 0), new BlockPos(boundary - 1, 8, 16), Biomes.PLAINS);
        fillBiome(helper, new BlockPos(boundary, -4, 0), new BlockPos(28, 8, 16), Biomes.MUSHROOM_FIELDS);
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        // A second JobBlock widens the settlement border to the far station.
        helper.setBlock(new BlockPos(10, 1, 5), ModBlocks.MINER_BLOCK.get());
        SettlementData data = SettlementData.get(level);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 1));
        chest.setItem(1, new ItemStack(Items.COBBLESTONE, 3));
        helper.assertTrue(StockRules.setRule(data, clerk,
                StockRule.minimum(0, Items.STONE_PICKAXE, 1, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        CraftspersonGameTests.placeTable(helper, new BlockPos(17, 1, 5));
        CraftspersonGameTests.define(helper, craft, 0, Items.STONE_PICKAXE);
        CraftspersonGameTests.define(helper, craft, 1, Items.STICK);
        CraftspersonGameTests.define(helper, craft, 2, Items.OAK_PLANKS);
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        CraftspersonGameTests.catchUp(level, settler, 4000L);
        helper.assertTrue(ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.STONE_PICKAXE)) == 1,
                "Pickaxe crafted at the station (status " + settler.getWork().status(craft) + ")");
        BiomeBonus today = CivModApi.biomeBonus(settler, craft);
        helper.assertTrue(today.biome().equals(Optional.of(Biomes.MUSHROOM_FIELDS)) && near(today.speed().value(), 1.5),
                "The station of the day's biome counts: " + today);
        helper.assertTrue(BiomeBonuses.at(level, ModJobs.CRAFTSPERSON.get(), craft).isEmpty(), "The JobBlock stands in plains");
        TestAssignments.unassign(data, clerk);
        settler.discard();
        fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(28, 8, 16), Biomes.PLAINS);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    static Holder<Biome> biome(GameTestHelper helper, ResourceKey<Biome> key) {
        return helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(key);
    }

    private static Job dummy() {
        return ModJobs.REGISTRY.getValue(CivMod.id("gametest_dummy"));
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    /**
     * Two matching files multiply their {@code speed} (1.1 × 1.1); the sources name both files. A Job without a rare
     * group ignores {@code rare_chance}; the Miner's factors multiply too, and the rare chance is capped at 1.0.
     */
    private static void combining(GameTestHelper helper) {
        BiomeBonus mushroom = BiomeBonuses.resolve(dummy(), biome(helper, Biomes.MUSHROOM_FIELDS));
        helper.assertTrue(near(mushroom.speed().value(), 1.21), "Speed 1.1 × 1.1 = 1.21: " + mushroom.speed());
        helper.assertTrue(mushroom.speed().sources().contains(CivMod.id("test_dummy_a")) && mushroom.speed().sources().contains(CivMod.id("test_dummy_b")),
                "Both files are sources of the speed: " + mushroom.speed().sources());
        helper.assertTrue(near(mushroom.rareChance().value(), 1.0) && mushroom.rareChance().sources().isEmpty(),
                "A Job without a rare group ignores rare_chance: " + mushroom.rareChance());
        helper.assertTrue(mushroom.biome().equals(Optional.of(Biomes.MUSHROOM_FIELDS)), "Names its biome: " + mushroom.biome());

        BiomeBonus miner = BiomeBonuses.resolve(ModJobs.MINER.get(), biome(helper, Biomes.MUSHROOM_FIELDS));
        helper.assertTrue(near(miner.rareChance().value(), 6.0), "Rare chance 2 × 3 = 6: " + miner.rareChance());
        helper.assertTrue(near(BiomeBonus.cappedChance(0.1, miner.rareChance().value()), 0.6)
                && near(BiomeBonus.cappedChance(0.34, miner.rareChance().value()), 1.0), "Rare chance capped at 1.0");
        helper.assertTrue(near(miner.speed().value(), 1.0) && miner.speed().sources().isEmpty(), "No speed file for the Miner: " + miner.speed());

        BiomeBonus plains = BiomeBonuses.resolve(dummy(), biome(helper, Biomes.PLAINS));
        helper.assertTrue(plains.isEmpty() && near(plains.speed().value(), 1.0) && plains.doubleDrops().isEmpty(), "No bonus in plains: " + plains);
        helper.succeed();
    }

    /**
     * Biomes by id, list and tag; {@code jobs} is an exact list: the Craftsperson's file does not reach its
     * Specialization, the Mechanic.
     */
    private static void matching(GameTestHelper helper) {
        BiomeBonus spikes = BiomeBonuses.resolve(dummy(), biome(helper, Biomes.ICE_SPIKES));
        helper.assertTrue(near(spikes.speed().value(), 1.1) && spikes.speed().sources().equals(List.of(CivMod.id("test_dummy_b"))),
                "A biome list matches its second biome: " + spikes.speed());
        BiomeBonus badlands = BiomeBonuses.resolve(dummy(), biome(helper, Biomes.WOODED_BADLANDS));
        helper.assertTrue(near(badlands.speed().value(), 2.0), "A biome tag matches: " + badlands.speed());

        BiomeBonus craftsperson = BiomeBonuses.resolve(ModJobs.CRAFTSPERSON.get(), biome(helper, Biomes.MUSHROOM_FIELDS));
        BiomeBonus mechanic = BiomeBonuses.resolve(ModJobs.MECHANIC.get(), biome(helper, Biomes.MUSHROOM_FIELDS));
        helper.assertTrue(near(craftsperson.speed().value(), 1.5), "The listed Job gets the bonus: " + craftsperson.speed());
        helper.assertTrue(mechanic.isEmpty(), "Its Specialization is not listed and gets nothing: " + mechanic);

        BiomeBonus oakPlanks = BiomeBonuses.resolve(dummy(), biome(helper, Biomes.MUSHROOM_FIELDS));
        helper.assertTrue(near(oakPlanks.doubleDropChance(new ItemStack(Items.OAK_PLANKS)), 1.3)
                        && near(oakPlanks.doubleDropChance(new ItemStack(Items.BIRCH_PLANKS)), 0.5)
                        && near(oakPlanks.doubleDropChance(new ItemStack(Items.STICK)), 0.5)
                        && near(oakPlanks.doubleDropChance(new ItemStack(Items.COBBLESTONE)), -0.4)
                        && near(oakPlanks.doubleDropChance(new ItemStack(Items.DIRT)), -1.2)
                        && near(oakPlanks.doubleDropChance(new ItemStack(Items.STONE)), 0.0),
                "Double drop chances of matching entries add up (item id, list, #tag)");
        helper.assertTrue(oakPlanks.doubleDrops(new ItemStack(Items.OAK_PLANKS)).stream().map(BiomeBonus.DoubleDrop::source).distinct().toList()
                .equals(List.of(CivMod.id("test_dummy_b"))), "Double drop sources: " + oakPlanks.doubleDrops(new ItemStack(Items.OAK_PLANKS)));
        helper.succeed();
    }

    /** Out-of-range values, unknown Jobs and broken JSON are rejected (the loader logs and ignores such files). */
    private static void invalidFiles(GameTestHelper helper) {
        helper.assertTrue(BiomeBonuses.files().containsKey(CivMod.id("test_dummy_a")), "A valid file is loaded: " + BiomeBonuses.files().keySet());
        for (String name : List.of("test_invalid_range", "test_invalid_job", "test_invalid_syntax")) {
            helper.assertFalse(BiomeBonuses.files().containsKey(CivMod.id(name)), "Invalid file " + name + " is ignored");
        }
        String base = "{\"jobs\":[\"civmod:gametest_dummy\"],\"biomes\":\"minecraft:plains\",";
        helper.assertTrue(BiomeBonuses.parse(JsonParser.parseString(base + "\"speed\":0.1,\"rare_chance\":10}")).isSuccess(), "0.1 and 10 are allowed");
        helper.assertTrue(BiomeBonuses.parse(JsonParser.parseString(base + "\"speed\":0.05}")).isError(), "speed below 0.1");
        helper.assertTrue(BiomeBonuses.parse(JsonParser.parseString(base + "\"rare_chance\":11}")).isError(), "rare_chance above 10");
        helper.assertTrue(BiomeBonuses.parse(JsonParser.parseString(base + "\"double_drop\":[{\"items\":\"minecraft:stick\",\"chance\":-1.5}]}")).isError(),
                "double_drop chance below -1");
        helper.assertTrue(BiomeBonuses.parse(JsonParser.parseString(base + "\"double_drop\":[{\"items\":\"minecraft:stick\",\"chance\":10}]}")).isSuccess(),
                "double_drop chance 10 is allowed");
        helper.assertTrue(BiomeBonuses.parse(JsonParser.parseString("{\"jobs\":[\"civmod:gametest_dummy\"]}")).isError(), "biomes is required");
        helper.succeed();
    }

    /**
     * The copies of a produced stack: S = 0.5 → one extra copy half of the time; S = 1.3 → always one, 30 % two;
     * S = −0.4 → lost 40 % of the time; S ≤ −1 → always lost.
     */
    private static void doubleDrop(GameTestHelper helper) {
        BiomeBonus bonus = BiomeBonuses.resolve(dummy(), biome(helper, Biomes.MUSHROOM_FIELDS));
        Map<Integer, Integer> half = copies(bonus, new ItemStack(Items.STICK));
        helper.assertTrue(half.keySet().equals(Set.of(1, 2)) && Math.abs(half.get(2) / (double) SAMPLES - 0.5) < 0.015,
                "S = 0.5: one or two stacks: " + half);
        Map<Integer, Integer> more = copies(bonus, new ItemStack(Items.OAK_PLANKS));
        helper.assertTrue(more.keySet().equals(Set.of(2, 3)) && Math.abs(more.get(3) / (double) SAMPLES - 0.3) < 0.015,
                "S = 1.3: always two, 30 % three: " + more);
        Map<Integer, Integer> malus = copies(bonus, new ItemStack(Items.COBBLESTONE));
        helper.assertTrue(malus.keySet().equals(Set.of(0, 1)) && Math.abs(malus.get(0) / (double) SAMPLES - 0.4) < 0.015,
                "S = -0.4: lost 40 % of the time: " + malus);
        helper.assertTrue(copies(bonus, new ItemStack(Items.DIRT)).equals(Map.of(0, SAMPLES)), "S <= -1: always lost");
        helper.assertTrue(copies(bonus, new ItemStack(Items.STONE)).equals(Map.of(1, SAMPLES)), "No entry: the stack as it is");

        List<ItemStack> stacks = bonus.applyDoubleDrop(List.of(new ItemStack(Items.OAK_PLANKS, 3)), RandomSource.create(5L));
        helper.assertTrue(stacks.size() >= 2 && stacks.stream().allMatch(stack -> stack.is(Items.OAK_PLANKS) && stack.getCount() == 3),
                "Extra copies are copies of the produced stack: " + stacks);
        helper.succeed();
    }

    private static Map<Integer, Integer> copies(BiomeBonus bonus, ItemStack stack) {
        RandomSource random = RandomSource.create(7L);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            counts.merge(bonus.copies(stack, random), 1, Integer::sum);
        }
        return counts;
    }
}
