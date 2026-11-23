package com.gilfort.civmod.job;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the time a meal from the pockets takes (#226, #323, vault {@code Systems/Needs.md}): one item takes 2
 * seconds plus half a second per nutrition point, at most 8 seconds, and "eat until full" takes that again per item.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class PocketMealGameTests {

    private PocketMealGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "pocket_meal_small_food", PocketMealGameTests::smallFood);
            register(helper, "pocket_meal_big_food", PocketMealGameTests::bigFood);
            register(helper, "pocket_meal_capped_at_eight_seconds", PocketMealGameTests::cappedAtEightSeconds);
            register(helper, "pocket_meal_until_full_per_item", PocketMealGameTests::untilFullPerItem);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A hungry settler without AI (its own meals stay out of the way) with food in its pockets. */
    private static Settler hungry(GameTestHelper helper, ItemStack pockets) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.getFood().setLevel(4);
        settler.getFood().setSaturation(0.0F);
        settler.getInventory().setItem(Settler.POCKETS_START, pockets);
        return settler;
    }

    /** Runs eating ticks and returns the last step. */
    private static BodyStep eat(SettlerMeals meals, ServerLevel level, int ticks) {
        BodyStep step = BodyStep.STAY;
        for (int i = 0; i < ticks; i++) {
            step = meals.eatTick(level);
        }
        return step;
    }

    private static int count(Settler settler) {
        return settler.getInventory().getItem(Settler.POCKETS_START).getCount();
    }

    /** One item of the pockets is eaten exactly after {@code ticks} ticks, not before. */
    private static void eatenAfter(GameTestHelper helper, ItemStack food, int ticks) {
        ServerLevel level = helper.getLevel();
        Settler settler = hungry(helper, food);
        int before = food.getCount();
        SettlerMeals meals = new SettlerMeals(settler.getWork());
        helper.assertTrue(meals.pocketMeal(false) == BodyStep.EAT, "Starts eating");
        helper.assertTrue(eat(meals, level, ticks - 1) == BodyStep.STAY && count(settler) == before && settler.getFood().level() == 4,
                "Still eating after " + (ticks - 1) + " ticks: " + count(settler) + " items, food " + settler.getFood().level());
        helper.assertTrue(eat(meals, level, 1) == BodyStep.DONE && count(settler) == before - 1 && settler.getFood().level() > 4,
                "One item eaten at tick " + ticks + ": " + count(settler) + " items, food " + settler.getFood().level());
        settler.discard();
        helper.succeed();
    }

    /** A carrot (3 nutrition): 2 s + 1.5 s = 70 ticks. */
    private static void smallFood(GameTestHelper helper) {
        eatenAfter(helper, new ItemStack(Items.CARROT, 2), 70);
    }

    /** Cooked beef (8 nutrition): 2 s + 4 s = 120 ticks. */
    private static void bigFood(GameTestHelper helper) {
        eatenAfter(helper, new ItemStack(Items.COOKED_BEEF, 2), 120);
    }

    /** A feast of 20 nutrition would take 12 s: the cap is 8 s = 160 ticks. */
    private static void cappedAtEightSeconds(GameTestHelper helper) {
        ItemStack feast = new ItemStack(Items.BREAD, 2);
        feast.set(DataComponents.FOOD, new FoodProperties(20, 1.0F, false));
        eatenAfter(helper, feast, 160);
    }

    /** "Eat until full" takes the time of the item again for every further item (bread, 5 nutrition: 90 ticks). */
    private static void untilFullPerItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = hungry(helper, new ItemStack(Items.BREAD, 2));
        SettlerMeals meals = new SettlerMeals(settler.getWork());
        helper.assertTrue(meals.pocketMeal(true) == BodyStep.EAT, "Starts eating");
        helper.assertTrue(eat(meals, level, 90) == BodyStep.STAY && count(settler) == 1, "First bread at tick 90, keeps eating: " + count(settler));
        helper.assertTrue(eat(meals, level, 89) == BodyStep.STAY && count(settler) == 1, "Second bread not yet at tick 179");
        helper.assertTrue(eat(meals, level, 1) == BodyStep.DONE && count(settler) == 0, "Second bread at tick 180: " + count(settler));
        settler.discard();
        helper.succeed();
    }
}
