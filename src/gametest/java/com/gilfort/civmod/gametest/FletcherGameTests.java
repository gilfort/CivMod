package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Fletcher (#127, vault {@code Jobs/Fletcher.md}): a Specialization of the Craftsperson unlocked by a
 * fletching table, crafting in the fletching domain; its innate recipes turn wool into string (with shears, worn by 1
 * per execution) and gravel into flint; a target near the fletching table replaces its job factor 0.3 by 1.2.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FletcherGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for a few executions at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;
    /** Work time of the previous workday the Unbreaking test catches up: plenty for 8 executions at job factor 0.3. */
    private static final long LONG_WORK_TICKS = 16000L;
    /** The work time the arrow tests catch up: enough for 4 executions at job factor 1.2, not at 0.3. */
    private static final long SPEED_TICKS = 1200L;

    private FletcherGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "fletcher_unlock", FletcherGameTests::unlock);
            register(helper, "fletcher_string_from_wool", FletcherGameTests::stringFromWool);
            register(helper, "fletcher_without_shears", FletcherGameTests::withoutShears);
            register(helper, "fletcher_unbreaking_shears", FletcherGameTests::unbreakingShears);
            register(helper, "fletcher_shears_from_collect", FletcherGameTests::shearsFromCollect);
            register(helper, "fletcher_shears_break_mid_round", FletcherGameTests::shearsBreakMidRound);
            register(helper, "fletcher_target_faster", FletcherGameTests::targetFaster);
            register(helper, "fletcher_without_target_slower", FletcherGameTests::withoutTargetSlower);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Fletcher. */
    static Job fletcher() {
        return StationTypeGameTests.job(CivMod.id("fletcher"));
    }

    /** A level-2 Craftsperson JobBlock with the Fletcher unlocked by a fletching table in its second Station Types slot. */
    static BlockPos fletchery(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.FLETCHING_TABLE), "A fletching table unlocks the Fletcher");
        return pos;
    }

    /**
     * The Fletcher specializes the Craftsperson (job factor 0.3, base EXP 2, hunger 0.5), is unlocked by a fletching
     * table, works {@code crafting} recipes at a fletching table in the fletching domain and looks like an arrow.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job fletcher = fletcher();
        helper.assertTrue(fletcher != null && fletcher.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Fletcher specializes the Craftsperson");
        helper.assertTrue(fletcher.unlockItem().equals(Optional.of(Items.FLETCHING_TABLE)), "Unlocked by a fletching table: " + fletcher.unlockItem());
        helper.assertTrue(fletcher.jobFactor() == 0.3 && fletcher.baseExp() == 2 && fletcher.hungerFactor() == 0.5,
                "Job factor 0.3, base EXP 2, hunger 0.5: " + fletcher.jobFactor() + " / " + fletcher.baseExp() + " / " + fletcher.hungerFactor());
        helper.assertTrue(fletcher.icon().is(Items.FLETCHING_TABLE) && fletcher.displayItem().equals(Optional.of(Items.ARROW)),
                "Icon fletching table, display item arrow");
        ProducingProfile profile = fletcher.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting recipes");
        TagKey<Item> domain = profile.domain().orElseThrow();
        helper.assertTrue(domain.location().equals(CivMod.id("domains/fletching")), "Fletching domain: " + domain);
        for (Item item : List.of(Items.STRING, Items.FEATHER, Items.FLINT, Items.ARROW, Items.SPECTRAL_ARROW)) {
            helper.assertTrue(item.getDefaultInstance().is(domain), item + " is in the fletching domain");
        }
        helper.assertFalse(Items.OAK_PLANKS.getDefaultInstance().is(domain), "Planks are not");
        BlockPos pos = fletchery(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(fletcher),
                "The job picker offers the Fletcher");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.FLETCHING_TABLE);
        helper.assertTrue(fletcher.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a fletching table");
        helper.succeed();
    }

    /**
     * A Clerk Min rule on 2 string: the Fletcher turns 1 wool into 2 string with its innate recipe at the fletching
     * table and wears its shears by 1.
     */
    private static void stringFromWool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.WOOL.white())), List.of(new ItemStack(Items.STRING, 2)));
        BlockPos craft = fletchery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLETCHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        ItemStack shears = new ItemStack(Items.SHEARS);
        settler.getInventory().setItem(Settler.BELT_START, shears);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), Items.STRING) == 2, "2 string arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(count(level, setup.clerk(), Items.WOOL.white()) == 0, "The wool is used up");
        ItemStack worn = settler.getInventory().getItem(Settler.BELT_START);
        helper.assertTrue(worn.is(Items.SHEARS) && worn.getDamageValue() == 1, "The shears wore by 1: " + worn + " damage " + worn.getDamageValue());
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Without shears the string recipe is skipped and marked "Needs Shears" in the Patterns tab, while the other innate
     * recipe still runs: gravel becomes flint.
     */
    private static void withoutShears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.WOOL.white()), new ItemStack(Items.GRAVEL)),
                List.of(new ItemStack(Items.STRING, 2), new ItemStack(Items.FLINT)));
        BlockPos craft = fletchery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLETCHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), Items.FLINT) == 1, "Flint arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(count(level, setup.clerk(), Items.STRING) == 0, "No string without shears");
        helper.assertTrue(count(level, setup.clerk(), Items.WOOL.white()) == 1, "The wool stays");
        PatternsTabData tab = PatternsTabData.of(level, craft, fletcher());
        Optional<String> line = Optional.ofNullable(tab.needs().get(1)).map(Component::getString);
        helper.assertTrue(line.equals(Optional.of("Needs Shears")), "The string recipe is marked: " + tab.needs());
        helper.assertTrue(!tab.needs().containsKey(0), "The flint recipe is not");
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Unbreaking works on the tool of an innate recipe like on a Resource Collection tool: Unbreaking III shears take
     * far less than 1 durability per execution over 8 executions (all 8 losing one: chance 1 in 65536).
     */
    private static void unbreakingShears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.WOOL.white(), 8)), List.of(new ItemStack(Items.STRING, 16)));
        BlockPos craft = fletchery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLETCHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        ItemStack shears = new ItemStack(Items.SHEARS);
        shears.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 3);
        settler.getInventory().setItem(Settler.BELT_START, shears);
        CraftspersonGameTests.catchUp(level, settler, LONG_WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), Items.STRING) == 16, "16 string arrived (status " + settler.getWork().status(craft) + ")");
        ItemStack worn = settler.getInventory().getItem(Settler.BELT_START);
        helper.assertTrue(worn.is(Items.SHEARS) && worn.getDamageValue() < 8, "Unbreaking spared durability: damage " + worn.getDamageValue());
        cleanUp(setup, settler);
        helper.succeed();
    }

    /** Without shears on the belt the Fletcher takes the shears from its collect inventory (the JobBlock) and uses them. */
    private static void shearsFromCollect(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.WOOL.white())), List.of(new ItemStack(Items.STRING, 2)));
        BlockPos craft = fletchery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLETCHING_TABLE);
        ((Container) level.getBlockEntity(craft)).setItem(0, new ItemStack(Items.SHEARS));
        Settler settler = worker(helper, setup, craft);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(count(level, setup.clerk(), Items.STRING) == 2, "2 string arrived (status " + settler.getWork().status(craft) + ")");
        ItemStack worn = settler.getInventory().getItem(Settler.BELT_START);
        helper.assertTrue(worn.is(Items.SHEARS) && worn.getDamageValue() == 1, "The fetched shears are on the belt, worn by 1: " + worn);
        helper.assertTrue(ClerkStorage.stock(level, craft, false).count(stack -> stack.is(Items.SHEARS)) == 0, "Taken out of the collect inventory");
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Shears that break during a round skip only the string recipe: the request is not marked missing shears, the
     * recipe shows "Needs Shears" and the Fletcher keeps working its other recipes (gravel becomes flint).
     */
    private static void shearsBreakMidRound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.WOOL.white(), 2), new ItemStack(Items.GRAVEL)),
                List.of(new ItemStack(Items.STRING, 4), new ItemStack(Items.FLINT)));
        BlockPos craft = fletchery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLETCHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        ItemStack shears = new ItemStack(Items.SHEARS);
        // One use left: they break on the first execution.
        shears.setDamageValue(shears.getMaxDamage() - 1);
        settler.getInventory().setItem(Settler.BELT_START, shears);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(settler.getInventory().getItem(Settler.BELT_START).isEmpty(), "The shears broke");
        helper.assertTrue(count(level, setup.clerk(), Items.FLINT) == 1, "Flint arrived (status " + settler.getWork().status(craft) + ")");
        List<Request.Missing> missing = Requests.forRule(setup.settlement(), setup.clerk(), 0).map(Request::missing).orElse(List.of());
        helper.assertTrue(missing.stream().noneMatch(entry -> entry.item() == Items.SHEARS), "The string request is not marked missing shears: " + missing);
        PatternsTabData tab = PatternsTabData.of(level, craft, fletcher());
        Optional<String> line = Optional.ofNullable(tab.needs().get(1)).map(Component::getString);
        helper.assertTrue(line.equals(Optional.of("Needs Shears")), "The string recipe is marked: " + tab.needs());
        cleanUp(setup, settler);
        helper.succeed();
    }

    /** With a target within 4 blocks of the fletching table the Fletcher works at job factor 1.2: 16 arrows in time. */
    private static void targetFaster(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.TARGET);
        int delivered = arrows(helper, 1.2);
        helper.assertTrue(delivered == 16, "With a target the Fletcher delivered 16 arrows: " + delivered);
        helper.succeed();
    }

    /** The same request in the same work time without a target: job factor 0.3, not done yet. */
    private static void withoutTargetSlower(GameTestHelper helper) {
        int delivered = arrows(helper, 0.3);
        helper.assertTrue(delivered < 16, "Without a target the Fletcher is slower: " + delivered);
        helper.succeed();
    }

    /** Requests 16 arrows (4 executions), lets a Fletcher work {@link #SPEED_TICKS} and returns how many arrived. */
    private static int arrows(GameTestHelper helper, double factor) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.FLINT, 4), new ItemStack(Items.STICK, 4), new ItemStack(Items.FEATHER, 4)),
                List.of(new ItemStack(Items.ARROW, 16)));
        BlockPos craft = fletchery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLETCHING_TABLE);
        Settler settler = worker(helper, setup, craft);
        helper.assertTrue(com.gilfort.civmod.settlement.Patterns.setOutput(level, craft, fletcher(), 2, Items.ARROW).orElseThrow().checked(),
                "Arrows checked");
        CraftspersonGameTests.catchUp(level, settler, SPEED_TICKS);
        helper.assertTrue(settler.getWork().jobFactor(level, craft) == factor, "Job factor of the day " + factor + ": "
                + settler.getWork().jobFactor(level, craft));
        int delivered = count(level, setup.clerk(), Items.ARROW);
        cleanUp(setup, settler);
        return delivered;
    }

    record Setup(BlockPos clerk, SettlementData data, Settlement settlement) {
    }

    /** A working Clerk whose storage holds the items, with a Min rule per wanted stack. */
    static Setup setup(GameTestHelper helper, List<ItemStack> stock, List<ItemStack> wanted) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int i = 0; i < stock.size(); i++) {
            chest.setItem(i, stock.get(i).copy());
        }
        for (int i = 0; i < wanted.size(); i++) {
            helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(i, wanted.get(i).getItem(), wanted.get(i).getCount(), 0)), "Rule set");
        }
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        return new Setup(clerk, data, settlement);
    }

    /** A settler working a Job at the JobBlock (the Fletcher by default). */
    static Settler worker(GameTestHelper helper, Setup setup, BlockPos craft) {
        return worker(helper, setup, craft, fletcher());
    }

    /** A settler working a Job at the JobBlock. */
    static Settler worker(GameTestHelper helper, Setup setup, BlockPos craft, Job job) {
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), setup.data(), setup.settlement());
        settler.setCustomName(Component.literal("Flavia"));
        settler.getFood().setMorningDay(Math.floorDiv(helper.getLevel().getOverworldClockTime(), 24000L));
        helper.assertTrue(TestAssignments.assign(setup.data(), craft, settler.getUUID(), "Flavia", setup.settlement().id(), List.of(), job)
                == JobAssignments.AssignResult.OK, job + " assigned");
        settler.addJob(new SettlerJob(job, craft));
        return settler;
    }

    static int count(ServerLevel level, BlockPos clerk, Item item) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(item));
    }

    static void cleanUp(Setup setup, Settler settler) {
        TestAssignments.unassign(setup.data(), setup.clerk());
        settler.discard();
    }
}
