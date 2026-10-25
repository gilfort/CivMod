package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.job.StationExtras;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Smith and Station Extras (#111, vault {@code Jobs/Smith.md}, {@code Systems/Producing Jobs.md}): a
 * Specialization of the Craftsperson unlocked by a smithing table, crafting metal recipes and netherite upgrades at a
 * smithing table; an anvil near it replaces its base job factor 0.3 by 1.2.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SmithGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for one execution at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;
    /** The work time the trapdoor tests catch up: enough for 4 executions at job factor 1.2, not at 0.3. */
    private static final long SPEED_TICKS = 1200L;

    private SmithGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "smith_unlock", SmithGameTests::unlock);
            register(helper, "smith_crafts_metal", SmithGameTests::craftsMetal);
            register(helper, "smith_intermediate_outside_domain", SmithGameTests::intermediateOutsideDomain);
            register(helper, "smith_missing_ingots", SmithGameTests::missingIngots);
            register(helper, "smith_netherite_upgrade", SmithGameTests::netheriteUpgrade);
            register(helper, "smith_no_trims", SmithGameTests::noTrims);
            register(helper, "smith_anvil_faster", SmithGameTests::anvilFaster);
            register(helper, "smith_without_anvil_slower", SmithGameTests::withoutAnvilSlower);
            register(helper, "smith_chipped_anvil", SmithGameTests::chippedAnvil);
            register(helper, "smith_extra_fixed_for_day", SmithGameTests::extraFixedForDay);
            register(helper, "station_extras_highest", SmithGameTests::highestExtra);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Smith. */
    static Job smith() {
        return StationTypeGameTests.job(CivMod.id("smith"));
    }

    /** A level-2 Craftsperson JobBlock with the Smith unlocked by a smithing table in its second Station Types slot. */
    static BlockPos smithy(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.SMITHING_TABLE), "A smithing table unlocks the Smith");
        return pos;
    }

    /**
     * The Smith specializes the Craftsperson (job factor 0.3, base EXP 2), is unlocked by a smithing table in a Station
     * Types slot and works {@code crafting} and {@code smithing} recipes at a smithing table.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job smith = smith();
        helper.assertTrue(smith != null && smith.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Smith specializes the Craftsperson");
        helper.assertTrue(smith.unlockItem().equals(Optional.of(Items.SMITHING_TABLE)), "Unlocked by a smithing table: " + smith.unlockItem());
        helper.assertTrue(smith.jobFactor() == 0.3 && smith.baseExp() == 2, "Job factor 0.3, base EXP 2: " + smith.jobFactor() + " / " + smith.baseExp());
        helper.assertTrue(smith.profile(ProducingProfile.class).orElseThrow().recipeTypes().equals(List.of(RecipeType.CRAFTING, RecipeType.SMITHING)),
                "Crafting and smithing recipes");
        BlockPos pos = smithy(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(smith),
                "The job picker offers the Smith");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.SMITHING_TABLE);
        helper.assertTrue(smith.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a smithing table");
        helper.assertFalse(smith.isValidWorkPlace(level, helper.absolutePos(new BlockPos(1, 0, 1))), "Not at the ground");
        helper.succeed();
    }

    /**
     * The Smith crafts metal items: a Clerk request for an iron pickaxe is filled from 3 iron ingots and 2 sticks in
     * stock. A stick pattern (planks only) lies outside the metal domain: greyed out with the domain's state line and
     * not checkable.
     */
    private static void craftsMetal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.STICK, 2)), Items.IRON_PICKAXE);
        BlockPos craft = smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        helper.assertTrue(Patterns.setOutput(level, craft, smith(), 0, Items.IRON_PICKAXE).orElseThrow().checked(), "Iron pickaxe checked");
        helper.assertFalse(Patterns.setOutput(level, craft, smith(), 1, Items.STICK).orElseThrow().checked(), "Sticks are outside the domain");
        helper.assertFalse(Patterns.toggleCheck(level, craft, smith(), 1), "And cannot be checked");
        PatternsTabData tab = PatternsTabData.of(level, craft, smith());
        helper.assertTrue(tab.filtered().contains(1) && !tab.filtered().contains(0)
                && tab.filteredLine().equals(Component.translatable("civmod.gui.patterns.outside_domain")), "Greyed out: " + tab.filtered());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), Items.IRON_PICKAXE) == 1, "The pickaxe arrived (status " + settler.getWork().status(craft) + ")");
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Intermediates outside the domain are not made: with planks but no sticks in stock the pickaxe request reports
     * "Missing: Stick" although the Smith's library holds a stick pattern.
     */
    private static void intermediateOutsideDomain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.OAK_PLANKS, 4)), Items.IRON_PICKAXE);
        BlockPos craft = smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        helper.assertTrue(Patterns.setOutput(level, craft, smith(), 0, Items.IRON_PICKAXE).orElseThrow().checked(), "Iron pickaxe checked");
        Patterns.setOutput(level, craft, smith(), 1, Items.STICK);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), Items.IRON_PICKAXE) == 0, "No pickaxe without sticks");
        List<Item> missing = request(setup, Items.IRON_PICKAXE).missing().stream().map(Request.Missing::item).toList();
        helper.assertTrue(missing.contains(Items.STICK), "Missing: Stick " + missing);
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Missing ingots stay "Missing: 2× Iron Ingot" on the request; the Smith posts no request of its own (no Smelter
     * order).
     */
    private static void missingIngots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.IRON_INGOT, 1), new ItemStack(Items.STICK, 2)), Items.IRON_PICKAXE);
        BlockPos craft = smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        helper.assertTrue(Patterns.setOutput(level, craft, smith(), 0, Items.IRON_PICKAXE).orElseThrow().checked(), "Iron pickaxe checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        List<Request.Missing> missing = request(setup, Items.IRON_PICKAXE).missing();
        helper.assertTrue(missing.contains(new Request.Missing(Items.IRON_INGOT, 2)), "Missing: 2× Iron Ingot " + missing);
        helper.assertTrue(Requests.byPriority(setup.settlement()).stream().noneMatch(found -> found.item() == Items.IRON_INGOT),
                "No request for ingots: " + Requests.byPriority(setup.settlement()));
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A netherite upgrade (template, diamond sword, netherite ingot) at the smithing table keeps the sword's enchantments
     * and damage like the vanilla smithing table.
     */
    private static void netheriteUpgrade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<Enchantment> sharpness = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(sharpness, 3);
        sword.setDamageValue(10);
        Setup setup = setup(helper, List.of(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), sword, new ItemStack(Items.NETHERITE_INGOT)),
                Items.NETHERITE_SWORD);
        BlockPos craft = smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        helper.assertTrue(Patterns.setOutput(level, craft, smith(), 0, Items.NETHERITE_SWORD).orElseThrow().checked(), "Netherite sword checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        ItemStack made = ClerkStorage.stock(level, setup.clerk(), true).contents().stream().filter(stack -> stack.is(Items.NETHERITE_SWORD))
                .findFirst().orElse(ItemStack.EMPTY);
        helper.assertTrue(!made.isEmpty(), "The netherite sword arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(EnchantmentHelper.getItemEnchantmentLevel(sharpness, made) == 3 && made.getDamageValue() == 10,
                "Enchantments and damage kept: " + made.getEnchantments() + ", damage " + made.getDamageValue());
        cleanUp(setup, settler);
        helper.succeed();
    }

    /** Only {@code smithing_transform}: an armor trim ({@code smithing_trim}) is no pattern, the netherite upgrade is. */
    private static void noTrims(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<RecipeType<?>> types = smith().profile(ProducingProfile.class).orElseThrow().recipeTypes();
        List<Item> trim = grid(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, Items.IRON_CHESTPLATE, Items.IRON_INGOT);
        helper.assertTrue(PatternRecipes.matching(level, types, trim).isEmpty(), "No trim pattern: " + PatternRecipes.matching(level, types, trim));
        List<Item> upgrade = grid(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_INGOT);
        helper.assertTrue(PatternRecipes.matching(level, types, upgrade).filter(shape -> shape.output() == Items.NETHERITE_CHESTPLATE).isPresent(),
                "The netherite upgrade is a pattern");
        helper.assertTrue(PatternRecipes.producing(level, types, Items.IRON_CHESTPLATE).stream().noneMatch(shape -> shape.type() == RecipeType.SMITHING),
                "No trim recipe makes an iron chestplate");
        helper.succeed();
    }

    /**
     * With an anvil within 4 blocks of the smithing table the Smith works at job factor 1.2 (instead of 0.3) and fills
     * a request for 4 iron trapdoors (4 executions) within {@link #SPEED_TICKS}; the anvil does not wear.
     */
    private static void anvilFaster(GameTestHelper helper) {
        BlockPos anvil = new BlockPos(3, 1, 9);
        helper.setBlock(anvil, Blocks.ANVIL);
        int delivered = trapdoors(helper, 1.2);
        helper.assertTrue(delivered == 4, "With an anvil the Smith delivered 4 trapdoors: " + delivered);
        helper.assertBlockPresent(Blocks.ANVIL, anvil);
        helper.succeed();
    }

    /** The same request in the same work time without an anvil: job factor 0.3, not done yet. */
    private static void withoutAnvilSlower(GameTestHelper helper) {
        int delivered = trapdoors(helper, 0.3);
        helper.assertTrue(delivered < 4, "Without an anvil the Smith is slower: " + delivered);
        helper.succeed();
    }

    /**
     * Requests 4 iron trapdoors (16 iron ingots in stock), lets a Smith work {@link #SPEED_TICKS} at a smithing table at
     * (3, 1, 5), checks its job factor of the day and returns how many trapdoors arrived.
     */
    private static int trapdoors(GameTestHelper helper, double factor) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.IRON_INGOT, 16)), Items.IRON_TRAPDOOR, 4);
        BlockPos craft = smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        helper.assertTrue(Patterns.setOutput(level, craft, smith(), 0, Items.IRON_TRAPDOOR).orElseThrow().checked(), "Trapdoors checked");
        CraftspersonGameTests.catchUp(level, settler, SPEED_TICKS);
        helper.assertTrue(settler.getWork().jobFactor(level, craft) == factor, "Job factor of the day " + factor + ": "
                + settler.getWork().jobFactor(level, craft));
        int delivered = count(level, setup.clerk(), Items.IRON_TRAPDOOR);
        cleanUp(setup, settler);
        return delivered;
    }

    /**
     * Chipped and damaged anvils count ({@code #minecraft:anvil}); the radius is 4 blocks around the smithing table in
     * every direction, an anvil 5 blocks away does not count.
     */
    private static void chippedAnvil(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos table = new BlockPos(5, 2, 5);
        helper.setBlock(table, Blocks.SMITHING_TABLE);
        BlockPos station = helper.absolutePos(table);
        helper.assertTrue(StationExtras.jobFactor(level, smith(), station) == 0.3, "No anvil: 0.3");
        helper.setBlock(table.offset(5, 0, 0), Blocks.ANVIL);
        helper.assertTrue(StationExtras.jobFactor(level, smith(), station) == 0.3, "5 blocks away: 0.3");
        helper.setBlock(table.offset(4, 0, 0), Blocks.CHIPPED_ANVIL);
        helper.assertTrue(StationExtras.jobFactor(level, smith(), station) == 1.2, "Chipped anvil 4 blocks away: 1.2");
        helper.setBlock(table.offset(4, 0, 0), Blocks.AIR);
        helper.setBlock(table.offset(-3, 3, 4), Blocks.DAMAGED_ANVIL);
        helper.assertTrue(StationExtras.jobFactor(level, smith(), station) == 1.2, "Damaged anvil diagonally above: 1.2");
        helper.assertTrue(StationExtras.jobFactor(level, CraftspersonGameTests.craftJob(), station) == 0.3,
                "The Craftsperson names no station extras: its job factor");
        helper.succeed();
    }

    /**
     * The station extra is checked once when the station of the day is chosen and kept for that day: an anvil removed
     * later the same day does not change the day's job factor.
     */
    private static void extraFixedForDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.IRON_INGOT, 16)), Items.IRON_TRAPDOOR, 4);
        BlockPos craft = smithy(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.SMITHING_TABLE);
        helper.setBlock(new BlockPos(3, 1, 7), Blocks.ANVIL);
        Settler settler = worker(helper, setup, craft);
        helper.assertTrue(Patterns.setOutput(level, craft, smith(), 0, Items.IRON_TRAPDOOR).orElseThrow().checked(), "Trapdoors checked");
        long end = SmelterGameTests.catchUpWork(level, settler, 2000L, 600);
        helper.assertTrue(settler.getWork().jobFactor(level, craft) == 1.2, "An anvil when the station was chosen: 1.2");
        helper.setBlock(new BlockPos(3, 1, 7), Blocks.AIR);
        SmelterGameTests.catchUpWork(level, settler, end, 600);
        helper.assertTrue(settler.getWork().jobFactor(level, craft) == 1.2, "The same day keeps 1.2: "
                + settler.getWork().jobFactor(level, craft));
        helper.assertTrue(StationExtras.jobFactor(level, smith(), helper.absolutePos(new BlockPos(3, 1, 5))) == 0.3,
                "Tomorrow's choice reads 0.3 there");
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Station extras of any Job come from its job file (GameTest datapack for {@code civmod:gametest_mason}: an anvil
     * within 2 blocks → 0.8; a grindstone or a log within 3 → 2.5): several matches → the highest job factor, none → the
     * Job's own. A later file replaces the list.
     */
    private static void highestExtra(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job mason = StationTypeGameTests.job(CivMod.id("gametest_mason"));
        BlockPos table = new BlockPos(5, 2, 5);
        CraftspersonGameTests.placeTable(helper, table);
        BlockPos station = helper.absolutePos(table);
        helper.assertTrue(StationExtras.jobFactor(level, mason, station) == mason.jobFactor(), "No extra: the Job's factor");
        helper.setBlock(table.offset(0, 0, 2), Blocks.ANVIL);
        helper.assertTrue(StationExtras.jobFactor(level, mason, station) == 0.8, "An anvil replaces it, even downwards: 0.8");
        helper.setBlock(table.offset(3, 0, 0), Blocks.GRINDSTONE);
        helper.assertTrue(StationExtras.jobFactor(level, mason, station) == 2.5, "Anvil and grindstone: the highest, 2.5");
        helper.setBlock(table.offset(3, 0, 0), Blocks.AIR);
        helper.setBlock(table.offset(0, 0, -3), Blocks.OAK_LOG);
        helper.assertTrue(StationExtras.jobFactor(level, mason, station) == 2.5, "A tag entry matches too: 2.5");
        helper.setBlock(table.offset(0, 0, -3), Blocks.AIR);
        helper.setBlock(table.offset(0, 0, 2), Blocks.AIR);
        helper.setBlock(table.offset(0, 0, 3), Blocks.ANVIL);
        helper.assertTrue(StationExtras.jobFactor(level, mason, station) == mason.jobFactor(), "The anvil outside its radius 2");

        JobBalancing merged = JobBalancing.merge(List.of(
                JsonParser.parseString("{\"station_extras\": [{\"blocks\": \"minecraft:anvil\", \"radius\": 1, \"job_factor\": 3.0}]}"),
                JsonParser.parseString("{\"base_exp\": 3}"),
                JsonParser.parseString("{\"station_extras\": [{\"blocks\": [\"minecraft:loom\", \"#minecraft:anvil\"], \"radius\": 2, \"job_factor\": 1.5}]}")));
        helper.assertTrue(merged.stationExtras().map(List::size).equals(Optional.of(1))
                && merged.stationExtras().orElseThrow().getFirst().jobFactor() == 1.5, "A later file replaces the list: " + merged.stationExtras());
        helper.succeed();
    }

    /** Three items in order in the 3×3 grid, the rest empty. */
    private static List<Item> grid(Item first, Item second, Item third) {
        return List.of(first, second, third, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR);
    }

    private record Setup(BlockPos clerk, SettlementData data, Settlement settlement) {
    }

    /** A working Clerk whose storage holds the items, with a rule for one of the wanted item. */
    private static Setup setup(GameTestHelper helper, List<ItemStack> stock, Item wanted) {
        return setup(helper, stock, wanted, 1);
    }

    /** A working Clerk whose storage holds the items, with a Min rule for the wanted item. */
    private static Setup setup(GameTestHelper helper, List<ItemStack> stock, Item wanted, int amount) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int i = 0; i < stock.size(); i++) {
            chest.setItem(i, stock.get(i).copy());
        }
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, wanted, amount, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        return new Setup(clerk, data, settlement);
    }

    /** A settler working the Smith at the JobBlock. */
    private static Settler worker(GameTestHelper helper, Setup setup, BlockPos craft) {
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), setup.data(), setup.settlement());
        settler.setCustomName(Component.literal("Sigrun"));
        settler.getFood().setMorningDay(Math.floorDiv(helper.getLevel().getOverworldClockTime(), 24000L));
        helper.assertTrue(TestAssignments.assign(setup.data(), craft, settler.getUUID(), "Sigrun", setup.settlement().id(), List.of(), smith())
                == JobAssignments.AssignResult.OK, "Smith assigned");
        settler.addJob(new SettlerJob(smith(), craft));
        return settler;
    }

    private static Request request(Setup setup, Item item) {
        return Requests.byPriority(setup.settlement()).stream().filter(found -> found.item() == item).findFirst().orElseThrow();
    }

    private static int count(ServerLevel level, BlockPos clerk, Item item) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(item));
    }

    private static void cleanUp(Setup setup, Settler settler) {
        TestAssignments.unassign(setup.data(), setup.clerk());
        settler.discard();
    }
}
