package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.AutoAssignment;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for thought bubbles (vault {@code Systems/Thought Bubbles.md}), server side: the bubble and problem
 * payloads a settler sends on its triggers, and the held item that follows the activity. Rendering is client-tested.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ThoughtBubbleGameTests {

    private ThoughtBubbleGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "thought_bubble_job_icons", ThoughtBubbleGameTests::jobIcons);
            register(helper, "thought_bubble_job_switch", ThoughtBubbleGameTests::jobSwitch);
            register(helper, "thought_bubble_auto_assigned", ThoughtBubbleGameTests::autoAssigned);
            register(helper, "thought_bubble_no_tool", ThoughtBubbleGameTests::noTool);
            register(helper, "thought_bubble_missing_inputs", ThoughtBubbleGameTests::missingInputs);
            register(helper, "thought_bubble_live_round", ThoughtBubbleGameTests::liveRound);
            register(helper, "thought_bubble_problem_after_catch_up", ThoughtBubbleGameTests::problemAfterCatchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static ThoughtBubble.Show show(Settler settler, ThoughtBubble bubble, Item icon) {
        return new ThoughtBubble.Show(settler.getId(), bubble, icon, true);
    }

    private static ThoughtBubble.Show off(Settler settler, ThoughtBubble bubble, Item icon) {
        return new ThoughtBubble.Show(settler.getId(), bubble, icon, false);
    }

    private static List<ThoughtBubble.Show> sent(Settler settler) {
        return settler.getWork().recentThoughts();
    }

    /** Each Job has its typical item as bubble icon; the GUI icon stays the JobBlock. */
    private static void jobIcons(GameTestHelper helper) {
        Map<Job, Item> expected = Map.of(ModJobs.LUMBERJACK.get(), Items.IRON_AXE, ModJobs.MINER.get(), Items.IRON_PICKAXE,
                ModJobs.FISHERMAN.get(), Items.COD, ModJobs.CLERK.get(), Items.EMERALD, ModJobs.CRAFTSPERSON.get(), ModItems.HAMMER.get(),
                ModJobs.DELIVERYMAN.get(), Items.BUNDLE, ModJobs.ADVENTURER.get(), Items.MAP);
        expected.forEach((job, icon) -> helper.assertTrue(job.bubbleIcon().is(icon), job + " bubble icon " + icon + ": " + job.bubbleIcon()));
        helper.assertTrue(ModJobs.LUMBERJACK.get().icon().is(ModItems.LUMBERJACK_BLOCK.get()), "The GUI icon stays the JobBlock");
        helper.assertTrue(ModJobs.CRAFTSPERSON.get().displayItem().filter(item -> item == ModItems.HAMMER.get()).isPresent(),
                "The Craftsperson shows a hammer");
        helper.assertTrue(ModJobs.DELIVERYMAN.get().displayItem().filter(item -> item == Items.BUNDLE).isPresent(), "The Deliveryman shows a bundle");
        helper.assertTrue(ModJobs.CLERK.get().displayItem().isEmpty() && ModJobs.LUMBERJACK.get().displayItem().isEmpty(),
                "Clerk: nothing to show; Lumberjack: its real tool");
        helper.assertTrue(ModItems.RENDER_ONLY.contains(ModItems.HAMMER), "The hammer is not in the creative tab");
        helper.succeed();
    }

    /**
     * Switching to a Job shows its bubble icon once and empties a hand that still held the old job's tool (a Clerk has
     * nothing to show).
     */
    private static void jobSwitch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        settler.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FISHING_ROD));
        helper.assertTrue(SettlementWorld.assign(level, clerk, settler, ModJobs.CLERK.get()) == JobAssignments.AssignResult.OK, "Clerk assigned");

        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().activeJob().isPresent(), "Working as Clerk: " + settler.getWork().status(clerk));
        helper.assertTrue(sent(settler).equals(List.of(show(settler, ThoughtBubble.JOB, Items.EMERALD))),
                "Job switch: the Clerk's bubble icon " + sent(settler));
        helper.assertTrue(settler.getMainHandItem().isEmpty(), "The rod is gone from the hand: " + settler.getMainHandItem());

        settler.getWork().decideNow(level);
        helper.assertTrue(sent(settler).size() == 1, "Shown once, not on every decision: " + sent(settler));
        JobAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /** An automatic assignment shows the Job's bubble icon at once. */
    private static void autoAssigned(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(3, 1, 3), data, settlement);

        AutoAssignment.setAuto(helper.getLevel(), jobBlock, ModJobs.LUMBERJACK.get(), true);
        helper.assertTrue(settler.getJobs().size() == 1, "Assigned: " + settler.getJobs());
        helper.assertTrue(sent(settler).equals(List.of(show(settler, ThoughtBubble.JOB, Items.IRON_AXE))),
                "Automatically assigned: the Lumberjack's bubble icon " + sent(settler));
        settler.discard();
        helper.succeed();
    }

    /**
     * No tool: a problem bubble with the needed tool, sent once while it lasts (no job bubble: the job never started);
     * "problem off" follows when the problem ends.
     */
    private static void noTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(3, 1, 3), data, settlement);
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        helper.assertTrue(SettlementWorld.assign(level, jobBlock, settler, ModJobs.LUMBERJACK.get()) == JobAssignments.AssignResult.OK,
                "Lumberjack assigned");

        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_TOOL, "No tool: " + settler.getWork().status(jobBlock));
        helper.assertTrue(sent(settler).equals(List.of(show(settler, ThoughtBubble.NO_TOOL, Items.STONE_AXE))),
                "Problem on with the needed tool: " + sent(settler));
        settler.getWork().decideNow(level);
        helper.assertTrue(sent(settler).size() == 1, "The client repeats it, the server sends it once: " + sent(settler));

        settler.removeJob(jobBlock);
        settler.getWork().decideNow(level);
        helper.assertTrue(sent(settler).equals(List.of(show(settler, ThoughtBubble.NO_TOOL, Items.STONE_AXE),
                off(settler, ThoughtBubble.NO_TOOL, Items.STONE_AXE))), "Problem off when it ends: " + sent(settler));
        JobAssignments.unassign(data, jobBlock);
        settler.discard();
        helper.succeed();
    }

    /**
     * A problem that ends while the settler catches up goes off once the catch-up is over: players that saw "problem
     * on" get "problem off" (nothing is sent during the catch-up itself).
     */
    private static void problemAfterCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(3, 1, 3), data, settlement);
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        helper.assertTrue(SettlementWorld.assign(level, jobBlock, settler, ModJobs.LUMBERJACK.get()) == JobAssignments.AssignResult.OK,
                "Lumberjack assigned");
        settler.getWork().decideNow(level);
        helper.assertTrue(sent(settler).equals(List.of(show(settler, ThoughtBubble.NO_TOOL, Items.STONE_AXE))), "Problem on: " + sent(settler));

        // The settler leaves the job and decides once while it catches up (the state follows quietly).
        settler.removeJob(jobBlock);
        JobAssignments.unassign(data, jobBlock);
        settler.getWork().decideQuietly(level);
        helper.assertTrue(sent(settler).size() == 1, "Nothing sent while caught up: " + sent(settler));
        settler.getWork().decideNow(level);
        helper.assertTrue(sent(settler).equals(List.of(show(settler, ThoughtBubble.NO_TOOL, Items.STONE_AXE),
                off(settler, ThoughtBubble.NO_TOOL, Items.STONE_AXE))), "Problem off after the catch-up: " + sent(settler));
        settler.discard();
        helper.succeed();
    }

    /**
     * Missing inputs: a problem bubble with the crafting table (no job bubble yet); when the next request can be made the
     * problem goes off, the Job's bubble shows and fetching the inputs shows the collect marker.
     */
    private static void missingInputs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        chest.setItem(1, new ItemStack(Items.STICK, 2));
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_PICKAXE, 1, 30));
        StockRules.setRule(data, clerk, StockRule.minimum(1, Items.STONE_HOE, 1, 5));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        CraftspersonGameTests.define(helper, craft, 0, Items.STONE_PICKAXE);
        CraftspersonGameTests.define(helper, craft, 1, Items.STONE_HOE);
        // Only the pickaxe is tried first: it lacks a cobblestone.
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");

        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.MISSING_INPUTS || settler.getWork().status(craft) == JobStatus.FETCHING_INPUTS,
                "Status: " + settler.getWork().status(craft));
        if (settler.getWork().status(craft) == JobStatus.MISSING_INPUTS) {
            helper.assertTrue(sent(settler).equals(List.of(show(settler, ThoughtBubble.MISSING_INPUTS, Items.CRAFTING_TABLE))),
                    "Problem on with the crafting table: " + sent(settler));
            settler.getWork().decideNow(level);
        }
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.FETCHING_INPUTS, "Fetching: " + settler.getWork().status(craft));
        List<ThoughtBubble.Show> sent = sent(settler);
        helper.assertTrue(sent.contains(show(settler, ThoughtBubble.JOB, ModItems.HAMMER.get())), "The Job's bubble: " + sent);
        helper.assertTrue(sent.getLast().equals(show(settler, ThoughtBubble.COLLECT, ModItems.COLLECT_MARKER.get())),
                "Fetching inputs: the collect marker " + sent);
        helper.assertTrue(!sent.contains(show(settler, ThoughtBubble.MISSING_INPUTS, Items.CRAFTING_TABLE))
                || sent.contains(off(settler, ThoughtBubble.MISSING_INPUTS, Items.CRAFTING_TABLE)), "Missing inputs went off: " + sent);
        helper.assertTrue(Requests.forRule(settlement, clerk, 1).orElseThrow().claimOf(settler.getUUID()).map(Request.Claim::amount)
                .equals(Optional.of(1)), "The hoe is claimed");
        settler.removeJob(craft);
        JobAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /**
     * Live play: a Craftsperson that still holds a fishing rod fetches its inputs with an empty hand, crafts with the
     * hammer (or the result just made) in its hand and walks the result to the Clerk; collect, crafting and drop-off
     * bubbles follow each other.
     */
    private static void liveRound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        CraftspersonGameTests.placeTable(helper, new BlockPos(4, 1, 6));
        CraftspersonGameTests.define(helper, craft, 0, Items.STICK);
        ((Container) level.getBlockEntity(craft)).setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        Requests.post(data, settlement, Items.STICK, 4, Optional.of(clerk), Optional.empty());
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        settler.getSkills().set(ModJobs.CRAFTSPERSON.get(), new com.gilfort.civmod.job.SettlerSkills.Skill(100, 0));
        settler.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FISHING_ROD));
        settler.setNoAi(false);
        AtomicBoolean hammer = new AtomicBoolean();
        AtomicBoolean wrongHand = new AtomicBoolean();
        StringBuilder wrong = new StringBuilder();
        helper.onEachTick(() -> {
            JobStatus status = settler.getWork().status(craft);
            ItemStack hand = settler.getMainHandItem();
            if (status == JobStatus.CRAFTING && hand.is(ModItems.HAMMER.get())) {
                hammer.set(true);
            }
            if (status == JobStatus.FETCHING_INPUTS && !hand.isEmpty() && !wrongHand.get()) {
                wrongHand.set(true);
                wrong.append(hand);
            }
        });
        Container clerkSlots = (Container) level.getBlockEntity(clerk);
        helper.succeedWhen(() -> {
            helper.assertTrue(clerkSlots.countItem(Items.STICK) == 4, "Sticks delivered; status " + settler.getWork().status(craft));
            helper.assertFalse(wrongHand.get(), "Fetching with an empty hand, not " + wrong);
            helper.assertTrue(hammer.get(), "The hammer was in the hand while crafting");
            List<ThoughtBubble> order = sent(settler).stream().map(ThoughtBubble.Show::bubble).toList();
            helper.assertTrue(order.indexOf(ThoughtBubble.COLLECT) >= 0 && order.indexOf(ThoughtBubble.COLLECT) < order.indexOf(ThoughtBubble.CRAFTING)
                    && order.indexOf(ThoughtBubble.CRAFTING) < order.lastIndexOf(ThoughtBubble.DROP_OFF), "Collect, crafting, drop-off: " + order);
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
