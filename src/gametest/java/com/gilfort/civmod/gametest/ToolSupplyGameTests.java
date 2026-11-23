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
import com.gilfort.civmod.job.ToolTiers;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.ItemKey;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ToolMaterial;
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

    /** A pickaxe of another "mod" with the iron mining level, in {@code #minecraft:pickaxes} by the test datapack. */
    private static final Identifier MODDED_PICKAXE = CivMod.id("test_modded_pickaxe");

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
            register(helper, "tool_supply_self_mode", ToolSupplyGameTests::selfMode);
            register(helper, "tool_supply_nobody_makes", ToolSupplyGameTests::nobodyMakes);
            register(helper, "tool_supply_skill_one", ToolSupplyGameTests::skillOne);
            register(helper, "tool_supply_rod_and_shears", ToolSupplyGameTests::rodAndShears);
            register(helper, "tool_supply_from_store", ToolSupplyGameTests::fromStore);
            register(helper, "tool_supply_highest_tier", ToolSupplyGameTests::highestTier);
            register(helper, "tool_supply_steps_down", ToolSupplyGameTests::stepsDown);
            register(helper, "tool_supply_no_step_down_with_ore", ToolSupplyGameTests::noStepDownWithOre);
            register(helper, "tool_supply_later_tool_changes_nothing", ToolSupplyGameTests::laterToolChangesNothing);
            register(helper, "tool_supply_dead_end_missing_input", ToolSupplyGameTests::deadEndMissingInput);
            register(helper, "tool_supply_modded_tool", ToolSupplyGameTests::moddedTool);
            register(helper, "tool_supply_golden_in_store", ToolSupplyGameTests::goldenInStore);
            register(helper, "tool_supply_steps_down_to_wooden", ToolSupplyGameTests::stepsDownToWooden);
        });
        event.register(Registries.ITEM, helper -> helper.register(MODDED_PICKAXE, new Item(new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, MODDED_PICKAXE)).pickaxe(ToolMaterial.IRON, 1.0F, -2.8F))));
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
        helper.assertTrue(Patterns.setOutput(level, smithy, SmithGameTests.smith(), 0, ItemKey.of(Items.IRON_PICKAXE)).orElseThrow().checked(), "Pickaxe checked");
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

    /** A JobBlock of level 5 without a block in the world, with a fed settler of the given skill that holds the given tool, if any. */
    private static Settler toolWorker(GameTestHelper helper, BlockPos relative, Job job, int skill, ItemStack belt) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = helper.absolutePos(relative);
        TestSettlements.place(helper, jobBlock, job, BLOCK_LEVEL);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getFood().setLevel(20);
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        settler.getSkills().set(job, new SettlerSkills.Skill(skill, 0));
        settler.getInventory().setItem(Settler.BELT_START, belt);
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Worker", settlement.id(), List.of(), job) == JobAssignments.AssignResult.OK,
                "Worker assigned");
        settler.addJob(new SettlerJob(job, jobBlock));
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
        helper.assertTrue(pickaxe.item().is(Items.IRON_PICKAXE) && pickaxe.amount() == 1 && pickaxe.target().equals(Optional.of(clerk))
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

        helper.assertFalse(JobAssignments.setAutoSupply(data, clerk, false), "The Clerk switches the supply off");
        ask(helper, switchedOff);
        helper.assertTrue(settlement.requests().isEmpty(), "No request while the Clerk's supply is off: " + settlement.requests());
        JobAssignments.setAutoSupply(data, clerk, true);
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
        SubRequestGameTests.finishInFull(data, settlement, id, smith.getUUID());
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
        Holds.hold(settlement, clerk, me, ItemKey.of(Items.IRON_PICKAXE), 1);
        helper.assertTrue(free(level, clerk, Items.IRON_PICKAXE) == 0, "Nobody else sees the tool held for the Miner");

        ask(helper, miner);
        List<DeliveryTask> inputs = settlement.deliveries().stream().filter(task -> task.input().isPresent()).toList();
        helper.assertTrue(inputs.size() == 1 && inputs.getFirst().source().equals(clerk) && inputs.getFirst().input().orElseThrow().to().equals(chest)
                && inputs.getFirst().input().orElseThrow().items().equals(Map.of(ItemKey.of(Items.IRON_PICKAXE), 1)),
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
     * The example of the issue: a Miner needs an iron pickaxe, the Clerk holds only raw iron, coal and a log. The Smith asks
     * for iron ingots and sticks, the Smelter makes the ingots, the Craftsperson makes the sticks from the log, the Smith makes the pickaxe; it waits in the Clerk for the
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
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.OAK_LOG, 1), new ItemStack(Items.COAL, 2)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        BlockPos smithy = SmithGameTests.smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        BlockPos workshop = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 9));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 9));
        Settler smith = worker(helper, data, settlement, smithy, new BlockPos(2, 1, 7), "Sigrun", SmithGameTests.smith());
        Settler crafter = worker(helper, data, settlement, workshop, new BlockPos(2, 1, 11), "Carl", CraftspersonGameTests.craftJob());
        BlockPos smelterBlock = SmelterGameTests.smelterBlock(helper, new BlockPos(6, 1, 17));
        helper.setBlock(new BlockPos(8, 1, 17), Blocks.FURNACE);
        SmelterGameTests.ironPattern(helper, smelterBlock, 0);
        Settler smelter = worker(helper, data, settlement, smelterBlock, new BlockPos(7, 1, 18), "Ida", SmelterGameTests.smelter());
        helper.assertTrue(Patterns.setOutput(level, smithy, SmithGameTests.smith(), 0, ItemKey.of(Items.IRON_PICKAXE)).orElseThrow().checked(), "Pickaxe checked");
        Patterns.setOutput(level, smithy, SmithGameTests.smith(), 1, ItemKey.of(Items.STICK));
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
        catchUp(level, smelter);
        for (int pass = 0; pass < MAX_PASSES && Requests.get(settlement, pickaxe).isPresent(); pass++) {
            catchUp(level, smelter);
            catchUp(level, smith);
        }
        helper.assertTrue(Requests.get(settlement, pickaxe).isEmpty(), "The pickaxe is made (Smith status " + smith.getWork().status(smithy) + "): "
                + settlement.requests());
        helper.assertTrue(Holds.heldFor(settlement, me).getOrDefault(clerk, Map.of()).getOrDefault(ItemKey.of(Items.IRON_PICKAXE), 0) == 1,
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
                    helper.assertTrue(onBelt(miner, Items.STONE_PICKAXE) && requestsOf(settlement, miner).isEmpty(), "A free belt keeps the stone pickaxe, no request is left: " + settlement.requests());
                    cleanUp(helper, smith, crafter, smelter, miner, dora);
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                    TestAssignments.unassign(data, smithy);
                    TestAssignments.unassign(data, workshop);
                    TestAssignments.unassign(data, smelterBlock);
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
        Holds.hold(settlement, clerk, me, ItemKey.of(Items.IRON_PICKAXE), 1);
        helper.assertTrue(free(level, clerk, Items.IRON_PICKAXE) == 0, "Nobody else sees the tool held for the Miner");

        ask(helper, miner);
        helper.assertTrue(Holds.heldFor(settlement, me).isEmpty() && free(level, clerk, Items.IRON_PICKAXE) == 1, "The tool is free stock at the collect point");
        helper.assertTrue(settlement.deliveries().isEmpty() && settlement.requests().isEmpty(), "Nothing to carry, nothing to ask: " + settlement.deliveries());
        finish(helper, miner);
    }

    /**
     * Without a Deliveryman the settler carries the tool over itself: the Clerk is not the collect inventory (a chest is),
     * no delivery is posted, and on the next search the tool is in the chest and no longer held.
     */
    private static void selfMode(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.IRON_PICKAXE)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler miner = miner(helper, new BlockPos(1, 1, 13), IRON_SKILL);
        helper.setBlock(new BlockPos(3, 1, 13), Blocks.CHEST);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 13));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock(miner), chest, false, true) == JobAssignments.PointResult.COLLECT,
                "The chest is the collect inventory");
        ReservedFor me = ReservedFor.settler(miner.getUUID());
        Holds.hold(settlement, clerk, me, ItemKey.of(Items.IRON_PICKAXE), 1);

        ask(helper, miner);
        helper.assertTrue(settlement.deliveries().stream().noneMatch(task -> task.input().isPresent()), "No delivery is posted: " + settlement.deliveries());
        helper.assertTrue(((Container) level.getBlockEntity(chest)).hasAnyMatching(stack -> stack.is(Items.IRON_PICKAXE)), "The tool is in the chest");
        helper.assertTrue(Holds.heldFor(settlement, me).isEmpty() && free(level, clerk, Items.IRON_PICKAXE) == 0, "Nothing is held and nothing is left in the Clerk");
        helper.assertTrue(settlement.requests().isEmpty(), "No request");
        finish(helper, miner);
    }

    /**
     * No producer can make the tool: the request is posted anyway and stays open (no timeout), so the Tasks tab can say
     * what is missing.
     */
    private static void nobodyMakes(GameTestHelper helper) {
        BlockPos clerk = clerkWith(helper, List.of());
        Settlement settlement = settlement(helper, clerk);
        Settler miner = miner(helper, new BlockPos(1, 1, 13), IRON_SKILL);
        ask(helper, miner);
        List<Request> asked = requestsOf(settlement, miner);
        helper.assertTrue(asked.size() == 1 && asked.getFirst().target().equals(Optional.of(clerk)) && asked.getFirst().amount() == 1,
                "A request is posted although nobody makes it: " + settlement.requests());
        helper.assertTrue(asked.getFirst().item().is(Items.DIAMOND_PICKAXE), "No pattern anywhere: the highest allowed tier is asked: " + asked);
        finish(helper, miner);
    }

    /** A Miner of skill 1 without a tool asks for a stone pickaxe (its highest allowed tier, no pattern needed); the first band used to post nothing. */
    private static void skillOne(GameTestHelper helper) {
        BlockPos clerk = clerkWith(helper, List.of());
        Settlement settlement = settlement(helper, clerk);
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), 1, ItemStack.EMPTY);
        ask(helper, miner);
        List<Request> asked = requestsOf(settlement, miner);
        helper.assertTrue(asked.size() == 1 && asked.getFirst().item().is(Items.STONE_PICKAXE) && asked.getFirst().target().equals(Optional.of(clerk)),
                "A skill-1 Miner asks for a stone pickaxe: " + settlement.requests());
        finish(helper, miner);
    }

    /** The fishing rod and the shears have no tier and are ordered like a pickaxe, from skill 1. */
    private static void rodAndShears(GameTestHelper helper) {
        BlockPos clerk = clerkWith(helper, List.of());
        Settlement settlement = settlement(helper, clerk);
        Settler fisherman = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.FISHERMAN.get(), 1, ItemStack.EMPTY);
        Settler keeper = toolWorker(helper, new BlockPos(1, 1, 17), ModJobs.BEEKEEPER.get(), 1, ItemStack.EMPTY);
        ask(helper, fisherman);
        ask(helper, keeper);
        // A settler without a Job may be put on the other JobBlock, so only its own tool counts.
        helper.assertTrue(requestsOf(settlement, fisherman).stream().filter(request -> request.item().is(Items.FISHING_ROD)).count() == 1,
                "The Fisherman asks for a fishing rod: " + settlement.requests());
        helper.assertTrue(requestsOf(settlement, keeper).stream().filter(request -> request.item().is(Items.SHEARS)).count() == 1,
                "The Beekeeper asks for shears: " + settlement.requests());
        finish(helper, fisherman, keeper);
    }

    /**
     * A fitting tool lying in the Clerk store is reserved and carried to the collect inventory; nothing is ordered. A
     * tool above the tier the skill allows (diamond at skill 1) does not count.
     */
    private static void fromStore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.STONE_PICKAXE)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), 1, ItemStack.EMPTY);
        helper.setBlock(new BlockPos(3, 1, 13), Blocks.CHEST);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 13));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock(miner), chest, false, true) == JobAssignments.PointResult.COLLECT,
                "The chest is the collect inventory");
        ask(helper, miner);
        helper.assertTrue(settlement.requests().isEmpty(), "A fitting tool is in the store, nothing is ordered: " + settlement.requests());
        helper.assertTrue(((Container) level.getBlockEntity(chest)).hasAnyMatching(stack -> stack.is(Items.STONE_PICKAXE)), "The stone pickaxe is in the chest");
        helper.assertFalse(((Container) level.getBlockEntity(chest)).hasAnyMatching(stack -> stack.is(Items.DIAMOND_PICKAXE)), "The diamond pickaxe stays");
        helper.assertTrue(free(level, clerk, Items.DIAMOND_PICKAXE) == 1 && free(level, clerk, Items.STONE_PICKAXE) == 0, "Nothing else left the store");
        finish(helper, miner);
    }

    // ---------------------------------------------------------------- tiers and dead ends

    /** A Stonemason with a checked pattern for the stone pickaxe: the settlement can make it. */
    private static Settler stonemason(GameTestHelper helper, SettlementData data, Settlement settlement, BlockPos relative) {
        BlockPos masonry = StonemasonGameTests.masonry(helper, relative);
        CraftspersonGameTests.placeTable(helper, relative.offset(2, 0, 0));
        Settler mason = worker(helper, data, settlement, masonry, masonry.offset(1, 0, 2), "Steffi", StonemasonGameTests.stonemason());
        helper.assertTrue(Patterns.setOutput(helper.getLevel(), masonry, StonemasonGameTests.stonemason(), 0, ItemKey.of(Items.STONE_PICKAXE)).orElseThrow().checked(),
                "Stone pickaxe checked");
        helper.assertTrue(com.gilfort.civmod.logistics.Producers.canMake(helper.getLevel(), settlement, ItemKey.of(Items.STONE_PICKAXE)), "The Stonemason can make it");
        return mason;
    }

    private static boolean open(Request request) {
        return request.amount() + request.crafting() > 0;
    }

    /** The open requests reserved for the settler. */
    private static List<Request> openFor(Settlement settlement, Settler settler) {
        return requestsOf(settlement, settler).stream().filter(ToolSupplyGameTests::open).toList();
    }

    /** A Miner of skill 45 may use iron and diamond; with patterns for the stone and the iron pickaxe it asks for the iron one. */
    private static void highestTier(GameTestHelper helper) {
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = settlement(helper, clerk);
        Settler smith = smith(helper, data, settlement, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler mason = stonemason(helper, data, settlement, new BlockPos(1, 1, 9));
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), IRON_SKILL, ItemStack.EMPTY);
        ask(helper, miner);
        List<Request> asked = openFor(settlement, miner);
        helper.assertTrue(asked.size() == 1 && asked.getFirst().item().is(Items.IRON_PICKAXE), "The iron pickaxe is asked for first: " + settlement.requests());
        cleanUp(helper, smith, mason, miner);
        helper.succeed();
    }

    /**
     * Ore missing everywhere: the Smelter has a pattern for the ingots but nothing to smelt, nobody can supply the ore.
     * After the hourly check the iron pickaxe is withdrawn and the stone pickaxe asked for instead; the Miner never moves
     * up again.
     */
    private static void stepsDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.STICK, 4)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler smith = smith(helper, data, settlement, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler mason = stonemason(helper, data, settlement, new BlockPos(1, 1, 9));
        BlockPos smelterBlock = SmelterGameTests.smelterBlock(helper, new BlockPos(6, 1, 17));
        helper.setBlock(new BlockPos(8, 1, 17), Blocks.FURNACE);
        SmelterGameTests.ironPattern(helper, smelterBlock, 0);
        Settler smelter = worker(helper, data, settlement, smelterBlock, new BlockPos(7, 1, 18), "Ida", SmelterGameTests.smelter());
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), IRON_SKILL, ItemStack.EMPTY);

        ask(helper, miner);
        UUID iron = openFor(settlement, miner).getFirst().id();
        // The Smith misses ingots and asks for them; the Smelter has no ore for them.
        catchUp(level, smith);
        catchUp(level, smelter);
        helper.assertTrue(Requests.deadEnd(settlement, iron), "The tree is stuck: " + settlement.requests());

        // The job step waits before it plans again.
        helper.startSequence().thenIdle(WAIT).thenExecute(() -> {
            ask(helper, miner);
            List<Request> asked = openFor(settlement, miner);
            helper.assertTrue(asked.size() == 1 && asked.getFirst().item().is(Items.STONE_PICKAXE), "One tier lower is asked for: " + settlement.requests());
            helper.assertTrue(Requests.get(settlement, iron).isEmpty(), "The iron pickaxe is withdrawn: " + settlement.requests());
            helper.assertTrue(settlement.requests().stream().filter(ToolSupplyGameTests::open).count() == 1, "The sub-requests went with it: " + settlement.requests());
        }).thenIdle(WAIT).thenExecute(() -> {
            // Nothing moves the settler up again.
            ask(helper, miner);
            helper.assertTrue(openFor(settlement, miner).size() == 1 && openFor(settlement, miner).getFirst().item().is(Items.STONE_PICKAXE),
                    "Still the stone pickaxe: " + settlement.requests());
            cleanUp(helper, smith, mason, smelter, miner);
        }).thenSucceed();
    }

    /**
     * Ore in stock and a Smelter with a pattern: the missing ingots are no dead end, neither while the Smelter has not
     * started nor when the ingots are made and wait for the Smith.
     */
    private static void noStepDownWithOre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL, 2), new ItemStack(Items.STICK, 4)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler smith = smith(helper, data, settlement, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler mason = stonemason(helper, data, settlement, new BlockPos(1, 1, 9));
        BlockPos smelterBlock = SmelterGameTests.smelterBlock(helper, new BlockPos(6, 1, 17));
        helper.setBlock(new BlockPos(8, 1, 17), Blocks.FURNACE);
        SmelterGameTests.ironPattern(helper, smelterBlock, 0);
        Settler smelter = worker(helper, data, settlement, smelterBlock, new BlockPos(7, 1, 18), "Ida", SmelterGameTests.smelter());
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), IRON_SKILL, ItemStack.EMPTY);

        ask(helper, miner);
        UUID iron = openFor(settlement, miner).getFirst().id();
        catchUp(level, smith);
        helper.startSequence().thenIdle(WAIT).thenExecute(() -> {
            ask(helper, miner);
            helper.assertTrue(Requests.get(settlement, iron).filter(ToolSupplyGameTests::open).isPresent() && openFor(settlement, miner).size() == 1,
                    "The Smelter is asked for ingots, the request stays: " + settlement.requests());
            catchUp(level, smelter);
        }).thenIdle(WAIT).thenExecute(() -> {
            ask(helper, miner);
            helper.assertTrue(Requests.get(settlement, iron).filter(ToolSupplyGameTests::open).isPresent() && openFor(settlement, miner).size() == 1,
                    "The ingots are made, the request stays: " + settlement.requests());
            helper.assertTrue(openFor(settlement, miner).getFirst().item().is(Items.IRON_PICKAXE), "Still the iron pickaxe");
            cleanUp(helper, smith, mason, smelter, miner);
        }).thenSucceed();
    }

    /** A better tool arriving later changes neither the open request nor a tool the settler has. */
    private static void laterToolChangesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of());
        Settlement settlement = settlement(helper, clerk);
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), 15, ItemStack.EMPTY);
        ask(helper, miner);
        UUID request = openFor(settlement, miner).getFirst().id();
        ClerkStorage.stock(level, clerk, true).insert(new ItemStack(Items.STONE_PICKAXE));
        helper.startSequence().thenIdle(WAIT).thenExecute(() -> {
            ask(helper, miner);
            helper.assertTrue(openFor(settlement, miner).size() == 1 && openFor(settlement, miner).getFirst().id().equals(request),
                    "The request stays as it is: " + settlement.requests());
            helper.assertTrue(free(level, clerk, Items.STONE_PICKAXE) == 1, "The pickaxe that arrived is not taken");
            cleanUp(helper, miner);
        }).thenSucceed();
    }

    /**
     * The Smith misses ingots (the Smelter has ore and coal, so that sub-request is working) and sticks (nobody makes them):
     * the tree has a dead end although the ingot request is not stuck, so the Miner steps down to the stone pickaxe.
     */
    private static void deadEndMissingInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL, 2)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler smith = smith(helper, data, settlement, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler mason = stonemason(helper, data, settlement, new BlockPos(1, 1, 9));
        BlockPos smelterBlock = SmelterGameTests.smelterBlock(helper, new BlockPos(6, 1, 17));
        helper.setBlock(new BlockPos(8, 1, 17), Blocks.FURNACE);
        SmelterGameTests.ironPattern(helper, smelterBlock, 0);
        Settler smelter = worker(helper, data, settlement, smelterBlock, new BlockPos(7, 1, 18), "Ida", SmelterGameTests.smelter());
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), IRON_SKILL, ItemStack.EMPTY);

        ask(helper, miner);
        UUID iron = openFor(settlement, miner).getFirst().id();
        catchUp(level, smith);
        helper.assertTrue(Requests.children(settlement, iron).stream().anyMatch(child -> child.item().is(Items.IRON_INGOT) && open(child)),
                "The Smith asked for the ingots: " + settlement.requests());
        helper.assertTrue(Requests.deadEnd(settlement, iron), "The sticks have no sub-request: " + settlement.requests());

        helper.startSequence().thenIdle(WAIT).thenExecute(() -> {
            ask(helper, miner);
            List<Request> asked = openFor(settlement, miner);
            helper.assertTrue(asked.size() == 1 && asked.getFirst().item().is(Items.STONE_PICKAXE), "One tier lower is asked for: " + settlement.requests());
            cleanUp(helper, smith, mason, smelter, miner);
        }).thenSucceed();
    }

    /**
     * A tool of another mod counts when its mining level fits: an iron-level pickaxe in the Clerk store (found by the tag
     * and the tool component, not by item id) is carried to the collect inventory of a skill-45 Miner and nothing is ordered.
     */
    private static void moddedTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item modded = BuiltInRegistries.ITEM.getValue(MODDED_PICKAXE);
        helper.assertTrue(modded != Items.AIR && new ItemStack(modded).is(ItemTags.PICKAXES), "The test pickaxe is registered and a pickaxe");
        helper.assertTrue(ToolTiers.tier(new ItemStack(modded)) == 2, "Its mining level is iron");
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(modded)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), IRON_SKILL, ItemStack.EMPTY);
        helper.setBlock(new BlockPos(3, 1, 13), Blocks.CHEST);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 13));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock(miner), chest, false, true) == JobAssignments.PointResult.COLLECT,
                "The chest is the collect inventory");
        ask(helper, miner);
        helper.assertTrue(settlement.requests().isEmpty(), "The modded tool is in the store, nothing is ordered: " + settlement.requests());
        helper.assertTrue(((Container) level.getBlockEntity(chest)).hasAnyMatching(stack -> stack.is(modded)), "The modded pickaxe is in the chest");
        finish(helper, miner);
    }

    /** A golden tool lying in the Clerk store is used (it meets a skill-1 requirement); nothing is ordered. */
    private static void goldenInStore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of(new ItemStack(Items.GOLDEN_PICKAXE)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), 1, ItemStack.EMPTY);
        helper.setBlock(new BlockPos(3, 1, 13), Blocks.CHEST);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 13));
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock(miner), chest, false, true) == JobAssignments.PointResult.COLLECT,
                "The chest is the collect inventory");
        ask(helper, miner);
        helper.assertTrue(settlement.requests().isEmpty(), "The golden pickaxe is in the store, nothing is ordered: " + settlement.requests());
        helper.assertTrue(((Container) level.getBlockEntity(chest)).hasAnyMatching(stack -> stack.is(Items.GOLDEN_PICKAXE)), "The golden pickaxe is in the chest");
        finish(helper, miner);
    }

    /**
     * Nothing to make the stone pickaxe from: the Stonemason misses cobblestone and sticks and nobody supplies them. The
     * request steps down from stone straight to wooden; the golden pickaxe, the same tier, is skipped.
     */
    private static void stepsDownToWooden(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = settlement(helper, clerk);
        Settler mason = stonemason(helper, data, settlement, new BlockPos(1, 1, 9));
        helper.setBlock(new BlockPos(3, 1, 10), Blocks.STONECUTTER);
        Settler miner = toolWorker(helper, new BlockPos(1, 1, 13), ModJobs.MINER.get(), 15, ItemStack.EMPTY);
        ask(helper, miner);
        UUID stone = openFor(settlement, miner).getFirst().id();
        helper.assertTrue(openFor(settlement, miner).getFirst().item().is(Items.STONE_PICKAXE), "The stone pickaxe is asked for first");
        catchUp(level, mason);
        catchUp(level, mason);
        helper.assertTrue(Requests.deadEnd(settlement, stone), "The stone pickaxe cannot be made (status " + mason.getWork().status(mason.getJobs().getFirst().jobBlock()) + "): " + settlement.requests());
        helper.startSequence().thenIdle(WAIT).thenExecute(() -> {
            ask(helper, miner);
            List<Request> asked = openFor(settlement, miner);
            helper.assertTrue(asked.size() == 1 && asked.getFirst().item().is(Items.WOODEN_PICKAXE), "Wooden, not golden: " + settlement.requests());
            cleanUp(helper, mason, miner);
        }).thenSucceed();
    }
}
