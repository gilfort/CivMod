package com.gilfort.civmod.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.GuardProfile;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.block.JobBlockSettlers;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.GuardDuty;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.job.SkillRules;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.WorkersTab;
import com.gilfort.civmod.menu.WorkersTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.GuardMode;
import com.gilfort.civmod.settlement.GuardPosts;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobPointsDisplay;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;
import com.gilfort.civmod.settlement.WorkPoint;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
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
 * GameTests for the Guard Post and the Guard on duty (vault {@code Jobs/Guard.md}, spec #349, ticket #352): the block and the
 * job, the mode in the window, the guard points, the pick at 06:00 and 18:30, standing at the point, no sleep, the bed and the
 * Entertainment need, the duty EXP and the broken Guard Post. The Guard has no combat in this step.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GuardGameTests {

    /** Day time 08:00. */
    private static final long MORNING = 2000L;
    /** Day time 18:29, the last tick of the first half day. */
    private static final long BEFORE_EVENING = GuardPosts.EVENING - 1;
    private static final BlockPos POST = ServiceGameTests.JOB_BLOCK;
    private static final BlockPos DATA_POST = new BlockPos(0, 64, 0);
    private static final long DAY = DayClock.DAY_TICKS;

    private GuardGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "guard_registry_and_block", GuardGameTests::registryAndBlock);
            register(helper, "guard_window_mode", GuardGameTests::windowMode);
            register(helper, "guard_points_set_remove_renumber", GuardGameTests::pointsSetRemoveRenumber);
            register(helper, "guard_points_container_cycle", GuardGameTests::pointsContainerCycle);
            register(helper, "guard_point_pick_free_first", GuardGameTests::pickFreeFirst);
            register(helper, "guard_point_pick_periods", GuardGameTests::pickPeriods);
            register(helper, "guard_point_pick_skips_unreachable", GuardGameTests::pickSkipsUnreachable);
            register(helper, "guard_points_display", GuardGameTests::pointsDisplay);
            register(helper, "guard_stands_at_point", GuardGameTests::standsAtPoint);
            register(helper, "guard_stands_at_post_without_points", GuardGameTests::standsAtPostWithoutPoints);
            register(helper, "guard_unreachable_point", GuardGameTests::unreachablePoint);
            register(helper, "guard_never_sleeps", GuardGameTests::neverSleeps);
            register(helper, "guard_break_sits_on_the_spot", GuardGameTests::breakSitsOnTheSpot);
            register(helper, "guard_bed_and_entertainment_need", GuardGameTests::bedAndEntertainmentNeed);
            register(helper, "guard_duty_exp_rule", GuardGameTests::dutyExpRule);
            register(helper, "guard_duty_exp_live", GuardGameTests::dutyExpLive);
            register(helper, "guard_duty_exp_catch_up", GuardGameTests::dutyExpCatchUp);
            register(helper, "guard_post_broken_laborer", GuardGameTests::postBroken);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- setup

    /** A Guard Post at {@link #POST} on a stone floor and a Guard working it, with plenty of food. */
    private static ServiceGameTests.Scene scene(GameTestHelper helper) {
        ServiceGameTests.Scene scene = ServiceGameTests.scene(helper, CivMod.id("guard_post"), CivMod.id("guard"));
        // Armed, as a Guard is after its first visit to the Guard Post: without a weapon its status is "No tool" (see GuardCombatGameTests).
        scene.performer().getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_SWORD));
        return scene;
    }

    /** A Guard Post in bare settlement data (no world), the place for the rules that need none. */
    private static SettlementData dataWithPost(int level) {
        SettlementData data = new SettlementData();
        TestSettlements.place(data, DATA_POST, ModJobs.GUARD.get(), level);
        return data;
    }

    private static SettlementBlock postOf(SettlementData data) {
        return data.settlementAt(DATA_POST).flatMap(settlement -> settlement.block(DATA_POST)).orElseThrow();
    }

    private static UUID guardOf(SettlementData data, int number) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000" + String.format("%04d", number));
        Settlement settlement = data.settlementAt(DATA_POST).orElseThrow();
        helperAssignOk(TestAssignments.assign(data, DATA_POST, id, "Guard " + number, settlement.id(), List.of()));
        return id;
    }

    private static void helperAssignOk(JobAssignments.AssignResult result) {
        if (result != JobAssignments.AssignResult.OK) {
            throw new IllegalStateException("Guard not assigned: " + result);
        }
    }

    private static BlockPos spot(int number) {
        return DATA_POST.offset(2 + number, 0, 3);
    }

    private static Settler guard(ServiceGameTests.Scene scene) {
        return scene.performer();
    }

    private static SettlementBlock block(GameTestHelper helper) {
        return SettlementData.get(helper.getLevel()).settlementAt(helper.absolutePos(POST)).flatMap(settlement -> settlement.block(helper.absolutePos(POST)))
                .orElseThrow();
    }

    private static Optional<WorkPoint> pickAt(SettlementData data, UUID guard, long day, long dayTime, RandomSource random) {
        return GuardPosts.pointOf(data, DATA_POST, guard, day, dayTime, random);
    }

    /** The total EXP points of a settler in the Guard job, counting the levels it passed. */
    private static int totalExp(Settler settler) {
        var skill = settler.getSkills().get(ModJobs.GUARD.get());
        int total = skill.exp();
        for (int level = 1; level < skill.level(); level++) {
            total += SkillRules.get().expToNext(level);
        }
        return total;
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents contents ? contents.getKey() : text.getString();
    }

    private static NeedsReport.Row needRow(Settler settler, ServerLevel level, String key) {
        return settler.getWork().needsReport(level).rows().stream().filter(row -> key(row.name()).equals(key)).findFirst().orElseThrow();
    }

    private static List<NeedsReport.Row> entertainmentRows(Settler settler, ServerLevel level) {
        return settler.getWork().needsReport(level).rows().stream().filter(row -> key(row.name()).equals("civmod.need.entertainment")).toList();
    }

    // ---------------------------------------------------------------- the block and the job

    /** The Guard is a Guard-category Job with a public profile, offered by the Guard Post, which holds 1 / 2 / 2 / 3 / 3 Guards and a stone sword. */
    private static void registryAndBlock(GameTestHelper helper) {
        helper.assertTrue(ModJobs.GUARD.get().category() == JobCategory.GUARD, "The Guard is a Guard Job");
        helper.assertTrue(ModJobs.GUARD.get().id().equals(CivMod.id("guard")), "Registered as civmod:guard");
        helper.assertTrue(ModJobs.GUARD.get().profile(GuardProfile.class).isPresent(), "It has a Guard profile");
        helper.assertTrue(ModBlocks.GUARD_POST.get().getJobType() == ModJobs.GUARD.get(), "The Guard Post offers the Guard");
        int[] expected = {1, 2, 2, 3, 3};
        for (int level = 1; level <= expected.length; level++) {
            helper.assertTrue(JobBlockSettlers.settlers(ModJobs.GUARD.get(), level) == expected[level - 1],
                    "Guards at level " + level + ": " + JobBlockSettlers.settlers(ModJobs.GUARD.get(), level));
        }
        var starter = ModItems.GUARD_POST.get().getDefaultInstance().get(ModDataComponents.STARTER_TOOL.get());
        helper.assertTrue(starter != null && starter.value() == Items.STONE_SWORD, "The Guard Post item carries a stone sword: " + starter);
        helper.assertTrue(BuiltInRegistries.ITEM.getKey(ModItems.GUARD_POST.get()).equals(CivMod.id("guard_post")), "Item id");
        helper.succeed();
    }

    // ---------------------------------------------------------------- the window

    private static Player player(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(POST);
        player.snapTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 1.5);
        return player;
    }

    private static JobBlockMenu menu(GameTestHelper helper, Player player) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(POST);
        JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(pos);
        SettlementBlock found = block(helper);
        return new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(found.jobType(), found.level()));
    }

    private static WorkersTabData sees(JobBlockMenu menu) {
        menu.resend();
        return menu.tab(WorkersTab.class).orElseThrow().data().get();
    }

    /**
     * The window shows the mode (Guard by default) and the Guards on duty; a click sets the mode of the block for every Guard,
     * two players asking for the same mode agree, the block keeps it and its picks through a save, and another JobBlock has no mode.
     */
    private static void windowMode(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        Player first = player(helper);
        Player second = player(helper);
        JobBlockMenu one = menu(helper, first);
        JobBlockMenu two = menu(helper, second);
        WorkersTabData start = sees(one);
        helper.assertTrue(start.guardMode() == GuardMode.GUARD, "Guard is the default mode");
        helper.assertTrue(start.workers().size() == 1 && start.workers().get(0).assignee().id().equals(guard(scene).getUUID()), "The Guard on duty is listed");
        helper.assertTrue(GuardMode.GUARD.next() == GuardMode.PATROL && GuardMode.PATROL.next() == GuardMode.ROAM && GuardMode.ROAM.next() == GuardMode.GUARD,
                "The button goes through Guard, Patrol, Roam");

        helper.assertTrue(one.clickMenuButton(first, one.tab(WorkersTab.class).orElseThrow().guardModeButtonId(GuardMode.PATROL)), "First click");
        helper.assertTrue(two.clickMenuButton(second, two.tab(WorkersTab.class).orElseThrow().guardModeButtonId(GuardMode.PATROL)), "Second click for the same mode");
        helper.assertTrue(block(helper).guard().mode() == GuardMode.PATROL, "The block is in Patrol mode");
        helper.assertTrue(sees(two).guardMode() == GuardMode.PATROL, "The window shows it");

        GuardPosts.togglePoint(SettlementData.get(level), helper.absolutePos(POST), helper.absolutePos(new BlockPos(5, 0, 4)), true, Direction.EAST);
        GuardPosts.pointOf(SettlementData.get(level), helper.absolutePos(POST), guard(scene).getUUID(), 4, MORNING, level.getRandom());
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementBlock loaded = SettlementBlock.CODEC.parse(ops, SettlementBlock.CODEC.encodeStart(ops, block(helper)).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.guard().mode() == GuardMode.PATROL, "The mode survives a save");
        helper.assertTrue(loaded.guard().pickOf(guard(scene).getUUID()).filter(pick -> pick.period() == 8).isPresent(), "The pick survives a save");
        helper.assertTrue(loaded.workPoints().equals(block(helper).workPoints()), "The points survive a save");

        TestSettlements.setBlock(helper, new BlockPos(7, 1, 1), ModBlocks.MINER_BLOCK.get());
        helper.assertFalse(GuardPosts.setMode(SettlementData.get(level), helper.absolutePos(new BlockPos(7, 1, 1)), GuardMode.ROAM), "A Miner Block has no mode");
        ServiceGameTests.cleanUp(level, scene);
        helper.succeed();
    }

    // ---------------------------------------------------------------- guard points

    /**
     * A click on a block sets a numbered guard point with the facing of the click, a click on a point removes it and the later
     * numbers close the gap; at most 8, only inside the settlement and only where a Guard can stand.
     */
    private static void pointsSetRemoveRenumber(GameTestHelper helper) {
        SettlementData data = dataWithPost(2);
        SettlementBlock post = postOf(data);
        Direction[] facings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < 4; i++) {
            helper.assertTrue(GuardPosts.togglePoint(data, DATA_POST, spot(i), true, facings[i]) == GuardPosts.Toggle.ADDED, "Point " + (i + 1) + " is added");
        }
        helper.assertTrue(post.workPoints().get(2).equals(new WorkPoint(spot(2), Direction.SOUTH)), "Number 3 keeps its position and facing");

        helper.assertTrue(GuardPosts.togglePoint(data, DATA_POST, spot(1), true, Direction.UP) == GuardPosts.Toggle.REMOVED, "A second click removes point 2");
        helper.assertTrue(post.workPoints().size() == 3, "Three points are left");
        helper.assertTrue(post.workPoints().get(1).pos().equals(spot(2)) && post.workPoints().get(2).pos().equals(spot(3)),
                "The numbers close the gap: 3 becomes 2 and 4 becomes 3");
        helper.assertTrue(post.workPoints().get(1).facing() == Direction.SOUTH, "A moved point keeps its facing");

        helper.assertTrue(GuardPosts.togglePoint(data, DATA_POST, DATA_POST.offset(60, 0, 0), true, Direction.NORTH) == GuardPosts.Toggle.OUTSIDE,
                "A block outside the settlement is no point");
        helper.assertTrue(GuardPosts.togglePoint(data, DATA_POST, spot(5), false, Direction.NORTH) == GuardPosts.Toggle.NOT_STANDABLE,
                "A block a Guard cannot stand on is no point");
        for (int i = 4; post.workPoints().size() < GuardPosts.MAX_POINTS; i++) {
            helper.assertTrue(GuardPosts.togglePoint(data, DATA_POST, spot(i), true, Direction.NORTH) == GuardPosts.Toggle.ADDED, "Point " + i + " fits");
        }
        helper.assertTrue(post.workPoints().size() == 8, "Eight points");
        helper.assertTrue(GuardPosts.togglePoint(data, DATA_POST, DATA_POST.offset(3, 0, 6), true, Direction.NORTH) == GuardPosts.Toggle.TOO_MANY, "A ninth is refused");
        helper.assertTrue(GuardPosts.togglePoint(data, DATA_POST, spot(0), true, Direction.NORTH) == GuardPosts.Toggle.REMOVED, "A full post still removes a point");
        helper.assertTrue(GuardPosts.togglePoint(data, new BlockPos(90, 64, 90), spot(0), true, Direction.NORTH) == GuardPosts.Toggle.NO_POST, "Not a Guard Post");
        helper.succeed();
    }

    /** A container keeps cycling collect inventory, drop-off inventory and nothing; it is never a guard point. */
    private static void pointsContainerCycle(GameTestHelper helper) {
        SettlementData data = dataWithPost(1);
        SettlementBlock post = postOf(data);
        BlockPos chest = spot(1);
        helper.assertTrue(JobAssignments.cyclePoint(data, DATA_POST, chest, false, true, Direction.NORTH) == JobAssignments.PointResult.COLLECT, "First click: collect");
        helper.assertTrue(post.collect().filter(chest::equals).isPresent(), "The container is the collect inventory");
        helper.assertTrue(JobAssignments.cyclePoint(data, DATA_POST, chest, false, true, Direction.NORTH) == JobAssignments.PointResult.DROP_OFF, "Second click: drop-off");
        helper.assertTrue(JobAssignments.cyclePoint(data, DATA_POST, chest, false, true, Direction.NORTH) == JobAssignments.PointResult.REMOVED, "Third click: none");
        helper.assertTrue(post.workPoints().isEmpty(), "No guard point came out of it");
        helper.succeed();
    }

    // ---------------------------------------------------------------- the pick

    /** Each Guard of the block picks a point no other Guard of it holds; with all points taken it picks any. */
    private static void pickFreeFirst(GameTestHelper helper) {
        SettlementData data = dataWithPost(4);
        UUID one = guardOf(data, 1);
        UUID two = guardOf(data, 2);
        UUID three = guardOf(data, 3);
        helper.assertTrue(pickAt(data, one, 5, 0, RandomSource.create(1L)).isEmpty(), "No points: no pick, the Guard stands at the post");
        GuardPosts.togglePoint(data, DATA_POST, spot(0), true, Direction.NORTH);
        GuardPosts.togglePoint(data, DATA_POST, spot(1), true, Direction.SOUTH);
        RandomSource random = RandomSource.create(7L);
        Set<BlockPos> third = new HashSet<>();
        for (long day = 5; day < 40; day++) {
            BlockPos first = pickAt(data, one, day, 0, random).orElseThrow().pos();
            BlockPos other = pickAt(data, two, day, 0, random).orElseThrow().pos();
            helper.assertFalse(first.equals(other), "Day " + day + ": the second Guard takes the free point");
            third.add(pickAt(data, three, day, 0, random).orElseThrow().pos());
        }
        helper.assertTrue(third.equals(Set.of(spot(0), spot(1))), "With every point taken the third Guard picks any: " + third);
        helper.succeed();
    }

    /** A pick lasts the half day from 06:00 to 18:29 and from 18:30 to 05:59; at 18:30 and at 06:00 the Guards pick anew. */
    private static void pickPeriods(GameTestHelper helper) {
        SettlementData data = dataWithPost(1);
        UUID guard = guardOf(data, 1);
        GuardPosts.togglePoint(data, DATA_POST, spot(0), true, Direction.NORTH);
        GuardPosts.togglePoint(data, DATA_POST, spot(1), true, Direction.SOUTH);
        RandomSource random = RandomSource.create(3L);
        helper.assertTrue(GuardPosts.period(9, 0) == 18 && GuardPosts.period(9, BEFORE_EVENING) == 18, "06:00 to 18:29 is the first half of day 9");
        helper.assertTrue(GuardPosts.period(9, GuardPosts.EVENING) == 19 && GuardPosts.period(10, 0) == 20, "18:30 starts the second half, 06:00 the next day");
        WorkPoint day = pickAt(data, guard, 9, 0, random).orElseThrow();
        for (long dayTime : new long[] {MORNING, 6000L, BEFORE_EVENING}) {
            helper.assertTrue(pickAt(data, guard, 9, dayTime, random).orElseThrow().equals(day), "Same point until 18:29 (" + dayTime + ")");
        }
        helper.assertTrue(postOf(data).guard().pickOf(guard).orElseThrow().period() == 18, "The pick is saved with its half day");
        helper.assertTrue(pickAt(data, guard, 9, GuardPosts.EVENING, random).isPresent(), "A point at 18:30");
        helper.assertTrue(postOf(data).guard().pickOf(guard).orElseThrow().period() == 19, "A new pick at 18:30");
        helper.assertTrue(pickAt(data, guard, 10, 0, random).isPresent(), "A point at 06:00");
        helper.assertTrue(postOf(data).guard().pickOf(guard).orElseThrow().period() == 20, "A new pick at 06:00");

        WorkPoint held = pickAt(data, guard, 10, 0, random).orElseThrow();
        GuardPosts.togglePoint(data, DATA_POST, held.pos(), true, Direction.NORTH);
        WorkPoint left = postOf(data).workPoints().get(0);
        helper.assertTrue(pickAt(data, guard, 10, 1000, random).orElseThrow().equals(left), "A point that was removed is replaced by one that is left");
        helper.succeed();
    }

    /** A point a Guard found out of reach is skipped for the rest of the day and tried again the next day. */
    private static void pickSkipsUnreachable(GameTestHelper helper) {
        SettlementData data = dataWithPost(1);
        UUID guard = guardOf(data, 1);
        GuardPosts.togglePoint(data, DATA_POST, spot(0), true, Direction.NORTH);
        GuardPosts.togglePoint(data, DATA_POST, spot(1), true, Direction.SOUTH);
        RandomSource random = RandomSource.create(5L);
        GuardPosts.recordReach(data, DATA_POST, spot(0), 12, true);
        helper.assertTrue(postOf(data).guard().unreachable(spot(0)) && !postOf(data).guard().unreachable(spot(1)), "Only the first point is marked");
        for (int tries = 0; tries < 10; tries++) {
            helper.assertTrue(pickAt(data, guard, 12, tries * 1000L, random).orElseThrow().pos().equals(spot(1)), "The unreachable point is skipped");
            GuardPosts.recordReach(data, DATA_POST, spot(0), 12, true);
        }
        GuardPosts.recordReach(data, DATA_POST, spot(1), 12, true);
        helper.assertTrue(pickAt(data, guard, 12, 5000L, random).isEmpty(), "With every point out of reach the Guard stands at the post");
        Set<BlockPos> next = new HashSet<>();
        for (long dayAfter = 13; dayAfter < 40; dayAfter++) {
            next.add(pickAt(data, guard, dayAfter, 0, random).orElseThrow().pos());
        }
        helper.assertTrue(next.contains(spot(0)), "The next day the point is tried again");
        GuardPosts.recordReach(data, DATA_POST, spot(0), 50, false);
        helper.assertFalse(postOf(data).guard().unreachable(spot(0)), "A check that finds it reachable clears the mark");
        helper.succeed();
    }

    /** What the scroll shows: the points of a Guard Post with number order, facing and the red mark; its work list stays empty. */
    private static void pointsDisplay(GameTestHelper helper) {
        SettlementData data = dataWithPost(1);
        GuardPosts.togglePoint(data, DATA_POST, spot(0), true, Direction.EAST);
        GuardPosts.togglePoint(data, DATA_POST, spot(1), true, Direction.WEST);
        int before = data.revision();
        GuardPosts.recordReach(data, DATA_POST, spot(1), 3, true);
        helper.assertTrue(data.revision() > before, "A new mark sends the points to the players holding the scroll again");
        before = data.revision();
        GuardPosts.recordReach(data, DATA_POST, spot(1), 3, true);
        helper.assertTrue(data.revision() == before, "The same result sends nothing");
        JobPointsDisplay.JobPoints points = JobPointsDisplay.collect(data, DATA_POST, Optional.of(DATA_POST)).get(0);
        helper.assertTrue(points.work().isEmpty(), "A Guard Post shows no work places");
        helper.assertTrue(points.guardPoints().equals(List.of(new JobPointsDisplay.GuardPoint(new WorkPoint(spot(0), Direction.EAST), false),
                new JobPointsDisplay.GuardPoint(new WorkPoint(spot(1), Direction.WEST), true))), "Numbered in order with facing and the red mark: " + points.guardPoints());
        helper.succeed();
    }

    // ---------------------------------------------------------------- standing at the point

    /** The Guard walks to its guard point and stands there facing the way the point was set. */
    private static void standsAtPoint(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        BlockPos point = helper.absolutePos(new BlockPos(8, 0, 4));
        helper.assertTrue(GuardPosts.togglePoint(SettlementData.get(level), helper.absolutePos(POST), point, true, Direction.EAST) == GuardPosts.Toggle.ADDED,
                "The point is set");
        Settler guard = guard(scene);
        helper.succeedWhen(() -> {
            helper.assertTrue(guard.position().distanceTo(Vec3.atBottomCenterOf(point.above())) < 1.0, "The Guard stands on its point: " + guard.position()
                    + ", unreachable " + block(helper).guard().unreachable(point));
            helper.assertTrue(Math.abs(Mth.wrapDegrees(guard.yBodyRot - Direction.EAST.toYRot())) <= 5.0F, "It faces the facing of the point: " + guard.yBodyRot);
            helper.assertTrue(guard.getWork().status(helper.absolutePos(POST)) == JobStatus.GUARDING, "Its status is Guarding: " + guard.getWork().status(helper.absolutePos(POST)));
            ServiceGameTests.cleanUp(level, scene);
        });
    }

    /** Without guard points the Guard stands next to its Guard Post. */
    private static void standsAtPostWithoutPoints(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(9, 1, 6)));
        guard.snapTo(start.x, start.y, start.z, 0.0F, 0.0F);
        helper.succeedWhen(() -> {
            helper.assertTrue(guard.position().distanceTo(Vec3.atCenterOf(helper.absolutePos(POST))) < 3.5, "The Guard is at the Guard Post: " + guard.position());
            helper.assertTrue(guard.getWork().status(helper.absolutePos(POST)) == JobStatus.GUARDING, "Guarding");
            ServiceGameTests.cleanUp(level, scene);
        });
    }

    /** A guard point cut off by walls is outlined red, skipped, and the Guard stands at its post meanwhile. */
    private static void unreachablePoint(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        BlockPos centre = new BlockPos(8, 1, 4);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 2; y++) {
                    if (x != 0 || z != 0) {
                        helper.setBlock(centre.offset(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        helper.setBlock(centre.above(3), Blocks.STONE);
        BlockPos point = helper.absolutePos(centre.below());
        SettlementData data = SettlementData.get(level);
        helper.assertTrue(GuardPosts.togglePoint(data, helper.absolutePos(POST), point, true, Direction.NORTH) == GuardPosts.Toggle.ADDED, "The point is set");
        Settler guard = guard(scene);
        guard.getWork().decideNow(level);
        helper.succeedWhen(() -> {
            helper.assertTrue(block(helper).guard().unreachable(point), "The Guard found the point out of reach");
            helper.assertTrue(guard.position().distanceTo(Vec3.atCenterOf(helper.absolutePos(POST))) < 3.5, "It stands at the post: " + guard.position());
            ServiceGameTests.cleanUp(level, scene);
        });
    }

    // ---------------------------------------------------------------- never sleeps, breaks

    /** From 20:00 on a Guard stays at work: no end of work, no bedtime, the Schedule never sends it to bed, whatever the clock says. */
    private static void neverSleeps(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        Settler plain = ServiceGameTests.settler(helper, scene.settlement(), Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3))), "Plain", true);
        plain.setNoAi(true);
        long day = DayClock.day(level);
        Schedule.DayTimes times = guard.getWork().dayTimes(day);
        helper.assertTrue(times.wake() == 0L && times.workEnd() >= DAY && times.bed() >= DAY, "A Guard has no end of work and no bedtime: " + times);
        Schedule.DayTimes usual = plain.getWork().dayTimes(day);
        helper.assertTrue(usual.workEnd() < DAY && usual.bed() < DAY, "Another settler has: " + usual);

        guard.setNoAi(true);
        // The tests of this batch share the clock: put it back where it was, not to the morning.
        long startTime = DayClock.dayTime(level);
        for (long dayTime : new long[] {14_100L, 18_000L, 23_900L, 0L, 500L}) {
            ServiceGameTests.setDayTime(level, dayTime);
            guard.getWork().decideNow(level);
            helper.assertFalse(guard.isSleeping(), "Awake at day time " + dayTime);
            helper.assertTrue(guard.getWork().atWork(), "On duty at day time " + dayTime);
        }
        ServiceGameTests.setDayTime(level, startTime);
        ServiceGameTests.cleanUp(level, scene, plain);
        helper.succeed();
    }

    /**
     * A fatigue break sits the Guard down where it stands, shows Pausing for 20 to 45 seconds and then it stands again.
     * Each decision of the test stands for 5 seconds of work.
     */
    private static void breakSitsOnTheSpot(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        BlockPos point = helper.absolutePos(new BlockPos(6, 0, 4));
        GuardPosts.togglePoint(SettlementData.get(level), helper.absolutePos(POST), point, true, Direction.SOUTH);
        Vec3 stand = Vec3.atBottomCenterOf(point.above());
        guard.snapTo(stand.x, stand.y, stand.z, 0.0F, 0.0F);
        BlockPos jobBlock = helper.absolutePos(POST);
        // A few ticks first, so the Guard is on the ground when the break sits it down.
        helper.runAfterDelay(10, () -> {
            int decisions = 0;
            while (guard.getWork().status(jobBlock) != JobStatus.PAUSING && decisions < 200) {
                guard.getWork().decideNow(level);
                decisions++;
            }
            helper.assertTrue(guard.getWork().status(jobBlock) == JobStatus.PAUSING, "A break came after " + decisions + " decisions");
            helper.assertTrue(guard.isSitting(), "The Guard sits down on the spot");
            helper.assertTrue(guard.position().distanceTo(stand) < 0.8, "It sits at its point: " + guard.position());
            int paused = 0;
            while (guard.getWork().status(jobBlock) == JobStatus.PAUSING && paused < 100) {
                guard.getWork().decideNow(level);
                paused++;
            }
            helper.assertTrue(paused >= 3 && paused <= 10, "The break lasted " + paused + " decisions of 5 seconds (20 to 45 seconds)");
            helper.assertFalse(guard.isSitting(), "It stands up again");
            helper.assertTrue(guard.getWork().status(jobBlock) == JobStatus.GUARDING, "Back on guard");
            ServiceGameTests.cleanUp(level, scene);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- needs

    private static void placeBed(GameTestHelper helper, BlockPos relativeHead) {
        BlockPos head = helper.absolutePos(relativeHead);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        helper.getLevel().setBlock(head.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        helper.getLevel().setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
    }

    /** A Guard claims a bed of its own, which meets the bed need, though it never lies in it; its Entertainment need counts as met. */
    private static void bedAndEntertainmentNeed(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        guard.setNoAi(true);
        placeBed(helper, new BlockPos(5, 1, 5));
        helper.assertTrue(scene.settlement().bedOf(guard.getUUID()).isEmpty(), "No bed claimed yet");
        helper.assertFalse(needRow(guard, level, "civmod.need.bed").met(), "The bed need is not met without a bed");
        guard.getWork().decideNow(level);
        helper.assertTrue(scene.settlement().bedOf(guard.getUUID()).isPresent(), "The Guard claimed a bed");
        helper.assertTrue(needRow(guard, level, "civmod.need.bed").met(), "The bed need is met by it");
        helper.assertFalse(guard.isSleeping() || guard.getWork().restPlace().isPresent(), "It never lies in it");
        helper.assertTrue(entertainmentRows(guard, level).stream().allMatch(NeedsReport.Row::met), "Entertainment counts as fulfilled for a Guard");
        Settler plain = ServiceGameTests.settler(helper, scene.settlement(), Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3))), "Plain", true);
        plain.setNoAi(true);
        helper.assertTrue(entertainmentRows(plain, level).stream().anyMatch(row -> !row.met()), "Another settler without Entertainment has a need unmet");
        ServiceGameTests.cleanUp(level, scene, plain);
        helper.succeed();
    }

    // ---------------------------------------------------------------- duty EXP

    /** The rule: the base EXP times the level of the Guard Post for every in-game hour, from literal numbers. */
    private static void dutyExpRule(GameTestHelper helper) {
        int base = ModJobs.GUARD.get().baseExp();
        helper.assertTrue(base == 1, "The Guard's base EXP is 1: " + base);
        helper.assertTrue(GuardDuty.dutyExp(1000L, base, 1) == 1, "One hour at level 1");
        helper.assertTrue(GuardDuty.dutyExp(1000L, base, 3) == 3, "One hour at level 3");
        helper.assertTrue(GuardDuty.dutyExp(DAY, base, 1) == 24, "A day at level 1 is 24");
        helper.assertTrue(GuardDuty.dutyExp(DAY, base, 5) == 120, "A day at level 5 is 120");
        helper.assertTrue(GuardDuty.dutyExp(500L, base, 1) == 0, "Half an hour pays nothing yet");
        helper.succeed();
    }

    /** A Guard on duty earns the base EXP times the level of its Guard Post for every in-game hour, as the clock runs. */
    private static void dutyExpLive(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        BlockPos post = helper.absolutePos(POST);
        helper.getBlockEntity(POST, JobBlockEntity.class).setJobLevel(3);
        SettlementManager.setLevel(SettlementData.get(level), post, 3, SettlementManager.Listener.NONE);
        Settler guard = guard(scene);
        guard.setNoAi(true);
        ServiceGameTests.setDayTime(level, MORNING);
        guard.getWork().decideNow(level);
        int before = totalExp(guard);
        ServiceGameTests.setDayTime(level, MORNING + DayClock.HOUR_TICKS / 2);
        guard.getWork().decideNow(level);
        helper.assertTrue(totalExp(guard) == before, "Half an hour pays nothing yet");
        ServiceGameTests.setDayTime(level, MORNING + 2 * DayClock.HOUR_TICKS);
        guard.getWork().decideNow(level);
        int gained = totalExp(guard) - before;
        helper.assertTrue(gained >= 6 && gained < 9, "Two hours at level 3 pay 6 EXP (a talent may add some): " + gained);
        ServiceGameTests.setDayTime(level, MORNING);
        ServiceGameTests.cleanUp(level, scene);
        helper.succeed();
    }

    /** Two missed days pay 24 hours a day, the night included, though catch-up only simulates the 14 awake hours. */
    private static void dutyExpCatchUp(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        guard.setNoAi(true);
        long dayStart = DayClock.day(level) * DAY;
        int before = totalExp(guard);
        guard.getWork().setCatchUp(dayStart - 2 * DAY, dayStart);
        CatchUp.runNow(level, List.of(guard));
        int gained = totalExp(guard) - before;
        helper.assertTrue(gained >= 48 && gained <= 72, "Two days at level 1 pay 48 EXP (a talent may add some): " + gained);
        helper.assertFalse(guard.getWork().hasPendingCatchUp(), "Nothing pending afterwards");

        // A short window inside the night, which no simulated hour covers: it pays at the end of the run.
        before = totalExp(guard);
        guard.getWork().setCatchUp(dayStart - 8 * DayClock.HOUR_TICKS, dayStart - 2 * DayClock.HOUR_TICKS);
        CatchUp.runNow(level, List.of(guard));
        gained = totalExp(guard) - before;
        helper.assertTrue(gained >= 6 && gained <= 9, "Six night hours pay 6 EXP: " + gained);
        ServiceGameTests.cleanUp(level, scene);
        helper.succeed();
    }

    // ---------------------------------------------------------------- the broken post

    /** A broken Guard Post takes the job away: the Guard has none, so it is a Laborer, and what it carries stays in its belt. */
    private static void postBroken(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        guard.setNoAi(true);
        guard.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_SWORD));
        BlockPos post = helper.absolutePos(POST);
        helper.assertTrue(guard.getJobs().size() == 1 && guard.getJobs().get(0).jobType() == ModJobs.GUARD.get(), "The Guard holds the Guard job");
        helper.assertTrue(guard.isInvulnerableTo(level, level.damageSources().generic()), "Settlers stay invulnerable");
        level.destroyBlock(post, false);
        helper.assertTrue(guard.getJobs().isEmpty(), "The Guard lost its job");
        helper.assertTrue(guard.getInventory().getItem(Settler.BELT_START).is(Items.STONE_SWORD), "The sword stays in its belt");
        guard.getWork().decideNow(level);
        helper.assertTrue(guard.getWork().activeJob().isEmpty(), "No Guard work any more");
        helper.assertTrue(SettlementData.get(level).settlementAt(post).flatMap(settlement -> settlement.block(post)).isEmpty(),
                "The Guard Post is gone from the settlement");
        ServiceGameTests.cleanUp(level, scene);
        helper.succeed();
    }
}
