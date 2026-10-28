package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.StationTypesTab;
import com.gilfort.civmod.menu.StationTypesTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.OccupiedPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;
import com.google.gson.JsonElement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Smelter's Specializations (#129, vault {@code Jobs/Cook.md}, {@code Jobs/Founder.md},
 * {@code Jobs/Smelter.md}): the Smelter JobBlock's Station Types tab unlocks the Cook (smoker, {@code smoking}) and the
 * Founder (blast furnace, {@code blasting}); both work like the Smelter with parallel stations and fuel.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SmelterSpecializationGameTests {

    /** Where the Smelter JobBlock stands. */
    private static final BlockPos BLOCK = SmelterGameTests.BLOCK;
    /** Where the first station stands. */
    private static final BlockPos STATION = SmelterGameTests.FURNACE;

    private SmelterSpecializationGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "smelter_station_types", SmelterSpecializationGameTests::stationTypes);
            register(helper, "cook_fills_request", SmelterSpecializationGameTests::cookFillsRequest);
            register(helper, "founder_fills_request", SmelterSpecializationGameTests::founderFillsRequest);
            register(helper, "founder_parallel_stations", SmelterSpecializationGameTests::founderParallelStations);
            register(helper, "founder_faster_than_smelter", SmelterSpecializationGameTests::founderFaster);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    static Job cook() {
        return StationTypeGameTests.job(CivMod.id("cook"));
    }

    static Job founder() {
        return StationTypeGameTests.job(CivMod.id("founder"));
    }

    /**
     * The Cook and the Founder specialize the Smelter with its balancing (job factor 1.0, base EXP 1, hunger 0.5,
     * {@code parallel_stations} [1, 2, 3, 4, 5], coal and charcoal) and their own recipe type, station and look.
     * The Smelter JobBlock shows the Station Types tab (furnace locked in slot 1) and a Patterns tab per
     * Specialization; a smoker in slot 2 offers the Cook, a blast furnace overwriting it offers the Founder and the Cook
     * falls back to the Smelter.
     */
    private static void stationTypes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job smelter = ModJobs.SMELTER.get();
        assertSpecialization(helper, cook(), Items.SMOKER, RecipeType.SMOKING, Blocks.SMOKER, Items.COOKED_BEEF, Items.COOKED_BEEF,
                "Cooks food in smokers, several at once, burning fuel");
        assertSpecialization(helper, founder(), Items.BLAST_FURNACE, RecipeType.BLASTING, Blocks.BLAST_FURNACE, Items.IRON_INGOT, Items.RAW_IRON,
                "Smelts ores and metal in blast furnaces, several at once, burning fuel");

        BlockPos pos = SmelterGameTests.smelterBlock(helper, BLOCK);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = StationTypeGameTests.menu(helper, player, pos);
        helper.assertTrue(menu.tab(StationTypesTab.class).map(StationTypesTab::shown).equals(Optional.of(true)), "The Smelter has a Station Types tab");
        helper.assertTrue(menu.patternJobs().containsAll(List.of(smelter, cook(), founder())), "Patterns tabs per Job: " + menu.patternJobs());
        helper.assertTrue(StationTypesTabData.of(level, pos).item(0) == Items.FURNACE && StationTypesTabData.of(level, pos).open() == 1,
                "Level 1: only the furnace slot");

        StationTypeGameTests.setLevel(helper, BLOCK, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.SMOKER), "A smoker unlocks the Cook");
        helper.assertTrue(offered(level, pos).equals(List.of(smelter, cook())), "Smelter and Cook offered: " + offered(level, pos));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), data, settlement);
        helper.assertTrue(SettlementWorld.assign(level, pos, settler, cook()) == JobAssignments.AssignResult.OK, "Cook assigned");

        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.BLAST_FURNACE), "The blast furnace replaces the smoker");
        helper.assertTrue(offered(level, pos).equals(List.of(smelter, founder())), "Smelter and Founder offered: " + offered(level, pos));
        SettlementBlock block = StationTypeGameTests.block(level, pos);
        helper.assertTrue(block.assignee(settler.getUUID()).map(SettlementBlock.Assignee::job).equals(Optional.of(smelter)),
                "The Cook falls back to the Smelter");
        settler.discard();
        helper.succeed();
    }

    private static void assertSpecialization(GameTestHelper helper, Job job, Item unlock, RecipeType<?> type, Block station, Item bubble,
            Item display, String description) {
        String name = job == null ? "null" : job.id().toString();
        helper.assertTrue(job != null && job.parent().equals(Optional.of(ModJobs.SMELTER.get())), name + " specializes the Smelter");
        helper.assertTrue(job.unlockItem().equals(Optional.of(unlock)) && job.icon().is(unlock), name + ": unlock item and icon " + unlock);
        helper.assertTrue(job.bubbleIcon().is(bubble) && job.displayItem().equals(Optional.of(display)), name + ": bubble and display item");
        helper.assertTrue(job.jobFactor() == 1.0 && job.baseExp() == 1 && job.hungerFactor() == 0.5,
                name + ": job factor 1.0, base EXP 1, hunger 0.5: " + job.jobFactor() + " / " + job.baseExp() + " / " + job.hungerFactor());
        ProducingProfile profile = job.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(type)), name + ": only " + type);
        helper.assertTrue(profile.fuel().equals(Optional.of(List.of(Items.COAL, Items.CHARCOAL))), name + ": coal and charcoal");
        helper.assertTrue(JobBalancing.of(job).parallelStations().equals(Optional.of(List.of(1, 2, 3, 4, 5))), name + ": parallel_stations");
        helper.assertTrue(JobBalancing.of(job).stationExtras().isEmpty(), name + ": no station extra");
        helper.setBlock(STATION, station);
        helper.assertTrue(job.isValidWorkPlace(helper.getLevel(), helper.absolutePos(STATION)), name + " works at " + station);
        helper.setBlock(STATION, Blocks.FURNACE);
        helper.assertFalse(job.isValidWorkPlace(helper.getLevel(), helper.absolutePos(STATION)), name + " does not work at a furnace");
        helper.setBlock(STATION, Blocks.AIR);
        String key = "job.civmod." + job.id().getPath() + ".description";
        JsonElement text =GuiTextGameTests.lang(helper).get(key);
        helper.assertTrue(text != null && text.getAsString().equals(description), name + " description: " + text);
    }

    /**
     * A Clerk's Min rule on 4 cooked beef is filled by a Cook from raw beef at a smoker; one coal burns for the 4 items.
     */
    private static void cookFillsRequest(GameTestHelper helper) {
        fillsRequest(helper, cook(), Items.SMOKER, Blocks.SMOKER, "cooked_beef_from_smoking", Items.BEEF, Items.COOKED_BEEF);
    }

    /**
     * A Clerk's Min rule on 4 iron ingots is filled by a Founder from raw iron at a blast furnace; one coal burns for the
     * 4 items.
     */
    private static void founderFillsRequest(GameTestHelper helper) {
        fillsRequest(helper, founder(), Items.BLAST_FURNACE, Blocks.BLAST_FURNACE, "iron_ingot_from_blasting_raw_iron", Items.RAW_IRON,
                Items.IRON_INGOT);
    }

    private static void fillsRequest(GameTestHelper helper, Job job, Item unlock, Block station, String recipe, Item input, Item output) {
        ServerLevel level = helper.getLevel();
        // Bread first: the settlers' provisions come from the first edible stack, not from the raw beef.
        Setup setup = setup(helper, job, unlock, station, recipe, input,
                List.of(new ItemStack(Items.BREAD, 32), new ItemStack(input, 4), new ItemStack(Items.COAL, 3)));
        helper.assertTrue(StockRules.setRule(setup.data(), setup.clerk(), StockRule.minimum(0, output, 4, 0)), "Rule set");
        StockRules.check(level, setup.clerk(), ClerkGameTests.MORNING);
        Settler settler = worker(helper, setup, job);
        CraftspersonGameTests.catchUp(level, settler, 6000L);

        helper.assertTrue(SmelterGameTests.count(level, setup.clerk(), output) == 4, "4 " + output + " delivered: "
                + SmelterGameTests.count(level, setup.clerk(), output) + ", status " + settler.getWork().status(setup.block()));
        helper.assertTrue(SmelterGameTests.count(level, setup.clerk(), input) == 0, "The input was used");
        helper.assertTrue(SmelterGameTests.count(level, setup.clerk(), Items.COAL) == 2,
                "One coal burned for 4 items: " + SmelterGameTests.count(level, setup.clerk(), Items.COAL));
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A level-2 JobBlock's Founder smelts at 2 blast furnaces in parallel ({@code parallel_stations} [1, 2, 3, 4, 5]):
     * 4 ingots in the time of 2 per blast furnace, each burns its own coal and both are occupied by the Founder; a third
     * blast furnace further away and a furnace stay free. (Level 2 only: a bigger JobBlock would reach into the
     * neighbouring tests' settlements.)
     */
    private static void founderParallelStations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, founder(), Items.BLAST_FURNACE, Blocks.BLAST_FURNACE, "iron_ingot_from_blasting_raw_iron", Items.RAW_IRON,
                List.of(new ItemStack(Items.RAW_IRON, 4), new ItemStack(Items.COAL, 3)));
        List<BlockPos> stations = List.of(STATION, new BlockPos(3, 1, 7));
        BlockPos spare = new BlockPos(6, 1, 3);
        helper.setBlock(stations.getLast(), Blocks.BLAST_FURNACE);
        helper.setBlock(spare, Blocks.BLAST_FURNACE);
        // A furnace is no station of the Founder.
        BlockPos furnace = new BlockPos(3, 1, 3);
        helper.setBlock(furnace, Blocks.FURNACE);
        Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 4, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup, founder());
        // Level 1: 157 ticks per item and blast furnace; 2 rounds of charges fit, 4 single charges would not.
        SmelterGameTests.catchUpWork(level, settler, 0L, 400.0);

        helper.assertTrue(SmelterGameTests.count(level, setup.clerk(), Items.IRON_INGOT) == 4, "4 ingots from 2 blast furnaces: "
                + SmelterGameTests.count(level, setup.clerk(), Items.IRON_INGOT) + ", status " + settler.getWork().status(setup.block()));
        helper.assertTrue(SmelterGameTests.count(level, setup.clerk(), Items.COAL) == 1,
                "One coal per blast furnace: " + SmelterGameTests.count(level, setup.clerk(), Items.COAL));
        long day = DayClock.day(level) - 1;
        for (BlockPos station : stations) {
            helper.assertTrue(OccupiedPlaces.occupant(setup.data(), helper.absolutePos(station), day).map(OccupiedPlaces.Occupation::settler)
                    .equals(Optional.of(settler.getUUID())), "Occupied by the Founder: " + station);
        }
        for (BlockPos free : List.of(spare, furnace)) {
            helper.assertTrue(OccupiedPlaces.occupant(setup.data(), helper.absolutePos(free), day).isEmpty(), "Stays free: " + free);
        }
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A blast furnace cooks twice as fast as a furnace (vanilla cooking speed 2 on the recipe's 200 ticks): at level 1
     * an iron ingot takes about 157 ticks, so 4 are done after 700 ticks where the Smelter makes 2
     * ({@code smelter_speed_and_exp}); EXP 1 per item × JobBlock level.
     */
    private static void founderFaster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, founder(), Items.BLAST_FURNACE, Blocks.BLAST_FURNACE, "iron_ingot_from_blasting_raw_iron", Items.RAW_IRON,
                List.of(new ItemStack(Items.RAW_IRON, 10), new ItemStack(Items.COAL, 2)));
        Requests.post(setup.data(), setup.settlement(), Items.IRON_INGOT, 10, Optional.of(setup.clerk()), Optional.empty());
        Settler settler = worker(helper, setup, founder());
        SmelterGameTests.catchUpWork(level, settler, 0L, 700.0);

        int made = settler.getWork().buffer(setup.block()).counts().getOrDefault(Items.IRON_INGOT, 0);
        helper.assertTrue(made == 4, "4 ingots after 700 ticks at level 1 (the Smelter makes 2): " + made + ", status "
                + settler.getWork().status(setup.block()));
        int exp = settler.getSkills().get(founder()).exp();
        helper.assertTrue(exp == 8, "EXP 1 per item × JobBlock level 2: " + exp);
        cleanUp(setup, settler);
        helper.succeed();
    }

    // ---------------------------------------------------------------- setup

    private record Setup(BlockPos clerk, BlockPos block, SettlementData data, Settlement settlement) {
    }

    /**
     * A working Clerk whose storage holds the items and a level-2 Smelter JobBlock with the Specialization unlocked,
     * one station and a checked pattern of the recipe.
     */
    private static Setup setup(GameTestHelper helper, Job job, Item unlock, Block station, String recipe, Item input, List<ItemStack> stock) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int i = 0; i < stock.size(); i++) {
            chest.setItem(i, stock.get(i).copy());
        }
        BlockPos block = SmelterGameTests.smelterBlock(helper, BLOCK);
        StationTypeGameTests.setLevel(helper, BLOCK, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, block, 1, unlock), "Unlocked by " + unlock);
        helper.setBlock(STATION, station);
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(recipe));
        Pattern pattern = Patterns.transfer(level, block, job, 0, Optional.of(key), List.of(input)).orElseThrow();
        helper.assertTrue(pattern.checked() || Patterns.toggleCheck(level, block, job, 0), "Pattern checked: " + pattern);
        return new Setup(clerk, block, data, settlement);
    }

    /** A settler working the Specialization at the setup's JobBlock. */
    private static Settler worker(GameTestHelper helper, Setup setup, Job job) {
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), setup.data(), setup.settlement());
        settler.setCustomName(Component.literal("Ida"));
        settler.getFood().setMorningDay(Math.floorDiv(helper.getLevel().getOverworldClockTime(), 24000L));
        helper.assertTrue(TestAssignments.assign(setup.data(), setup.block(), settler.getUUID(), "Ida", setup.settlement().id(), List.of(), job)
                == JobAssignments.AssignResult.OK, job + " assigned");
        settler.addJob(new SettlerJob(job, setup.block()));
        return settler;
    }

    private static List<Job> offered(ServerLevel level, BlockPos pos) {
        return JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList();
    }

    private static void cleanUp(Setup setup, Settler settler) {
        TestAssignments.unassign(setup.data(), setup.clerk());
        TestAssignments.unassign(setup.data(), setup.block());
        settler.discard();
    }
}
