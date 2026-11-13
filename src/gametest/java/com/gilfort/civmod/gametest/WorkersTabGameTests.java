package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.PatternsTab;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.menu.WorkersTab;
import com.gilfort.civmod.menu.WorkersTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestSettlements;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Workers tab of the JobBlock window (#141, vault {@code Systems/GUI.md}, {@code Systems/JobBlock.md}):
 * one row per settler with its level, ✕ unassigns one settler at once and keeps its skill, the automatic assignment
 * buttons, the settler window opened from the tab and the "back in x d" of a settler on a mission.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class WorkersTabGameTests {

    private static final BlockPos CLERK = new BlockPos(1, 1, 1);

    private WorkersTabGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "workers_tab_rows", WorkersTabGameTests::rows);
            register(helper, "workers_tab_unassign_one", WorkersTabGameTests::unassignOne);
            register(helper, "workers_tab_auto_toggle", WorkersTabGameTests::autoToggle);
            register(helper, "workers_tab_wanted_state", WorkersTabGameTests::wantedState);
            register(helper, "workers_tab_treasure_toggle", WorkersTabGameTests::treasureToggle);
            register(helper, "workers_tab_tool_supply_toggle", WorkersTabGameTests::toolSupplyToggle);
            register(helper, "workers_tab_settler_window", WorkersTabGameTests::settlerWindow);
            register(helper, "workers_tab_days_left", WorkersTabGameTests::daysLeft);
            register(helper, "workers_tab_away_absolute", WorkersTabGameTests::awayAbsolute);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A Clerk JobBlock at level 2 (1 Clerk, 2 Deliverymen) on a stone floor; returns its settlement. */
    private static Settlement clerkBlock(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        TestSettlements.setBlock(helper, CLERK, ModBlocks.CLERK_BLOCK.get());
        SettlementManager.setLevel(SettlementData.get(helper.getLevel()), helper.absolutePos(CLERK), 2, SettlementManager.Listener.NONE);
        return SettlementData.get(helper.getLevel()).settlementAt(helper.absolutePos(CLERK)).orElseThrow();
    }

    private static Settler settler(GameTestHelper helper, Settlement settlement, String name, BlockPos at) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(at));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal(name));
        level.addFreshEntity(settler);
        settler.joinSettlement(SettlementData.get(level), settlement);
        return settler;
    }

    private static void hire(GameTestHelper helper, Settler settler, Job job) {
        SettlementWorld.assign(helper.getLevel(), helper.absolutePos(CLERK), settler, job);
    }

    /** The Clerk JobBlock window as the server opens it for a player at the block. */
    private static JobBlockMenu menu(GameTestHelper helper, Player player) {
        return menu(helper, player, CLERK);
    }

    /** The window of the JobBlock at a relative position as the server opens it for a player. */
    private static JobBlockMenu menu(GameTestHelper helper, Player player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(pos);
        SettlementBlock block = JobBlockSite.blockAt(level, pos).orElseThrow();
        return new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(block.jobType(), block.level()));
    }

    /** The Workers data a window holds now (what the server last sent its client), without reading it again. */
    private static WorkersTabData shown(JobBlockMenu menu) {
        return menu.tab(WorkersTab.class).orElseThrow().data().get();
    }

    /** The Workers data a window holds after its next broadcast: the server reads the block again. */
    private static WorkersTabData sees(JobBlockMenu menu) {
        menu.resend();
        return shown(menu);
    }

    private static Player player(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(CLERK);
        player.snapTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 1.5);
        return player;
    }

    private static int index(WorkersTabData info, Settler settler) {
        List<WorkersTabData.Worker> workers = info.workers();
        for (int i = 0; i < workers.size(); i++) {
            if (workers.get(i).assignee().id().equals(settler.getUUID())) {
                return i;
            }
        }
        return -1;
    }

    private static JobPlaces.Line line(WorkersTabData data, Job job) {
        return data.places().stream().filter(line -> line.job() == job).findFirst().orElseThrow();
    }

    /** One worker per settler with its Job and its level in that Job; a loaded settler on no mission can be opened. */
    private static void rows(GameTestHelper helper) {
        Settlement settlement = clerkBlock(helper);
        Settler alice = settler(helper, settlement, "Alice", new BlockPos(3, 1, 3));
        Settler bob = settler(helper, settlement, "Bob", new BlockPos(4, 1, 3));
        hire(helper, alice, ModJobs.CLERK.get());
        hire(helper, bob, ModJobs.DELIVERYMAN.get());
        bob.getSkills().set(ModJobs.DELIVERYMAN.get(), new SettlerSkills.Skill(23, 5));
        WorkersTabData info = sees(menu(helper, player(helper)));
        helper.assertValueEqual(info.workers().size(), 2, "One worker per settler");
        WorkersTabData.Worker worker = info.workers().get(index(info, bob));
        helper.assertValueEqual(worker.assignee().name(), "Bob", "Worker name");
        helper.assertTrue(worker.assignee().job() == ModJobs.DELIVERYMAN.get(), "Worker Job: " + worker);
        helper.assertValueEqual(worker.level(), 23, "Level in the Job it holds here");
        helper.assertTrue(worker.loaded() && worker.away().isEmpty() && worker.opensWindow(), "A loaded settler at home opens its window: " + worker);
        alice.discard();
        bob.discard();
        helper.succeed();
    }

    /** ✕ removes exactly one settler at once; the others stay, the removed one keeps its skill and EXP. */
    private static void unassignOne(GameTestHelper helper) {
        Settlement settlement = clerkBlock(helper);
        Settler alice = settler(helper, settlement, "Alice", new BlockPos(3, 1, 3));
        Settler bob = settler(helper, settlement, "Bob", new BlockPos(4, 1, 3));
        hire(helper, alice, ModJobs.CLERK.get());
        hire(helper, bob, ModJobs.DELIVERYMAN.get());
        bob.getSkills().set(ModJobs.DELIVERYMAN.get(), new SettlerSkills.Skill(23, 5));
        Player player = player(helper);
        JobBlockMenu menu = menu(helper, player);
        JobBlockMenu other = menu(helper, player(helper));
        helper.assertTrue(index(sees(other), bob) >= 0, "The other window lists Bob before");
        WorkersTab tab = menu.tab(WorkersTab.class).orElseThrow();
        helper.assertTrue(tab.unassign(bob.getUUID()), "✕ is taken");
        SettlementBlock block = JobBlockSite.blockAt(helper.getLevel(), helper.absolutePos(CLERK)).orElseThrow();
        helper.assertTrue(bob.getJobs().isEmpty() && block.assignee(bob.getUUID()).isEmpty(), "Bob left the JobBlock: " + bob.getJobs());
        helper.assertTrue(block.assignee(alice.getUUID()).isPresent() && alice.getJobs().size() == 1, "Alice stays");
        helper.assertValueEqual(bob.getSkills().get(ModJobs.DELIVERYMAN.get()), new SettlerSkills.Skill(23, 5), "Skill and EXP stay with the settler");
        helper.assertTrue(index(shown(menu), bob) < 0 && index(shown(menu), alice) >= 0, "The tab lists only Alice: " + shown(menu).workers());
        helper.assertTrue(shown(menu).places().stream().filter(line -> line.job() == ModJobs.DELIVERYMAN.get()).allMatch(line -> line.taken() == 0),
                "The Deliveryman place is free: " + shown(menu).places());
        helper.assertTrue(index(sees(other), bob) < 0 && index(shown(other), alice) >= 0, "The other window lists only Alice: " + shown(other).workers());
        // Keyed by settler: a click on a row that left the list in the meantime does nothing, even if another row took its place.
        helper.assertFalse(tab.unassign(bob.getUUID()), "A settler no longer listed is not unassigned again");
        helper.assertTrue(block.assignee(alice.getUUID()).isPresent(), "Alice stays after the stale click");
        alice.discard();
        bob.discard();
        helper.succeed();
    }

    /** The automatic assignment button sets its Job's toggle; on, a jobless settler takes the free place at once. */
    private static void autoToggle(GameTestHelper helper) {
        Settlement settlement = clerkBlock(helper);
        Settler idle = settler(helper, settlement, "Idle", new BlockPos(3, 1, 3));
        Player player = player(helper);
        JobBlockMenu menu = menu(helper, player);
        JobBlockMenu other = menu(helper, player(helper));
        WorkersTab tab = menu.tab(WorkersTab.class).orElseThrow();
        Job deliverymen = ModJobs.DELIVERYMAN.get();
        helper.assertFalse(line(sees(menu), deliverymen).auto(), "Off before");
        helper.assertTrue(tab.setAuto(deliverymen, true), "Toggle on is taken");
        JobPlaces.Line deliveryman = line(shown(menu), deliverymen);
        helper.assertTrue(deliveryman.auto(), "Automatic assignment on: " + deliveryman);
        helper.assertTrue(index(shown(menu), idle) >= 0 && idle.getJobs().size() == 1, "The jobless settler took the place: " + shown(menu).workers());
        helper.assertTrue(line(sees(other), deliverymen).auto() && index(shown(other), idle) >= 0, "The other window shows both: " + shown(other));
        helper.assertFalse(tab.setAuto(ModJobs.MINER.get(), true), "A Job the block does not offer is refused");
        helper.assertTrue(tab.setAuto(deliverymen, false), "Toggle off is taken");
        helper.assertFalse(line(shown(menu), deliverymen).auto(), "Automatic assignment off");
        idle.discard();
        helper.succeed();
    }

    /**
     * The leaf and delivery buttons send the wanted state: two players with the window of the same JobBlock open both
     * switch it on, and the second click, made from a view that still shows it off, leaves it on.
     */
    private static void wantedState(GameTestHelper helper) {
        clerkBlock(helper);
        BlockPos lumberjack = new BlockPos(4, 1, 1);
        BlockPos craft = new BlockPos(7, 1, 1);
        TestSettlements.setBlock(helper, lumberjack, ModBlocks.LUMBERJACK_BLOCK.get());
        TestSettlements.setBlock(helper, craft, ModBlocks.CRAFTSPERSON_BLOCK.get());
        Player first = player(helper);
        Player second = player(helper);

        JobBlockMenu leavesOne = menu(helper, first, lumberjack);
        JobBlockMenu leavesTwo = menu(helper, second, lumberjack);
        boolean leaves = !sees(leavesOne).collectLeaves();
        helper.assertTrue(leavesOne.clickMenuButton(first, leavesOne.tab(WorkersTab.class).orElseThrow().leavesButtonId(leaves)), "First leaf click");
        helper.assertTrue(leavesTwo.clickMenuButton(second, leavesTwo.tab(WorkersTab.class).orElseThrow().leavesButtonId(leaves)), "Second leaf click");
        helper.assertValueEqual(JobBlockSite.blockAt(helper.getLevel(), helper.absolutePos(lumberjack)).orElseThrow().collectLeaves(), leaves,
                "Leaf collection after two clicks for the same state");
        helper.assertValueEqual(sees(leavesTwo).collectLeaves(), leaves, "The second window shows the leaf state");

        JobBlockMenu deliveryOne = menu(helper, first, craft);
        JobBlockMenu deliveryTwo = menu(helper, second, craft);
        helper.assertFalse(sees(deliveryOne).byDeliveryman(), "Default: Self");
        PatternsTab baseOne = PatternsTab.of(deliveryOne, ModJobs.CRAFTSPERSON.get()).orElseThrow();
        PatternsTab baseTwo = PatternsTab.of(deliveryTwo, ModJobs.CRAFTSPERSON.get()).orElseThrow();
        helper.assertTrue(deliveryOne.clickMenuButton(first, baseOne.deliveryButtonId(true)), "First delivery click");
        helper.assertTrue(deliveryTwo.clickMenuButton(second, baseTwo.deliveryButtonId(true)), "Second delivery click");
        helper.assertTrue(JobBlockSite.blockAt(helper.getLevel(), helper.absolutePos(craft)).orElseThrow().byDeliveryman(),
                "Deliveryman after two clicks for the same state");
        helper.assertTrue(sees(deliveryTwo).byDeliveryman(), "The second window shows Deliveryman");
        helper.succeed();
    }

    /**
     * The Fisherman's treasure toggle (#244): on by default, the button sends the wanted state, the JobBlock keeps it
     * through a save and the window shows it.
     */
    private static void treasureToggle(GameTestHelper helper) {
        clerkBlock(helper);
        BlockPos fisherman = new BlockPos(4, 1, 1);
        TestSettlements.setBlock(helper, fisherman, ModBlocks.FISHERMAN_BLOCK.get());
        Player first = player(helper);
        Player second = player(helper);
        SettlementBlock block = JobBlockSite.blockAt(helper.getLevel(), helper.absolutePos(fisherman)).orElseThrow();
        helper.assertTrue(block.treasure() && sees(menu(helper, first, fisherman)).treasure(), "Treasure is on by default");

        JobBlockMenu one = menu(helper, first, fisherman);
        JobBlockMenu two = menu(helper, second, fisherman);
        helper.assertTrue(one.clickMenuButton(first, one.tab(WorkersTab.class).orElseThrow().treasureButtonId(false)), "First click switches it off");
        helper.assertTrue(two.clickMenuButton(second, two.tab(WorkersTab.class).orElseThrow().treasureButtonId(false)), "Second click for the same state");
        helper.assertFalse(block.treasure(), "Treasure is off after two clicks for the same state");
        helper.assertFalse(sees(two).treasure(), "The window shows it off");

        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementBlock loaded = SettlementBlock.CODEC.parse(ops, SettlementBlock.CODEC.encodeStart(ops, block).getOrThrow()).getOrThrow();
        helper.assertFalse(loaded.treasure(), "The JobBlock keeps it off through a save");
        helper.assertTrue(one.clickMenuButton(first, one.tab(WorkersTab.class).orElseThrow().treasureButtonId(true)) && block.treasure(), "On again");
        helper.succeed();
    }

    /**
     * The Clerk's "Auto tool supply" toggle (#242): on by default, the button sends the wanted state, the Clerk keeps it
     * through a save and the window shows it.
     */
    private static void toolSupplyToggle(GameTestHelper helper) {
        clerkBlock(helper);
        Player first = player(helper);
        Player second = player(helper);
        SettlementBlock block = JobBlockSite.blockAt(helper.getLevel(), helper.absolutePos(CLERK)).orElseThrow();
        helper.assertTrue(block.autoToolSupply() && sees(menu(helper, first)).autoToolSupply(), "The tool supply is on by default");

        JobBlockMenu one = menu(helper, first);
        JobBlockMenu two = menu(helper, second);
        helper.assertTrue(one.clickMenuButton(first, one.tab(WorkersTab.class).orElseThrow().toolSupplyButtonId(false)), "First click switches it off");
        helper.assertTrue(two.clickMenuButton(second, two.tab(WorkersTab.class).orElseThrow().toolSupplyButtonId(false)), "Second click for the same state");
        helper.assertFalse(block.autoToolSupply(), "The supply is off after two clicks for the same state");
        helper.assertFalse(sees(two).autoToolSupply(), "The window shows it off");

        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementBlock loaded = SettlementBlock.CODEC.parse(ops, SettlementBlock.CODEC.encodeStart(ops, block).getOrThrow()).getOrThrow();
        helper.assertFalse(loaded.autoToolSupply(), "The Clerk keeps it off through a save");
        helper.assertTrue(one.clickMenuButton(first, one.tab(WorkersTab.class).orElseThrow().toolSupplyButtonId(true)) && block.autoToolSupply(), "On again");
        helper.succeed();
    }

    /**
     * A settler window opened from the Workers tab stays open while the player stays at the JobBlock, even far from
     * the settler, as long as the settler works there; one opened at the settler closes when the player walks away.
     */
    private static void settlerWindow(GameTestHelper helper) {
        // The test position is random and its far side may not be loaded. A settler moved into an unloaded chunk is no
        // longer found by its UUID, so the unassign would skip its job list.
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(40, 0, 8));
        ServerLevel level = helper.getLevel();
        try {
            Settlement settlement = clerkBlock(helper);
            Settler alice = settler(helper, settlement, "Alice", new BlockPos(3, 1, 3));
            hire(helper, alice, ModJobs.DELIVERYMAN.get());
            alice.snapTo(alice.getX() + 30, alice.getY(), alice.getZ());
            Player player = player(helper);
            BlockPos pos = helper.absolutePos(CLERK);
            SettlerMenu fromBlock = new SettlerMenu(2, player.getInventory(), alice, SettlerInfo.of(alice), Optional.of(pos));
            helper.assertTrue(fromBlock.stillValid(player), "Valid at the JobBlock, far from the settler");
            SettlerMenu atSettler = new SettlerMenu(3, player.getInventory(), alice, SettlerInfo.of(alice), Optional.empty());
            helper.assertFalse(atSettler.stillValid(player), "Opened at the settler: closes away from it");
            SettlementWorld.unassign(level, pos, alice.getUUID());
            helper.assertFalse(fromBlock.stillValid(player), "No longer assigned to the JobBlock: the JobBlock stops counting");
            hire(helper, alice, ModJobs.DELIVERYMAN.get());
            player.snapTo(pos.getX() + 20.5, pos.getY() + 1, pos.getZ() + 0.5);
            helper.assertFalse(fromBlock.stillValid(player), "Away from the JobBlock and the settler: closes");
            alice.discard();
            helper.succeed();
        } finally {
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        }
    }

    /** "back in x d": whole days rounded up, at least 1. */
    private static void daysLeft(GameTestHelper helper) {
        helper.assertValueEqual(WorkersTabData.Worker.daysLeft(0L, 36000L), 2, "1.5 days");
        helper.assertValueEqual(WorkersTabData.Worker.daysLeft(0L, 48000L), 2, "Exactly 2 days");
        helper.assertValueEqual(WorkersTabData.Worker.daysLeft(10L, 48011L), 3, "Just over 2 days");
        helper.assertValueEqual(WorkersTabData.Worker.daysLeft(0L, 100L), 1, "Later today");
        helper.assertValueEqual(WorkersTabData.Worker.daysLeft(500L, 100L), 1, "Overdue: still 1");
        helper.succeed();
    }

    /**
     * A settler on a mission carries the absolute return time, so the "back in x d" counts down on the client and the
     * data does not change while the mission runs.
     */
    private static void awayAbsolute(GameTestHelper helper) {
        Settlement settlement = clerkBlock(helper);
        Settler alice = settler(helper, settlement, "Alice", new BlockPos(3, 1, 3));
        hire(helper, alice, ModJobs.CLERK.get());
        long returnTime = helper.getLevel().getOverworldClockTime() + 3 * DayClock.DAY_TICKS;
        Identifier mission = CivMod.id("dungeon_raid");
        alice.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                Optional.of(new MissionTrip(helper.absolutePos(CLERK), mission, returnTime))));
        JobBlockMenu menu = menu(helper, player(helper));
        WorkersTabData first = sees(menu);
        WorkersTabData.Worker worker = first.workers().get(index(first, alice));
        helper.assertTrue(worker.away().equals(Optional.of(new WorkersTabData.Worker.Away(mission, returnTime))), "Away until the return time: " + worker);
        helper.assertFalse(worker.opensWindow(), "A settler on a mission does not open its window");
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(sees(menu).equals(first), "The data does not change while the mission runs");
            alice.discard();
            helper.succeed();
        });
    }
}
