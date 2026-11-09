package com.gilfort.civmod.job;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for a meal from the pockets (#226, vault {@code Systems/Needs.md}): one item takes 5 seconds, and "eat until
 * full" takes 5 seconds per item.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class PocketMealGameTests {

    /** One item of a pocket meal: 5 s. */
    private static final int ITEM_TICKS = 100;

    private PocketMealGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "pocket_meal_takes_five_seconds", PocketMealGameTests::takesFiveSeconds);
            register(helper, "pocket_meal_until_full_per_item", PocketMealGameTests::untilFullPerItem);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A hungry settler without AI (its own meals stay out of the way) with bread in its pockets. */
    private static Settler hungry(GameTestHelper helper, int bread) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.getFood().setLevel(4);
        settler.getFood().setSaturation(0.0F);
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, bread));
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

    private static int bread(Settler settler) {
        return settler.getInventory().getItem(Settler.POCKETS_START).getCount();
    }

    /** One item is eaten after 100 ticks, not before. */
    private static void takesFiveSeconds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = hungry(helper, 2);
        SettlerMeals meals = new SettlerMeals(settler.getWork());
        helper.assertTrue(meals.pocketMeal(false) == BodyStep.EAT, "Starts eating");
        helper.assertTrue(eat(meals, level, ITEM_TICKS - 1) == BodyStep.STAY && bread(settler) == 2 && settler.getFood().level() == 4,
                "Still eating after 99 ticks: " + bread(settler) + " bread, food " + settler.getFood().level());
        helper.assertTrue(eat(meals, level, 1) == BodyStep.DONE && bread(settler) == 1 && settler.getFood().level() > 4,
                "One bread eaten at tick 100: " + bread(settler) + " bread, food " + settler.getFood().level());
        settler.discard();
        helper.succeed();
    }

    /** "Eat until full" takes the 100 ticks again for every further item. */
    private static void untilFullPerItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = hungry(helper, 2);
        SettlerMeals meals = new SettlerMeals(settler.getWork());
        helper.assertTrue(meals.pocketMeal(true) == BodyStep.EAT, "Starts eating");
        helper.assertTrue(eat(meals, level, ITEM_TICKS) == BodyStep.STAY && bread(settler) == 1, "First bread at tick 100, keeps eating: " + bread(settler));
        helper.assertTrue(eat(meals, level, ITEM_TICKS - 1) == BodyStep.STAY && bread(settler) == 1, "Second bread not yet at tick 199");
        helper.assertTrue(eat(meals, level, 1) == BodyStep.DONE && bread(settler) == 0, "Second bread at tick 200: " + bread(settler));
        settler.discard();
        helper.succeed();
    }
}
