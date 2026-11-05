package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.ToolNeeds;
import com.gilfort.civmod.job.ToolStaircase;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the tool staircase (vault {@code Systems/Needs.md}, decisions of 2026-11-04): the allowed and required
 * tool tier per Job skill, the tool need (grace day, lower work level, climbing back), and the tool swap with the collect
 * inventory in live play and in catch-up. The seam is the settler's work step: the tool in hand, the level it works at
 * and its status.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ToolStaircaseGameTests {

    private static final long DAY = 24000L;
    /** A JobBlock of level 5: skill cap 100. */
    private static final int BLOCK_LEVEL = 5;

    private ToolStaircaseGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "tool_staircase_table", ToolStaircaseGameTests::table);
            register(helper, "tool_staircase_tool_jobs", ToolStaircaseGameTests::toolJobs);
            register(helper, "tool_staircase_band_edges", ToolStaircaseGameTests::bandEdges);
            register(helper, "tool_staircase_tool_above_allowed", ToolStaircaseGameTests::aboveAllowed);
            register(helper, "tool_staircase_climb_back", ToolStaircaseGameTests::climbBack);
            register(helper, "tool_staircase_two_jobs", ToolStaircaseGameTests::twoJobs);
            register(helper, "tool_staircase_job_without_progression", ToolStaircaseGameTests::withoutProgression);
            register(helper, "tool_staircase_catch_up_need", ToolStaircaseGameTests::catchUpNeed);
            register(helper, "tool_staircase_saved", ToolStaircaseGameTests::saved);
            register(helper, "tool_staircase_old_save", ToolStaircaseGameTests::oldSave);
            register(helper, "tool_staircase_no_tool", ToolStaircaseGameTests::noTool);
            register(helper, "tool_staircase_swap_free_belt", ToolStaircaseGameTests::swapFreeBelt);
            register(helper, "tool_staircase_swap_full_belt", ToolStaircaseGameTests::swapFullBelt);
            register(helper, "tool_staircase_no_swap_when_met", ToolStaircaseGameTests::noSwapWhenMet);
            register(helper, "tool_staircase_ignores_above_allowed", ToolStaircaseGameTests::ignoresAboveAllowed);
            register(helper, "tool_staircase_search_throttled", ToolStaircaseGameTests::searchThrottled);
            register(helper, "tool_staircase_catch_up_swap_free_belt", ToolStaircaseGameTests::catchUpSwapFreeBelt);
            register(helper, "tool_staircase_catch_up_swap_full_belt", ToolStaircaseGameTests::catchUpSwapFullBelt);
            register(helper, "tool_staircase_catch_up_no_swap", ToolStaircaseGameTests::catchUpNoSwap);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- tools

    private static ItemStack enchanted(ServerLevel level, Item item, net.minecraft.resources.ResourceKey<Enchantment> enchantment) {
        Holder<Enchantment> holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantment);
        ItemStack stack = new ItemStack(item);
        stack.enchant(holder, 1);
        return stack;
    }

    /** A tool whose material no vanilla tier tag knows. */
    private static ItemStack unknownMaterial() {
        ItemStack stack = new ItemStack(Items.STONE_AXE);
        stack.remove(DataComponents.TOOL);
        return stack;
    }

    // ---------------------------------------------------------------- rules

    /** The table: allowed and required tier per band edge, enchanted, plain, cursed-only, unknown material. */
    private static void table(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int none = ToolStaircase.NO_TIER;
        // skill, allowed, required, enchanted
        int[][] bands = {
                {1, 1, none, 0}, {20, 1, none, 0}, {21, 2, 1, 0}, {40, 2, 1, 0}, {41, 3, 2, 0}, {60, 3, 2, 0},
                {61, 4, 3, 1}, {80, 4, 3, 1}, {81, 4, 4, 1}, {100, 4, 4, 1}};
        for (int[] band : bands) {
            ToolStaircase.Rule rule = ToolStaircase.forSkill(band[0]);
            helper.assertTrue(rule.allowed() == band[1] && rule.required() == band[2] && rule.enchanted() == (band[3] == 1),
                    "Skill " + band[0] + ": " + rule);
        }
        ItemStack plain = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack good = enchanted(level, Items.DIAMOND_PICKAXE, Enchantments.UNBREAKING);
        ItemStack cursed = enchanted(level, Items.DIAMOND_PICKAXE, Enchantments.VANISHING_CURSE);
        helper.assertFalse(ToolStaircase.isEnchanted(plain), "Plain is not enchanted");
        helper.assertTrue(ToolStaircase.isEnchanted(good), "Unbreaking is enchanted");
        helper.assertFalse(ToolStaircase.isEnchanted(cursed), "A curse alone is not enchanted");
        ItemStack both = enchanted(level, Items.DIAMOND_PICKAXE, Enchantments.VANISHING_CURSE);
        both.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 2);
        helper.assertTrue(ToolStaircase.isEnchanted(both), "A curse and another enchantment count");

        ToolStaircase.Rule sixtyOne = ToolStaircase.forSkill(61);
        helper.assertTrue(sixtyOne.meets(good) && !sixtyOne.meets(plain) && !sixtyOne.meets(cursed), "Skill 61 needs an enchanted T3");
        helper.assertTrue(!sixtyOne.meets(enchanted(level, Items.IRON_PICKAXE, Enchantments.UNBREAKING)), "Skill 61: T2 is too weak");
        helper.assertTrue(sixtyOne.allows(new ItemStack(Items.NETHERITE_PICKAXE)), "Skill 61 may use T4");
        helper.assertFalse(ToolStaircase.forSkill(60).allows(new ItemStack(Items.NETHERITE_PICKAXE)), "Skill 60 ignores T4");
        helper.assertFalse(ToolStaircase.forSkill(20).allows(new ItemStack(Items.IRON_PICKAXE)), "Skill 20 ignores T2");
        helper.assertTrue(ToolStaircase.forSkill(20).allows(new ItemStack(Items.COPPER_PICKAXE)) && ToolStaircase.forSkill(20).allows(new ItemStack(Items.GOLDEN_PICKAXE)),
                "Skill 20 may use copper and gold");
        helper.assertTrue(ToolStaircase.forSkill(40).meets(unknownMaterial()) && !ToolStaircase.forSkill(41).meets(unknownMaterial()),
                "An unknown material counts as T1");
        helper.assertTrue(ToolStaircase.Rule.NONE.meets(new ItemStack(Items.NETHERITE_PICKAXE)) && ToolStaircase.Rule.NONE.meets(new ItemStack(Items.WOODEN_PICKAXE)),
                "Outside the staircase every tier works");
        helper.succeed();
    }

    /** The staircase applies to the axe, hoe, shovel and pickaxe Jobs; fishing rod, shears and bare hands stay out. */
    private static void toolJobs(GameTestHelper helper) {
        List<Job> in = List.of(ModJobs.LUMBERJACK.get(), ModJobs.MINER.get(), ModJobs.PLANTATION_FARMER.get(), ModJobs.ANIMAL_FARMER.get(), ModJobs.VEGGY_FARMER.get());
        for (Job job : in) {
            helper.assertTrue(applies(job), job + " follows the staircase");
        }
        List<Job> out = List.of(ModJobs.FISHERMAN.get(), ModJobs.BEEKEEPER.get(), ModJobs.GATHERER.get());
        for (Job job : out) {
            helper.assertFalse(applies(job), job + " stays outside the staircase");
        }
        helper.assertTrue(ToolStaircase.rule(ModJobs.FISHERMAN.get().profile(ResourceCollectionProfile.class).orElseThrow(), 90).equals(ToolStaircase.Rule.NONE),
                "No rule for a Job outside");
        helper.succeed();
    }

    private static boolean applies(Job job) {
        return job.profile(ResourceCollectionProfile.class).map(ToolStaircase::appliesTo).orElse(false);
    }

    // ---------------------------------------------------------------- the tool need

    /** Registers a JobBlock of level 5 without a block in the world. */
    private static BlockPos place(GameTestHelper helper, BlockPos relative, Job job) {
        BlockPos pos = helper.absolutePos(relative);
        TestSettlements.place(helper, pos, job, BLOCK_LEVEL);
        return pos;
    }

    /** A fed settler of the JobBlock's settlement that does not move, with a job there and a skill in it. */
    private static Settler settler(GameTestHelper helper, BlockPos jobBlock, Job job, int skill) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        settler.getFood().setLevel(20);
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        settler.getSkills().set(job, new SettlerSkills.Skill(skill, 0));
        settler.addJob(new SettlerJob(job, jobBlock));
        return settler;
    }

    private static void end(GameTestHelper helper, Settler settler, BlockPos... jobBlocks) {
        settler.discard();
        for (BlockPos pos : jobBlocks) {
            SettlementManager.remove(SettlementData.get(helper.getLevel()), pos, 0L, SettlementManager.Listener.NONE);
        }
        helper.succeed();
    }

    private static void belt(Settler settler, ItemStack... tools) {
        for (int i = 0; i < Settler.BELT_SIZE; i++) {
            settler.getInventory().setItem(Settler.BELT_START + i, i < tools.length ? tools[i] : ItemStack.EMPTY);
        }
    }

    /** The level the tool allows after the morning checks of days 100, 101, ... on a fresh tool need. */
    private static int[] levels(ServerLevel level, Settler settler, SettlerJob job, int days) {
        settler.getWork().restoreToolNeeds(List.of());
        int[] levels = new int[days];
        for (int day = 0; day < days; day++) {
            settler.getWork().checkNeedsNow(level, 100L + day);
            levels[day] = settler.getWork().toolLevel(job);
        }
        return levels;
    }

    /** One Job, one tool: the level after three mornings. */
    private record Edge(int skill, ItemStack tool, int day1, int day2, int day3) {
    }

    /**
     * Every band edge: a met requirement holds the level, a missed one gives a grace day and then lowers the work level
     * band by band to the one whose requirement is met (20/21, 40/41, 60/61, 80/81); enchanted, plain, cursed-only,
     * unknown material.
     */
    private static void bandEdges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get());
        Settler settler = settler(helper, jobBlock, ModJobs.LUMBERJACK.get(), 1);
        SettlerJob job = settler.getJobs().get(0);
        ItemStack wood = new ItemStack(Items.WOODEN_AXE);
        ItemStack stone = new ItemStack(Items.STONE_AXE);
        ItemStack iron = new ItemStack(Items.IRON_AXE);
        ItemStack diamond = new ItemStack(Items.DIAMOND_AXE);
        ItemStack netherite = new ItemStack(Items.NETHERITE_AXE);
        ItemStack diamondGood = enchanted(level, Items.DIAMOND_AXE, Enchantments.UNBREAKING);
        ItemStack diamondCursed = enchanted(level, Items.DIAMOND_AXE, Enchantments.BINDING_CURSE);
        ItemStack netheriteGood = enchanted(level, Items.NETHERITE_AXE, Enchantments.EFFICIENCY);
        List<Edge> edges = List.of(
                // 1-20: nothing required.
                new Edge(20, wood, 20, 20, 20), new Edge(20, stone, 20, 20, 20), new Edge(1, wood, 1, 1, 1),
                // 21-40: T1.
                new Edge(21, stone, 21, 21, 21), new Edge(21, wood, 21, 20, 20), new Edge(40, stone, 40, 40, 40), new Edge(40, wood, 40, 20, 20),
                new Edge(30, unknownMaterial(), 30, 30, 30),
                // No usable tool at all (or one above the allowed tier): the status "No tool", not this need.
                new Edge(50, ItemStack.EMPTY, 50, 50, 50), new Edge(30, netherite, 30, 30, 30),
                // 41-60: T2.
                new Edge(41, iron, 41, 41, 41), new Edge(41, stone, 41, 40, 40), new Edge(60, iron, 60, 60, 60), new Edge(60, stone, 60, 40, 40),
                new Edge(50, unknownMaterial(), 50, 40, 40),
                // 61-80: T3, enchanted.
                new Edge(61, diamondGood, 61, 61, 61), new Edge(61, diamond, 61, 60, 60), new Edge(61, diamondCursed, 61, 60, 60), new Edge(61, iron, 61, 60, 60),
                new Edge(61, netheriteGood, 61, 61, 61), new Edge(80, diamondGood, 80, 80, 80), new Edge(80, diamond, 80, 60, 60),
                // 81-100: T4, enchanted.
                new Edge(81, netheriteGood, 81, 81, 81), new Edge(81, diamondGood, 81, 80, 80), new Edge(81, netherite, 81, 80, 60));
        for (Edge edge : edges) {
            settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(edge.skill(), 0));
            belt(settler, edge.tool());
            int[] levels = levels(level, settler, job, 3);
            helper.assertTrue(levels[0] == edge.day1() && levels[1] == edge.day2() && levels[2] == edge.day3(),
                    "Skill " + edge.skill() + " with " + edge.tool() + ": expected " + edge.day1() + ", " + edge.day2() + ", " + edge.day3()
                            + " but got " + levels[0] + ", " + levels[1] + ", " + levels[2]);
        }
        end(helper, settler, jobBlock);
    }

    /** A tool above the allowed tier is ignored completely: not in hand, not counted for the requirement. */
    private static void aboveAllowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get());
        Settler settler = settler(helper, jobBlock, ModJobs.LUMBERJACK.get(), 10);
        SettlerJob job = settler.getJobs().get(0);
        belt(settler, new ItemStack(Items.NETHERITE_AXE));
        helper.assertTrue(settler.getWork().tool(job).isEmpty(), "Skill 10 ignores a netherite axe");
        belt(settler, new ItemStack(Items.NETHERITE_AXE), new ItemStack(Items.COPPER_AXE));
        helper.assertTrue(settler.getWork().tool(job).map(ItemStack::getItem).orElse(Items.AIR) == Items.COPPER_AXE, "It uses the copper axe instead");

        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(21, 0));
        belt(settler, new ItemStack(Items.DIAMOND_AXE), new ItemStack(Items.WOODEN_AXE));
        helper.assertTrue(settler.getWork().tool(job).map(ItemStack::getItem).orElse(Items.AIR) == Items.WOODEN_AXE, "Skill 21 ignores diamond");
        int[] levels = levels(level, settler, job, 2);
        helper.assertTrue(levels[0] == 21 && levels[1] == 20, "The ignored diamond axe does not meet T1: " + levels[0] + ", " + levels[1]);

        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(61, 0));
        belt(settler, new ItemStack(Items.NETHERITE_AXE));
        helper.assertTrue(settler.getWork().tool(job).isPresent(), "Skill 61 may use netherite");
        end(helper, settler, jobBlock);
    }

    /** Back up at once when the requirement is met again, also within the grace day and the day after. */
    private static void climbBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.MINER.get());
        Settler settler = settler(helper, jobBlock, ModJobs.MINER.get(), 30);
        SettlerJob job = settler.getJobs().get(0);
        belt(settler, new ItemStack(Items.WOODEN_PICKAXE));
        settler.getWork().checkNeedsNow(level, 100L);
        helper.assertTrue(settler.getWork().toolLevel(job) == 30, "Grace day at the full level: " + settler.getWork().toolLevel(job));
        settler.getWork().checkNeedsNow(level, 101L);
        helper.assertTrue(settler.getWork().toolLevel(job) == 20, "Then the band whose requirement is met: " + settler.getWork().toolLevel(job));
        helper.assertTrue(settler.getWork().effectiveLevel(job) <= 30, "The effective level never rises above the skill");

        belt(settler, new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.STONE_PICKAXE));
        settler.getWork().checkNeedsNow(level, 101L);
        helper.assertTrue(settler.getWork().toolLevel(job) == 30, "A stone pickaxe on the belt: back at once, same day: " + settler.getWork().toolLevel(job));

        belt(settler, new ItemStack(Items.WOODEN_PICKAXE));
        settler.getWork().checkNeedsNow(level, 102L);
        helper.assertTrue(settler.getWork().toolLevel(job) == 30, "A grace day again after being met: " + settler.getWork().toolLevel(job));
        end(helper, settler, jobBlock);
    }

    /** The tool need is judged per Job with that Job's skill, not with the highest skill of the settler. */
    private static void twoJobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lumberjack = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get());
        BlockPos miner = place(helper, new BlockPos(3, 1, 1), ModJobs.MINER.get());
        Settler settler = settler(helper, lumberjack, ModJobs.LUMBERJACK.get(), 5);
        settler.getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(70, 0));
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), miner));
        SettlerJob lumber = settler.getJobs().get(0);
        SettlerJob mining = settler.getJobs().get(1);
        belt(settler, new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.WOODEN_PICKAXE));
        for (long day = 100L; day <= 103L; day++) {
            settler.getWork().checkNeedsNow(level, day);
        }
        helper.assertTrue(settler.getWork().toolLevel(lumber) == 5, "The Lumberjack 5 is not held back by the Miner 70: " + settler.getWork().toolLevel(lumber));
        helper.assertTrue(settler.getWork().toolLevel(mining) == 20, "The Miner 70 with a wooden pickaxe works at 20: " + settler.getWork().toolLevel(mining));
        end(helper, settler, lumberjack, miner);
    }

    /** A Job with a fishing rod has no tool progression: skill 90 with a plain rod is never held back. */
    private static void withoutProgression(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.FISHERMAN.get());
        Settler settler = settler(helper, jobBlock, ModJobs.FISHERMAN.get(), 90);
        SettlerJob job = settler.getJobs().get(0);
        belt(settler, new ItemStack(Items.FISHING_ROD));
        int[] levels = levels(level, settler, job, 4);
        helper.assertTrue(levels[0] == 90 && levels[1] == 90 && levels[2] == 90 && levels[3] == 90, "Never held back: " + levels[3]);
        helper.assertTrue(settler.getWork().toolNeedStates().isEmpty(), "No tool need kept for it: " + settler.getWork().toolNeedStates());
        end(helper, settler, jobBlock);
    }

    /** Catch-up takes the same step: two missed days with a wooden axe at 45 give a grace day and then level 40. */
    private static void catchUpNeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get());
        Settler settler = settler(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        SettlerJob job = settler.getJobs().get(0);
        belt(settler, new ItemStack(Items.STONE_AXE));
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), DAY) * DAY;
        settler.getWork().setCatchUp(dayStart - 2 * DAY, dayStart);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(settler.getWork().toolLevel(job) == 40, "A stone axe at 45 while away: grace day, then 40: " + settler.getWork().toolLevel(job));

        belt(settler, new ItemStack(Items.IRON_AXE));
        settler.getWork().checkNeedsNow(level, Math.floorDiv(dayStart, DAY));
        helper.assertTrue(settler.getWork().toolLevel(job) == 45, "An iron axe: back at once: " + settler.getWork().toolLevel(job));
        end(helper, settler, jobBlock);
    }

    /** The tool need is saved with the settler. */
    private static void saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get());
        Settler settler = settler(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        belt(settler, new ItemStack(Items.WOODEN_AXE));
        settler.getWork().checkNeedsNow(level, 100L);
        settler.getWork().checkNeedsNow(level, 101L);
        List<ToolNeeds.Saved> before = settler.getWork().toolNeedStates();

        Settler loaded = reload(level, settler, false);
        helper.assertTrue(loaded.getWork().toolNeedStates().equals(before) && before.size() == 1, "Saved with the settler: " + loaded.getWork().toolNeedStates());
        loaded.discard();
        end(helper, settler, jobBlock);
    }

    /** A save without tool needs loads and starts from the capped level. */
    private static void oldSave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get());
        Settler settler = settler(helper, jobBlock, ModJobs.LUMBERJACK.get(), 45);
        belt(settler, new ItemStack(Items.WOODEN_AXE));
        settler.getWork().checkNeedsNow(level, 100L);
        settler.getWork().checkNeedsNow(level, 101L);

        Settler loaded = reload(level, settler, true);
        helper.assertTrue(loaded.getWork().toolNeedStates().isEmpty(), "Nothing saved: no tool need: " + loaded.getWork().toolNeedStates());
        helper.assertTrue(loaded.getWork().toolLevel(loaded.getJobs().get(0)) == 45, "Starts at the capped level");
        loaded.discard();
        end(helper, settler, jobBlock);
    }

    private static Settler reload(ServerLevel level, Settler settler, boolean dropToolNeeds) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        if (dropToolNeeds) {
            tag.remove("tool_needs");
        }
        Settler loaded = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        return loaded;
    }

    // ---------------------------------------------------------------- the work step at a real JobBlock

    /** A Lumberjack at a real JobBlock of level 5 with a tree, its belt and the collect inventory (the JobBlock itself). */
    private record World(Settler settler, SettlerJob job, BlockPos jobBlock, List<long[]> forced) {

        Container storage(ServerLevel level) {
            return (Container) level.getBlockEntity(jobBlock);
        }
    }

    private static World world(GameTestHelper helper, int skill, List<ItemStack> belt, List<ItemStack> collect) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        SettlerJob job = settler.getJobs().get(0);
        // The block entity keeps the level too and syncs it to the settlement: set both.
        ((com.gilfort.civmod.block.JobBlockEntity) level.getBlockEntity(job.jobBlock())).setJobLevel(BLOCK_LEVEL);
        SettlementManager.setLevel(SettlementData.get(level), job.jobBlock(), BLOCK_LEVEL, SettlementManager.Listener.NONE);
        World world = new World(settler, job, job.jobBlock(), forced);
        arrange(level, world, skill, belt, collect);
        return world;
    }

    private static void arrange(ServerLevel level, World world, int skill, List<ItemStack> belt, List<ItemStack> collect) {
        world.settler().getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(skill, 0));
        belt(world.settler(), belt.stream().map(ItemStack::copy).toArray(ItemStack[]::new));
        Container storage = world.storage(level);
        storage.clearContent();
        for (int i = 0; i < collect.size(); i++) {
            storage.setItem(i, collect.get(i).copy());
        }
    }

    private static void finish(ServerLevel level, World world) {
        world.settler().discard();
        world.forced().forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
    }

    private static boolean has(Container container, Item item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean onBelt(Settler settler, Item item) {
        for (int i = 0; i < Settler.BELT_SIZE; i++) {
            if (settler.getInventory().getItem(Settler.BELT_START + i).is(item)) {
                return true;
            }
        }
        return false;
    }

    /** Without any tool the status stays "No tool", also when the only tool is above the allowed tier. */
    private static void noTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 10, List.of(new ItemStack(Items.NETHERITE_AXE)), List.of());
        helper.succeedWhen(() -> {
            helper.assertTrue(world.settler().getWork().status(world.jobBlock()) == JobStatus.NO_TOOL, "Status " + world.settler().getWork().status(world.jobBlock()));
            helper.assertTrue(world.settler().getWork().tool(world.job()).isEmpty(), "The netherite axe is ignored at skill 10");
            finish(level, world);
        });
    }

    /** The belt holds a stone axe but skill 45 needs iron: the settler fetches the iron axe and keeps the stone axe. */
    private static void swapFreeBelt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 45, List.of(new ItemStack(Items.STONE_AXE)), List.of(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.IRON_AXE)));
        helper.succeedWhen(() -> {
            helper.assertTrue(onBelt(world.settler(), Items.IRON_AXE), "No iron axe yet (status " + world.settler().getWork().status(world.jobBlock()) + ")");
            helper.assertTrue(onBelt(world.settler(), Items.STONE_AXE), "The stone axe stays on the free belt");
            helper.assertFalse(has(world.storage(level), Items.IRON_AXE), "The iron axe left the collect inventory");
            helper.assertTrue(has(world.storage(level), Items.WOODEN_AXE), "The wooden axe was not needed");
            helper.assertTrue(world.settler().getWork().tool(world.job()).map(ItemStack::getItem).orElse(Items.AIR) == Items.IRON_AXE, "The iron axe is the tool");
            finish(level, world);
        });
    }

    /** With a full belt the weakest axe goes back first, then the better one is taken. */
    private static void swapFullBelt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 45, List.of(new ItemStack(Items.STONE_AXE), new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.SHEARS), new ItemStack(Items.FISHING_ROD)),
                List.of(new ItemStack(Items.IRON_AXE)));
        helper.succeedWhen(() -> {
            helper.assertTrue(onBelt(world.settler(), Items.IRON_AXE), "No iron axe yet (status " + world.settler().getWork().status(world.jobBlock()) + ")");
            helper.assertFalse(onBelt(world.settler(), Items.WOODEN_AXE), "The weakest axe was put back");
            helper.assertTrue(has(world.storage(level), Items.WOODEN_AXE), "It is in the collect inventory");
            helper.assertTrue(onBelt(world.settler(), Items.STONE_AXE) && onBelt(world.settler(), Items.SHEARS) && onBelt(world.settler(), Items.FISHING_ROD),
                    "The rest of the belt is untouched");
            finish(level, world);
        });
    }

    /** A tool that meets the requirement is never swapped, not even for a better allowed one. */
    private static void noSwapWhenMet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 45, List.of(new ItemStack(Items.IRON_AXE)), List.of(new ItemStack(Items.DIAMOND_AXE)));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(world.settler().getWork().status(world.jobBlock()) == JobStatus.WORKING,
                        "Not working yet: " + world.settler().getWork().status(world.jobBlock())))
                .thenIdle(250)
                .thenExecute(() -> {
                    helper.assertTrue(onBelt(world.settler(), Items.IRON_AXE) && !onBelt(world.settler(), Items.DIAMOND_AXE), "The belt is unchanged");
                    helper.assertTrue(has(world.storage(level), Items.DIAMOND_AXE), "The diamond axe stays in the collect inventory");
                    finish(level, world);
                })
                .thenSucceed();
    }

    /** A tool above the allowed tier in the collect inventory is ignored: the settler works with what it has. */
    private static void ignoresAboveAllowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 45, List.of(new ItemStack(Items.STONE_AXE)), List.of(new ItemStack(Items.NETHERITE_AXE)));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(world.settler().getWork().status(world.jobBlock()) == JobStatus.WORKING,
                        "Not working yet: " + world.settler().getWork().status(world.jobBlock())))
                .thenIdle(250)
                .thenExecute(() -> {
                    helper.assertTrue(onBelt(world.settler(), Items.STONE_AXE) && !onBelt(world.settler(), Items.NETHERITE_AXE), "No netherite axe taken at skill 45");
                    helper.assertTrue(has(world.storage(level), Items.NETHERITE_AXE), "It stays in the collect inventory");
                    finish(level, world);
                })
                .thenSucceed();
    }

    /** The collect inventory is searched once per in-game hour while the need is unmet: a tool put in later waits. */
    private static void searchThrottled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 45, List.of(new ItemStack(Items.STONE_AXE)), List.of());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(world.settler().getWork().status(world.jobBlock()) == JobStatus.WORKING,
                        "Not working yet: " + world.settler().getWork().status(world.jobBlock())))
                .thenExecute(() -> world.storage(level).setItem(0, new ItemStack(Items.IRON_AXE)))
                .thenIdle(300)
                .thenExecute(() -> {
                    helper.assertFalse(onBelt(world.settler(), Items.IRON_AXE), "The search found nothing a moment ago: it waits for the hour");
                    finish(level, world);
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- catch-up at a real JobBlock

    /** Runs one simulated hour of work once the live scan exists; the arrangement is made again right before it. */
    private static void catchUp(GameTestHelper helper, int skill, List<ItemStack> belt, List<ItemStack> collect, Consumer<World> check) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, skill, belt, collect);
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || LumberjackGameTests.foundNothing(world.settler(), world.job())) {
                return;
            }
            done[0] = true;
            // The settler may have fetched the tool live meanwhile: start from the same arrangement.
            arrange(level, world, skill, belt, collect);
            long yesterday = Math.floorDiv(level.getOverworldClockTime(), DAY) * DAY - DAY;
            long from = yesterday + world.settler().getWork().dayTimes(Math.floorDiv(yesterday, DAY)).wake();
            world.settler().getWork().setCatchUp(from, from + 1000);
            CatchUp.runNow(level, List.of(world.settler()));
            check.accept(world);
            finish(level, world);
            helper.succeed();
        });
    }

    /** The settler takes the iron axe from the collect inventory while it is away and keeps the stone axe. */
    private static void catchUpSwapFreeBelt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        catchUp(helper, 45, List.of(new ItemStack(Items.STONE_AXE)), List.of(new ItemStack(Items.IRON_AXE)), world -> {
            helper.assertTrue(onBelt(world.settler(), Items.IRON_AXE), "The iron axe was taken");
            helper.assertTrue(onBelt(world.settler(), Items.STONE_AXE), "The stone axe stays");
            helper.assertFalse(has(world.storage(level), Items.IRON_AXE), "It left the collect inventory");
        });
    }

    /** With a full belt the weakest axe goes back while it is away. */
    private static void catchUpSwapFullBelt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        catchUp(helper, 45, List.of(new ItemStack(Items.STONE_AXE), new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.SHEARS), new ItemStack(Items.FISHING_ROD)),
                List.of(new ItemStack(Items.IRON_AXE)), world -> {
                    helper.assertTrue(onBelt(world.settler(), Items.IRON_AXE), "The iron axe was taken");
                    helper.assertFalse(onBelt(world.settler(), Items.WOODEN_AXE), "The weakest axe was put back");
                    helper.assertTrue(has(world.storage(level), Items.WOODEN_AXE), "It is in the collect inventory");
                });
    }

    /** No swap while the requirement is met, also while it is away. */
    private static void catchUpNoSwap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        catchUp(helper, 45, List.of(new ItemStack(Items.IRON_AXE)), List.of(new ItemStack(Items.DIAMOND_AXE)), world -> {
            helper.assertTrue(onBelt(world.settler(), Items.IRON_AXE) && !onBelt(world.settler(), Items.DIAMOND_AXE), "The belt is unchanged");
            helper.assertTrue(has(world.storage(level), Items.DIAMOND_AXE), "The diamond axe stays");
        });
    }
}
