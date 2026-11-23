package com.gilfort.civmod.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.chronicle.DiaryEntry;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.food.FoodVariety;
import com.gilfort.civmod.food.FoodVariety.Diet;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.PlayerTalk;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.origin.Origins;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestSettlements;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the favourite meal (vault {@code Systems/Needs.md}): the roll from the tag of the race, the reveal by a talk and by the
 * first meal, the Needs tab, the tier 5 need and the preference at the counter.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FavouriteMealGameTests {

    private static final Vec3 SETTLER_AT = new Vec3(2.5, 1.0, 2.5);
    /** The prepared foods of the Human tag, as the design names them; cake is in the tag too but has no food component, so it is never rolled. */
    private static final Set<Item> HUMAN_FAVOURITES = Set.of(Items.BREAD, Items.BAKED_POTATO, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
            Items.COOKED_MUTTON, Items.COOKED_CHICKEN, Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.PUMPKIN_PIE,
            Items.MUSHROOM_STEW, Items.RABBIT_STEW, Items.BEETROOT_SOUP, Items.COOKIE);

    private FavouriteMealGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "favourite_meal_arrival_rolls_from_race_tag", FavouriteMealGameTests::arrivalRolls);
            register(helper, "favourite_meal_race_without_tag_has_none", FavouriteMealGameTests::raceWithoutTag);
            register(helper, "favourite_meal_old_save_rolls_on_load", FavouriteMealGameTests::oldSaveRolls);
            register(helper, "favourite_meal_told_after_origin_lines", FavouriteMealGameTests::toldAfterOrigin);
            register(helper, "favourite_meal_told_first_without_origin", FavouriteMealGameTests::toldFirstWithoutOrigin);
            register(helper, "favourite_meal_first_meal_shows_bubble_and_diary", FavouriteMealGameTests::firstMeal);
            register(helper, "favourite_meal_first_meal_in_catch_up", FavouriteMealGameTests::firstMealInCatchUp);
            register(helper, "favourite_meal_row_hidden_until_known", FavouriteMealGameTests::rowHiddenUntilKnown);
            register(helper, "favourite_meal_row_names_known_favourite", FavouriteMealGameTests::rowNamesKnownFavourite);
            register(helper, "favourite_meal_tier_five_needs_it", FavouriteMealGameTests::tierFiveNeedsIt);
            register(helper, "favourite_meal_no_favourite_asks_none", FavouriteMealGameTests::noFavouriteAsksNone);
            register(helper, "favourite_meal_texts_exist", FavouriteMealGameTests::textsExist);
            register(helper, "favourite_meal_choice_prefers_missing_favourite", FavouriteMealGameTests::choice);
            register(helper, "favourite_meal_pantry_prefers_favourite_on_tier_5", FavouriteMealGameTests::pantryTierFive);
            register(helper, "favourite_meal_pantry_ignores_favourite_below_tier_5", FavouriteMealGameTests::pantryTierFour);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    private static Identifier id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    /** A hand-built Human settler that does not think: no origin, no favourite. */
    private static Settler handBuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 at = helper.absoluteVec(SETTLER_AT);
        settler.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        settler.setRace(Races.HUMAN);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        return settler;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.add(helper, helper.absoluteVec(new Vec3(3.5, 1.0, 2.5)));
        player.setShiftKeyDown(true);
        return player;
    }

    private static PlayerTalk.Result talk(GameTestHelper helper, Settler settler, ServerPlayer player) {
        return PlayerTalk.talk(settler, player).orElseThrow(() -> helper.assertionException("The settler did not talk"));
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "not translatable: " + text;
    }

    private static List<DiaryEntry> favouriteEntries(Settler settler) {
        return settler.getPast().chronicle().entries().stream().filter(entry -> entry.type().equals(ModDiaryEntryTypes.FAVOURITE_MEAL.getId())).toList();
    }

    // ---------------------------------------------------------------- the roll

    /** A settler that arrives rolls its favourite from the tag of its race: Human only prepared foods, and not always the same one. */
    private static void arrivalRolls(GameTestHelper helper) {
        Set<Identifier> allowed = new HashSet<>();
        HUMAN_FAVOURITES.forEach(item -> allowed.add(id(item)));
        Set<Identifier> seen = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            Settler settler = handBuilt(helper);
            Origins.assignEgg(settler, RandomSource.create(seed));
            Identifier favourite = settler.getFood().favourite().orElse(null);
            settler.discard();
            helper.assertTrue(favourite != null, "A settler that arrives has a favourite meal");
            helper.assertTrue(allowed.contains(favourite), "A prepared food of the Human tag: " + favourite);
            seen.add(favourite);
        }
        helper.assertTrue(seen.size() >= 4, "The roll spreads over the tag: " + seen);
        helper.succeed();
    }

    /** A race whose tag nobody defined has no favourite meal: the tier 5 need then asks for none. */
    private static void raceWithoutTag(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        settler.getFood().rollFavourite(Identifier.parse("civmod:nobody"), RandomSource.create(1L));
        helper.assertTrue(settler.getFood().favourite().isEmpty(), "No tag, no favourite: " + settler.getFood().favourite());
        settler.discard();
        helper.succeed();
    }

    /** A settler from an old save has no favourite saved: it rolls one on its first load and keeps it through every save after that. */
    private static void oldSaveRolls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler old = handBuilt(helper);
        CompoundTag saved = save(level, old);
        old.discard();
        helper.assertTrue(!saved.getCompoundOrEmpty("food").contains("favourite"), "An old save holds no favourite: " + saved.getCompoundOrEmpty("food"));
        Settler loaded = reload(level, saved);
        Identifier rolled = loaded.getFood().favourite().orElse(null);
        helper.assertTrue(rolled != null && HUMAN_FAVOURITES.stream().map(FavouriteMealGameTests::id).toList().contains(rolled),
                "Rolled on the first load from the Human tag: " + rolled);
        Settler again = reload(level, save(level, loaded));
        helper.assertTrue(again.getFood().favourite().equals(loaded.getFood().favourite()), "Kept: " + again.getFood().favourite() + " vs " + rolled);
        helper.succeed();
    }

    // ---------------------------------------------------------------- the reveal by a talk

    /**
     * After the three parts of the origin the next talk tells the favourite meal, once, and writes it into the diary with the same words;
     * after that it is small talk again, and the settler keeps the favourite hidden until then.
     */
    private static void toldAfterOrigin(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        ServerPlayer player = player(helper);
        try {
            Origins.assignEgg(settler, RandomSource.create(4L));
            Identifier favourite = settler.getFood().favourite().orElseThrow();
            helper.assertFalse(settler.getFood().isFavouriteKnown(), "The favourite is hidden at first");
            for (int i = 0; i < 3; i++) {
                helper.assertTrue(talk(helper, settler, player).told().isPresent(), "Talk " + (i + 1) + " tells the origin");
                helper.assertFalse(settler.getFood().isFavouriteKnown(), "Still hidden after " + (i + 1) + " talks");
            }
            helper.assertTrue(favouriteEntries(settler).isEmpty(), "Nothing about the favourite in the diary yet");

            PlayerTalk.Result fourth = talk(helper, settler, player);
            helper.assertTrue(fourth.told().isEmpty() && key(fourth.line()).equals("diary.civmod.favourite_meal.told"), "The fourth talk tells the favourite: " + key(fourth.line()));
            helper.assertTrue(((TranslatableContents) fourth.line().getContents()).getArgs()[0].equals(new ItemStack(BuiltInRegistries.ITEM.getValue(favourite)).getHoverName()),
                    "It names the favourite meal: " + fourth.line());
            helper.assertTrue(settler.getFood().isFavouriteKnown(), "The favourite is known now");
            List<DiaryEntry> entries = favouriteEntries(settler);
            helper.assertTrue(entries.size() == 1, "One diary entry: " + entries);
            helper.assertTrue(ModDiaryEntryTypes.FAVOURITE_MEAL.get().text().apply(entries.get(0).params()).equals(fourth.line()), "The entry reads like the line: " + entries.get(0));

            for (int i = 0; i < 4; i++) {
                PlayerTalk.Result later = talk(helper, settler, player);
                helper.assertTrue(later.told().isEmpty() && key(later.line()).startsWith("civmod.talk.small."), "Small talk after that: " + key(later.line()));
            }
            helper.assertTrue(favouriteEntries(settler).size() == 1, "Told once: " + favouriteEntries(settler));
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** A settler with a favourite but no origin (an old save) has no origin lines to tell first: its first talk tells the favourite. */
    private static void toldFirstWithoutOrigin(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        ServerPlayer player = player(helper);
        try {
            settler.getFood().rollFavourite(Races.HUMAN, RandomSource.create(2L));
            PlayerTalk.Result first = talk(helper, settler, player);
            helper.assertTrue(key(first.line()).equals("diary.civmod.favourite_meal.told") && settler.getFood().isFavouriteKnown(), "The first talk tells the favourite: " + key(first.line()));
            helper.assertTrue(key(talk(helper, settler, player).line()).startsWith("civmod.talk.small."), "Small talk after that");
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    // ---------------------------------------------------------------- the reveal by the first meal

    /** A settler with a favourite that is not the given food: the other food, so that a meal of it shows nothing. */
    private static Item otherFood(Settler settler) {
        return settler.getFood().favourite().orElseThrow().equals(id(Items.CARROT)) ? Items.APPLE : Items.CARROT;
    }

    private static List<ThoughtBubble.Show> favouriteBubbles(Settler settler) {
        return settler.getWork().recentThoughts().stream().filter(show -> show.bubble() == ThoughtBubble.FAVOURITE_MEAL).toList();
    }

    /**
     * The first time the settler eats its favourite it shows a thought bubble with the food and writes a diary entry; another food and
     * every later meal of the favourite show nothing.
     */
    private static void firstMeal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = handBuilt(helper);
        try {
            settler.getFood().rollFavourite(Races.HUMAN, RandomSource.create(7L));
            Item favourite = BuiltInRegistries.ITEM.getValue(settler.getFood().favourite().orElseThrow());
            settler.getFood().setLevel(5);
            settler.getWork().feed(level, new ItemStack(otherFood(settler)));
            helper.assertFalse(settler.getFood().isFavouriteKnown(), "Another food shows nothing");
            helper.assertTrue(favouriteBubbles(settler).isEmpty() && favouriteEntries(settler).isEmpty(), "No bubble, no entry: " + settler.getWork().recentThoughts());

            settler.getFood().setLevel(5);
            settler.getWork().feed(level, new ItemStack(favourite));
            helper.assertTrue(settler.getFood().isFavouriteKnown(), "The first favourite meal shows it");
            helper.assertTrue(favouriteBubbles(settler).equals(List.of(new ThoughtBubble.Show(settler.getId(), ThoughtBubble.FAVOURITE_MEAL, favourite, true))),
                    "One thought bubble with the food: " + settler.getWork().recentThoughts());
            List<DiaryEntry> entries = favouriteEntries(settler);
            helper.assertTrue(entries.size() == 1 && entries.get(0).params().equals(List.of(id(favourite).toString(), "eaten")), "One diary entry: " + entries);
            helper.assertTrue(key(ModDiaryEntryTypes.FAVOURITE_MEAL.get().text().apply(entries.get(0).params())).equals("diary.civmod.favourite_meal.eaten"),
                    "The entry says it was eaten");

            settler.getFood().setLevel(5);
            settler.getWork().feed(level, new ItemStack(favourite));
            helper.assertTrue(favouriteBubbles(settler).size() == 1 && favouriteEntries(settler).size() == 1, "Only the first time: " + settler.getWork().recentThoughts());
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /** A first favourite meal eaten while the settler catches up writes the diary entry and shows no bubble. */
    private static void firstMealInCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), 1);
        Settler settler = ClothingNeedGameTests.comfortable(helper, jobBlock, 5);
        settler.getFood().rollFavourite(Races.HUMAN, RandomSource.create(7L));
        Identifier favourite = settler.getFood().favourite().orElseThrow();
        settler.getFood().setSaturation(0.0F);
        settler.getFood().setLevel(4);
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(BuiltInRegistries.ITEM.getValue(favourite), 16));
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;
        settler.getWork().setCatchUp(dayStart - 3 * 24000L, dayStart);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(settler.getFood().variety().meals().stream().anyMatch(meal -> meal.item().equals(favourite)), "The settler ate its favourite meal while it caught up");
        helper.assertTrue(settler.getFood().isFavouriteKnown() && favouriteEntries(settler).size() == 1, "Known, with an entry: " + favouriteEntries(settler));
        helper.assertTrue(favouriteBubbles(settler).isEmpty(), "No bubble while caught up: " + settler.getWork().recentThoughts());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    // ---------------------------------------------------------------- the need on tier 5

    private static final int BLOCK_LEVEL = 5;
    private static final String FAVOURITE_ROW = "civmod.need.favourite_meal";
    /** Ten different foods that are not the favourite meal of {@link #tierFive}. */
    private static final List<Item> DIET = List.of(Items.BREAD, Items.CARROT, Items.APPLE, Items.COOKED_BEEF, Items.COOKIE, Items.BAKED_POTATO,
            Items.MELON_SLICE, Items.COOKED_PORKCHOP, Items.PUMPKIN_PIE, Items.COOKED_COD);
    /** A Human favourite meal that is none of the {@link #DIET}. */
    private static final Item FAVOURITE = Items.COOKED_MUTTON;

    private static long today(ServerLevel level) {
        return DayClock.day(level.getOverworldClockTime());
    }

    /** A settler of tier 5 whose other needs are met and who ate ten different foods today, none of them {@link #FAVOURITE}. It has no favourite meal yet. */
    private static Settler tierFive(GameTestHelper helper, BlockPos jobBlock) {
        ServerLevel level = helper.getLevel();
        Settler settler = ClothingNeedGameTests.comfortable(helper, jobBlock, 85);
        ClothingNeedGameTests.dressInHighest(settler);
        for (Item food : DIET) {
            settler.getFood().variety().record(id(food), today(level));
        }
        return settler;
    }

    /** The need check of a day with full Entertainment, which decays from day to day, so that the diet is the only need that can fail. */
    private static void check(Settler settler, ServerLevel level, long day) {
        settler.getWork().setEntertainment(Entertainment.of(EntertainmentRules.MAX));
        settler.getWork().checkNeedsNow(level, day);
    }

    private static NeedsReport.Row favouriteRow(Settler settler, ServerLevel level) {
        return settler.getWork().needsReport(level).rows(5).stream().filter(row -> key(row.name()).equals(FAVOURITE_ROW)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No favourite meal row on tier 5: " + settler.getWork().needsReport(level).rows(5)));
    }

    /** An unknown favourite meal shows "?" and no icon, and the thought bubble of the missing need does not give it away. */
    private static void rowHiddenUntilKnown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = tierFive(helper, jobBlock);
        settler.getFood().setFavourite(id(FAVOURITE));
        helper.assertFalse(settler.getFood().isFavouriteKnown(), "Hidden");
        NeedsReport.Row row = favouriteRow(settler, level);
        helper.assertFalse(row.met(), "Ten foods, but not the favourite meal");
        helper.assertTrue(key(row.detail()).equals("civmod.need.favourite_meal.unknown") && row.icons().isEmpty(), "Shows \"?\" and no icon: " + row.detail() + " " + row.icons());
        check(settler, level, today(level));
        settler.getWork().decideNow(level);
        List<ThoughtBubble.Show> needBubbles = settler.getWork().recentThoughts().stream().filter(show -> show.bubble() == ThoughtBubble.NEED_UNMET).toList();
        helper.assertTrue(!needBubbles.isEmpty() && needBubbles.stream().noneMatch(show -> show.icon() == FAVOURITE),
                "The need bubble is shown without the favourite meal: " + settler.getWork().recentThoughts());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Once known the row names the favourite meal with its icon; it turns green when the meal is eaten within the window. */
    private static void rowNamesKnownFavourite(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = tierFive(helper, jobBlock);
        settler.getFood().setFavourite(id(FAVOURITE));
        settler.getFood().revealFavourite();
        NeedsReport.Row missing = favouriteRow(settler, level);
        helper.assertFalse(missing.met(), "Not eaten lately");
        helper.assertTrue(key(missing.detail()).equals("civmod.need.favourite_meal.missing"), "Says it is missing: " + missing.detail());
        helper.assertTrue(((TranslatableContents) missing.detail().getContents()).getArgs()[0].equals(new ItemStack(FAVOURITE).getHoverName()), "Names the meal: " + missing.detail());
        helper.assertTrue(missing.icons().size() == 1 && missing.icons().get(0).is(FAVOURITE), "Shows its icon: " + missing.icons());
        check(settler, level, today(level));
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().recentThoughts().stream().anyMatch(show -> show.bubble() == ThoughtBubble.NEED_UNMET && show.icon() == FAVOURITE),
                "The need bubble shows the favourite meal once it is known: " + settler.getWork().recentThoughts());

        settler.getFood().variety().record(id(FAVOURITE), today(level));
        NeedsReport.Row eaten = favouriteRow(settler, level);
        helper.assertTrue(eaten.met() && key(eaten.detail()).equals("civmod.need.favourite_meal.met"), "Eaten lately: " + eaten.detail());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /**
     * Ten foods are not enough on tier 5: without the favourite meal in the window the settler loses a tier after its grace day, and
     * the meal brings it back at once.
     */
    private static void tierFiveNeedsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = tierFive(helper, jobBlock);
        settler.getFood().setFavourite(id(FAVOURITE));
        long day = today(level);
        check(settler, level, day);
        helper.assertTrue(settler.getWork().needState().inGrace() && settler.getWork().needState().workTier() == NeedState.MAX_TIER, "Grace day first: " + settler.getWork().needState());
        check(settler, level, day + 1);
        helper.assertTrue(settler.getWork().needState().workTier() == 4, "Next morning tier 4: " + settler.getWork().needState());

        settler.getFood().setLevel(5);
        settler.getWork().feed(level, new ItemStack(FAVOURITE));
        settler.getFood().setLevel(15);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().needState().workTier() == NeedState.MAX_TIER, "Back at once with the favourite meal: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** A settler without a favourite meal (its race has no tag) is asked for none, and its row says so. */
    private static void noFavouriteAsksNone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
        Settler settler = tierFive(helper, jobBlock);
        NeedsReport.Row row = favouriteRow(settler, level);
        helper.assertTrue(row.met() && key(row.detail()).equals("civmod.need.favourite_meal.none"), "Met, with the line for no favourite: " + row.detail());
        long day = today(level);
        check(settler, level, day);
        helper.assertTrue(settler.getWork().needState().workTier() == NeedState.MAX_TIER && !settler.getWork().needState().inGrace(), "Ten foods are enough: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Every language key the row, the diary entry and the talk line need exists. */
    private static void textsExist(GameTestHelper helper) {
        JsonObject lang = GuiTextGameTests.lang(helper);
        for (String key : List.of(FAVOURITE_ROW, FAVOURITE_ROW + ".unknown", FAVOURITE_ROW + ".none", FAVOURITE_ROW + ".met", FAVOURITE_ROW + ".missing",
                "diary.civmod.favourite_meal.told", "diary.civmod.favourite_meal.eaten")) {
            helper.assertTrue(lang.has(key), "Language key " + key);
        }
        helper.assertTrue(lang.get(FAVOURITE_ROW + ".unknown").getAsString().equals("?"), "An unknown favourite shows a question mark");
        helper.succeed();
    }

    // ---------------------------------------------------------------- at the counter

    /**
     * The favourite meal goes first while it is missing from the window, even a small one and also when the variety is not short; once
     * it is in the window, or when it is not in the stock or may not be eaten, the usual choice stands.
     */
    private static void choice(GameTestHelper helper) {
        Diet ten = Diet.forTier(5);
        List<ItemStack> stock = List.of(new ItemStack(Items.COOKED_BEEF), new ItemStack(FAVOURITE), new ItemStack(Items.STONE));
        FoodVariety fed = new FoodVariety();
        DIET.forEach(food -> fed.record(id(food), 10L));
        FoodVariety.Wishes none = FoodVariety.Wishes.forTier(5, Optional.empty());
        FoodVariety.Wishes wished = FoodVariety.Wishes.forTier(5, Optional.of(id(FAVOURITE)));
        helper.assertFalse(fed.isShort(10L, ten), "Ten foods: the variety is not short");
        helper.assertTrue(fed.choose(stock, 12, 10L, none).equals(Optional.of(0)), "Without a favourite the beef fits best");
        helper.assertTrue(fed.choose(stock, 12, 10L, wished).equals(Optional.of(1)), "The missing favourite first, though the beef fits better");
        helper.assertTrue(fed.choose(stock, 14, 10L, wished).equals(Optional.of(1)), "The favourite is chosen where it fits");
        helper.assertTrue(fed.choose(stock, 15, 10L, wished).isEmpty(), "The rule against overeating applies to the favourite too: " + fed.choose(stock, 15, 10L, wished));
        helper.assertTrue(fed.choose(List.of(new ItemStack(Items.COOKED_BEEF)), 12, 10L, wished).equals(Optional.of(0)), "Not in the stock: the usual choice");
        fed.record(id(FAVOURITE), 10L);
        helper.assertTrue(fed.choose(stock, 12, 10L, wished).equals(Optional.of(0)), "In the window: the usual choice again");
        helper.succeed();
    }

    /** A Pantry with beef, bread and a stack of the favourite meal, and a hungry settler of the given skill who ate ten foods lately. */
    private static Settler hungryAtPantry(GameTestHelper helper, int skill) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos jobBlock = helper.absolutePos(new BlockPos(1, 1, 1));
        ((JobBlockEntity) level.getBlockEntity(jobBlock)).setJobLevel(BLOCK_LEVEL);
        SettlementManager.setLevel(SettlementData.get(level), jobBlock, BLOCK_LEVEL, SettlementManager.Listener.NONE);
        BlockPos clerkPos = helper.absolutePos(new BlockPos(6, 1, 6));
        TestSettlements.setBlock(helper, new BlockPos(6, 1, 6), ModBlocks.PANTRY_BLOCK.get());
        Container clerk = (Container) level.getBlockEntity(clerkPos);
        clerk.setItem(0, new ItemStack(Items.COOKED_BEEF, 16));
        clerk.setItem(1, new ItemStack(FAVOURITE, 4));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(skill, 0));
        // Through the settlement, so that the settler is an assignee of the block: the membership check drops a job that is only on the settler.
        helper.assertTrue(SettlementWorld.assign(level, jobBlock, settler, ModJobs.LUMBERJACK.get()) == JobAssignments.AssignResult.OK, "Lumberjack assigned");
        settler.getFood().setFavourite(id(FAVOURITE));
        DIET.forEach(food -> settler.getFood().variety().record(id(food), today(level)));
        settler.getFood().setLevel(5);
        settler.getFood().setSaturation(0.0F);
        // The membership check runs every 200 ticks at an offset of the entity id; run one now so that the test does not depend on it.
        settler.checkMembership(level);
        return settler;
    }

    /** Waits for the first meal after the ten of the diet and hands it to the check. */
    private static void firstMealAtPantry(GameTestHelper helper, Settler settler, List<long[]> forced, Consumer<Identifier> check) {
        ServerLevel level = helper.getLevel();
        helper.succeedWhen(() -> {
            List<Identifier> meals = settler.getFood().variety().meals().stream().map(FoodVariety.Meal::item).toList();
            helper.assertTrue(meals.size() > DIET.size(), "Not eaten yet: " + meals + " (" + settler.statusText().getString() + ")");
            check.accept(meals.get(DIET.size()));
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** At the Pantry a settler of tier 5 whose favourite meal is missing takes it first, though the beef would fill it better. */
    private static void pantryTierFive(GameTestHelper helper) {
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = hungryAtPantry(helper, 85);
        firstMealAtPantry(helper, settler, forced,
                first -> helper.assertTrue(first.equals(id(FAVOURITE)), "The favourite meal comes first on tier 5: " + first));
    }

    /** Below tier 5 the favourite meal asks for nothing: the settler takes the usual choice, the beef. */
    private static void pantryTierFour(GameTestHelper helper) {
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = hungryAtPantry(helper, 65);
        firstMealAtPantry(helper, settler, forced,
                first -> helper.assertTrue(first.equals(id(Items.COOKED_BEEF)), "The usual choice on tier 4: " + first));
    }

    private static CompoundTag save(ServerLevel level, Settler settler) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        return output.buildResult();
    }

    private static Settler reload(ServerLevel level, CompoundTag tag) {
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.LOAD);
        settler.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        return settler;
    }
}
