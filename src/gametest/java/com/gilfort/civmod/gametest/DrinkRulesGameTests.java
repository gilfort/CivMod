package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.entertainment.EntertainmentRules.DrinkRules;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.food.TavernDrinks;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.registry.ModItems;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * GameTests for the numbers and the small parts of the Tavern drinks (spec #325, story #326): the datapack values, the
 * marks a settler keeps of its beers, the Pantry that takes drinks, and the rule that a drink is never food.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class DrinkRulesGameTests {

    private static final double EPSILON = 1.0E-9;

    private DrinkRulesGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "drink_rules_defaults", DrinkRulesGameTests::defaults);
            register(helper, "drink_rules_merge", DrinkRulesGameTests::merge);
            register(helper, "drink_rules_points_by_skill", DrinkRulesGameTests::pointsBySkill);
            register(helper, "drink_marks", DrinkRulesGameTests::marks);
            register(helper, "drink_pantry_takes_drinks", DrinkRulesGameTests::pantryTakesDrinks);
            register(helper, "drink_is_never_food", DrinkRulesGameTests::neverFood);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    /** The shipped file says what the code defaults say: Tavern base 2, half a point per beer, 3 to 5 sips of 3 s, at most 3 beers. */
    private static void defaults(GameTestHelper helper) {
        EntertainmentRules loaded = EntertainmentRules.get();
        DrinkRules drinks = loaded.drinks();
        helper.assertTrue(near(loaded.tavernPoints(), 2.0) && near(EntertainmentRules.FALLBACK.tavernPoints(), 2.0), "The Tavern base is 2");
        helper.assertTrue(near(drinks.points(), 0.5) && drinks.sipsMin() == 3 && drinks.sipsMax() == 5 && drinks.sipTicks() == 60
                && drinks.pauseMinTicks() == 100 && drinks.pauseMaxTicks() == 200 && drinks.maxPerEvening() == 3
                && drinks.refillLimitTicks() == 1000 && drinks.catchup() == 2, "The numbers of the drinks: " + drinks);
        helper.assertTrue(drinks.equals(DrinkRules.FALLBACK) && loaded.equals(EntertainmentRules.FALLBACK),
                "The shipped rules file and the code defaults agree: " + loaded);
        double[][] evening = {{0, 0, 0}, {3, 0, 0}, {4, 0, 2}, {9, 0, 2}, {0, 1, 2}, {1, 1, 2}, {3, 1, 2}, {4, 1, 3}};
        for (double[] row : evening) {
            double actual = loaded.tavernPoints((int) row[0], row[1] == 1);
            helper.assertTrue(near(actual, row[2]), row[0] + " present, jukebox " + (row[1] == 1) + ": " + row[2] + ", got " + actual);
        }
        // A full evening: the jukebox factor (2 x 1.5), three beers and the Bard (a round stop and a stage act).
        helper.assertTrue(near(loaded.tavernPoints(4, true) + 3 * drinks.points() + 2.0, 6.5), "A full evening reaches 6.5");
        helper.succeed();
    }

    /** Later files override single values; the sip count and the pause keep their order; a range that cannot hold is refused. */
    private static void merge(GameTestHelper helper) {
        EntertainmentRules merged = EntertainmentRules.merge(List.of(json("{\"drink_points\": 1, \"sips_max\": 6, \"catchup_drinks\": 1}"),
                json("{\"sips_min\": 2, \"sip_ticks\": 40, \"pause_min_ticks\": 20, \"pause_max_ticks\": 30, \"max_drinks_per_evening\": 5, \"refill_limit_ticks\": 500}")));
        helper.assertTrue(merged.drinks().equals(new DrinkRules(1.0, 2, 6, 40, 20, 30, 5, 500, 1)), "The drink values merge in order: " + merged.drinks());
        helper.assertTrue(near(merged.tavernPoints(), 2.0), "Values of other keys keep their default");
        boolean refused = false;
        try {
            EntertainmentRules.merge(List.of(json("{\"sips_min\": 6, \"sips_max\": 4}")));
        } catch (RuntimeException e) {
            refused = true;
        }
        helper.assertTrue(refused, "More sips at least than at most cannot hold");
        refused = false;
        try {
            EntertainmentRules.merge(List.of(json("{\"pause_min_ticks\": 300, \"pause_max_ticks\": 100}")));
        } catch (RuntimeException e) {
            refused = true;
        }
        helper.assertTrue(refused, "A longest pause below the shortest cannot hold");
        helper.succeed();
    }

    /** A beer gives 0.5 times (1 + 1 % per skill level of the Tavern Host); the sip count and the pause stay in their range. */
    private static void pointsBySkill(GameTestHelper helper) {
        EntertainmentRules rules = EntertainmentRules.get();
        double[][] table = {{1, 0.505}, {10, 0.55}, {50, 0.75}, {100, 1.0}};
        for (double[] row : table) {
            double actual = rules.drinkPoints((int) row[0]);
            helper.assertTrue(near(actual, row[1]), "Host skill " + row[0] + " gives " + row[1] + " per beer, got " + actual);
        }
        RandomSource random = RandomSource.create(7L);
        boolean sawMin = false;
        boolean sawMax = false;
        for (int i = 0; i < 400; i++) {
            int sips = rules.drinks().sipCount(random);
            int pause = rules.drinks().pauseTicks(random);
            helper.assertTrue(sips >= 3 && sips <= 5 && pause >= 100 && pause <= 200, "Sips " + sips + " and pause " + pause + " stay in range");
            sawMin |= sips == 3;
            sawMax |= sips == 5;
        }
        helper.assertTrue(sawMin && sawMax, "Both ends of the sip range come up");
        helper.succeed();
    }

    /** A settler counts the beers it took on a day and the points they gave; another day starts at 0; the marks are saved. */
    private static void marks(GameTestHelper helper) {
        long day = 12L;
        Entertainment start = Entertainment.of(3.0);
        helper.assertTrue(start.drinks().on(day) == 0 && near(start.drinks().pointsOn(day), 0.0), "No beer yet");
        Entertainment first = start.tookDrink(day);
        helper.assertTrue(first.drinks().on(day) == 1 && near(first.value(), 3.0), "Taking a beer counts it and gives nothing yet");
        Entertainment drank = first.tookDrink(day).drankDrink(0.5, day);
        helper.assertTrue(drank.drinks().on(day) == 2 && near(drank.value(), 3.5) && near(drank.drinks().pointsOn(day), 0.5)
                && near(drank.pointsOn(day), 0.5), "Two taken, one drunk: " + drank);
        helper.assertTrue(drank.drinks().on(day + 1) == 0 && near(drank.drinks().pointsOn(day + 1), 0.0), "The next day starts at 0");
        Entertainment nextDay = drank.tookDrink(day + 1);
        helper.assertTrue(nextDay.drinks().on(day + 1) == 1 && nextDay.drinks().on(day) == 0, "A beer of the next day: " + nextDay.drinks());
        Entertainment capped = Entertainment.of(9.8).drankDrink(0.5, day);
        helper.assertTrue(near(capped.value(), 10.0) && near(capped.drinks().pointsOn(day), 0.2), "Only the points gained count: " + capped.drinks());

        JsonElement written = Entertainment.CODEC.encodeStart(JsonOps.INSTANCE, drank).getOrThrow();
        Entertainment read = Entertainment.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow();
        helper.assertTrue(read.equals(drank), "Saved and read again: " + read);
        JsonElement old = json("{\"value\": 4.0}");
        helper.assertTrue(Entertainment.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow().drinks().equals(Entertainment.DrinkMarks.NONE),
                "An old save has no drink marks");
        helper.succeed();
    }

    /** The Pantry takes the tag civmod:tavern_drinks like food (by hand and by hopper) and still no cobblestone. */
    private static void pantryTakesDrinks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = TavernGameTests.pantry(helper);
        JobBlockEntity pantry = (JobBlockEntity) level.getBlockEntity(pos);
        ItemStack beer = new ItemStack(ModItems.BEER.get());
        ItemStack otherModDrink = new ItemStack(Items.HONEY_BOTTLE);
        helper.assertTrue(beer.is(TavernDrinks.TAG), "Beer is in the tag");
        helper.assertTrue(otherModDrink.is(TavernDrinks.TAG), "The test pack adds a drink to the tag, as an addon would");
        helper.assertTrue(pantry.canPlaceItem(0, beer) && pantry.canPlaceItem(0, otherModDrink), "Drinks by hand");
        helper.assertTrue(pantry.canPlaceItem(0, new ItemStack(Items.GLASS_BOTTLE)) && pantry.canPlaceItem(0, new ItemStack(Items.BOWL)),
                "The empty bottle of a drink and the bowl of a stew go back in");
        helper.assertFalse(pantry.canPlaceItem(0, new ItemStack(Items.COBBLESTONE)), "Still no cobblestone");
        var handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(handler.insert(ItemResource.of(ModItems.BEER.get()), 8, transaction) == 8, "Beer by hopper");
            helper.assertTrue(handler.insert(ItemResource.of(Items.COBBLESTONE), 8, transaction) == 0, "No cobblestone by hopper");
        }
        helper.succeed();
    }

    /** A drink never counts as food: a Pantry that holds only drinks is no food point, and the Settler does not eat it. */
    private static void neverFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = TavernGameTests.pantry(helper);
        JobBlockEntity pantry = (JobBlockEntity) level.getBlockEntity(pos);
        pantry.setItem(0, new ItemStack(ModItems.BEER.get(), 8));
        pantry.setItem(1, new ItemStack(Items.HONEY_BOTTLE, 4));
        helper.assertFalse(SettlerFood.isEdible(new ItemStack(ModItems.BEER.get())), "Beer is no food");
        helper.assertFalse(SettlerFood.isEdible(new ItemStack(Items.HONEY_BOTTLE)), "A tagged drink with a food value is no food either");
        helper.assertTrue(ClerkStorage.stock(level, pos, false).contains(stack -> stack.is(ModItems.BEER.get())), "The drinks are in the stock");
        helper.assertFalse(ClerkStorage.stock(level, pos, false).contains(SettlerFood::isEdible), "But none of it is food");
        var data = com.gilfort.civmod.settlement.SettlementData.get(level);
        helper.assertTrue(ClerkStorage.nearestFood(level, data.settlementAt(pos).orElseThrow(), pos, 5000L, java.util.Optional.empty(), p -> true).isEmpty(),
                "A Pantry with only drinks is no food point");
        pantry.setItem(2, new ItemStack(Items.BREAD));
        helper.assertTrue(ClerkStorage.nearestFood(level, data.settlementAt(pos).orElseThrow(), pos, 5000L, java.util.Optional.empty(), p -> true).isPresent(),
                "With bread it is one again");
        helper.succeed();
    }
}
