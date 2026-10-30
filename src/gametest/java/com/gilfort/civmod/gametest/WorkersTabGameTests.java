package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.menu.WorkersTab;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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
            register(helper, "workers_tab_settler_window", WorkersTabGameTests::settlerWindow);
            register(helper, "workers_tab_days_left", WorkersTabGameTests::daysLeft);
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
        helper.setBlock(CLERK, ModBlocks.CLERK_BLOCK.get());
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

    /** The JobBlock window as the server opens it for a player at the block. */
    private static JobBlockMenu menu(GameTestHelper helper, Player player) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CLERK);
        JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(pos);
        SettlementBlock block = JobBlockSite.blockAt(level, pos).orElseThrow();
        return new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(level, pos, block.jobType(), block.level()));
    }

    private static Player player(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(CLERK);
        player.snapTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 1.5);
        return player;
    }

    private static int index(JobBlockInfo info, Settler settler) {
        List<JobBlockInfo.Worker> workers = info.workers();
        for (int i = 0; i < workers.size(); i++) {
            if (workers.get(i).assignee().id().equals(settler.getUUID())) {
                return i;
            }
        }
        return -1;
    }

    /** One worker per settler with its Job and its level in that Job; a loaded settler on no mission can be opened. */
    private static void rows(GameTestHelper helper) {
        Settlement settlement = clerkBlock(helper);
        Settler alice = settler(helper, settlement, "Alice", new BlockPos(3, 1, 3));
        Settler bob = settler(helper, settlement, "Bob", new BlockPos(4, 1, 3));
        hire(helper, alice, ModJobs.CLERK.get());
        hire(helper, bob, ModJobs.DELIVERYMAN.get());
        bob.getSkills().set(ModJobs.DELIVERYMAN.get(), new SettlerSkills.Skill(23, 5));
        JobBlockInfo info = menu(helper, player(helper)).getInfo();
        helper.assertValueEqual(info.workers().size(), 2, "One worker per settler");
        JobBlockInfo.Worker worker = info.workers().get(index(info, bob));
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
        WorkersTab tab = menu.tab(WorkersTab.class).orElseThrow();
        helper.assertTrue(tab.unassign(bob.getUUID()), "✕ is taken");
        SettlementBlock block = JobBlockSite.blockAt(helper.getLevel(), helper.absolutePos(CLERK)).orElseThrow();
        helper.assertTrue(bob.getJobs().isEmpty() && block.assignee(bob.getUUID()).isEmpty(), "Bob left the JobBlock: " + bob.getJobs());
        helper.assertTrue(block.assignee(alice.getUUID()).isPresent() && alice.getJobs().size() == 1, "Alice stays");
        helper.assertValueEqual(bob.getSkills().get(ModJobs.DELIVERYMAN.get()), new SettlerSkills.Skill(23, 5), "Skill and EXP stay with the settler");
        helper.assertTrue(index(menu.getInfo(), bob) < 0 && index(menu.getInfo(), alice) >= 0, "The tab lists only Alice: " + menu.getInfo().workers());
        helper.assertTrue(menu.getInfo().places().stream().filter(line -> line.job() == ModJobs.DELIVERYMAN.get()).allMatch(line -> line.taken() == 0),
                "The Deliveryman place is free: " + menu.getInfo().places());
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
        WorkersTab tab = menu.tab(WorkersTab.class).orElseThrow();
        int line = 0;
        while (menu.getInfo().places().get(line).job() != ModJobs.DELIVERYMAN.get()) {
            line++;
        }
        helper.assertTrue(menu.clickMenuButton(player, tab.autoButtonId(line, true)), "Toggle on is taken");
        JobPlaces.Line deliveryman = menu.getInfo().places().get(line);
        helper.assertTrue(deliveryman.auto(), "Automatic assignment on: " + deliveryman);
        helper.assertTrue(index(menu.getInfo(), idle) >= 0 && idle.getJobs().size() == 1, "The jobless settler took the place: " + menu.getInfo().workers());
        helper.assertTrue(menu.clickMenuButton(player, tab.autoButtonId(line, false)), "Toggle off is taken");
        helper.assertFalse(menu.getInfo().places().get(line).auto(), "Automatic assignment off");
        idle.discard();
        helper.succeed();
    }

    /**
     * A settler window opened from the Workers tab stays open while the player stays at the JobBlock, even far from
     * the settler, as long as the settler works there; one opened at the settler closes when the player walks away.
     */
    private static void settlerWindow(GameTestHelper helper) {
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
        SettlementWorld.unassign(helper.getLevel(), pos, alice.getUUID());
        helper.assertFalse(fromBlock.stillValid(player), "No longer assigned to the JobBlock: the JobBlock stops counting");
        hire(helper, alice, ModJobs.DELIVERYMAN.get());
        player.snapTo(pos.getX() + 20.5, pos.getY() + 1, pos.getZ() + 0.5);
        helper.assertFalse(fromBlock.stillValid(player), "Away from the JobBlock and the settler: closes");
        alice.discard();
        helper.succeed();
    }

    /** "back in x d": whole days rounded up, at least 1. */
    private static void daysLeft(GameTestHelper helper) {
        helper.assertValueEqual(JobBlockInfo.Worker.daysLeft(0L, 36000L), 2, "1.5 days");
        helper.assertValueEqual(JobBlockInfo.Worker.daysLeft(0L, 48000L), 2, "Exactly 2 days");
        helper.assertValueEqual(JobBlockInfo.Worker.daysLeft(10L, 48011L), 3, "Just over 2 days");
        helper.assertValueEqual(JobBlockInfo.Worker.daysLeft(0L, 100L), 1, "Later today");
        helper.assertValueEqual(JobBlockInfo.Worker.daysLeft(500L, 100L), 1, "Overdue: still 1");
        helper.succeed();
    }
}
