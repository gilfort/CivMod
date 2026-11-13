package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockSettlers;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Laborers;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Laborer (vault {@code Jobs/Laborer.md}): a settler without a Job that can work helps at a Resource
 * Collection JobBlock at a quarter of an employee's speed. The settlers of the choice tests do not think on their own
 * ({@code setNoAi}); the test asks for each decision.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class LaborerGameTests {

    private static final long DAY = 24000L;
    /** Work time compared in the speed test, in ticks (2.5 minutes). */
    private static final int WINDOW = 3000;
    private static final BlockPos A = new BlockPos(1, 1, 1);
    private static final BlockPos B = new BlockPos(1, 1, 9);
    private static final BlockPos C = new BlockPos(1, 1, 17);
    /** Where the logs of the free block at {@link #A} lie, relative to it: on the far side, out of reach of the own Job. */
    private static final BlockPos FREE_LOGS_OFFSET = new BlockPos(0, 0, -18);
    /**
     * Beyond the free block's logs. The forced area runs from here to {@link #FLOOR_END}: the settler walks between the
     * logs and the floor, and a settler in a chunk that is not forced does not tick.
     */
    private static final BlockPos FREE_LOGS_CORNER = new BlockPos(0, 0, -24);
    /** The end (exclusive) of the stone floor of {@link #floor}. */
    private static final BlockPos FLOOR_END = new BlockPos(12, 0, 24);

    private LaborerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "laborer_helps_whether_or_not_taken", LaborerGameTests::helpsWithOrWithoutEmployee);
            register(helper, "laborer_limit_and_shared_place", LaborerGameTests::limitAndSharedPlace);
            register(helper, "laborer_block_choice", LaborerGameTests::blockChoice);
            register(helper, "laborer_skips_block_without_tool", LaborerGameTests::skipsBlockWithoutTool);
            register(helper, "laborer_switches_to_higher_job", LaborerGameTests::switchesToHigherJob);
            register(helper, "laborer_works_and_levels", LaborerGameTests::worksAndLevels);
            register(helper, "laborer_speed_quarter", LaborerGameTests::speedQuarter);
            register(helper, "laborer_window_label", LaborerGameTests::windowLabel);
            register(helper, "laborer_keeps_buffer_when_job_changes", LaborerGameTests::keepsBufferWhenJobChanges);
            register(helper, "laborer_catch_up_with_jobs", LaborerGameTests::catchUpWithJobs);
            register(helper, "laborer_limit_values", LaborerGameTests::limitValues);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- setup

    /** A stone floor under the test area. */
    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < FLOOR_END.getX(); x++) {
            for (int z = 0; z < FLOOR_END.getZ(); z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** A Lumberjack JobBlock with a block of logs three blocks away, in the test's settlement. */
    private static BlockPos lumberjackBlock(GameTestHelper helper, BlockPos at) {
        return lumberjackBlock(helper, at, BlockPos.ZERO);
    }

    /**
     * A Lumberjack JobBlock in the test's settlement with a block of logs next to the position {@code at + shift}.
     * Auto-search finds logs 20 blocks around a JobBlock, so only a shift away from the other blocks keeps their logs apart.
     */
    private static BlockPos lumberjackBlock(GameTestHelper helper, BlockPos at, BlockPos shift) {
        TestSettlements.setBlock(helper, at, ModBlocks.LUMBERJACK_BLOCK.get());
        for (int x = 3; x < 7; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 4; z++) {
                    helper.setBlock(at.offset(shift).offset(x, y, z), Blocks.OAK_LOG);
                }
            }
        }
        return helper.absolutePos(at);
    }

    private static Settlement settlement(GameTestHelper helper) {
        return SettlementData.get(helper.getLevel()).settlementAt(helper.absolutePos(A)).orElseThrow();
    }

    /** A settler without a Job in the test's settlement, with a stone axe and food; thinks on its own only if asked to. */
    private static Settler settler(GameTestHelper helper, String name, BlockPos at, boolean ai) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(at));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal(name));
        settler.setNoAi(!ai);
        level.addFreshEntity(settler);
        settler.joinSettlement(SettlementData.get(level), settlement(helper));
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        return settler;
    }

    private static void hire(GameTestHelper helper, Settler settler, BlockPos jobBlock) {
        SettlementWorld.assign(helper.getLevel(), jobBlock, settler, ModJobs.LUMBERJACK.get());
        helper.assertTrue(settler.jobAt(jobBlock).isPresent(), settler.getName().getString() + " is hired at " + jobBlock);
    }

    private static Optional<SettlerJob> lumberjackAt(BlockPos jobBlock) {
        return Optional.of(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
    }

    private static Optional<BlockPos> workPlace(Settler settler, BlockPos jobBlock) {
        return settler.getWork().snapshot().workPlaces().stream().filter(entry -> entry.jobBlock().equals(jobBlock))
                .map(SettlerWork.WorkPlaceEntry::poi).findFirst();
    }

    private static void discard(List<Settler> settlers) {
        settlers.forEach(Settler::discard);
    }

    // ---------------------------------------------------------------- tests

    /**
     * A jobless settler helps at a Lumberjack JobBlock without an employee, and at one whose place an employee takes: it
     * works the Job of the block's employee and shares his work place of the day.
     */
    private static void helpsWithOrWithoutEmployee(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos free = lumberjackBlock(helper, A);
        Settler lena = settler(helper, "Lena", new BlockPos(2, 1, 2), false);
        lena.getWork().decideNow(level);
        helper.assertTrue(lena.getWork().helping().equals(lumberjackAt(free)), "Helps at the free block: " + lena.getWork().helping());
        helper.assertTrue(lena.getWork().activeJob().equals(lumberjackAt(free)), "Works there: " + lena.getWork().activeJob());
        helper.assertTrue(lena.getWork().status(free) == JobStatus.WORKING, "Status: " + lena.getWork().status(free));
        helper.assertTrue(lena.getJobs().isEmpty(), "No Job is stored for the Laborer");

        BlockPos taken = lumberjackBlock(helper, B);
        Settler boss = settler(helper, "Boss", new BlockPos(2, 1, 10), false);
        hire(helper, boss, taken);
        boss.getWork().decideNow(level);
        Settler lars = settler(helper, "Lars", new BlockPos(2, 1, 11), false);
        // The free block is full for Lars's choice: Lena and one more help there; Lars is nearest to the taken block.
        Settler second = settler(helper, "Second", new BlockPos(2, 1, 3), false);
        second.getWork().decideNow(level);
        lars.getWork().decideNow(level);
        helper.assertTrue(lars.getWork().helping().equals(lumberjackAt(taken)), "Helps where the place is taken: " + lars.getWork().helping());
        helper.assertTrue(workPlace(lars, taken).isPresent() && workPlace(lars, taken).equals(workPlace(boss, taken)),
                "Shares the employee's work place: " + workPlace(lars, taken) + " vs " + workPlace(boss, taken));
        discard(List.of(lena, boss, lars, second));
        helper.succeed();
    }

    /** At most 2 Laborers per block (a datapack value); the third stays idle. The two share one work place. */
    private static void limitAndSharedPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos block = lumberjackBlock(helper, A);
        helper.assertTrue(JobBlockSettlers.laborers(ModJobs.LUMBERJACK.get()) == 2, "The limit is a value of the datapack: 2");
        Settler one = settler(helper, "One", new BlockPos(2, 1, 2), false);
        Settler two = settler(helper, "Two", new BlockPos(3, 1, 2), false);
        Settler three = settler(helper, "Three", new BlockPos(4, 1, 2), false);
        one.getWork().decideNow(level);
        two.getWork().decideNow(level);
        three.getWork().decideNow(level);
        helper.assertTrue(one.getWork().helping().equals(lumberjackAt(block)) && two.getWork().helping().equals(lumberjackAt(block)),
                "Two help: " + one.getWork().helping() + ", " + two.getWork().helping());
        helper.assertTrue(three.getWork().helping().isEmpty(), "The third finds no block: " + three.getWork().helping());
        helper.assertTrue(three.getWork().activeJob().isEmpty(), "The third idles: " + three.getWork().activeJob());
        helper.assertTrue(Laborers.count(settlement(helper), block) == 2, "Two in the crew: " + Laborers.count(settlement(helper), block));
        helper.assertTrue(workPlace(one, block).isPresent() && workPlace(one, block).equals(workPlace(two, block)),
                "One shared work place: " + workPlace(one, block) + ", " + workPlace(two, block));
        discard(List.of(one, two, three));
        helper.succeed();
    }

    /**
     * Blocks without an employee first, then the fewest Laborers, then the nearest; a block with an employee takes
     * Laborers too once the others are full; a seventh settler finds nothing.
     */
    private static void blockChoice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos a = lumberjackBlock(helper, A);
        BlockPos b = lumberjackBlock(helper, B);
        BlockPos c = lumberjackBlock(helper, C);
        Settler employee = settler(helper, "Employee", new BlockPos(2, 1, 2), false);
        hire(helper, employee, a);
        employee.getWork().decideNow(level);
        List<Settler> laborers = new ArrayList<>();
        BlockPos nearA = new BlockPos(2, 1, 3);
        BlockPos nearB = new BlockPos(2, 1, 10);
        // Expected block of each Laborer, in order; null: none.
        BlockPos[] expected = {b, c, b, c, a, a, null};
        BlockPos[] starts = {nearA, nearB, nearA, nearA, nearA, nearA, nearA};
        String[] why = {"no employee, nearest", "fewest Laborers beats nearest", "tie: nearest", "full block skipped", "employee when the rest is full",
                "employee, one Laborer", "everything full"};
        for (int i = 0; i < expected.length; i++) {
            Settler laborer = settler(helper, "Laborer" + i, starts[i], false);
            laborers.add(laborer);
            laborer.getWork().decideNow(level);
            Optional<SettlerJob> helping = laborer.getWork().helping();
            helper.assertTrue(helping.map(SettlerJob::jobBlock).equals(Optional.ofNullable(expected[i])),
                    "Laborer " + i + " (" + why[i] + "): " + helping.map(SettlerJob::jobBlock) + " instead of " + expected[i]);
        }
        discard(laborers);
        employee.discard();
        helper.succeed();
    }

    /** A block without a fitting tool is skipped: no tool on the belt and none in the collect inventory. */
    private static void skipsBlockWithoutTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos block = lumberjackBlock(helper, A);
        Settler bare = settler(helper, "Bare", new BlockPos(2, 1, 2), false);
        bare.getInventory().setItem(Settler.BELT_START, ItemStack.EMPTY);
        bare.getWork().decideNow(level);
        helper.assertTrue(bare.getWork().helping().isEmpty(), "Skips the block: " + bare.getWork().helping());
        helper.assertTrue(bare.getWork().status(block) == JobStatus.NO_TOOL, "Why: " + bare.getWork().status(block));
        helper.assertTrue(Laborers.count(settlement(helper), block) == 0, "Not in the crew");
        helper.assertTrue(bare.getWork().recentThoughts().stream().noneMatch(show -> show.bubble() == ThoughtBubble.NO_TOOL && show.on()),
                "Problem bubbles stay with the settler's own Jobs: " + bare.getWork().recentThoughts());
        bare.discard();
        helper.succeed();
    }

    /**
     * A settler whose Job cannot work yet helps as a Laborer; when the Job has a task (here: a log appears at its
     * block) the next decision after its wait switches there and the Laborer steps back.
     */
    private static void switchesToHigherJob(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, FREE_LOGS_CORNER, FLOOR_END);
        // The free block's logs lie on the far side, out of reach of the settler's own Job.
        BlockPos free = lumberjackBlock(helper, A, FREE_LOGS_OFFSET);
        // The settler's own Job: a Lumberjack block without any log in reach yet.
        TestSettlements.setBlock(helper, B, ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos own = helper.absolutePos(B);
        Settler settler = settler(helper, "Switch", new BlockPos(2, 1, 5), false);
        hire(helper, settler, own);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().helping().equals(lumberjackAt(free)), "Helps while the Job has no task: " + settler.getWork().helping());
        helper.assertTrue(Laborers.count(settlement(helper), free) == 1, "In the crew");
        helper.setBlock(B.offset(3, 0, 0), Blocks.OAK_LOG);
        // The blocked Job is checked again after 30 s.
        helper.runAfterDelay(WORK_WAIT, () -> {
            settler.getWork().decideNow(level);
            helper.assertTrue(settler.getWork().helping().isEmpty(), "The Laborer steps back: " + settler.getWork().helping());
            helper.assertTrue(settler.getWork().activeJob().map(SettlerJob::jobBlock).equals(Optional.of(own)),
                    "Works the Job: " + settler.getWork().activeJob());
            helper.assertTrue(Laborers.count(settlement(helper), free) == 0, "Left the crew");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** Ticks after which a blocked Job is checked again, with a margin. */
    private static final int WORK_WAIT = 650;

    /** Live: the Laborer produces into its buffer, wears the axe and gains EXP in the Job. */
    private static void worksAndLevels(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos block = lumberjackBlock(helper, A);
        Settler settler = settler(helper, "Worker", new BlockPos(2, 1, 2), true);
        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(SettlementRules.skillCap(SettlementRules.MIN_LEVEL) - 1, 0));
        ItemStack axe = new ItemStack(Items.STONE_AXE);
        axe.set(net.minecraft.core.component.DataComponents.MAX_DAMAGE, 100000);
        settler.getInventory().setItem(Settler.BELT_START, axe);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(block).count() > 0, "No wood yet (status " + settler.getWork().status(block) + ", helping "
                    + settler.getWork().helping() + ")");
            SettlerSkills.Skill skill = settler.getSkills().get(ModJobs.LUMBERJACK.get());
            helper.assertTrue(skill.exp() > 0 || skill.level() > SettlementRules.skillCap(SettlementRules.MIN_LEVEL) - 1, "EXP follows the output");
            helper.assertTrue(settler.getInventory().getItem(Settler.BELT_START).getDamageValue() > 0, "The axe wears");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * An employee and a Laborer of the same skill and tool catch up the same time at one block; the Laborer's output is
     * about a quarter of the employee's. A full drop-off sends all output into the settlers' own buffers to count.
     */
    private static void speedQuarter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler employee = CatchUpGameTests.lumberjack(helper, forced);
        employee.setCustomName(Component.literal("Employee"));
        SettlerJob job = employee.getJobs().get(0);
        BlockPos jobBlock = job.jobBlock();
        Settler laborer = settler(helper, "Laborer", new BlockPos(2, 1, 3), false);
        int cap = SettlementRules.skillCap(SettlementRules.MIN_LEVEL);
        List.of(employee, laborer).forEach(settler -> {
            settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(cap, 0));
            ItemStack axe = new ItemStack(Items.STONE_AXE);
            axe.set(net.minecraft.core.component.DataComponents.MAX_DAMAGE, 100000);
            settler.getInventory().setItem(Settler.BELT_START, axe);
        });
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || LumberjackGameTests.foundNothing(employee, job)) {
                return;
            }
            done[0] = true;
            employee.setNoAi(true);
            Container container = (Container) level.getBlockEntity(jobBlock);
            for (int i = 0; i < container.getContainerSize(); i++) {
                container.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            int employed = caughtUp(level, employee, jobBlock);
            int helped = caughtUp(level, laborer, jobBlock);
            helper.assertTrue(employed >= 20, "The employee produced too little to compare: " + employed);
            double ratio = helped / (double) employed;
            helper.assertTrue(ratio > 0.12 && ratio < 0.45, "Laborer " + helped + " vs employee " + employed + " (ratio "
                    + String.format(java.util.Locale.ROOT, "%.2f", ratio) + ", expected about 0.25)");
            helper.assertTrue(laborer.getWork().helping().isEmpty(), "A finished catch-up leaves the crew");
            employee.discard();
            laborer.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** Catches up {@value #WINDOW} ticks of the previous day's work time and returns what came into the settler's buffer. */
    private static int caughtUp(ServerLevel level, Settler settler, BlockPos jobBlock) {
        long clock = level.getOverworldClockTime();
        long yesterday = Math.floorDiv(clock, DAY) * DAY - DAY;
        long from = yesterday + settler.getWork().dayTimes(Math.floorDiv(yesterday, DAY)).wake();
        int before = settler.getWork().buffer(jobBlock).count();
        settler.getWork().setCatchUp(from, from + WINDOW);
        CatchUp.runNow(level, List.of(settler));
        return settler.getWork().buffer(jobBlock).count() - before;
    }

    /**
     * A Laborer helps the Job of a Gatherer block (the Base Job) and carries berries; an employee with a Specialization
     * arrives, so the Job it helps changes. What it carries stays in its buffer, it is not dropped.
     */
    private static void keepsBufferWhenJobChanges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = GathererGameTests.gathererBlock(helper);
        for (int x = 5; x < 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 5), GathererGameTests.bush(3));
        }
        StationTypeGameTests.setLevel(helper, relative, 2);
        BlockPos block = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(level);
        TestAssignments.setStationType(data, block, 1, Items.BAMBOO);
        Settler settler = settler(helper, "Carrier", new BlockPos(2, 1, 2), false);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().helping().map(SettlerJob::jobType).equals(Optional.of(ModJobs.GATHERER.get())),
                "Helps the Base Job: " + settler.getWork().helping());
        settler.getWork().buffer(block).add(new ItemStack(Items.SWEET_BERRIES, 3));
        TestAssignments.assign(data, block, java.util.UUID.randomUUID(), "Boss", settlement(helper).id(), List.of(), ModJobs.PLANTATION_FARMER.get());
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().helping().map(SettlerJob::jobType).filter(ModJobs.GATHERER.get()::equals).isEmpty(),
                "No longer the Base Job: " + settler.getWork().helping());
        helper.assertTrue(settler.getWork().buffer(block).count() == 3, "The berries stay in its buffer: " + settler.getWork().buffer(block).count());
        settler.discard();
        helper.succeed();
    }

    /**
     * A settler whose own Job cannot work still helps in a catch-up: with no scan kept anywhere it prepares one for the
     * block it would help, and the same time gives a quarter of an employee's output.
     */
    private static void catchUpWithJobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, FREE_LOGS_CORNER, FLOOR_END);
        BlockPos free = lumberjackBlock(helper, A, FREE_LOGS_OFFSET);
        // The worker's own Job: a Lumberjack block without any log in reach.
        TestSettlements.setBlock(helper, B, ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos own = helper.absolutePos(B);
        Settler worker = settler(helper, "Worker", new BlockPos(2, 1, 5), false);
        hire(helper, worker, own);
        Settler boss = settler(helper, "Boss", new BlockPos(2, 1, 3), false);
        int cap = SettlementRules.skillCap(SettlementRules.MIN_LEVEL);
        List.of(worker, boss).forEach(settler -> {
            settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(cap, 0));
            ItemStack axe = new ItemStack(Items.STONE_AXE);
            axe.set(net.minecraft.core.component.DataComponents.MAX_DAMAGE, 100000);
            settler.getInventory().setItem(Settler.BELT_START, axe);
        });
        Container container = (Container) level.getBlockEntity(free);
        for (int i = 0; i < container.getContainerSize(); i++) {
            container.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        int[] stage = {0};
        int[] helped = new int[1];
        helper.onEachTick(() -> {
            if (stage[0] == 0 && worker.getWork().catchUpReady(level)) {
                helped[0] = caughtUp(level, worker, free);
                hire(helper, boss, free);
                stage[0] = 1;
            } else if (stage[0] == 1 && boss.getWork().catchUpReady(level)) {
                stage[0] = 2;
                int employed = caughtUp(level, boss, free);
                helper.assertTrue(employed >= 20, "The employee produced too little to compare: " + employed);
                double ratio = helped[0] / (double) employed;
                helper.assertTrue(ratio > 0.12 && ratio < 0.45, "Worker's help " + helped[0] + " vs employee " + employed + " (ratio "
                        + String.format(java.util.Locale.ROOT, "%.2f", ratio) + ", expected about 0.25)");
                worker.discard();
                boss.discard();
                forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                helper.succeed();
            }
        });
    }

    /** The Laborers limit of a JobBlock file: 0 to 10, a later pack replaces an earlier value, a file without it keeps it. */
    private static void limitValues(GameTestHelper helper) {
        helper.assertTrue(JobBlockSettlers.mergeLaborers(files("{\"laborers\": 4}", "{\"settlers\": [1, 1, 1, 1, 1]}")).equals(Optional.of(4)),
                "A file without the value keeps it");
        helper.assertTrue(JobBlockSettlers.mergeLaborers(files("{\"laborers\": 4}", "{\"laborers\": 0}")).equals(Optional.of(0)), "A later pack wins, 0 is allowed");
        helper.assertTrue(JobBlockSettlers.mergeLaborers(files("{\"laborers\": 10}")).equals(Optional.of(10)), "10 is allowed");
        helper.assertTrue(JobBlockSettlers.mergeLaborers(files("{\"settlers\": [1, 1, 1, 1, 1]}")).isEmpty(), "No value, no limit");
        for (String invalid : List.of("{\"laborers\": 11}", "{\"laborers\": -1}", "{\"laborers\": \"two\"}")) {
            boolean rejected = false;
            try {
                JobBlockSettlers.mergeLaborers(files(invalid));
            } catch (RuntimeException e) {
                rejected = true;
            }
            helper.assertTrue(rejected, invalid + " is rejected");
        }
        helper.assertTrue(JobBlockSettlers.laborers(ModJobs.MINER.get()) == 2, "Two Laborers without a value in a file");
        helper.succeed();
    }

    private static List<com.google.gson.JsonElement> files(String... json) {
        return java.util.Arrays.stream(json).map(com.google.gson.JsonParser::parseString).toList();
    }

    /** The settler window names the Job the Laborer helps with. */
    private static void windowLabel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        lumberjackBlock(helper, A);
        Settler settler = settler(helper, "Label", new BlockPos(2, 1, 2), false);
        helper.assertTrue(SettlerInfo.of(settler).helping().isEmpty(), "Nothing helped before the first decision");
        settler.getWork().decideNow(level);
        helper.assertTrue(SettlerInfo.of(settler).helping().equals(Optional.of(ModJobs.LUMBERJACK.get())),
                "The window shows \"Laborer: Lumberjack\": " + SettlerInfo.of(settler).helping());
        settler.discard();
        helper.succeed();
    }
}
