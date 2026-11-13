package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Holds;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the automatic tool supply (#242, vault {@code Systems/Needs.md}): a settler without a tool of the
 * required tier asks the settlement for one; the request is reserved for it and ends with the need.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ToolSupplyGameTests {

    /** Skill 45 requires an iron tool (tier 2) and allows diamond. */
    private static final int IRON_SKILL = 45;
    private static final int BLOCK_LEVEL = 5;
    /** Longer than the retry of a blocked job step (600 ticks). */
    private static final int WAIT = 650;
    /** Work time of the previous workday each catch-up pass covers. */
    private static final long WORK_TICKS = 4000L;
    /** Most passes a producer needs for the pickaxe: post, make, then the parent. */
    private static final int MAX_PASSES = 4;

    private ToolSupplyGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "tool_supply_request", ToolSupplyGameTests::request);
            register(helper, "tool_supply_skipped", ToolSupplyGameTests::skipped);
            register(helper, "tool_supply_end", ToolSupplyGameTests::endOfNeed);
            register(helper, "tool_supply_delivery", ToolSupplyGameTests::delivery);
            register(helper, "tool_supply_chain", ToolSupplyGameTests::chain);
            register(helper, "tool_supply_clerk_collect", ToolSupplyGameTests::clerkCollect);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- layout

    /** A working Clerk whose storage holds the items. */
    private static BlockPos clerkWith(GameTestHelper helper, List<ItemStack> stock) {
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int i = 0; i < stock.size(); i++) {
            chest.setItem(i, stock.get(i).copy());
        }
        return clerk;
    }

    /** A Smith with a checked pattern for the iron pickaxe: the settlement can make it. */
    private static Settler smith(GameTestHelper helper, SettlementData data, Settlement settlement, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos smithy = SmithGameTests.smithy(helper, relative);
        Settler smith = settler(helper, data, settlement, smithy.offset(1, 0, 2), "Sigrun");
        helper.assertTrue(TestAssignments.assign(data, smithy, smith.getUUID(), "Sigrun", settlement.id(), List.of(), SmithGameTests.smith())
                == JobAssignments.AssignResult.OK, "Smith assigned");
        smith.addJob(new SettlerJob(SmithGameTests.smith(), smithy));
        helper.assertTrue(Patterns.setOutput(level, smithy, SmithGameTests.smith(), 0, Items.IRON_PICKAXE).orElseThrow().checked(), "Pickaxe checked");
        return smith;
    }

    /** A settler working a Job at a JobBlock. */
    private static Settler worker(GameTestHelper helper, SettlementData data, Settlement settlement, BlockPos jobBlock, BlockPos at, String name, Job job) {
        Settler settler = settler(helper, data, settlement, at, name);
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), name, settlement.id(), List.of(), job) == JobAssignments.AssignResult.OK,
                name + " assigned");
        settler.addJob(new SettlerJob(job, jobBlock));
        return settler;
    }

    /** Catches up some hours of the previous workday. */
    private static void catchUp(ServerLevel level, Settler settler) {
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;
        settler.getWork().setCatchUp(dayStart - 24000L, dayStart - 24000L + WORK_TICKS);
        CatchUp.runNow(level, List.of(settler));
    }

    private static int free(ServerLevel level, BlockPos clerk, Item item) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(item));
    }

    private static boolean onBelt(Settler settler, Item item) {
        for (int i = 0; i < Settler.BELT_SIZE; i++) {
            if (settler.getInventory().getItem(Settler.BELT_START + i).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static Settler settler(GameTestHelper helper, SettlementData data, Settlement settlement, BlockPos at, String name) {
        Settler settler = ClerkGameTests.settler(helper, at, data, settlement);
        settler.setCustomName(Component.literal(name));
        settler.getFood().setMorningDay(Math.floorDiv(helper.getLevel().getOverworldClockTime(), 24000L));
        return settler;
    }

    /** A Miner JobBlock of level 5 (no block in the world) with a settler of the given skill and a stone pickaxe on its belt. */
    private static Settler miner(GameTestHelper helper, BlockPos relative, int skill) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = helper.absolutePos(relative);
        TestSettlements.setBlock(helper, relative, ModBlocks.MINER_BLOCK.get());
        // The block entity keeps the level too and syncs it to the settlement: set both.
        ((JobBlockEntity) level.getBlockEntity(jobBlock)).setJobLevel(BLOCK_LEVEL);
        SettlementManager.setLevel(SettlementData.get(level), jobBlock, BLOCK_LEVEL, SettlementManager.Listener.NONE);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getFood().setLevel(20);
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        settler.getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(skill, 0));
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_PICKAXE));
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Miner", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Miner assigned");
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), jobBlock));
        return settler;
    }

    private static BlockPos jobBlock(Settler settler) {
        return settler.getJobs().getFirst().jobBlock();
    }

    private static Settlement settlement(GameTestHelper helper, BlockPos clerk) {
        return SettlementData.get(helper.getLevel()).settlementAt(clerk).orElseThrow();
    }

    /** What the settler's tool need asks the settlement for: the requests reserved for it. */
    private static List<Request> requestsOf(Settlement settlement, Settler settler) {
        return settlement.requests().stream().filter(request -> request.reservedFor().filter(ReservedFor.settler(settler.getUUID())::equals).isPresent())
                .toList();
    }

    private static void ask(GameTestHelper helper, Settler settler) {
        // A fresh tool need has no waiting hour: the search runs at once.
        settler.getWork().restoreToolNeeds(List.of());
        settler.getWork().decideNow(helper.getLevel());
    }

    private static void cleanUp(GameTestHelper helper, Settler... settlers) {
        SettlementData data = SettlementData.get(helper.getLevel());
        for (Settler settler : settlers) {
            for (SettlerJob job : settler.getJobs()) {
                SettlementManager.remove(data, job.jobBlock(), 0L, SettlementManager.Listener.NONE);
            }
            settler.discard();
        }
    }

    private static void finish(GameTestHelper helper, Settler... settlers) {
        cleanUp(helper, settlers);
        helper.succeed();
    }

    // ---------------------------------------------------------------- tests

    /**
     * A Miner of skill 45 without an iron pickaxe asks for one at the Home Clerk of its JobBlock: one request, reserved
     * for it; another check of the same need posts no second one; two Miners with the same need have two requests.
     */
    private static void request(GameTestHelper helper) {
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = settlement(helper, clerk);
        Settler smith = smith(helper, data, settlement, new BlockPos(1, 1, 5));
        Settler first = miner(helper, new BlockPos(1, 1, 13), IRON_SKILL);
        Settler second = miner(helper, new BlockPos(1, 1, 17), IRON_SKILL);
        helper.assertTrue(JobBlockSite.blockAt(helper.getLevel(), jobBlock(first)).orElseThrow().homeClerk().equals(Optional.of(clerk)), "The Clerk is home");

        ask(helper, first);
        List<Request> asked = requestsOf(settlement, first);
        helper.assertTrue(asked.size() == 1, "One request for the first Miner: " + settlement.requests());
        Request pickaxe = asked.getFirst();
        helper.assertTrue(pickaxe.item() == Items.IRON_PICKAXE && pickaxe.amount() == 1 && pickaxe.target().equals(Optional.of(clerk))
                && pickaxe.parent().isEmpty() && pickaxe.rule().isEmpty(), "An iron pickaxe for the Home Clerk: " + pickaxe);

        // The job step of a Miner without a work place waits half a minute before it plans again.
        helper.startSequence().thenIdle(WAIT).thenExecute(() -> {
            ask(helper, first);
            helper.assertTrue(requestsOf(settlement, first).size() == 1, "The same need posts no second request: " + settlement.requests());
            ask(helper, second);
            helper.assertTrue(requestsOf(settlement, second).size() == 1 && settlement.requests().size() == 2,
                    "The second Miner has a request of its own: " + settlement.requests());
            cleanUp(helper, smith, first, second);
        }).thenSucceed();
    }

    /**
     * No request without the need or against the rules: skill 15 requires nothing, skill 70 requires an enchanted tool
     * (stays manual), a Clerk with the supply switched off, and a belt tool that meets the requirement.
     */
    private static void skipped(GameTestHelper helper) {
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = settlement(helper, clerk);
        Settler smith = smith(helper, data, settlement, new BlockPos(1, 1, 5));
        Settler novice = miner(helper, new BlockPos(1, 1, 13), 15);
        Settler master = miner(helper, new BlockPos(1, 1, 17), 70);
        Settler met = miner(helper, new BlockPos(1, 1, 21), IRON_SKILL);
        met.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.IRON_PICKAXE));
        Settler switchedOff = miner(helper, new BlockPos(1, 1, 25), IRON_SKILL);

        ask(helper, novice);
        ask(helper, master);
        ask(helper, met);
        helper.assertTrue(settlement.requests().isEmpty(), "Skill 15, an enchanted tier and a met requirement ask for nothing: " + settlement.requests());

        helper.assertFalse(JobAssignments.setAutoToolSupply(data, clerk, false), "The Clerk switches the supply off");
        ask(helper, switchedOff);
        helper.assertTrue(settlement.requests().isEmpty(), "No request while the Clerk's supply is off: " + settlement.requests());
        JobAssignments.setAutoToolSupply(data, clerk, true);
        Settler switchedOn = miner(helper, new BlockPos(1, 1, 29), IRON_SKILL);
        ask(helper, switchedOn);
        helper.assertTrue(requestsOf(settlement, switchedOn).size() == 1 && settlement.requests().size() == 1,
                "On again: the request is posted: " + settlement.requests());
        finish(helper, smith, novice, master, met, switchedOff, switchedOn);
    }

    /**
     * The request ends with the need: when the Miner's job goes away, when the Miner is gone; a request a producer is
     * crafting loses its reservation, so the finished tool is plain stock.
     */
    private static void endOfNeed(GameTestHelper helper) {
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = settlement(helper, clerk);
        Settler smith = smith(helper, data, settlement, new BlockPos(1, 1, 5));
        Settler leaves = miner(helper, new BlockPos(1, 1, 13), IRON_SKILL);
        Settler dies = miner(helper, new BlockPos(1, 1, 17), IRON_SKILL);
        Settler crafted = miner(helper, new BlockPos(1, 1, 21), IRON_SKILL);
        ask(helper, leaves);
        ask(helper, dies);
        ask(helper, crafted);
        helper.assertTrue(settlement.requests().size() == 3, "Three requests: " + settlement.requests());

        leaves.removeJob(jobBlock(leaves));
        helper.assertTrue(requestsOf(settlement, leaves).isEmpty() && settlement.requests().size() == 2, "The job went away: " + settlement.requests());
        dies.discard();
        helper.assertTrue(settlement.requests().size() == 1 && requestsOf(settlement, dies).isEmpty(), "The settler is gone: " + settlement.requests());

        // The Smith crafts the last one; the Miner's job then goes away.
        UUID id = requestsOf(settlement, crafted).getFirst().id();
        helper.assertTrue(Requests.claim(data, settlement, id, smith.getUUID(), "Sigrun", 1) == 1, "Claimed");
        crafted.removeJob(jobBlock(crafted));
        Request left = Requests.get(settlement, id).orElseThrow();
        helper.assertTrue(left.reservedFor().isEmpty() && left.amount() == 0 && left.claims().size() == 1, "Crafting goes on, nothing is reserved: " + left);
        Requests.finish(data, settlement, id, smith.getUUID());
        helper.assertTrue(Holds.heldFor(settlement, ReservedFor.settler(crafted.getUUID())).isEmpty() && settlement.requests().isEmpty(),
                "The finished tool is plain stock: " + Holds.heldFor(settlement, ReservedFor.settler(crafted.getUUID())));
        finish(helper, smith, crafted);
    }

    /**
     * A tool made for the Miner waits in the Clerk for it, nobody else sees it. The collect inventory is a chest, so an
     * input delivery carries it: the hold goes to the Delivery task, a Deliveryman takes the tool to the chest, the hold
     * ends on arrival and the Miner asks for nothing more.
     */
    private static void delivery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.IRON_PICKAXE)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler miner = miner(helper, new BlockPos(1, 1, 13), IRON_SKILL);
        helper.setBlock(new BlockPos(3, 1, 13), Blocks.CHEST);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 13));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock(miner), chest, false, true) == JobAssignments.PointResult.COLLECT,
                "The chest is the collect inventory");
        Settler dora = settler(helper, data, settlement, new BlockPos(2, 1, 3), "Dora");
        dora.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 16));
        ResultDeliveryGameTests.deliveryman(helper, clerk, dora.getUUID());
        dora.addJob(new SettlerJob(ModJobs.DELIVERYMAN.get(), clerk));
        ReservedFor me = ReservedFor.settler(miner.getUUID());
        Holds.hold(settlement, clerk, me, Items.IRON_PICKAXE, 1);
        helper.assertTrue(free(level, clerk, Items.IRON_PICKAXE) == 0, "Nobody else sees the tool held for the Miner");

        ask(helper, miner);
        List<DeliveryTask> inputs = settlement.deliveries().stream().filter(task -> task.input().isPresent()).toList();
        helper.assertTrue(inputs.size() == 1 && inputs.getFirst().source().equals(clerk) && inputs.getFirst().input().orElseThrow().to().equals(chest)
                && inputs.getFirst().input().orElseThrow().items().equals(Map.of(Items.IRON_PICKAXE, 1)),
                "An input delivery from the Clerk into the chest: " + inputs);
        helper.assertTrue(settlement.requests().isEmpty(), "The tool is made already, so no request: " + settlement.requests());
        ReservedFor task = ReservedFor.delivery(inputs.getFirst().id());
        helper.assertTrue(Holds.heldFor(settlement, me).isEmpty() && !Holds.heldFor(settlement, task).isEmpty(), "The Delivery task holds it now");
        helper.assertTrue(free(level, clerk, Items.IRON_PICKAXE) == 0, "Still nobody else sees it");

        catchUp(level, dora);
        helper.assertTrue(((Container) level.getBlockEntity(chest)).hasAnyMatching(stack -> stack.is(Items.IRON_PICKAXE)),
                "The tool is in the chest: " + settlement.deliveries());
        helper.assertTrue(settlement.deliveries().stream().noneMatch(found -> found.input().isPresent()), "The delivery is done: " + settlement.deliveries());
        helper.assertTrue(Holds.heldFor(settlement, task).isEmpty() && Holds.held(settlement, clerk).isEmpty(), "The reservation ended on arrival");
        helper.assertTrue(settlement.requests().isEmpty(), "No further request");
        cleanUp(helper, miner, dora);
        TestAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /**
     * The example of the issue: a Miner needs an iron pickaxe, the Clerk holds only iron ingots and a log. The Smith asks
     * for sticks, the Craftsperson makes them from the log, the Smith makes the pickaxe; it waits in the Clerk for the
     * Miner, nobody else sees it, a Deliveryman carries it into the Miner's collect chest and the Miner swaps to it.
     */
    private static void chain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // The Miner walks to its chest: solid ground and loaded chunks.
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(8, 0, 20));
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.OAK_LOG, 1)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        BlockPos smithy = SmithGameTests.smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        BlockPos workshop = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 9));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 9));
        Settler smith = worker(helper, data, settlement, smithy, new BlockPos(2, 1, 7), "Sigrun", SmithGameTests.smith());
        Settler crafter = worker(helper, data, settlement, workshop, new BlockPos(2, 1, 11), "Carl", CraftspersonGameTests.craftJob());
        helper.assertTrue(Patterns.setOutput(level, smithy, SmithGameTests.smith(), 0, Items.IRON_PICKAXE).orElseThrow().checked(), "Pickaxe checked");
        Patterns.setOutput(level, smithy, SmithGameTests.smith(), 1, Items.STICK);
        CraftspersonGameTests.define(helper, workshop, 0, Items.STICK);
        CraftspersonGameTests.define(helper, workshop, 1, Items.OAK_PLANKS);

        Settler miner = miner(helper, new BlockPos(1, 1, 13), IRON_SKILL);
        miner.setNoAi(false);
        helper.setBlock(new BlockPos(3, 1, 13), Blocks.CHEST);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 13));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock(miner), chest, false, true) == JobAssignments.PointResult.COLLECT,
                "The chest is the collect inventory");
        Settler dora = settler(helper, data, settlement, new BlockPos(2, 1, 3), "Dora");
        dora.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 16));
        ResultDeliveryGameTests.deliveryman(helper, clerk, dora.getUUID());
        dora.addJob(new SettlerJob(ModJobs.DELIVERYMAN.get(), clerk));
        ReservedFor me = ReservedFor.settler(miner.getUUID());

        ask(helper, miner);
        helper.assertTrue(requestsOf(settlement, miner).size() == 1, "The Miner asked: " + settlement.requests());
        UUID pickaxe = requestsOf(settlement, miner).getFirst().id();

        catchUp(level, smith);
        catchUp(level, crafter);
        for (int pass = 0; pass < MAX_PASSES && Requests.get(settlement, pickaxe).isPresent(); pass++) {
            catchUp(level, smith);
        }
        helper.assertTrue(Requests.get(settlement, pickaxe).isEmpty(), "The pickaxe is made (Smith status " + smith.getWork().status(smithy) + "): "
                + settlement.requests());
        helper.assertTrue(Holds.heldFor(settlement, me).getOrDefault(clerk, Map.of()).getOrDefault(Items.IRON_PICKAXE, 0) == 1,
                "The pickaxe waits for the Miner: " + Holds.heldFor(settlement, me));
        helper.assertTrue(free(level, clerk, Items.IRON_PICKAXE) == 0, "Nobody else sees it");

        helper.startSequence()
                .thenIdle(WAIT)
                .thenExecute(() -> {
                    // The Miner looks again: an input delivery brings the tool to the chest, a Deliveryman carries it.
                    ask(helper, miner);
                    helper.assertTrue(settlement.deliveries().stream().anyMatch(task -> task.input().isPresent()), "An input delivery was posted: "
                            + settlement.deliveries());
                    catchUp(level, dora);
                    helper.assertTrue(((Container) level.getBlockEntity(chest)).hasAnyMatching(stack -> stack.is(Items.IRON_PICKAXE)),
                            "The pickaxe is in the chest: " + settlement.deliveries());
                    helper.assertTrue(Holds.held(settlement, clerk).isEmpty(), "Nothing is held any more: " + Holds.held(settlement, clerk));
                })
                .thenIdle(WAIT)
                .thenExecute(() -> ask(helper, miner))
                .thenWaitUntil(() -> helper.assertTrue(onBelt(miner, Items.IRON_PICKAXE), "The Miner has not swapped yet (status "
                        + miner.getWork().status(jobBlock(miner)) + ", chest " + ((Container) level.getBlockEntity(chest)).getItem(0) + ", belt "
                        + miner.getInventory().getItem(Settler.BELT_START) + ", at " + miner.blockPosition() + ", chest at " + chest + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(onBelt(miner, Items.STONE_PICKAXE) && settlement.requests().isEmpty(), "A free belt keeps the stone pickaxe, no request is left");
                    cleanUp(helper, smith, crafter, miner, dora);
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                    TestAssignments.unassign(data, smithy);
                    TestAssignments.unassign(data, workshop);
                    TestAssignments.unassign(data, clerk);
                })
                .thenSucceed();
    }

    /**
     * With the Clerk as collect inventory nothing is carried: the tool made for the Miner is released where it is, so
     * the Miner's next search finds it, and no delivery is posted.
     */
    private static void clerkCollect(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.IRON_PICKAXE)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler miner = miner(helper, new BlockPos(1, 1, 13), IRON_SKILL);
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock(miner), clerk, false, true) == JobAssignments.PointResult.COLLECT,
                "The Clerk is the collect point");
        ReservedFor me = ReservedFor.settler(miner.getUUID());
        Holds.hold(settlement, clerk, me, Items.IRON_PICKAXE, 1);
        helper.assertTrue(free(level, clerk, Items.IRON_PICKAXE) == 0, "Nobody else sees the tool held for the Miner");

        ask(helper, miner);
        helper.assertTrue(Holds.heldFor(settlement, me).isEmpty() && free(level, clerk, Items.IRON_PICKAXE) == 1, "The tool is free stock at the collect point");
        helper.assertTrue(settlement.deliveries().isEmpty() && settlement.requests().isEmpty(), "Nothing to carry, nothing to ask: " + settlement.deliveries());
        finish(helper, miner);
    }
}
