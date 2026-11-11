package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.Roaming;
import com.gilfort.civmod.job.RoamingContext;
import com.gilfort.civmod.job.RoamingProfile;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the roaming of the Lumberjack and the Veggy Farmer (vault {@code Jobs/Lumberjack.md},
 * {@code Jobs/Veggy Farmer.md}): the decisions of {@link Roaming} with a clock the test sets, and one live run each.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class RoamingGameTests {

    /** The longest wait before the Lumberjack changes his log: 4 game hours. */
    private static final long LOG_CHANGE_MAX_TICKS = 4000L;
    /** Just under the shortest wait: 2 game hours. */
    private static final long LOG_CHANGE_MIN_TICKS_LESS_ONE = 1999L;
    /** The Veggy Farmer looks for the next crop every 5 seconds. */
    private static final long CROP_MOVE_TICKS = 100L;
    /** The crop list of the Veggy Farmer lives for 30 seconds. */
    private static final long CROP_CACHE_TICKS = 600L;
    private static final Predicate<BlockPos> ALL_FREE = pos -> true;

    private RoamingGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "roaming_lumberjack_rotates_work_points", RoamingGameTests::lumberjackRotatesWorkPoints);
            register(helper, "roaming_lumberjack_one_work_point_stays", RoamingGameTests::lumberjackOneWorkPointStays);
            register(helper, "roaming_lumberjack_auto_search", RoamingGameTests::lumberjackAutoSearch);
            register(helper, "roaming_lumberjack_log_gone", RoamingGameTests::lumberjackLogGone);
            register(helper, "roaming_farmer_moves_to_crops", RoamingGameTests::farmerMovesToCrops);
            register(helper, "roaming_farmer_leash", RoamingGameTests::farmerLeash);
            register(helper, "roaming_farmer_caches_crops", RoamingGameTests::farmerCachesCrops);
            register(helper, "roaming_farmer_works_in_world", RoamingGameTests::farmerWorksInWorld);
            register(helper, "roaming_lumberjack_works_in_world", RoamingGameTests::lumberjackWorksInWorld);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- Lumberjack

    /** A stone floor and a Lumberjack JobBlock at (1, 1, 1); returns its absolute position. */
    private static BlockPos lumberjackBlock(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        TestSettlements.setBlock(helper, relative, ModBlocks.LUMBERJACK_BLOCK.get());
        return helper.absolutePos(relative);
    }

    private static BlockPos log(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, Blocks.OAK_LOG);
        return helper.absolutePos(relative);
    }

    private static RoamingProfile lumberjack() {
        return ModJobs.LUMBERJACK.get().profile(RoamingProfile.class).orElseThrow();
    }

    private static RoamingContext context(ServerLevel level, BlockPos jobBlock, BlockPos position, long gameTime, Predicate<BlockPos> free) {
        return new RoamingContext(level, JobBlockSite.at(level, jobBlock).orElseThrow(), position, gameTime, free);
    }

    /** With several work points he rotates between them: not before 2 hours, then at the latest after 4, in order. */
    private static void lumberjackRotatesWorkPoints(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = lumberjackBlock(helper);
        BlockPos first = log(helper, new BlockPos(5, 1, 1));
        BlockPos second = log(helper, new BlockPos(9, 1, 1));
        BlockPos third = log(helper, new BlockPos(13, 1, 1));
        SettlementData data = SettlementData.get(level);
        for (BlockPos point : List.of(first, second, third)) {
            JobAssignments.cyclePoint(data, jobBlock, point, true, false);
        }
        long start = level.getGameTime();
        Roaming roaming = lumberjack().roaming(level, JobBlockSite.at(level, jobBlock).orElseThrow(), first, start);
        helper.assertValueEqual(roaming.stand(), first, "He starts at the first pick");
        helper.assertValueEqual(roaming.rollPlace(), first, "The double weight sits on the log he stands at");
        helper.assertTrue(roaming.moveOn(context(level, jobBlock, first, start + LOG_CHANGE_MIN_TICKS_LESS_ONE, ALL_FREE)).isEmpty(),
                "Not before 2 hours");
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, first, start + LOG_CHANGE_MAX_TICKS, ALL_FREE)), Optional.of(second), "Second point");
        helper.assertValueEqual(roaming.stand(), second, "Stands at the second point");
        helper.assertValueEqual(roaming.rollPlace(), second, "The double weight moves with him");
        helper.assertValueEqual(roaming.anchor(), first, "The scan anchor stays the first pick");
        helper.assertTrue(roaming.moveOn(context(level, jobBlock, second, start + LOG_CHANGE_MAX_TICKS + LOG_CHANGE_MIN_TICKS_LESS_ONE, ALL_FREE)).isEmpty(),
                "The timer starts again");
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, second, start + 2 * LOG_CHANGE_MAX_TICKS, ALL_FREE)), Optional.of(third), "Third point");
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, third, start + 3 * LOG_CHANGE_MAX_TICKS, ALL_FREE)), Optional.of(first), "Back to the first");
        // A point somebody else occupies is skipped.
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, first, start + 4 * LOG_CHANGE_MAX_TICKS, pos -> !pos.equals(second))),
                Optional.of(third), "Occupied point skipped");
        helper.succeed();
    }

    /** With exactly one work point he stays there. */
    private static void lumberjackOneWorkPointStays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = lumberjackBlock(helper);
        BlockPos only = log(helper, new BlockPos(5, 1, 1));
        log(helper, new BlockPos(9, 1, 1));
        JobAssignments.cyclePoint(SettlementData.get(level), jobBlock, only, true, false);
        long start = level.getGameTime();
        Roaming roaming = lumberjack().roaming(level, JobBlockSite.at(level, jobBlock).orElseThrow(), only, start);
        helper.assertTrue(roaming.moveOn(context(level, jobBlock, only, start + 5 * LOG_CHANGE_MAX_TICKS, ALL_FREE)).isEmpty(), "He stays");
        helper.assertValueEqual(roaming.stand(), only, "At the only point");
        helper.succeed();
    }

    /**
     * Without work points the automatic search is the default: the nearest free log, but a visible step away from the
     * one he stands at (a neighbour on the same trunk is no change).
     */
    private static void lumberjackAutoSearch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = lumberjackBlock(helper);
        BlockPos nearest = log(helper, new BlockPos(4, 1, 1));
        log(helper, new BlockPos(4, 1, 3));
        BlockPos stepAway = log(helper, new BlockPos(9, 1, 1));
        BlockPos far = log(helper, new BlockPos(14, 1, 1));
        long start = level.getGameTime();
        JobBlockSite site = JobBlockSite.at(level, jobBlock).orElseThrow();
        Roaming roaming = lumberjack().roaming(level, site, nearest, start);
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, nearest, start + LOG_CHANGE_MAX_TICKS, ALL_FREE)), Optional.of(stepAway),
                "The nearest log that is a step away");
        Roaming other = lumberjack().roaming(level, site, nearest, start);
        helper.assertValueEqual(other.moveOn(context(level, jobBlock, nearest, start + LOG_CHANGE_MAX_TICKS, pos -> !pos.equals(stepAway))),
                Optional.of(far), "A log somebody else occupies is skipped");
        helper.assertValueEqual(other.anchor(), nearest, "The scan anchor stays the first pick");
        helper.succeed();
    }

    /**
     * The log he moved to is felled: he does not wait for the timer but takes the next log at once. If it is the work place of
     * the day itself, the loop chooses a new one and he stays.
     */
    private static void lumberjackLogGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = lumberjackBlock(helper);
        BlockPos first = log(helper, new BlockPos(5, 1, 1));
        BlockPos second = log(helper, new BlockPos(9, 1, 1));
        BlockPos third = log(helper, new BlockPos(13, 1, 1));
        SettlementData data = SettlementData.get(level);
        for (BlockPos point : List.of(first, second, third)) {
            JobAssignments.cyclePoint(data, jobBlock, point, true, false);
        }
        long start = level.getGameTime();
        Roaming roaming = lumberjack().roaming(level, JobBlockSite.at(level, jobBlock).orElseThrow(), first, start);
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, first, start + LOG_CHANGE_MAX_TICKS, ALL_FREE)), Optional.of(second), "Second point");
        helper.setBlock(new BlockPos(9, 1, 1), Blocks.AIR);
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, second, start + LOG_CHANGE_MAX_TICKS + 1, ALL_FREE)), Optional.of(third),
                "Straight to the next log");
        helper.setBlock(new BlockPos(13, 1, 1), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.AIR);
        helper.assertTrue(roaming.moveOn(context(level, jobBlock, third, start + LOG_CHANGE_MAX_TICKS + 2, ALL_FREE)).isEmpty(),
                "No log left to go to");
        helper.succeed();
    }

    // ---------------------------------------------------------------- Veggy Farmer

    /**
     * A grass floor with a farmland field (x 4–14, z 4–14) and a level-{@code level} Gatherer JobBlock with the composter at
     * (1, 1, 1); returns its absolute position.
     */
    private static BlockPos farm(GameTestHelper helper, int jobBlockLevel) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), x >= 4 && z >= 4 ? Blocks.FARMLAND : Blocks.GRASS_BLOCK);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        TestSettlements.setBlock(helper, relative, ModBlocks.GATHERER_BLOCK.get());
        StationTypeGameTests.setLevel(helper, relative, jobBlockLevel);
        BlockPos jobBlock = helper.absolutePos(relative);
        TestAssignments.setStationType(SettlementData.get(helper.getLevel()), jobBlock, 1, Items.COMPOSTER);
        return jobBlock;
    }

    private static void wheat(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
    }

    private static RoamingProfile farmer() {
        return ModJobs.VEGGY_FARMER.get().profile(RoamingProfile.class).orElseThrow();
    }

    private static boolean matureCrop(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).equals(((CropBlock) Blocks.WHEAT).getStateForAge(7));
    }

    /**
     * He starts at the Scarecrow; every 5 seconds he picks a fully grown crop from a box of radius 5 around where he is
     * now. His rolls stay with the Scarecrow.
     */
    private static void farmerMovesToCrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = farm(helper, 2);
        VeggyFarmerGameTests.scarecrow(helper, new BlockPos(9, 1, 9));
        BlockPos scarecrow = helper.absolutePos(new BlockPos(9, 1, 9));
        for (int x = 8; x <= 10; x++) {
            wheat(helper, new BlockPos(x, 1, 7));
        }
        BlockPos tooFar = helper.absolutePos(new BlockPos(9, 1, 15));
        helper.setBlock(new BlockPos(9, 1, 15), ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        long start = level.getGameTime();
        Roaming roaming = farmer().roaming(level, JobBlockSite.at(level, jobBlock).orElseThrow(), scarecrow, start);
        helper.assertValueEqual(roaming.stand(), scarecrow, "He starts at the Scarecrow");
        helper.assertTrue(roaming.moveOn(context(level, jobBlock, scarecrow, start + CROP_MOVE_TICKS - 1, ALL_FREE)).isEmpty(), "Not before 5 seconds");
        Optional<BlockPos> picked = roaming.moveOn(context(level, jobBlock, scarecrow, start + CROP_MOVE_TICKS, ALL_FREE));
        helper.assertTrue(picked.isPresent(), "A crop after 5 seconds");
        helper.assertTrue(matureCrop(level, picked.get()), "A fully grown crop: " + picked.get());
        helper.assertFalse(picked.get().equals(tooFar), "Not the crop outside the box of radius 5");
        helper.assertValueEqual(roaming.stand(), picked.get(), "He stands at the crop");
        helper.assertValueEqual(roaming.rollPlace(), scarecrow, "The rolls stay with the Scarecrow");
        helper.assertValueEqual(roaming.anchor(), scarecrow, "The scan anchor stays the Scarecrow");
        helper.succeed();
    }

    /** The leash: a crop inside his box but outside the radius of the Scarecrow's own scan is never picked. */
    private static void farmerLeash(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = farm(helper, 1);
        VeggyFarmerGameTests.scarecrow(helper, new BlockPos(6, 1, 6));
        BlockPos scarecrow = helper.absolutePos(new BlockPos(6, 1, 6));
        BlockPos inside = helper.absolutePos(new BlockPos(10, 1, 6));
        wheat(helper, new BlockPos(10, 1, 6));
        wheat(helper, new BlockPos(12, 1, 6));
        wheat(helper, new BlockPos(14, 1, 6));
        // He stands at the edge of the leash (radius 5 at JobBlock level 1): his box reaches 5 blocks further.
        BlockPos edge = helper.absolutePos(new BlockPos(11, 1, 6));
        long start = level.getGameTime();
        Roaming roaming = farmer().roaming(level, JobBlockSite.at(level, jobBlock).orElseThrow(), scarecrow, start);
        List<BlockPos> picks = new ArrayList<>();
        for (int move = 1; move <= 10; move++) {
            roaming.moveOn(context(level, jobBlock, edge, start + move * CROP_MOVE_TICKS, ALL_FREE)).ifPresent(picks::add);
        }
        helper.assertValueEqual(picks, List.of(inside), "Only the crop inside the leash");
        helper.succeed();
    }

    /** One search serves several moves: crops that grow in meanwhile are found only when the list is refreshed after 30 seconds. */
    private static void farmerCachesCrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = farm(helper, 2);
        VeggyFarmerGameTests.scarecrow(helper, new BlockPos(9, 1, 9));
        BlockPos scarecrow = helper.absolutePos(new BlockPos(9, 1, 9));
        BlockPos old = helper.absolutePos(new BlockPos(9, 1, 7));
        BlockPos grownLater = helper.absolutePos(new BlockPos(7, 1, 9));
        wheat(helper, new BlockPos(9, 1, 7));
        long start = level.getGameTime();
        Roaming roaming = farmer().roaming(level, JobBlockSite.at(level, jobBlock).orElseThrow(), scarecrow, start);
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, scarecrow, start + CROP_MOVE_TICKS, ALL_FREE)), Optional.of(old), "The search finds the crop");
        wheat(helper, new BlockPos(7, 1, 9));
        for (long time = start + 2 * CROP_MOVE_TICKS; time < start + CROP_MOVE_TICKS + CROP_CACHE_TICKS; time += CROP_MOVE_TICKS) {
            helper.assertTrue(roaming.moveOn(context(level, jobBlock, old, time, ALL_FREE)).isEmpty(), "The cached list has no other crop at " + (time - start));
        }
        helper.assertValueEqual(roaming.moveOn(context(level, jobBlock, old, start + CROP_MOVE_TICKS + CROP_CACHE_TICKS, ALL_FREE)), Optional.of(grownLater),
                "The refreshed list has it");
        helper.succeed();
    }

    /**
     * Live: the Veggy Farmer walks off the Scarecrow to the plants, produces for the Scarecrow's field and never harvests.
     */
    private static void farmerWorksInWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        BlockPos jobBlock = farm(helper, 2);
        for (int x = 4; x <= 14; x++) {
            for (int z = 4; z <= 14; z++) {
                wheat(helper, new BlockPos(x, 1, z));
            }
        }
        helper.setBlock(new BlockPos(9, 1, 9), Blocks.AIR);
        VeggyFarmerGameTests.scarecrow(helper, new BlockPos(9, 1, 9));
        BlockPos scarecrow = helper.absolutePos(new BlockPos(9, 1, 9));
        Settler settler = VeggyFarmerGameTests.veggyFarmer(helper, jobBlock);
        helper.succeedWhen(() -> {
            double away = Math.sqrt(settler.position().distanceToSqr(Vec3.atBottomCenterOf(scarecrow)));
            helper.assertTrue(away > 3.0, "Still at the Scarecrow (" + away + ", status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() > 0, "Nothing produced yet");
            for (int x = 4; x <= 14; x++) {
                for (int z = 4; z <= 14; z++) {
                    if (x != 9 || z != 9) {
                        helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(x, 1, z));
                    }
                }
            }
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * Live: with two work points the Lumberjack leaves his first log after at most 4 game hours and works at the other
     * one; the scan stays where it was.
     */
    private static void lumberjackWorksInWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = lumberjackBlock(helper);
        for (int y = 1; y <= 3; y++) {
            for (int x = 4; x < 6; x++) {
                for (int z = 4; z < 6; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                    helper.setBlock(new BlockPos(x + 8, y, z), Blocks.OAK_LOG);
                }
            }
        }
        BlockPos first = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos second = helper.absolutePos(new BlockPos(12, 1, 4));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        JobAssignments.cyclePoint(data, jobBlock, first, true, false);
        JobAssignments.cyclePoint(data, jobBlock, second, true, false);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        // Far from both logs, so that being near each one shows he went there.
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 10)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Tester"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Tester", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
        // Whichever log the day starts with, he is at the other one later.
        boolean[] seenFirst = new boolean[1];
        boolean[] seenSecond = new boolean[1];
        // The buffer is emptied when it is delivered, so "produced" sticks once seen.
        boolean[] produced = new boolean[1];
        helper.succeedWhen(() -> {
            Vec3 position = settler.position();
            seenFirst[0] |= position.distanceToSqr(Vec3.atBottomCenterOf(first)) < 16.0;
            seenSecond[0] |= position.distanceToSqr(Vec3.atBottomCenterOf(second)) < 16.0;
            produced[0] |= settler.getWork().buffer(jobBlock).count() > 0;
            helper.assertTrue(seenFirst[0] && seenSecond[0], "He has not worked at both logs yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(produced[0], "Nothing produced (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(settler.getWork().scan(jobBlock).isPresent(), "The scan is still there");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
