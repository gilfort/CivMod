package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.ToolTexts;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the tool visibility (vault {@code Systems/Needs.md}, decisions of 2026-11-04 and 2026-11-05): the Jobs tab
 * line per band, the Needs tab rows and the Thought Bubble of an unmet tool need. The seam is the data the settler window
 * is built from ({@link SettlerInfo}, {@link NeedsReport}, {@link ToolTexts}) and the bubbles the settler sends; rendering
 * is client-tested.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ToolVisibilityGameTests {

    private static final int BLOCK_LEVEL = 5;

    private ToolVisibilityGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "tool_visibility_jobs_line_bands", ToolVisibilityGameTests::jobsLineBands);
            register(helper, "tool_visibility_jobs_line_none", ToolVisibilityGameTests::jobsLineNone);
            register(helper, "tool_visibility_needs_row", ToolVisibilityGameTests::needsRow);
            register(helper, "tool_visibility_one_row_per_job", ToolVisibilityGameTests::oneRowPerJob);
            register(helper, "tool_visibility_no_tool_row", ToolVisibilityGameTests::noToolRow);
            register(helper, "tool_visibility_thought_bubble", ToolVisibilityGameTests::thoughtBubble);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static BlockPos place(GameTestHelper helper, BlockPos relative, Job job) {
        BlockPos pos = helper.absolutePos(relative);
        TestSettlements.place(helper, pos, job, BLOCK_LEVEL);
        return pos;
    }

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

    private static void belt(Settler settler, ItemStack... tools) {
        for (int i = 0; i < Settler.BELT_SIZE; i++) {
            settler.getInventory().setItem(Settler.BELT_START + i, i < tools.length ? tools[i] : ItemStack.EMPTY);
        }
    }

    private static void end(GameTestHelper helper, Settler settler, BlockPos... jobBlocks) {
        settler.discard();
        for (BlockPos pos : jobBlocks) {
            SettlementManager.remove(SettlementData.get(helper.getLevel()), pos, 0L, SettlementManager.Listener.NONE);
        }
        helper.succeed();
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : component.getString();
    }

    /** The English text of a component built from a translation key. */
    private static String text(JsonObject lang, Component component) {
        return lang.get(key(component)).getAsString();
    }

    private static List<NeedsReport.Row> toolRows(Settler settler) {
        return settler.getWork().toolRows();
    }

    // ---------------------------------------------------------------- Jobs tab

    /** The line follows the band of the Job skill and changes exactly at the edges 20/21, 40/41, 60/61, 80/81. */
    private static void jobsLineBands(GameTestHelper helper) {
        JsonObject lang = GuiTextGameTests.lang(helper);
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.MINER.get());
        Settler settler = settler(helper, jobBlock, ModJobs.MINER.get(), 1);
        SettlerJob job = settler.getJobs().get(0);
        String any = "Tool: any – Stone";
        String stone = "Tool: Stone – Iron";
        String iron = "Tool: Iron – Diamond";
        String diamond = "Tool: Diamond (enchanted) – Netherite";
        String netherite = "Tool: Netherite (enchanted)";
        Object[][] cases = {{1, any}, {20, any}, {21, stone}, {40, stone}, {41, iron}, {60, iron}, {61, diamond}, {80, diamond}, {81, netherite}, {100, netherite}};
        for (Object[] one : cases) {
            int skill = (Integer) one[0];
            settler.getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(skill, 0));
            SettlerInfo info = SettlerInfo.of(settler);
            int band = info.jobStates().get(0).toolBand();
            String line = text(lang, ToolTexts.line(band));
            helper.assertTrue(line.equals(one[1]), "Skill " + skill + ": " + line + " instead of " + one[1]);
        }
        helper.assertTrue(text(lang, ToolTexts.lineTooltip()).contains("Stone"), "A tooltip explains the tiers");
        helper.assertTrue(settler.getWork().toolBand(job) == 5, "Skill 100 is band 5");
        end(helper, settler, jobBlock);
    }

    /** A Job without tool progression (fishing rod) shows no line; a tool Job does. */
    private static void jobsLineNone(GameTestHelper helper) {
        BlockPos fisherman = place(helper, new BlockPos(1, 1, 1), ModJobs.FISHERMAN.get());
        BlockPos lumberjack = place(helper, new BlockPos(3, 1, 1), ModJobs.LUMBERJACK.get());
        Settler settler = settler(helper, fisherman, ModJobs.FISHERMAN.get(), 90);
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), lumberjack));
        SettlerInfo info = SettlerInfo.of(settler);
        helper.assertTrue(info.jobStates().get(0).toolBand() == 0, "The Fisherman shows no tool line: " + info.jobStates().get(0).toolBand());
        helper.assertTrue(info.jobStates().get(1).toolBand() == 1, "The Lumberjack 1 shows band 1: " + info.jobStates().get(1).toolBand());
        end(helper, settler, fisherman, lumberjack);
    }

    // ---------------------------------------------------------------- Needs tab

    /** A met need reads "Happy with tool", an unmet one "Requires better tool"; icon is the Job's tool, tooltip the Job's name. */
    private static void needsRow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        JsonObject lang = GuiTextGameTests.lang(helper);
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.MINER.get());
        Settler settler = settler(helper, jobBlock, ModJobs.MINER.get(), 30);
        SettlerJob job = settler.getJobs().get(0);
        belt(settler, new ItemStack(Items.STONE_PICKAXE));
        settler.getWork().checkNeedsNow(level, 100L);
        NeedsReport.Row row = toolRows(settler).get(0);
        helper.assertTrue(row.met() && text(lang, row.detail()).equals("Happy with tool"), "Met: " + row.detail().getString());
        helper.assertTrue(row.tier() == 2, "The row sits in the tier of the Job skill: " + row.tier());
        helper.assertTrue(row.icons().size() == 1 && row.icons().get(0).is(job.jobType().profile(ResourceCollectionProfile.class).orElseThrow().toolIcon()),
                "The Job's tool as icon: " + row.icons());
        helper.assertTrue(row.tooltip().getString().equals(job.jobType().displayName().getString()), "The Job's name in the tooltip: " + row.tooltip().getString());

        belt(settler, new ItemStack(Items.WOODEN_PICKAXE));
        settler.getWork().checkNeedsNow(level, 101L);
        row = toolRows(settler).get(0);
        helper.assertTrue(!row.met() && text(lang, row.detail()).equals("Requires better tool"), "Unmet: " + row.detail().getString());
        helper.assertTrue(!text(lang, row.detail()).toLowerCase(java.util.Locale.ROOT).contains("stone") && !text(lang, row.name()).toLowerCase(java.util.Locale.ROOT).contains("stone"),
                "The row names no material");
        helper.assertTrue(SettlerInfo.of(settler).needs().rows().stream().anyMatch(one -> one.tooltip().getString().equals(job.jobType().displayName().getString())
                && !one.met()), "The Needs tab report carries the row");

        belt(settler, new ItemStack(Items.STONE_PICKAXE));
        settler.getWork().checkNeedsNow(level, 101L);
        helper.assertTrue(toolRows(settler).get(0).met(), "Met again at once");
        end(helper, settler, jobBlock);
    }

    /** One row per tool Job (judged per Job), none for a Job without tool progression. */
    private static void oneRowPerJob(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lumberjack = place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get());
        BlockPos miner = place(helper, new BlockPos(3, 1, 1), ModJobs.MINER.get());
        BlockPos fisherman = place(helper, new BlockPos(5, 1, 1), ModJobs.FISHERMAN.get());
        Settler settler = settler(helper, lumberjack, ModJobs.LUMBERJACK.get(), 5);
        settler.getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(70, 0));
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), miner));
        settler.addJob(new SettlerJob(ModJobs.FISHERMAN.get(), fisherman));
        belt(settler, new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.WOODEN_PICKAXE));
        settler.getWork().checkNeedsNow(level, 100L);
        List<NeedsReport.Row> rows = toolRows(settler);
        helper.assertTrue(rows.size() == 2, "Two tool Jobs, two rows: " + rows.size());
        helper.assertTrue(rows.get(0).met() && !rows.get(1).met(), "Lumberjack 5 is happy, Miner 70 needs better: " + rows.get(0).met() + ", " + rows.get(1).met());
        helper.assertTrue(rows.get(0).tooltip().getString().equals(ModJobs.LUMBERJACK.get().displayName().getString())
                && rows.get(1).tooltip().getString().equals(ModJobs.MINER.get().displayName().getString()), "Each row names its Job");
        end(helper, settler, lumberjack, miner, fisherman);

        BlockPos fishing = place(helper, new BlockPos(1, 1, 3), ModJobs.FISHERMAN.get());
        Settler fisher = settler(helper, fishing, ModJobs.FISHERMAN.get(), 50);
        fisher.getWork().checkNeedsNow(level, 100L);
        helper.assertTrue(toolRows(fisher).isEmpty(), "A settler without tool Jobs shows no tool row");
        helper.assertTrue(SettlerInfo.of(fisher).needs().rows().stream().noneMatch(row -> row.tooltip().getString().length() > 0), "No row carries a Job tooltip");
        end(helper, fisher, fishing);
    }

    /** A settler without a usable tool keeps "No tool": its row does not say "Requires better tool". */
    private static void noToolRow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        JsonObject lang = GuiTextGameTests.lang(helper);
        BlockPos jobBlock = place(helper, new BlockPos(1, 1, 1), ModJobs.MINER.get());
        Settler settler = settler(helper, jobBlock, ModJobs.MINER.get(), 10);
        belt(settler);
        settler.getWork().checkNeedsNow(level, 100L);
        settler.getWork().checkNeedsNow(level, 101L);
        NeedsReport.Row row = toolRows(settler).get(0);
        helper.assertTrue(!row.met() && text(lang, row.detail()).equals("No tool"), "No tool at all: " + row.detail().getString());
        belt(settler, new ItemStack(Items.NETHERITE_PICKAXE));
        settler.getWork().checkNeedsNow(level, 102L);
        row = toolRows(settler).get(0);
        helper.assertTrue(text(lang, row.detail()).equals("No tool"), "A tool above the allowed tier gives no hint: " + row.detail().getString());
        end(helper, settler, jobBlock);
    }

    // ---------------------------------------------------------------- Thought Bubble

    /** While the tool need of the worked Job is unmet the bubble shows the Job's tool; it goes off when the need is met. */
    private static void thoughtBubble(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        TestSettlements.setBlock(helper, relative, ModBlocks.LUMBERJACK_BLOCK.get());
        TestSettlements.place(helper, helper.absolutePos(relative), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        for (int x = 4; x < 8; x++) {
            for (int z = 0; z < 4; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
            }
        }
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        helper.setBlock(new BlockPos(7, 1, 7), Blocks.CAMPFIRE);
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 2), data, settlement);
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(30, 0));
        JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(4, 1, 1)), true, false);
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Tester", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Assigned");
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
        Item toolIcon = ModJobs.LUMBERJACK.get().profile(ResourceCollectionProfile.class).orElseThrow().toolIcon();
        belt(settler, new ItemStack(Items.WOODEN_AXE));
        settler.getWork().checkNeedsNow(level, 100L);
        settler.getWork().decideNow(level);
        ThoughtBubble.Show on = new ThoughtBubble.Show(settler.getId(), ThoughtBubble.NEED_UNMET, toolIcon, true);
        helper.assertTrue(settler.getWork().recentThoughts().contains(on), "The bubble shows the Job's tool: " + settler.getWork().recentThoughts()
                + " (status " + settler.getWork().status(jobBlock) + ", rows " + toolRows(settler).get(0).detail().getString() + ", states " + settler.getWork().toolNeedStates() + ", level " + settler.getWork().toolLevel(settler.getJobs().get(0)) + ")");

        belt(settler, new ItemStack(Items.STONE_AXE));
        settler.getWork().decideNow(level);
        // Another need (the bed) may take the bubble over; either way it no longer shows the tool.
        ThoughtBubble.Show last = settler.getWork().recentThoughts().stream().filter(show -> show.bubble() == ThoughtBubble.NEED_UNMET).reduce((x, y) -> y).orElseThrow();
        helper.assertTrue(!last.on() || last.icon() != toolIcon, "The tool bubble goes away when the need is met: " + settler.getWork().recentThoughts());
        settler.discard();
        TestAssignments.unassign(data, jobBlock);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
    }
}
