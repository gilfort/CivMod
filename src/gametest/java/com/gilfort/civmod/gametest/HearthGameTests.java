package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.SettlerWork.RestPlace;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Hearths;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Fire need and the camp (vault {@code Systems/Needs.md}): the hearth cache of a settlement, the row
 * in the Needs tab, the ring of straw beds and the campfire of the first sleeper.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class HearthGameTests {

    private static final BlockPos CENTER = new BlockPos(8, 1, 8);
    private static final long DAY = 24000L;

    private HearthGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "hearth_cache_follows_blocks", HearthGameTests::cacheFollowsBlocks);
            register(helper, "hearth_found_when_founding", HearthGameTests::foundWhenFounding);
            register(helper, "hearth_tag_added_block", HearthGameTests::tagAddedBlock);
            register(helper, "hearth_fire_row", HearthGameTests::fireRow);
            register(helper, "camp_campfire_once", HearthGameTests::campfireOnce);
            register(helper, "camp_campfire_not_on_wood", HearthGameTests::campfireNotOnWood);
            register(helper, "camp_campfire_replaced", HearthGameTests::campfireReplaced);
            register(helper, "camp_campfire_not_in_catch_up", HearthGameTests::campfireNotInCatchUp);
            register(helper, "camp_straw_ring_faces_fire", HearthGameTests::strawRingFacesFire);
            register(helper, "camp_full_ring_overflows", HearthGameTests::fullRingOverflows);
            register(helper, "camp_far_bed", HearthGameTests::farBed);
            register(helper, "camp_near_bed", HearthGameTests::nearBed);
            register(helper, "camp_fire_row_far", HearthGameTests::fireRowFar);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A stone floor around the middle of the test. */
    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 2 * CENTER.getX(); x++) {
            for (int z = 0; z <= 2 * CENTER.getZ(); z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** Registers a JobBlock without a block in the world, in a settlement of its own. */
    private static BlockPos jobBlock(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        SettlementManager.place(SettlementData.get(helper.getLevel()), pos, ModJobs.LUMBERJACK.get(), 1, 0L, RandomSource.create(0L),
                SettlementManager.Listener.NONE);
        return pos;
    }

    private static Settlement settlement(GameTestHelper helper, BlockPos jobBlock) {
        return SettlementData.get(helper.getLevel()).settlementAt(jobBlock).orElseThrow();
    }

    /** A settler that does not move, with a job at the JobBlock. */
    private static Settler settler(GameTestHelper helper, BlockPos jobBlock) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
        return settler;
    }

    private static Optional<NeedsReport.Row> fireRow(Settler settler) {
        NeedsReport report = settler.getWork().needsReport((ServerLevel) settler.level());
        return report.rows(1).stream().filter(row -> key(row.name()).equals("civmod.need.fire")).findFirst();
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents contents ? contents.getKey() : text.getString();
    }

    private static void end(GameTestHelper helper, BlockPos jobBlock, Settler... settlers) {
        for (Settler settler : settlers) {
            settler.discard();
        }
        SettlementManager.remove(SettlementData.get(helper.getLevel()), jobBlock, SettlementManager.Listener.NONE);
        helper.succeed();
    }

    /** A JobBlock of a higher level: the border reaches the far bed and the far hearth. */
    private static BlockPos wideJobBlock(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        SettlementManager.place(SettlementData.get(helper.getLevel()), pos, ModJobs.LUMBERJACK.get(), 4, 0L, RandomSource.create(0L),
                SettlementManager.Listener.NONE);
        return pos;
    }

    /** Places a bed (both halves) with its head at the given relative position, facing north. */
    private static BlockPos bed(GameTestHelper helper, BlockPos relativeHead) {
        BlockPos head = helper.absolutePos(relativeHead);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        helper.getLevel().setBlock(head.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        helper.getLevel().setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
        return head;
    }

    private static RestPlace goToBed(Settler settler) {
        settler.getWork().goToBedNow((ServerLevel) settler.level());
        return settler.getWork().restPlace().orElseThrow();
    }

    /** The first sleeper places one campfire 3–6 blocks from the Settlement Block; the others use it. */
    private static void campfireOnce(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER);
        Settler first = settler(helper, jobBlock);
        Settler second = settler(helper, jobBlock);
        goToBed(first);
        goToBed(second);
        Set<BlockPos> hearths = Hearths.of(level, settlement(helper, jobBlock));
        helper.assertTrue(hearths.size() == 1, "One campfire: " + hearths);
        BlockPos fire = hearths.iterator().next();
        BlockState state = level.getBlockState(fire);
        helper.assertTrue(state.is(Blocks.CAMPFIRE) && state.getValue(CampfireBlock.LIT), "Lit campfire: " + state);
        double distance = Math.sqrt(fire.distSqr(jobBlock));
        helper.assertTrue(distance >= 3.0 && distance <= 6.0, "3–6 blocks from the Settlement Block: " + distance);
        helper.assertTrue(fireRow(first).orElseThrow().met() && fireRow(second).orElseThrow().met(), "Both sleep at the fire");
        end(helper, jobBlock, first, second);
    }

    /** Wood is no place for a fire: only the one stone block of a wooden floor takes it. */
    private static void campfireNotOnWood(GameTestHelper helper) {
        for (int x = 0; x <= 2 * CENTER.getX(); x++) {
            for (int z = 0; z <= 2 * CENTER.getZ(); z++) {
                helper.setBlock(new BlockPos(x, 0, z), (x + z) % 3 == 0 ? Blocks.OAK_LOG : (x + z) % 3 == 1 ? Blocks.OAK_PLANKS : Blocks.OAK_LEAVES);
            }
        }
        BlockPos stone = CENTER.east(5).north(1);
        helper.setBlock(stone.below(), Blocks.STONE);
        BlockPos jobBlock = jobBlock(helper, CENTER);
        Settler settler = settler(helper, jobBlock);
        goToBed(settler);
        helper.assertTrue(Hearths.of(helper.getLevel(), settlement(helper, jobBlock)).equals(Set.of(helper.absolutePos(stone))),
                "Only on the stone: " + Hearths.of(helper.getLevel(), settlement(helper, jobBlock)));
        end(helper, jobBlock, settler);
    }

    /** Broken with no hearth left: the next first sleeper places a new one. */
    private static void campfireReplaced(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER);
        Settler first = settler(helper, jobBlock);
        Settler next = settler(helper, jobBlock);
        goToBed(first);
        BlockPos fire = Hearths.of(level, settlement(helper, jobBlock)).iterator().next();
        level.removeBlock(fire, false);
        helper.assertTrue(Hearths.of(level, settlement(helper, jobBlock)).isEmpty(), "Broken");
        goToBed(next);
        helper.assertTrue(Hearths.of(level, settlement(helper, jobBlock)).size() == 1, "A new campfire");
        end(helper, jobBlock, first, next);
    }

    /** Catch-up places nothing; the first live evening does. */
    private static void campfireNotInCatchUp(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER);
        Settler settler = settler(helper, jobBlock);
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), DAY) * DAY;
        settler.getWork().setCatchUp(dayStart - 2 * DAY, dayStart);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(Hearths.of(level, settlement(helper, jobBlock)).isEmpty(), "No campfire in catch-up");
        goToBed(settler);
        helper.assertTrue(Hearths.of(level, settlement(helper, jobBlock)).size() == 1, "The first live evening places it");
        end(helper, jobBlock, settler);
    }

    /** Settlers without a bed lie in a ring around the hearth nearest to their JobBlock, feet to the fire. */
    private static void strawRingFacesFire(GameTestHelper helper) {
        floor(helper);
        BlockPos jobBlock = jobBlock(helper, CENTER.west(6));
        helper.setBlock(CENTER.east(6), Blocks.CAMPFIRE);
        helper.setBlock(CENTER.west(2), Blocks.CAMPFIRE);
        BlockPos hearth = helper.absolutePos(CENTER.west(2));
        Settler first = settler(helper, jobBlock);
        Settler second = settler(helper, jobBlock);
        Set<BlockPos> spots = new HashSet<>();
        for (Settler settler : List.of(first, second)) {
            RestPlace place = goToBed(settler);
            helper.assertTrue(place.straw() != null && !place.bed(), "On a straw bed: " + place);
            helper.assertTrue(Math.max(Math.abs(place.pos().getX() - hearth.getX()), Math.abs(place.pos().getZ() - hearth.getZ())) == SettlerBeds.CAMP_RING,
                    "In the ring: " + place + " around " + hearth);
            BlockPos feet = place.pos().relative(place.straw().facing().getOpposite());
            helper.assertTrue(feet.distSqr(hearth) < place.pos().distSqr(hearth), "Feet to the fire: " + place);
            spots.add(place.pos());
        }
        helper.assertTrue(spots.size() == 2, "Each its own straw bed: " + spots);
        end(helper, jobBlock, first, second);
    }

    /** A full ring: the next settler lies on the bare ground at the camp, and no second fire comes. */
    private static void fullRingOverflows(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER);
        helper.setBlock(CENTER.east(2), Blocks.CAMPFIRE);
        BlockPos hearth = helper.absolutePos(CENTER.east(2));
        List<UUID> others = new ArrayList<>();
        while (true) {
            UUID other = new UUID(1L, others.size());
            others.add(other);
            BlockPos spot = SettlerBeds.reserveCampSpot(level, hearth, other).orElseThrow().pos();
            if (!SettlerBeds.inCampRing(spot, hearth)) {
                break;
            }
        }
        Settler settler = settler(helper, jobBlock);
        RestPlace place = goToBed(settler);
        others.forEach(SettlerBeds::releaseStrawSpot);
        helper.assertTrue(place.straw() == null && !place.bed(), "Bare ground: " + place);
        helper.assertTrue(place.pos().distSqr(hearth) <= 2 * SettlerBeds.CAMP_GROUND * SettlerBeds.CAMP_GROUND, "At the camp: " + place);
        helper.assertTrue(fireRow(settler).orElseThrow().met(), "The fire is near");
        helper.assertTrue(Hearths.of(level, settlement(helper, jobBlock)).size() == 1, "No second fire");
        end(helper, jobBlock, settler);
    }

    /** A real bed too far from any hearth: the settler keeps it but sleeps at the camp, and the fire is met. */
    private static void farBed(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = wideJobBlock(helper, CENTER);
        helper.setBlock(CENTER.east(4), Blocks.CAMPFIRE);
        BlockPos far = bed(helper, CENTER.west(30));
        Settler settler = settler(helper, jobBlock);
        SettlerBeds.assign(level, SettlementData.get(level), Optional.of(settlement(helper, jobBlock)), settler.getUUID(), far);
        helper.assertFalse(settler.getWork().sleepsInOwnBed(level), "Too far from the fire: sleeps at the camp");
        RestPlace place = goToBed(settler);
        helper.assertTrue(!place.bed() && place.straw() != null, "At the camp: " + place);
        helper.assertTrue(settlement(helper, jobBlock).bedOf(settler.getUUID()).filter(far::equals).isPresent(), "It keeps its bed");
        helper.assertTrue(fireRow(settler).orElseThrow().met(), "Fire met");
        helper.assertFalse(settler.getWork().sleepsInOwnBed(level), "Still the camp");
        end(helper, jobBlock, settler);
    }

    /** A real bed near a hearth stays the sleeping place; the campfire of the first sleeper may make it near. */
    private static void nearBed(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER);
        BlockPos near = bed(helper, CENTER.west(2));
        Settler settler = settler(helper, jobBlock);
        SettlerBeds.assign(level, SettlementData.get(level), Optional.of(settlement(helper, jobBlock)), settler.getUUID(), near);
        RestPlace place = goToBed(settler);
        helper.assertTrue(Hearths.of(level, settlement(helper, jobBlock)).size() == 1, "Also a settler with a bed places the first campfire");
        helper.assertTrue(place.bed() && place.pos().equals(near), "In its bed: " + place);
        helper.assertTrue(settler.getWork().sleepsInOwnBed(level), "Its own real bed");
        helper.assertTrue(fireRow(settler).orElseThrow().met(), "Fire met in bed");
        end(helper, jobBlock, settler);
    }

    /** No room anywhere around the hearth: the settler stays at its JobBlock and the row says how far the fire is. */
    private static void fireRowFar(GameTestHelper helper) {
        floor(helper);
        BlockPos jobBlock = wideJobBlock(helper, CENTER);
        BlockPos fire = CENTER.west(31);
        helper.setBlock(fire.below(), Blocks.STONE);
        helper.setBlock(fire, Blocks.CAMPFIRE);
        Settler settler = settler(helper, jobBlock);
        goToBed(settler);
        NeedsReport.Row row = fireRow(settler).orElseThrow();
        helper.assertFalse(row.met(), "Too far");
        helper.assertTrue(key(row.detail()).equals("civmod.need.fire.far"), "Detail: " + row.detail());
        end(helper, jobBlock, settler);
    }

    /** Place, extinguish, light and break each update the cache without a scan; an unlit campfire is no hearth. */
    private static void cacheFollowsBlocks(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER);
        Settlement settlement = settlement(helper, jobBlock);
        helper.assertTrue(Hearths.of(level, settlement).isEmpty(), "No hearth yet");

        BlockPos relative = CENTER.east(2);
        BlockPos fire = helper.absolutePos(relative);
        helper.setBlock(relative, Blocks.CAMPFIRE);
        helper.assertTrue(Hearths.of(level, settlement).contains(fire), "A placed lit campfire counts");

        helper.setBlock(relative, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        helper.assertTrue(Hearths.of(level, settlement).isEmpty(), "Extinguished: no hearth");

        helper.setBlock(relative, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        helper.assertTrue(Hearths.of(level, settlement).contains(fire), "Lit again");

        helper.setBlock(relative, Blocks.AIR);
        helper.assertTrue(Hearths.of(level, settlement).isEmpty(), "Broken: no hearth");

        BlockPos outside = CENTER.east(16);
        helper.setBlock(outside.below(), Blocks.STONE);
        helper.setBlock(outside, Blocks.CAMPFIRE);
        helper.assertTrue(Hearths.of(level, settlement).isEmpty(), "Outside the border: not this settlement's");
        end(helper, jobBlock);
    }

    /** Hearths that stood before the settlement are found once, on the first look; unlit ones are not. */
    private static void foundWhenFounding(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        helper.setBlock(CENTER.east(2), Blocks.CAMPFIRE);
        helper.setBlock(CENTER.west(2), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        BlockPos jobBlock = jobBlock(helper, CENTER);
        helper.assertTrue(Hearths.of(level, settlement(helper, jobBlock)).equals(Set.of(helper.absolutePos(CENTER.east(2)))),
                "Only the lit campfire: " + Hearths.of(level, settlement(helper, jobBlock)));
        end(helper, jobBlock);
    }

    /** A block an addon adds to the tag counts (the example addon adds the lava cauldron). */
    private static void tagAddedBlock(GameTestHelper helper) {
        floor(helper);
        BlockPos jobBlock = jobBlock(helper, CENTER);
        helper.setBlock(CENTER.east(2), Blocks.LAVA_CAULDRON);
        helper.assertTrue(Hearths.of(helper.getLevel(), settlement(helper, jobBlock)).contains(helper.absolutePos(CENTER.east(2))),
                "A tag-added block counts");
        end(helper, jobBlock);
    }

    /** The Fire row of the Needs tab also applies to a settler without a bed that belongs to a settlement. */
    private static void fireRow(GameTestHelper helper) {
        floor(helper);
        BlockPos jobBlock = jobBlock(helper, CENTER);
        Settler settler = settler(helper, jobBlock);
        NeedsReport.Row none = fireRow(settler).orElseThrow();
        helper.assertFalse(none.met(), "No hearth: not met");
        helper.assertTrue(key(none.detail()).equals("civmod.need.fire.none"), "Detail: " + none.detail());

        helper.setBlock(CENTER.east(4), Blocks.CAMPFIRE);
        NeedsReport.Row near = fireRow(settler).orElseThrow();
        helper.assertTrue(near.met(), "A hearth makes a camp: met");
        helper.assertTrue(key(near.detail()).equals("civmod.need.fire.near"), "Detail: " + near.detail());
        end(helper, jobBlock, settler);
    }
}
