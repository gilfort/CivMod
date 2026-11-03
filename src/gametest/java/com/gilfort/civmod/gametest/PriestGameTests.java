package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.PriestLoop;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SkillRules;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.Shrine;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Priest and the shrine of the Center Block (vault {@code Jobs/Priest.md}): the job and its place,
 * the daily glow, the roll, the chain, the free bed and the settler cap, EXP and catch-up.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class PriestGameTests {

    private static final BlockPos CENTER = new BlockPos(4, 1, 4);
    private static final BlockPos FIRST_BED = new BlockPos(1, 1, 1);
    private static final BlockPos SECOND_BED = new BlockPos(7, 1, 1);
    private static final long DAY = 5L;

    private PriestGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "priest_offered_at_center_block", PriestGameTests::offered);
            register(helper, "priest_glow_rules", PriestGameTests::glowRules);
            register(helper, "priest_roll_chance", PriestGameTests::rollChance);
            register(helper, "priest_catch_up_chain", PriestGameTests::chain);
            register(helper, "priest_catch_up_new_settler", PriestGameTests::newSettler);
            register(helper, "priest_catch_up_no_free_bed", PriestGameTests::noBed);
            register(helper, "priest_catch_up_settler_cap", PriestGameTests::settlerCap);
            register(helper, "priest_catch_up_without_priest", PriestGameTests::withoutPriest);
            register(helper, "priest_catch_up_one_prayer_per_day", PriestGameTests::onePrayerPerDay);
            register(helper, "priest_prays_at_glowing_center_block", PriestGameTests::livePrayer);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    /** A random source whose doubles come from a script: {@code true} rolls under any chance, {@code false} over any. */
    private static final class Scripted extends SingleThreadedRandomSource {
        private final boolean[] script;
        private int next;

        Scripted(boolean... script) {
            super(0L);
            this.script = script;
        }

        @Override
        public double nextDouble() {
            boolean success = next < script.length && script[next];
            next++;
            return success ? 0.0 : 0.999;
        }

        int rolls() {
            return next;
        }
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** A bed (both halves) with its head at the given relative position, facing north. */
    private static BlockPos bed(GameTestHelper helper, BlockPos relativeHead) {
        BlockPos head = helper.absolutePos(relativeHead);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        helper.getLevel().setBlock(head.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        helper.getLevel().setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
        return head;
    }

    /** A settlement around a Center Block whose starting settlers count as arrived. */
    private static Settlement settlement(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        TestSettlements.centerAt(level, helper.absolutePos(CENTER));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(helper.absolutePos(CENTER)).orElseThrow();
        data.settlersArrived(settlement);
        return settlement;
    }

    /** A Settler of the settlement with the Priest job at the Center Block. */
    private static Settler priest(GameTestHelper helper, Settlement settlement) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Prayer"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        BlockPos center = helper.absolutePos(CENTER);
        helper.assertTrue(SettlementWorld.assign(level, center, settler, ModJobs.PRIEST.get()) == AssignResult.OK, "Priest assigned");
        SettlementWorld.setJobs(level, settler, List.of(new SettlerJob(ModJobs.PRIEST.get(), center)));
        return settler;
    }

    /** Runs the prayer chain of {@link #DAY} with the scripted rolls. */
    private static Scripted catchUp(GameTestHelper helper, Settler settler, long day, boolean... script) {
        Scripted random = new Scripted(script);
        PriestLoop.catchUpDay(helper.getLevel(), settler, day, (day + 1) * DayClock.DAY_TICKS, random);
        return random;
    }

    private static int totalExp(Settler settler) {
        SettlerSkills.Skill skill = settler.getSkills().get(ModJobs.PRIEST.get());
        int total = skill.exp();
        for (int level = 1; level < skill.level(); level++) {
            total += SkillRules.get().expToNext(level);
        }
        return total;
    }

    private static void cleanUp(Settler... settlers) {
        for (Settler settler : settlers) {
            settler.discard();
        }
    }

    // ---------------------------------------------------------------- tests

    /** The Center Block offers the Priest next to the Administrator, one place on every level. */
    private static void offered(GameTestHelper helper) {
        helper.assertTrue(ModJobs.PRIEST.getId().equals(CivMod.id("priest")), "Job id");
        helper.assertTrue(ModJobs.PRIEST.get().category() == JobCategory.SERVICE, "Service job");
        helper.assertTrue(JobPlaces.offered(ModJobs.ADMINISTRATOR.get()).equals(List.of(ModJobs.ADMINISTRATOR.get(), ModJobs.PRIEST.get())),
                "Offered Jobs: " + JobPlaces.offered(ModJobs.ADMINISTRATOR.get()));
        for (int level = 1; level <= 5; level++) {
            helper.assertTrue(JobPlaces.places(ModJobs.ADMINISTRATOR.get(), ModJobs.PRIEST.get(), level) == 1, "One place at level " + level);
        }
        helper.assertTrue(ModJobs.PRIEST.get().baseExp() == Shrine.EXP_PER_LEVEL, "15 EXP per JobBlock level");
        helper.succeed();
    }

    /** The glow starts between 07:00 and 16:00, lasts an hour, is the same all day and ends with a failed prayer. */
    private static void glowRules(GameTestHelper helper) {
        for (long day = 0; day < 400; day++) {
            long start = Shrine.glowStart(3, day);
            helper.assertTrue(start >= 1000 && start <= 10000, "Start between 07:00 and 16:00: " + start);
            helper.assertTrue(start == Shrine.glowStart(3, day), "Same start all day");
        }
        helper.assertTrue(Shrine.glowStart(3, 1) != Shrine.glowStart(3, 2) || Shrine.glowStart(3, 2) != Shrine.glowStart(3, 3), "Varies by day");
        long start = Shrine.glowStart(3, DAY);
        long clock = DAY * DayClock.DAY_TICKS + start;
        Shrine.State none = Shrine.State.NONE;
        helper.assertFalse(Shrine.glowing(3, DAY, start - 1, clock - 1, none), "Dark before the glow");
        helper.assertTrue(Shrine.glowing(3, DAY, start, clock, none), "Glows at its start");
        helper.assertTrue(Shrine.glowing(3, DAY, start + Shrine.GLOW_TICKS - 1, clock + Shrine.GLOW_TICKS - 1, none), "Glows for 1000 ticks");
        helper.assertFalse(Shrine.glowing(3, DAY, start + Shrine.GLOW_TICKS, clock + Shrine.GLOW_TICKS, none), "Dark after the hour");
        helper.assertFalse(Shrine.glowing(3, DAY, start + 10, clock + 10, new Shrine.State(DAY, 0L)), "A failed prayer ended it");
        helper.assertTrue(Shrine.glowing(3, DAY, start + Shrine.GLOW_TICKS + 10, clock + Shrine.GLOW_TICKS + 10,
                new Shrine.State(Shrine.NO_DAY, clock + 2 * Shrine.GLOW_TICKS)), "A chain keeps it glowing");
        helper.succeed();
    }

    /** 5 % + 0.1 % per Priest level. */
    private static void rollChance(GameTestHelper helper) {
        helper.assertTrue(Math.abs(Shrine.chance(1) - 0.051) < 1e-9, "Level 1: " + Shrine.chance(1));
        helper.assertTrue(Math.abs(Shrine.chance(100) - 0.15) < 1e-9, "Level 100: " + Shrine.chance(100));
        RandomSource under = new Scripted(true);
        RandomSource over = new Scripted(false);
        helper.assertTrue(Shrine.roll(under, 1) && !Shrine.roll(over, 1), "The roll compares against the chance");
        helper.succeed();
    }

    /** A success lets the Priest pray and roll again; a failure ends the chain. EXP is 15 per JobBlock level and roll. */
    private static void chain(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        bed(helper, FIRST_BED);
        bed(helper, SECOND_BED);
        Settler priest = priest(helper, settlement);
        Scripted random = catchUp(helper, priest, DAY, true, true, false);
        helper.assertTrue(random.rolls() == 3, "Success, success, failure: " + random.rolls() + " rolls");
        helper.assertTrue(totalExp(priest) == 3 * Shrine.EXP_PER_LEVEL, "15 EXP for every roll, also the failed one: " + totalExp(priest));
        helper.assertTrue(settlement.shrine().endedDay() == DAY, "The failure ended the day's glow");
        helper.assertTrue(settlement.members().size() == 3, "Two new Settlers joined the Priest: " + settlement.members().size());
        settlement.members().forEach(member -> {
            if (helper.getLevel().getEntity(member) instanceof Settler settler) {
                settler.discard();
            }
        });
        helper.succeed();
    }

    /** A new Settler has the race of the Center Block, level 1 in every Job, 2 bread and a bed. */
    private static void newSettler(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        BlockPos bed = bed(helper, FIRST_BED);
        Settler priest = priest(helper, settlement);
        catchUp(helper, priest, DAY, true, false);
        helper.assertTrue(settlement.members().size() == 2, "One new Settler: " + settlement.members().size());
        Settler recruit = settlement.members().stream().filter(member -> !member.equals(priest.getUUID()))
                .map(member -> helper.getLevel().getEntity(member)).filter(Settler.class::isInstance).map(Settler.class::cast).findFirst().orElseThrow();
        helper.assertTrue(recruit.getRace().equals(Races.HUMAN), "Race of the Center Block");
        helper.assertTrue(settlement.bedOf(recruit.getUUID()).filter(bed::equals).isPresent(), "Takes the free bed");
        helper.assertTrue(recruit.getInventory().getItem(Settler.POCKETS_START).is(Items.BREAD)
                && recruit.getInventory().getItem(Settler.POCKETS_START).getCount() == 2, "2 bread");
        ModJobs.REGISTRY.forEach(job -> helper.assertTrue(recruit.getSkills().get(job).level() == 1, "Level 1 in " + job));
        cleanUp(priest, recruit);
        helper.succeed();
    }

    /** Without a free bed a roll spawns nobody, but the Priest still prays: EXP and chain as usual. */
    private static void noBed(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Settler priest = priest(helper, settlement);
        Scripted random = catchUp(helper, priest, DAY, true, false);
        helper.assertTrue(random.rolls() == 2, "The chain went on: " + random.rolls());
        helper.assertTrue(settlement.members().size() == 1, "Nobody joined: " + settlement.members().size());
        helper.assertTrue(totalExp(priest) == 2 * Shrine.EXP_PER_LEVEL, "EXP anyway: " + totalExp(priest));
        cleanUp(priest);
        helper.succeed();
    }

    /** At {@code maxSettlersPerSettlement} a roll spawns nobody either. */
    private static void settlerCap(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        bed(helper, FIRST_BED);
        bed(helper, SECOND_BED);
        Settler priest = priest(helper, settlement);
        int before = CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.get();
        CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(1);
        try {
            catchUp(helper, priest, DAY, true, false);
            helper.assertTrue(settlement.members().size() == 1, "The cap holds: " + settlement.members().size());
        } finally {
            CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(before);
        }
        cleanUp(priest);
        helper.succeed();
    }

    /** No Priest, no prayer. */
    private static void withoutPriest(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        bed(helper, FIRST_BED);
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        level.addFreshEntity(settler);
        settler.joinSettlement(SettlementData.get(level), settlement);
        Scripted random = catchUp(helper, settler, DAY, true);
        helper.assertTrue(random.rolls() == 0, "Nothing rolled");
        helper.assertTrue(settlement.members().size() == 1, "Nobody joined");
        helper.assertTrue(settlement.shrine().equals(Shrine.State.NONE), "The shrine is untouched");
        cleanUp(settler);
        helper.succeed();
    }

    /** One chain per missed day; a day a prayer already ended, or one still to come, is skipped. */
    private static void onePrayerPerDay(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Settler priest = priest(helper, settlement);
        ServerLevel level = helper.getLevel();
        Scripted first = catchUp(helper, priest, DAY, false);
        Scripted again = catchUp(helper, priest, DAY, false);
        helper.assertTrue(first.rolls() == 1 && again.rolls() == 0, "The second chain of the same day is skipped: " + again.rolls());
        Scripted nextDay = catchUp(helper, priest, DAY + 1, false);
        helper.assertTrue(nextDay.rolls() == 1, "The next day prays again");
        Scripted early = new Scripted(false);
        PriestLoop.catchUpDay(level, priest, DAY + 2, (DAY + 2) * DayClock.DAY_TICKS, early);
        helper.assertTrue(early.rolls() == 0, "A glow that has not happened yet is not simulated");
        cleanUp(priest);
        helper.succeed();
    }

    /** A Priest at a glowing Center Block prays for 10 seconds and rolls once (EXP either way); a dark shrine sends it on. */
    private static void livePrayer(GameTestHelper helper) {
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settlement settlement = settlement(helper);
        Settler priest = priest(helper, settlement);
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        long clock = level.getOverworldClockTime();
        long day = DayClock.day(clock);
        // Dark shrine: the Priest does not go to pray.
        data.setShrine(settlement, new Shrine.State(day, 0L));
        priest.getWork().decideNow(level);
        helper.assertTrue(priest.getWork().activeJob().isEmpty() || priest.getWork().activeJob().filter(job -> job.jobType() == ModJobs.PRIEST.get()).isEmpty(),
                "Does not pray in the dark: " + priest.getWork().activeJob());
        // Glowing: it prays and rolls once.
        data.setShrine(settlement, new Shrine.State(Shrine.NO_DAY, clock + Shrine.GLOW_TICKS));
        helper.startSequence().thenExecute(() -> priest.getWork().decideNow(level))
                .thenWaitUntil(() -> helper.assertTrue(totalExp(priest) > 0, "Prayed and rolled: EXP " + totalExp(priest)))
                .thenExecute(() -> {
                    helper.assertTrue(totalExp(priest) >= Shrine.EXP_PER_LEVEL, "15 EXP for the roll: " + totalExp(priest));
                    helper.assertTrue(settlement.shrine().endedDay() == day || settlement.shrine().chainUntil() > clock + Shrine.GLOW_TICKS,
                            "The roll ended the glow or kept it going: " + settlement.shrine());
                    settlement.members().forEach(member -> {
                        if (level.getEntity(member) instanceof Settler settler) {
                            settler.discard();
                        }
                    });
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                }).thenSucceed();
    }
}
