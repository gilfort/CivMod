package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.UpgradeCosts;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.StockRules;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for several settlers per JobBlock and the job switcher (#93, vault {@code Systems/JobBlock.md},
 * {@code Systems/Jobs.md}, {@code Systems/Producing Jobs.md}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobSwitcherGameTests {

    private static final BlockPos BLOCK = new BlockPos(0, 64, 0);
    private static final Identifier STONECUTTER = Identifier.fromNamespaceAndPath("civmod_example", "stonecutter");

    private JobSwitcherGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "settlers_per_level", JobSwitcherGameTests::settlersPerLevel);
            register(helper, "settlers_two_jobs_at_one_block", JobSwitcherGameTests::twoJobsAtOneBlock);
            register(helper, "job_switcher", JobSwitcherGameTests::switcher);
            register(helper, "checkmark_limit_best_settler", JobSwitcherGameTests::checkmarkLimitOfBestSettler);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static int[] settlers(Job job) {
        int[] places = new int[SettlementRules.MAX_LEVEL];
        for (int level = SettlementRules.MIN_LEVEL; level <= SettlementRules.MAX_LEVEL; level++) {
            places[level - 1] = UpgradeCosts.settlers(job, level);
        }
        return places;
    }

    private static void assertSettlers(GameTestHelper helper, Job job, int... expected) {
        helper.assertTrue(java.util.Arrays.equals(settlers(job), expected),
                job + ": " + java.util.Arrays.toString(settlers(job)) + " instead of " + java.util.Arrays.toString(expected));
    }

    /**
     * The settlers a JobBlock holds per level come from the {@code settlers} list of {@code civmod_upgrades/<job>.json}:
     * Producing 1 / 1 / 2 / 2 / 3, Resource Collection 1, Mission Board 1 / 2 / 4 / 6 / 10, Clerk 1 and Deliverymen
     * 1 / 2 / 4 / 6 / 8; a Job without a list has 1. Later packs replace the list; the costs stay readable.
     */
    private static void settlersPerLevel(GameTestHelper helper) {
        assertSettlers(helper, ModJobs.CRAFTSPERSON.get(), 1, 1, 2, 2, 3);
        assertSettlers(helper, ModJobs.LUMBERJACK.get(), 1, 1, 1, 1, 1);
        assertSettlers(helper, ModJobs.MINER.get(), 1, 1, 1, 1, 1);
        assertSettlers(helper, ModJobs.FISHERMAN.get(), 1, 1, 1, 1, 1);
        assertSettlers(helper, ModJobs.ADVENTURER.get(), 1, 2, 4, 6, 10);
        assertSettlers(helper, ModJobs.CLERK.get(), 1, 1, 1, 1, 1);
        assertSettlers(helper, ModJobs.DELIVERYMAN.get(), 1, 2, 4, 6, 8);
        assertSettlers(helper, StationTypeGameTests.job(CivMod.id("gametest_dummy")), 2, 3, 4, 5, 6);
        assertSettlers(helper, StationTypeGameTests.job(CivMod.id("gametest_mason")), 1, 1, 1, 1, 1);
        helper.assertFalse(UpgradeCosts.get(ModJobs.CRAFTSPERSON.get(), 2).isEmpty(), "The costs next to the list stay readable");

        List<JsonElement> files = List.of(JsonParser.parseString("{\"settlers\": [1, 1, 2, 2, 3], \"2\": []}"),
                JsonParser.parseString("{\"settlers\": [2, 2, 2, 2, 2]}"), JsonParser.parseString("{\"3\": []}"));
        helper.assertTrue(UpgradeCosts.mergeSettlers(files).equals(java.util.Optional.of(List.of(2, 2, 2, 2, 2))),
                "A later pack replaces the list, one without keeps it: " + UpgradeCosts.mergeSettlers(files));

        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        for (int level = SettlementRules.MIN_LEVEL; level <= SettlementRules.MAX_LEVEL; level++) {
            SettlementData data = new SettlementData();
            SettlementManager.place(data, BLOCK, craftsperson, level, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
            SettlementBlock block = data.settlementAt(BLOCK).flatMap(s -> s.block(BLOCK)).orElseThrow();
            int expected = new int[] {1, 1, 2, 2, 3}[level - 1];
            helper.assertTrue(JobPlaces.lines(block).equals(List.of(new JobPlaces.Line(craftsperson, 0, expected))),
                    "Craftsperson JobBlock at level " + level + ": " + JobPlaces.lines(block));
        }
        helper.succeed();
    }

    /**
     * A level-3 Craftsperson JobBlock with a Stonecutter unlocked holds two settlers, each in any Job unlocked there:
     * one Craftsperson and one Stonecutter work at once, each with its own library; a third settler finds the block
     * full, and a settler still holds one Job per JobBlock. Its own batch: a level-3 block reaches into neighbouring tests.
     */
    private static void twoJobsAtOneBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        Job stonecutter = StationTypeGameTests.job(STONECUTTER);
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.STONECUTTER), "Stonecutter unlocked");
        StationTypeGameTests.setLevel(helper, relative, 3);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        UUID cora = UUID.randomUUID();
        UUID stella = UUID.randomUUID();
        helper.assertTrue(JobAssignments.assign(data, pos, cora, "Cora", settlement.id(), List.of(), craftsperson) == AssignResult.OK,
                "Cora: Craftsperson");
        helper.assertTrue(JobAssignments.assign(data, pos, cora, "Cora", settlement.id(), List.of(craftsperson), stonecutter) == AssignResult.SAME_BLOCK,
                "One Job per JobBlock");
        helper.assertTrue(JobAssignments.assign(data, pos, stella, "Stella", settlement.id(), List.of(), stonecutter) == AssignResult.OK,
                "Stella: Stonecutter at the same block");
        SettlementBlock block = StationTypeGameTests.block(level, pos);
        helper.assertTrue(JobPlaces.lines(block).stream().allMatch(line -> line.taken() == 2 && line.places() == 2)
                && JobPlaces.lines(block).stream().map(JobPlaces.Line::job).toList().containsAll(List.of(craftsperson, stonecutter)),
                "Every Job of the block shares its 2 places: " + JobPlaces.lines(block));
        helper.assertTrue(JobAssignments.assign(data, pos, UUID.randomUUID(), "Carl", settlement.id(), List.of(), craftsperson) == AssignResult.FULL,
                "A third settler finds the block full");

        helper.assertTrue(Patterns.setOutput(level, pos, craftsperson, 0, Items.STICK).orElseThrow().checked(), "Craftsperson: sticks");
        helper.assertTrue(Patterns.setOutput(level, pos, stonecutter, 0, Items.STONE_BRICKS).orElseThrow().checked(), "Stonecutter: bricks");
        helper.assertTrue(Producers.canMake(level, settlement, Items.STICK) && Producers.canMake(level, settlement, Items.STONE_BRICKS),
                "Workshop and Stonecutter at once");
        JobAssignments.unassign(data, pos);
        // Back to level 1: a level-3 block left in the settlement data would push later tests' blocks out.
        StationTypeGameTests.setLevel(helper, relative, 1);
        helper.succeed();
    }

    /**
     * The job switcher: a settler linked to a JobBlock lists the other Jobs unlocked there (Mission Jobs on the Mission
     * Board) and switches in its settler window. The switch keeps the JobBlock link and the priority, releases a running
     * Producing round (claim released, fetched inputs into the own drop-off) and starts the new Job at the settler's
     * level in it.
     */
    private static void switcher(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        Job stonecutter = StationTypeGameTests.job(STONECUTTER);
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        chest.setItem(1, new ItemStack(Items.STICK, 2));
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_AXE, 1, 20));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);

        BlockPos relative = new BlockPos(1, 1, 5);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, relative);
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, craft, 1, Items.STONECUTTER), "Stonecutter unlocked");
        CraftspersonGameTests.define(helper, craft, 0, Items.STONE_AXE);
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        settler.getSkills().set(craftsperson, new SettlerSkills.Skill(15, 0));
        settler.getSkills().set(stonecutter, new SettlerSkills.Skill(3, 0));
        helper.setBlock(new BlockPos(6, 1, 8), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos lumber = helper.absolutePos(new BlockPos(6, 1, 8));
        helper.assertTrue(SettlementWorld.assign(level, lumber, settler, ModJobs.LUMBERJACK.get()) == AssignResult.OK, "Second job: Lumberjack");

        settler.getWork().decideNow(level);
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).orElseThrow().claimOf(settler.getUUID()).isPresent(), "The axe is claimed");
        settler.getWork().buffer(craft).add(new ItemStack(Items.COBBLESTONE, 2));
        SettlerJob lumberJob = settler.getJobs().get(1);
        settler.setJobs(List.of(lumberJob, settler.getJobs().getFirst()));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.snapTo(settler.getX(), settler.getY(), settler.getZ());
        SettlerMenu menu = new SettlerMenu(1, player.getInventory(), settler, SettlerInfo.of(settler));
        // The stonecutter unlocks the example addon's Stonecutter and the GameTest Mason.
        Job mason = StationTypeGameTests.job(CivMod.id("gametest_mason"));
        helper.assertTrue(menu.getInfo().jobStates().get(1).switchTo().equals(List.of(mason, stonecutter)),
                "The Craftsperson can switch to the Jobs the stonecutter unlocks: " + menu.getInfo().jobStates().get(1).switchTo());
        helper.assertTrue(menu.getInfo().jobStates().getFirst().switchTo().isEmpty(), "The Lumberjack block offers nothing else");
        helper.assertFalse(menu.switchJob(player, 1, StationTypeGameTests.job(CivMod.id("gametest_smith"))), "Not unlocked there: refused");
        helper.assertFalse(menu.switchJob(player, 0, stonecutter), "Not offered at the Lumberjack block: refused");

        helper.assertTrue(menu.switchJob(player, 1, stonecutter), "Switched to the Stonecutter");
        helper.assertTrue(settler.getJobs().equals(List.of(lumberJob, new SettlerJob(stonecutter, craft))),
                "Same JobBlock, same priority: " + settler.getJobs());
        helper.assertTrue(menu.getInfo().jobs().equals(settler.getJobs()), "The window shows the new Job: " + menu.getInfo().jobs());
        helper.assertTrue(StationTypeGameTests.block(level, craft).assignee(settler.getUUID()).map(SettlementBlock.Assignee::job)
                .equals(Optional.of(stonecutter)), "The JobBlock lists the settler as Stonecutter");
        helper.assertTrue(Requests.forRule(settlement, clerk, 0).orElseThrow().claims().isEmpty(), "The switch releases the claim");
        helper.assertTrue(((Container) level.getBlockEntity(craft)).countItem(Items.COBBLESTONE) == 2,
                "Fetched inputs go to the own drop-off (the JobBlock)");
        helper.assertTrue(settler.getWork().effectiveLevel(settler.getJobs().get(1)) == 3 && settler.getSkills().get(craftsperson).level() == 15,
                "Skill stays per Job: the Stonecutter works at its own level 3, the Craftsperson level is kept");
        helper.assertTrue(SettlerInfo.of(settler).jobStates().get(1).switchTo().equals(List.of(craftsperson, mason)), "And back to the Craftsperson");

        SettlementData boardData = new SettlementData();
        BlockPos board = new BlockPos(0, 64, 0);
        Job adventurer = ModJobs.ADVENTURER.get();
        SettlementManager.place(boardData, board, adventurer, 2, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        Settlement boardSettlement = boardData.settlementAt(board).orElseThrow();
        UUID scout = UUID.randomUUID();
        helper.assertTrue(JobAssignments.assign(boardData, board, scout, "Sam", boardSettlement.id(), List.of(), adventurer) == AssignResult.OK,
                "Adventurer on the Mission Board");
        List<Job> missionJobs = JobAssignments.switchOptions(boardData, board, scout, List.of());
        helper.assertTrue(!missionJobs.isEmpty() && missionJobs.stream().allMatch(job -> job.parent().filter(adventurer::equals).isPresent()),
                "The Mission Board offers its Mission Jobs: " + missionJobs);
        helper.assertTrue(JobAssignments.switchJob(boardData, board, scout, List.of(), missionJobs.getFirst()), "Switched to a Mission Job");
        helper.assertTrue(JobAssignments.switchOptions(boardData, board, scout, List.of()).contains(adventurer), "And back");

        JobAssignments.unassign(data, clerk);
        SettlementWorld.unassign(level, lumber);
        settler.discard();
        helper.succeed();
    }

    /**
     * Two Craftspersons at one level-3 JobBlock share its library: the checkmark limit is the one of the higher
     * effective skill, whichever of them works; when the best one leaves, the limit drops and the last checkmarks are
     * removed as before. Its own batch: a level-3 block reaches into neighbouring tests.
     */
    private static void checkmarkLimitOfBestSettler(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, relative);
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 1));
        StationTypeGameTests.setLevel(helper, relative, 3);
        Settler novice = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 3), "Nora");
        Settler master = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(4, 1, 3), "Mats");
        novice.getSkills().set(craftsperson, new SettlerSkills.Skill(10, 0));
        master.getSkills().set(craftsperson, new SettlerSkills.Skill(50, 0));
        int noviceLimit = Patterns.checkLimit(10);
        int masterLimit = Patterns.checkLimit(50);
        helper.assertTrue(noviceLimit == 8 && masterLimit == 16, "Limits 8 and 16");

        master.getWork().decideNow(level);
        helper.assertTrue(StationTypeGameTests.block(level, craft).checkLimit(craftsperson) == masterLimit,
                "The master's limit: " + StationTypeGameTests.block(level, craft).checkLimit(craftsperson));
        net.minecraft.world.item.Item[] outputs = {Items.STICK, Items.OAK_PLANKS, Items.TORCH, Items.CHEST, Items.STONE_PICKAXE, Items.STONE_AXE,
                Items.STONE_SHOVEL, Items.LADDER, Items.BOWL, Items.CRAFTING_TABLE};
        for (int slot = 0; slot < outputs.length; slot++) {
            CraftspersonGameTests.define(helper, craft, slot, outputs[slot]);
        }
        novice.getWork().decideNow(level);
        SettlementBlock block = StationTypeGameTests.block(level, craft);
        helper.assertTrue(block.checkLimit(craftsperson) == masterLimit && Patterns.checked(block, craftsperson).size() == outputs.length,
                "The novice works with the master's limit: " + block.checkLimit(craftsperson) + ", " + Patterns.checked(block, craftsperson).size());

        master.removeJob(craft);
        JobAssignments.unassign(SettlementData.get(level), craft, master.getUUID());
        novice.getWork().decideNow(level);
        block = StationTypeGameTests.block(level, craft);
        helper.assertTrue(block.checkLimit(craftsperson) == noviceLimit && Patterns.checked(block, craftsperson).size() == noviceLimit,
                "The master left: limit " + block.checkLimit(craftsperson) + ", checked " + Patterns.checked(block, craftsperson).size());
        helper.assertTrue(block.notice(craftsperson).equals(Optional.of(new SettlementBlock.Notice.Trimmed(outputs.length - noviceLimit, noviceLimit))),
                "Trimmed as before: " + block.notice(craftsperson));
        novice.discard();
        master.discard();
        JobAssignments.unassign(SettlementData.get(level), craft);
        // Back to level 1: a level-3 block left in the settlement data would push later tests' blocks out.
        StationTypeGameTests.setLevel(helper, relative, 1);
        helper.succeed();
    }
}
