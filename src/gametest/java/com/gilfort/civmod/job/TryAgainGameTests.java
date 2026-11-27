package com.gilfort.civmod.job;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the "Try again" button of the settler window's job tab (story #341, vault {@code Systems/GUI.md}). The
 * seam is {@link SettlerMenu#tryAgain}, called directly like {@code menu.switchJob}, and the payload codec.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class TryAgainGameTests {

    /** The statuses the button shows for, written out here on purpose: the test checks the list of the ticket, not the code. */
    private static final Set<JobStatus> WAITING = EnumSet.of(JobStatus.NO_TOOL, JobStatus.NO_FOOD, JobStatus.NO_WORK_PLACE,
            JobStatus.NO_FREE_WORK_PLACE, JobStatus.DROP_OFF_FULL, JobStatus.STORAGE_FULL, JobStatus.BLOCKED, JobStatus.MISSING_INPUTS,
            JobStatus.MISSING_FUEL, JobStatus.NO_MISSION);

    private TryAgainGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "try_again_clears_waits", TryAgainGameTests::clearsWaits);
            register(helper, "try_again_out_of_reach", TryAgainGameTests::outOfReach);
            register(helper, "try_again_reads_state", TryAgainGameTests::readsState);
            register(helper, "try_again_finds_tool", TryAgainGameTests::findsTool);
            register(helper, "try_again_payload_codec", TryAgainGameTests::payloadCodec);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A settler with a Lumberjack job at a JobBlock registered without a block in the world. */
    private record Scene(Settler settler, SettlerJob job, BlockPos jobBlock) {

        Workplace workplace() {
            return settler.getWork().workplace(jobBlock);
        }

        /** Puts every wait the button clears on the job: a block with the status, a skipped request, a skipped pattern, a tool search. */
        void waitOn(JobStatus status, long now) {
            workplace().block(status, now);
            workplace().skips().put(UUID.randomUUID(), now + ProducingLoop.SKIP_TICKS);
            workplace().alwaysSkips().put(0, now + ProducingLoop.SKIP_TICKS);
            settler.getWork().toolNeeds().searched(job, now);
        }

        boolean waitsAnywhere(long now) {
            return workplace().isBlocked(now) || !workplace().skips().isEmpty() || !workplace().alwaysSkips().isEmpty()
                    || !settler.getWork().toolNeeds().searchDue(job, now);
        }

        boolean waitsEverywhere(long now) {
            return workplace().isBlocked(now) && !workplace().skips().isEmpty() && !workplace().alwaysSkips().isEmpty()
                    && !settler.getWork().toolNeeds().searchDue(job, now);
        }

        SettlerMenu menu(Player player) {
            return new SettlerMenu(1, player.getInventory(), settler, SettlerInfo.of(settler));
        }
    }

    private static Scene scene(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = helper.absolutePos(new BlockPos(2, 1, 2));
        TestSettlements.place(helper, jobBlock, ModJobs.LUMBERJACK.get(), 5);
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        SettlerJob job = new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock);
        settler.addJob(job);
        return new Scene(settler, job, jobBlock);
    }

    private static void end(GameTestHelper helper, Scene scene) {
        scene.settler().discard();
        SettlementManager.remove(SettlementData.get(helper.getLevel()), scene.jobBlock(), 0L, SettlementManager.Listener.NONE);
        helper.succeed();
    }

    private static Player playerAt(GameTestHelper helper, Settler settler, double distance) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.snapTo(settler.getX() + distance, settler.getY(), settler.getZ());
        return player;
    }

    /**
     * For each status the button shows for, a click clears the block timer, the skipped requests and patterns and the
     * tool-search wait; for every other status it changes nothing and says so.
     */
    private static void clearsWaits(GameTestHelper helper) {
        Scene scene = scene(helper);
        long now = helper.getLevel().getGameTime();
        Player player = playerAt(helper, scene.settler(), 1.0);
        SettlerMenu menu = scene.menu(player);
        for (JobStatus status : JobStatus.values()) {
            scene.waitOn(status, now);
            helper.assertTrue(scene.waitsEverywhere(now), status + ": the set-up waits on every timer");
            boolean clicked = menu.tryAgain(player, 0);
            if (WAITING.contains(status)) {
                helper.assertTrue(clicked, status + ": the click is taken");
                helper.assertFalse(scene.waitsAnywhere(now), status + ": every timer is cleared");
            } else {
                helper.assertFalse(clicked, status + ": no button, the click is refused");
                helper.assertTrue(scene.waitsEverywhere(now), status + ": the timers stay");
            }
            scene.workplace().unblock();
            scene.workplace().skips().clear();
            scene.workplace().alwaysSkips().clear();
        }
        helper.assertFalse(menu.tryAgain(player, 1), "No such job");
        helper.assertFalse(menu.tryAgain(player, -1), "No such job");
        end(helper, scene);
    }

    /** A player out of reach of the settler cannot click: the timers stay. */
    private static void outOfReach(GameTestHelper helper) {
        Scene scene = scene(helper);
        long now = helper.getLevel().getGameTime();
        Player far = playerAt(helper, scene.settler(), 20.0);
        scene.waitOn(JobStatus.NO_TOOL, now);
        helper.assertFalse(scene.menu(far).tryAgain(far, 0), "Out of reach: refused");
        helper.assertTrue(scene.waitsEverywhere(now), "The timers stay");
        Player near = playerAt(helper, scene.settler(), 7.0);
        helper.assertTrue(scene.menu(near).tryAgain(near, 0), "Within the 8 blocks every settler button uses: taken");
        helper.assertFalse(scene.waitsAnywhere(now), "The timers are cleared");
        end(helper, scene);
    }

    /** After a click the window's snapshot is read from the settler again, and a refresh is due once the settler decided. */
    private static void readsState(GameTestHelper helper) {
        Scene scene = scene(helper);
        ServerLevel level = helper.getLevel();
        long now = level.getGameTime();
        Player player = playerAt(helper, scene.settler(), 1.0);
        scene.waitOn(JobStatus.DROP_OFF_FULL, now);
        SettlerMenu menu = scene.menu(player);
        helper.assertTrue(menu.getInfo().jobStates().getFirst().buffered() == 0, "Opened with an empty buffer");
        scene.settler().getWork().buffer(scene.jobBlock()).add(new ItemStack(Items.COBBLESTONE, 3));
        helper.assertTrue(menu.getInfo().jobStates().getFirst().buffered() == 0, "The window keeps its snapshot until something reads it");
        helper.assertTrue(menu.tryAgain(player, 0), "Clicked");
        helper.assertTrue(menu.getInfo().jobStates().getFirst().buffered() == 3, "The click reads the job state again");
        helper.assertTrue(menu.dueRefresh(now).isEmpty(), "The settler has not decided yet: nothing to send");
        helper.assertTrue(menu.dueRefresh(now + SettlerWork.DECISION_INTERVAL - 1).isEmpty(), "Not before the next decision");
        scene.settler().getWork().buffer(scene.jobBlock()).add(new ItemStack(Items.COBBLESTONE, 2));
        var refresh = menu.dueRefresh(now + SettlerWork.DECISION_INTERVAL + 1);
        helper.assertTrue(refresh.isPresent() && refresh.get().info().jobStates().getFirst().buffered() == 5,
                "After the next decision the window gets the state the settler has by then: " + refresh);
        helper.assertTrue(menu.getInfo().jobStates().getFirst().buffered() == 5, "The server menu holds the same snapshot");
        helper.assertTrue(menu.dueRefresh(now + 10 * SettlerWork.DECISION_INTERVAL).isEmpty(), "One refresh per click");
        end(helper, scene);
    }

    /**
     * A Lumberjack without an axe waits ("No tool"); a tool put into the collect inventory is not found while the waits
     * run, and found at the next decision after the click.
     */
    private static void findsTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        TestSettlements.setBlock(helper, relative, ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(relative);
        helper.setBlock(new BlockPos(7, 1, 7), Blocks.CAMPFIRE);
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(helper.absolutePos(new BlockPos(3, 1, 3)).getX() + 0.5, helper.absolutePos(new BlockPos(3, 1, 3)).getY(),
                helper.absolutePos(new BlockPos(3, 1, 3)).getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        helper.assertTrue(SettlementWorld.assign(level, jobBlock, settler, ModJobs.LUMBERJACK.get()) == JobAssignments.AssignResult.OK,
                "Lumberjack assigned");

        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_TOOL, "No tool: " + settler.getWork().status(jobBlock));
        ((Container) level.getBlockEntity(jobBlock)).setItem(0, new ItemStack(Items.STONE_AXE));
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_TOOL,
                "The waits hold: the axe is not looked for yet: " + settler.getWork().status(jobBlock));

        Player player = playerAt(helper, settler, 1.0);
        helper.assertTrue(new SettlerMenu(1, player.getInventory(), settler, SettlerInfo.of(settler)).tryAgain(player, 0), "Clicked");
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.FETCHING_TOOL,
                "The next decision finds the axe in the collect inventory: " + settler.getWork().status(jobBlock));

        settler.discard();
        TestAssignments.unassign(data, jobBlock);
        helper.succeed();
    }

    /** The click payload survives encoding and decoding; the refresh the server sends back does as well. */
    private static void payloadCodec(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerMenu.TryAgain click = new SettlerMenu.TryAgain(7, 2);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        SettlerMenu.TryAgain.STREAM_CODEC.encode(buffer, click);
        helper.assertTrue(SettlerMenu.TryAgain.STREAM_CODEC.decode(buffer).equals(click), "The click decodes to what was sent");
        helper.assertTrue(buffer.readableBytes() == 0, "Nothing is left over");

        Scene scene = scene(helper);
        SettlerMenu.Refresh refresh = new SettlerMenu.Refresh(7, SettlerInfo.of(scene.settler()));
        RegistryFriendlyByteBuf first = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        SettlerMenu.Refresh.STREAM_CODEC.encode(first, refresh);
        byte[] sent = ByteBufUtil.getBytes(first);
        SettlerMenu.Refresh decoded = SettlerMenu.Refresh.STREAM_CODEC.decode(first);
        RegistryFriendlyByteBuf second = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        SettlerMenu.Refresh.STREAM_CODEC.encode(second, decoded);
        helper.assertTrue(decoded.containerId() == 7 && java.util.Arrays.equals(sent, ByteBufUtil.getBytes(second)),
                "The refresh decodes to what was sent");
        end(helper, scene);
    }
}
