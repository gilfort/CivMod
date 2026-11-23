package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.food.FoodVariety;
import com.gilfort.civmod.food.FoodVariety.Diet;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.job.NeedTally;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the food variety of tiers 4 and 5 (vault {@code Systems/Needs.md}): 5 foods in 5 days, 10 foods in 7 days.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FoodVarietyTiersGameTests {

    private static final int BLOCK_LEVEL = 5;
    private static final String VARIETY = "civmod.need.food_variety";
    /** Ten different foods, none of them a candidate of the pantry test. */
    private static final List<Item> DIET = List.of(Items.BREAD, Items.CARROT, Items.APPLE, Items.COOKED_BEEF, Items.COOKIE, Items.BAKED_POTATO,
            Items.MELON_SLICE, Items.COOKED_PORKCHOP, Items.PUMPKIN_PIE, Items.COOKED_COD);

    private FoodVarietyTiersGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "variety_tiers_diets", FoodVarietyTiersGameTests::diets);
            register(helper, "variety_tiers_window_days", FoodVarietyTiersGameTests::windowDays);
            register(helper, "variety_tiers_light_eater", FoodVarietyTiersGameTests::lightEater);
            register(helper, "variety_tiers_choice", FoodVarietyTiersGameTests::choice);
            register(helper, "variety_tiers_choice_lower_diet_short", FoodVarietyTiersGameTests::lowerDietShort);
            register(helper, "variety_tiers_tier4_five_foods", FoodVarietyTiersGameTests::tierFour);
            register(helper, "variety_tiers_tier5_ten_foods", FoodVarietyTiersGameTests::tierFive);
            register(helper, "variety_tiers_climb_back", FoodVarietyTiersGameTests::climbBack);
            register(helper, "variety_tiers_catch_up", FoodVarietyTiersGameTests::catchUp);
            register(helper, "variety_tiers_reputation", FoodVarietyTiersGameTests::reputation);
            register(helper, "variety_tiers_pantry_prefers_new_food", FoodVarietyTiersGameTests::pantryPrefersNewFood);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Identifier id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static FoodVariety eaten(long day, Item... items) {
        FoodVariety variety = new FoodVariety();
        for (Item item : items) {
            variety.record(id(item), day);
        }
        return variety;
    }

    /** What a settler of the tier wants, without a favourite meal. */
    private static FoodVariety.Wishes wishes(int tier) {
        return FoodVariety.Wishes.forTier(tier, Optional.empty());
    }

    private static long today(ServerLevel level) {
        return DayClock.day(level.getOverworldClockTime());
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents contents ? contents.getKey() : text.getString();
    }

    private static List<Object> args(Component text) {
        return List.of(((TranslatableContents) text.getContents()).getArgs());
    }

    // ---------------------------------------------------------------- the rule

    /** Tier 2 and 3 ask for 3 foods in 3 days, tier 4 for 5 in 5, tier 5 for 10 in 7. */
    private static void diets(GameTestHelper helper) {
        helper.assertTrue(Diet.forTier(1).equals(new Diet(3, 3)) && Diet.forTier(2).equals(new Diet(3, 3)) && Diet.forTier(3).equals(new Diet(3, 3)),
                "3 foods in 3 days up to tier 3: " + Diet.forTier(3));
        helper.assertTrue(Diet.forTier(4).equals(new Diet(5, 5)), "5 foods in 5 days: " + Diet.forTier(4));
        helper.assertTrue(Diet.forTier(5).equals(new Diet(10, 7)), "10 foods in 7 days: " + Diet.forTier(5));
        helper.succeed();
    }

    /** A food of the sixth day back counts for 5 foods but not for 3, one of the eighth day for 10 foods; older ones are out. */
    private static void windowDays(GameTestHelper helper) {
        Diet three = Diet.forTier(2);
        Diet five = Diet.forTier(4);
        Diet ten = Diet.forTier(5);
        // Enough meals of bread that the carrot is not among the last items of any window.
        FoodVariety fiveDays = eaten(6L, Items.CARROT);
        FoodVariety sixDays = eaten(5L, Items.CARROT);
        FoodVariety sevenDays = eaten(4L, Items.CARROT);
        FoodVariety eightDays = eaten(3L, Items.CARROT);
        for (int i = 0; i < 2 * ten.foods(); i++) {
            fiveDays.record(id(Items.BREAD), 10L);
            sixDays.record(id(Items.BREAD), 10L);
            sevenDays.record(id(Items.BREAD), 10L);
            eightDays.record(id(Items.BREAD), 10L);
        }
        helper.assertFalse(fiveDays.window(10L, three).contains(id(Items.CARROT)), "Day 6 is outside the 3 days");
        helper.assertTrue(fiveDays.window(10L, five).contains(id(Items.CARROT)), "Day 6 is the fifth day back, inside 5 days");
        helper.assertFalse(sixDays.window(10L, five).contains(id(Items.CARROT)), "Day 5 is outside the 5 days");
        helper.assertTrue(sixDays.window(10L, ten).contains(id(Items.CARROT)), "Day 5 is inside the 7 days");
        helper.assertTrue(sevenDays.window(10L, ten).contains(id(Items.CARROT)), "Day 4 is the seventh day back, inside 7 days");
        helper.assertFalse(eightDays.window(10L, ten).contains(id(Items.CARROT)), "Day 3 is outside the 7 days");
        helper.succeed();
    }

    /** A light eater: the window always reaches back over twice the required count in meals, 10 for 5 foods and 20 for 10 foods. */
    private static void lightEater(GameTestHelper helper) {
        Diet five = Diet.forTier(4);
        Diet ten = Diet.forTier(5);
        FoodVariety fiveFoods = eaten(1L, Items.BREAD, Items.CARROT, Items.APPLE, Items.COOKED_BEEF, Items.COOKIE);
        helper.assertFalse(fiveFoods.isShort(30L, five), "Five old foods are the last items: not short");
        helper.assertTrue(fiveFoods.isShort(30L, ten), "Five foods are short of 10");

        FoodVariety tenFoods = eaten(1L, DIET.toArray(Item[]::new));
        helper.assertFalse(tenFoods.isShort(30L, ten), "Ten old foods are well inside the last 20 items: not short");

        FoodVariety pushedOut = eaten(1L, DIET.toArray(Item[]::new));
        for (int i = 0; i < 2 * ten.foods(); i++) {
            pushedOut.record(id(Items.CARROT), 1L);
        }
        helper.assertTrue(pushedOut.distinct(30L, ten) == 1, "Beyond the last 20 items the old foods are out: " + pushedOut.distinct(30L, ten));
        helper.succeed();
    }

    /** Short of variety: a food outside the window, even a small one; the diet of the tier decides what is short. */
    private static void choice(GameTestHelper helper) {
        List<ItemStack> stock = List.of(new ItemStack(Items.COOKED_BEEF), new ItemStack(Items.CARROT), new ItemStack(Items.STONE));
        FoodVariety four = eaten(10L, Items.BREAD, Items.APPLE, Items.COOKIE, Items.COOKED_BEEF);
        helper.assertTrue(four.choose(stock, 12, 10L, wishes(3)).equals(Optional.of(0)), "4 foods are enough for 3: the beef fits best");
        helper.assertTrue(four.choose(stock, 12, 10L, wishes(4)).equals(Optional.of(1)), "4 foods are short of 5: the new carrot first");
        FoodVariety five = eaten(10L, Items.BREAD, Items.APPLE, Items.COOKIE, Items.COOKED_BEEF, Items.MELON_SLICE);
        helper.assertTrue(five.choose(stock, 12, 10L, wishes(4)).equals(Optional.of(0)), "5 foods are enough for 5: the usual choice");
        helper.assertTrue(five.choose(stock, 12, 10L, wishes(5)).equals(Optional.of(1)), "5 foods are short of 10: the new carrot first");
        FoodVariety nine = eaten(10L, DIET.subList(0, 9).toArray(Item[]::new));
        List<ItemStack> last = List.of(new ItemStack(Items.COOKED_BEEF), new ItemStack(DIET.get(9)));
        helper.assertTrue(nine.choose(last, 6, 10L, wishes(5)).equals(Optional.of(1)), "9 foods are short of 10: the tenth, new food");
        helper.succeed();
    }

    /**
     * A settler of tier 5 meets the 5 foods of tier 4 too: 10 foods in the 7 days but only 4 in the last 5 days leave the tier 4 diet short,
     * so the counter gives it a food of its older days, though the tier 5 diet is full.
     */
    private static void lowerDietShort(GameTestHelper helper) {
        FoodVariety variety = eaten(4L, DIET.subList(0, 6).toArray(Item[]::new));
        for (int i = 0; i < 10; i++) {
            variety.record(id(DIET.get(6 + i % 4)), 10L);
        }
        helper.assertFalse(variety.isShort(10L, Diet.forTier(5)), "10 foods in the 7 days: the tier 5 diet is full");
        helper.assertTrue(variety.isShort(10L, Diet.forTier(4)), "4 foods in the last 5 days: the tier 4 diet is short");
        List<ItemStack> stock = List.of(new ItemStack(Items.COOKED_PORKCHOP), new ItemStack(Items.CARROT));
        helper.assertTrue(variety.choose(stock, 12, 10L, wishes(4)).equals(Optional.of(1)), "Tier 4: the carrot of the older days, though the porkchop fits better");
        helper.assertTrue(variety.choose(stock, 12, 10L, wishes(5)).equals(Optional.of(1)), "Tier 5 settler: the tier 4 diet is short as well, so the carrot");
        helper.assertTrue(variety.choose(stock, 12, 10L, wishes(3)).equals(Optional.of(0)), "Tier 3: 4 foods are enough for 3, the porkchop fits best");
        helper.succeed();
    }

    // ---------------------------------------------------------------- the need at the settlement

    /** Adds foods of {@link #DIET} to what the settler ate today, up to {@code count} different ones in all. */
    private static void eatUpTo(Settler settler, ServerLevel level, int count) {
        for (int i = 0; i < count; i++) {
            settler.getFood().variety().record(id(DIET.get(i)), today(level));
        }
    }

    private static Settler settler(GameTestHelper helper, BlockPos jobBlock, int skill) {
        Settler settler = ClothingNeedGameTests.comfortable(helper, jobBlock, skill);
        settler.getWork().setEntertainment(Entertainment.of(EntertainmentRules.MAX));
        ClothingNeedGameTests.dressInHighest(settler);
        return settler;
    }

    /** The need check of a day with full Entertainment, which decays from day to day, so that the diet is the only need that can fail. */
    private static void check(Settler settler, ServerLevel level, long day) {
        settler.getWork().setEntertainment(Entertainment.of(EntertainmentRules.MAX));
        settler.getWork().checkNeedsNow(level, day);
    }

    private static NeedsReport.Row varietyRow(Settler settler, ServerLevel level, int tier) {
        return settler.getWork().needsReport(level).rows(tier).stream().filter(row -> key(row.name()).equals(VARIETY)).findFirst().orElseThrow();
    }

    private static BlockPos block(GameTestHelper helper) {
        return NeedTierGameTests.place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
    }

    /** Tier 4 with 4 foods: grace day, then tier 3; the row shows 4 / 5 with the foods as icons. With 5 foods nothing is missing. */
    private static void tierFour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = settler(helper, jobBlock, 65);
        long day = today(level);
        eatUpTo(settler, level, 4);
        NeedsReport.Row row = varietyRow(settler, level, 4);
        helper.assertFalse(row.met(), "4 of 5 foods");
        helper.assertTrue(args(row.detail()).equals(List.of(4, 5, 5)), "4 / 5 foods in 5 days: " + args(row.detail()));
        helper.assertTrue(row.icons().stream().map(ItemStack::getItem).toList().equals(DIET.subList(0, 4)), "Icons: " + row.icons());
        helper.assertTrue(varietyRow(settler, level, 2).met(), "Tier 2 asks for 3 foods: met");

        check(settler, level, day);
        helper.assertTrue(settler.getWork().needState().inGrace() && settler.getWork().needState().workTier() == NeedState.MAX_TIER, "Grace day first");
        check(settler, level, day + 1);
        helper.assertTrue(settler.getWork().needState().workTier() == 3, "Next morning tier 3: " + settler.getWork().needState());

        eatUpTo(settler, level, 5);
        NeedsReport.Row full = varietyRow(settler, level, 4);
        helper.assertTrue(full.met() && args(full.detail()).equals(List.of(5, 5, 5)), "5 of 5 foods: " + args(full.detail()));
        check(settler, level, day + 2);
        helper.assertTrue(settler.getWork().needState().workTier() == NeedState.MAX_TIER && !settler.getWork().needState().inGrace(), "Nothing missing: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Tier 5 with 9 foods: grace day, then tier 4; the row shows 9 / 10 in 7 days. With 10 foods nothing is missing. */
    private static void tierFive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = settler(helper, jobBlock, 85);
        long day = today(level);
        eatUpTo(settler, level, 9);
        NeedsReport.Row row = varietyRow(settler, level, 5);
        helper.assertFalse(row.met(), "9 of 10 foods");
        helper.assertTrue(args(row.detail()).equals(List.of(9, 10, 7)), "9 / 10 foods in 7 days: " + args(row.detail()));
        helper.assertTrue(varietyRow(settler, level, 4).met(), "Tier 4 asks for 5 foods: met");

        check(settler, level, day);
        helper.assertTrue(settler.getWork().needState().inGrace(), "Grace day first");
        check(settler, level, day + 1);
        helper.assertTrue(settler.getWork().needState().workTier() == 4, "Next morning tier 4: " + settler.getWork().needState());

        eatUpTo(settler, level, 10);
        helper.assertTrue(varietyRow(settler, level, 5).met(), "10 of 10 foods");
        check(settler, level, day + 2);
        helper.assertTrue(settler.getWork().needState().workTier() == NeedState.MAX_TIER && !settler.getWork().needState().inGrace(), "Nothing missing: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** A settler fed the missing food climbs back at once, in the 5 s decision step. */
    private static void climbBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = settler(helper, jobBlock, 65);
        long day = today(level);
        eatUpTo(settler, level, 4);
        check(settler, level, day);
        check(settler, level, day + 1);
        helper.assertTrue(settler.getWork().needState().workTier() == 3, "Downgraded: " + settler.getWork().needState());

        settler.getFood().setLevel(5);
        settler.getWork().feed(level, new ItemStack(DIET.get(4)));
        settler.getFood().setLevel(15);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().needState().workTier() == NeedState.MAX_TIER, "Back at once with the 5th food: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Catch-up plays the same rule: a settler of tier 4 with only 4 foods to eat loses a tier over the missed days. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = settler(helper, jobBlock, 65);
        settler.getFood().setSaturation(0.0F);
        settler.getFood().setLevel(4);
        for (int i = 0; i < 4; i++) {
            settler.getInventory().setItem(Settler.POCKETS_START + i, new ItemStack(DIET.get(i), 8));
        }
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;
        settler.getWork().setCatchUp(dayStart - 3 * 24000L, dayStart);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(settler.getFood().variety().distinct(today(level), Diet.forTier(4)) <= 4, "Four foods at most");
        settler.getWork().setEntertainment(Entertainment.of(EntertainmentRules.MAX));
        helper.assertFalse(varietyRow(settler, level, 4).met(), "4 of 5 foods over the missed days");
        helper.assertTrue(settler.getWork().needState().workTier() < NeedState.MAX_TIER || settler.getWork().needState().inGrace(),
                "The missed days cost a tier or a grace day: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** The reputation counts the 5 foods for a settler of tier 4 and up, and the 10 foods for tier 5 only. */
    private static void reputation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler tierFour = settler(helper, jobBlock, 65);
        Settler tierFive = settler(helper, jobBlock, 85);
        Settler tierThree = settler(helper, jobBlock, 45);
        eatUpTo(tierFour, level, 4);
        eatUpTo(tierFive, level, 9);
        NeedTally four = tierFour.getWork().needTally(level);
        NeedTally five = tierFive.getWork().needTally(level);
        NeedTally three = tierThree.getWork().needTally(level);
        eatUpTo(tierFour, level, 5);
        eatUpTo(tierFive, level, 10);
        eatUpTo(tierThree, level, 10);
        helper.assertTrue(tierFour.getWork().needTally(level).met() == four.met() + 1 && tierFour.getWork().needTally(level).total() == four.total(),
                "The 5th food is one more need met at tier 4: " + four + " then " + tierFour.getWork().needTally(level));
        helper.assertTrue(tierFive.getWork().needTally(level).met() == five.met() + 1 && tierFive.getWork().needTally(level).total() == five.total(),
                "The 10th food is one more need met at tier 5: " + five + " then " + tierFive.getWork().needTally(level));
        helper.assertTrue(tierThree.getWork().needTally(level).equals(three), "Tier 3 counts neither: " + three + " then " + tierThree.getWork().needTally(level));
        tierThree.discard();
        tierFour.discard();
        NeedTierGameTests.end(helper, tierFive, jobBlock);
    }

    // ---------------------------------------------------------------- at the counter

    /** At a Pantry a settler of tier 4 with 3 foods eaten is short of 5 and takes a new food first, though 3 foods are enough for tier 2. */
    private static void pantryPrefersNewFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(new BlockPos(1, 1, 1));
        // The skill of 65 needs a JobBlock of level 4 or higher; the block entity keeps the level too and syncs it to the settlement.
        ((JobBlockEntity) level.getBlockEntity(jobBlock)).setJobLevel(BLOCK_LEVEL);
        SettlementManager.setLevel(SettlementData.get(level), jobBlock, BLOCK_LEVEL, SettlementManager.Listener.NONE);
        BlockPos clerkPos = helper.absolutePos(new BlockPos(6, 1, 6));
        TestSettlements.setBlock(helper, new BlockPos(6, 1, 6), ModBlocks.PANTRY_BLOCK.get());
        Container clerk = (Container) level.getBlockEntity(clerkPos);
        clerk.setItem(0, new ItemStack(Items.COOKED_BEEF, 16));
        clerk.setItem(1, new ItemStack(Items.BREAD, 4));
        clerk.setItem(2, new ItemStack(Items.APPLE, 4));
        clerk.setItem(3, new ItemStack(Items.CARROT, 4));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(65, 0));
        // Through the settlement, so that the settler is an assignee of the block: the membership check drops a job that is only on the settler.
        helper.assertTrue(SettlementWorld.assign(level, jobBlock, settler, ModJobs.LUMBERJACK.get()) == JobAssignments.AssignResult.OK, "Lumberjack assigned");
        settler.getFood().variety().record(id(Items.COOKED_BEEF), today(level));
        settler.getFood().variety().record(id(Items.BREAD), today(level));
        settler.getFood().variety().record(id(Items.APPLE), today(level));
        settler.getFood().setLevel(5);
        settler.getFood().setSaturation(0.0F);
        // The membership check runs every 200 ticks at an offset of the entity id; run one now so that the test does not depend on it.
        settler.checkMembership(level);
        helper.succeedWhen(() -> {
            List<Identifier> meals = settler.getFood().variety().meals().stream().map(FoodVariety.Meal::item).toList();
            helper.assertTrue(meals.size() >= 4, "Not eaten yet: " + meals + " (" + settler.statusText().getString() + ")");
            helper.assertTrue(meals.get(3).equals(id(Items.CARROT)), "The new food comes first for tier 4: " + meals);
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
