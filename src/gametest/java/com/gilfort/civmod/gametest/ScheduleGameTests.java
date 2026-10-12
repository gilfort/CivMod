package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.JobType;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Story 6 (daily schedule and beds).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ScheduleGameTests {

    private ScheduleGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "schedule_bed_claim", ScheduleGameTests::bedClaim);
            register(helper, "schedule_bed_assign", ScheduleGameTests::bedAssign);
            register(helper, "schedule_straw_spots", ScheduleGameTests::strawSpots);
            register(helper, "schedule_time_jump", ScheduleGameTests::timeJump);
            register(helper, "schedule_sleeping", ScheduleGameTests::sleeping);
            register(helper, "schedule_standing_spot", ScheduleGameTests::standingSpot);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Places a bed (both halves) with its head at the given relative position, facing north. */
    private static BlockPos placeBed(GameTestHelper helper, BlockPos relativeHead) {
        ServerLevel level = helper.getLevel();
        BlockPos head = helper.absolutePos(relativeHead);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(head.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
        return head;
    }

    private static Settlement settlementAt(SettlementData data, BlockPos pos) {
        return SettlementManager.place(data, pos, JobType.LUMBERJACK, 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE).settlement();
    }

    /** The nearest free bed is claimed, beds are never shared, a destroyed bed is released. */
    private static void bedClaim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = placeBed(helper, new BlockPos(2, 1, 1));
        BlockPos far = placeBed(helper, new BlockPos(6, 1, 1));
        SettlementData data = new SettlementData();
        Settlement settlement = settlementAt(data, helper.absolutePos(new BlockPos(3, 1, 4)));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        try {
            BlockPos from = helper.absolutePos(new BlockPos(1, 1, 3));
            helper.assertTrue(SettlerBeds.ensureBed(level, data, settlement, alice, from).filter(near::equals).isPresent(), "Alice should get the nearest bed");
            helper.assertTrue(SettlerBeds.ensureBed(level, data, settlement, bob, from).filter(far::equals).isPresent(), "Bob should get the other bed");
            helper.assertTrue(SettlerBeds.ensureBed(level, data, settlement, carol, from).isEmpty(), "No bed left for Carol");
            helper.assertTrue(SettlerBeds.ensureBed(level, data, settlement, alice, from).filter(near::equals).isPresent(), "Alice keeps her bed");

            level.setBlock(near, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            level.setBlock(near.relative(Direction.SOUTH), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            helper.assertTrue(SettlerBeds.ensureBed(level, data, settlement, alice, from).isEmpty(), "Destroyed bed must be released, no free bed left");
            helper.assertTrue(settlement.bedOf(alice).isEmpty() && settlement.beds().size() == 1, "Only Bob's bed is still owned");
            helper.succeed();
        } finally {
            SettlerBeds.releasePoi(level, near);
            SettlerBeds.releasePoi(level, far);
        }
    }

    /** Assigning via the scroll: foot click works, transfer from another settler, second click releases. */
    private static void bedAssign(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos head = placeBed(helper, new BlockPos(2, 1, 1));
        SettlementData data = new SettlementData();
        Settlement settlement = settlementAt(data, helper.absolutePos(new BlockPos(3, 1, 4)));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        try {
            helper.assertTrue(SettlerBeds.assign(level, data, Optional.of(settlement), alice, head.relative(Direction.SOUTH))
                    == SettlerBeds.AssignResult.ASSIGNED, "Clicking the foot should assign the bed");
            helper.assertTrue(settlement.bedOf(alice).filter(head::equals).isPresent(), "Bed stored by its head");
            helper.assertTrue(SettlerBeds.assign(level, data, Optional.of(settlement), bob, head) == SettlerBeds.AssignResult.TRANSFERRED,
                    "Bed should be transferred to Bob");
            helper.assertTrue(settlement.bedOf(alice).isEmpty() && settlement.bedOf(bob).isPresent(), "Alice lost the bed to Bob");
            helper.assertTrue(SettlerBeds.assign(level, data, Optional.of(settlement), bob, head) == SettlerBeds.AssignResult.RELEASED,
                    "Second click releases");
            helper.assertTrue(SettlerBeds.assign(level, data, Optional.of(settlement), bob, helper.absolutePos(new BlockPos(5, 1, 5)))
                    == SettlerBeds.AssignResult.NOT_A_BED, "Air is not a bed");
            helper.assertTrue(SettlerBeds.assign(level, data, Optional.empty(), bob, head) == SettlerBeds.AssignResult.HOMELESS, "Homeless");
            helper.succeed();
        } finally {
            SettlerBeds.releasePoi(level, head);
        }
    }

    /** Straw spots spread around the JobBlock: free, distinct and not adjacent to each other. */
    private static void strawSpots(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos jobBlock = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.STONE);
        List<BlockPos> taken = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SettlerBeds.StrawSpot spot = SettlerBeds.findStrawSpot(helper.getLevel(), jobBlock, taken).orElseThrow();
            helper.assertTrue(spot.pos().distManhattan(jobBlock) <= 2 * SettlerBeds.STRAW_RADIUS, "Spot too far away");
            for (BlockPos other : taken) {
                helper.assertTrue(other.distManhattan(spot.pos()) > 1, "Spots must not be adjacent: " + taken + " / " + spot);
            }
            taken.add(spot.pos());
        }
        helper.succeed();
    }

    /** A skipped night is detected from the clock jump between two decisions; other clock changes are not. */
    private static void timeJump(GameTestHelper helper) {
        long workEnd = SettlerWork.WORK_END;
        helper.assertFalse(SettlerBeds.nightSkipped(1000, 1100, 100, workEnd), "Normal decision step");
        helper.assertFalse(SettlerBeds.nightSkipped(-1, 50000, 100, workEnd), "No previous decision");
        helper.assertTrue(SettlerBeds.nightSkipped(14000, 24100, 100, workEnd), "Night skipped");
        helper.assertTrue(SettlerBeds.nightSkipped(12600, 24000, 100, workEnd), "Player slept before the settlers went to bed");
        helper.assertFalse(SettlerBeds.nightSkipped(1000, 11500, 100, workEnd), "/time set to the evening is no skipped night");
        helper.assertFalse(SettlerBeds.nightSkipped(11500, 14000, 100, workEnd), "/time set to the night is no skipped night");
        helper.succeed();
    }

    /** Teleports land on a free spot next to the target: not inside the trunk, not on the bed, within reach. */
    private static void standingSpot(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        for (int y = 1; y <= 6; y++) {
            helper.setBlock(new BlockPos(4, y, 4), Blocks.OAK_LOG);
        }
        ServerLevel level = helper.getLevel();
        BlockPos log = helper.absolutePos(new BlockPos(4, 1, 4));
        Vec3 spot = SettlerWork.standingSpot(level, log);
        BlockPos feet = BlockPos.containing(spot);
        helper.assertTrue(level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir(), "Spot must be free: " + feet);
        helper.assertTrue(feet.getY() == log.getY() && feet.distSqr(log) <= 4, "Spot must be next to the log: " + feet);

        BlockPos head = placeBed(helper, new BlockPos(1, 1, 1));
        BlockPos bedSpot = BlockPos.containing(SettlerWork.standingSpot(level, head));
        helper.assertFalse(level.getBlockState(bedSpot).getBlock() instanceof BedBlock || level.getBlockState(bedSpot.below()).getBlock() instanceof BedBlock,
                "Must not stand on the bed: " + bedSpot);
        helper.succeed();
    }

    /** A settler sleeps in a bed (bed occupied) and on a straw bed (keeps sleeping without bed block) and wakes up. */
    private static void sleeping(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos head = placeBed(helper, new BlockPos(2, 1, 1));
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 4)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        // No AI: the test level is at day time, the schedule would wake the settler (physics still apply).
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        helper.assertTrue(settler.startSleeping(head) && settler.isSleeping(), "Settler should sleep in the bed");
        helper.assertTrue(level.getBlockState(head).getValue(BedBlock.OCCUPIED), "Bed should be occupied");
        settler.wakeUp();
        helper.assertFalse(settler.isSleeping() || level.getBlockState(head).getValue(BedBlock.OCCUPIED), "Woke up, bed free");

        BlockPos spot = helper.absolutePos(new BlockPos(5, 1, 4));
        // Wait one tick: on its very first tick vanilla puts a sleeping entity "into its bed" and wakes it without bed.
        helper.runAfterDelay(1, () -> {
            settler.sleepOnStraw(new SettlerBeds.StrawSpot(spot, Direction.EAST), true);
            helper.assertTrue(settler.isSleeping() && settler.strawBedFacing().filter(Direction.EAST::equals).isPresent(), "Sleeping on straw");
            Vec3 lying = settler.position();
            // Leftover movement must not slide the settler away (travel is called directly: without AI it is skipped).
            settler.setDeltaMovement(0.3, 0.0, 0.3);
            settler.travel(new Vec3(0.0, 0.0, 1.0));
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(settler.isSleeping(), "Settler must keep sleeping without a bed block");
                helper.assertTrue(settler.position().equals(lying) && lying.y - spot.getY() > 0.25,
                        "Settler must lie still on top of the straw bed: " + settler.position().subtract(lying) + ", height " + (lying.y - spot.getY()));
                settler.wakeUp();
                helper.assertFalse(settler.isSleeping() || settler.isSleepingWithoutBed(), "Woke up from straw");
                settler.discard();
                helper.succeed();
            });
        });
    }
}
