package com.gilfort.civmod.job;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.Schedule.MealPurpose;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the time a meal at a Pantry takes (#323, vault {@code Systems/Needs.md}, {@code Systems/Tavern Host.md}):
 * the settler takes the food at the counter and eats it beside it; the meal lasts as long as the food says, the queue moves
 * on meanwhile, and catch-up eats on the spot.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class PantryMealGameTests {

    private PantryMealGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "pantry_meal_takes_time_by_food", PantryMealGameTests::takesTimeByFood);
            register(helper, "pantry_meal_queue_moves_while_eating", PantryMealGameTests::queueMovesWhileEating);
            register(helper, "meal_catch_up_is_instant", PantryMealGameTests::catchUpIsInstant);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A settler without AI, hungry, with empty pockets, in the settlement of the Pantry. */
    private static Settler settler(GameTestHelper helper, BlockPos relative, SettlementData data, Settlement settlement) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            settler.getInventory().setItem(i, ItemStack.EMPTY);
        }
        settler.getFood().setAppetite(1.0F);
        settler.getFood().setLevel(4);
        settler.getFood().setSaturation(0.0F);
        return settler;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** Places a Pantry at (1, 1, 1). */
    private static BlockPos pantry(GameTestHelper helper) {
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.PANTRY_BLOCK.get());
        return helper.absolutePos(new BlockPos(1, 1, 1));
    }

    /** A settler on its way to the nearest Pantry with food, as the Schedule sends it when it is hungry. */
    private static SettlerMeals walkToPantry(ServerLevel level, Settler settler) {
        SettlerMeals meals = new SettlerMeals(settler.getWork());
        ClerkStorage.FoodPoint point = meals.lookup(level, DayClock.dayTime(level)).get().orElseThrow();
        meals.fetchFood(level, point, MealPurpose.HUNGRY);
        return meals;
    }

    /** One customer of a Pantry, driven tick by tick like the Settler Shell does. */
    private static final class Customer {
        private final Settler settler;
        private final SettlerMeals meals;
        private boolean eating;
        private boolean done;

        Customer(ServerLevel level, Settler settler) {
            this.settler = settler;
            this.meals = walkToPantry(level, settler);
        }

        /** One tick: ask for the food at the Pantry, or eat what was taken. */
        void tick(ServerLevel level) {
            if (done) {
                return;
            }
            BodyStep step = eating ? meals.eatTick(level) : meals.atFoodPoint(level);
            eating |= step == BodyStep.EAT;
            done = step instanceof BodyStep.Done;
        }
    }

    /** Self-service: a carrot (3 nutrition) takes 70 ticks, cooked beef (8) takes 120, from the taking to the effect. */
    private static void takesTimeByFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos pantryPos = pantry(helper);
        Container pantry = (Container) level.getBlockEntity(pantryPos);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pantryPos).orElseThrow();
        eatenAfter(helper, pantry, new ItemStack(Items.CARROT), 70, settler(helper, new BlockPos(3, 1, 3), data, settlement));
        eatenAfter(helper, pantry, new ItemStack(Items.COOKED_BEEF), 120, settler(helper, new BlockPos(3, 1, 3), data, settlement));
        helper.succeed();
    }

    private static void eatenAfter(GameTestHelper helper, Container pantry, ItemStack food, int ticks, Settler settler) {
        ServerLevel level = helper.getLevel();
        pantry.setItem(0, food);
        SettlerMeals meals = walkToPantry(level, settler);
        helper.assertTrue(meals.atFoodPoint(level) == BodyStep.EAT, "The settler takes the food and starts eating");
        helper.assertTrue(pantry.getItem(0).isEmpty(), "The food is taken from the Pantry at once");
        for (int tick = 1; tick < ticks; tick++) {
            helper.assertTrue(meals.eatTick(level) == BodyStep.STAY && settler.getFood().level() == 4,
                    food.getItem() + " still eaten at tick " + tick + ", food " + settler.getFood().level());
        }
        helper.assertTrue(meals.eatTick(level) instanceof BodyStep.Done && settler.getFood().level() > 4,
                food.getItem() + " eaten at tick " + ticks + ", food " + settler.getFood().level());
        settler.discard();
    }

    /**
     * Two settlers wait for a working Tavern Host: the first takes its beef at the counter (40 ticks) and eats it for 120
     * ticks. The second is served 40 ticks after the first, while the first still eats.
     */
    private static void queueMovesWhileEating(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos pantryPos = pantry(helper);
        Container pantry = (Container) level.getBlockEntity(pantryPos);
        pantry.setItem(0, new ItemStack(Items.COOKED_BEEF, 4));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pantryPos).orElseThrow();
        Settler host = settler(helper, new BlockPos(1, 1, 3), data, settlement);
        TestAssignments.assign(data, pantryPos, host.getUUID(), "Host", settlement.id(), List.of());
        host.addJob(new SettlerJob(ModJobs.TAVERN_HOST.get(), pantryPos));
        Customer first = new Customer(level, settler(helper, new BlockPos(3, 1, 3), data, settlement));
        Customer second = new Customer(level, settler(helper, new BlockPos(4, 1, 3), data, settlement));
        helper.succeedWhen(() -> {
            first.tick(level);
            second.tick(level);
            helper.assertTrue(second.eating, "The second settler is not served yet");
            helper.assertTrue(first.eating && !first.done, "The second was served while the first still eats (first eating " + first.eating
                    + ", done " + first.done + ")");
            helper.assertTrue(first.settler.getFood().level() == 4, "The first has not finished its beef");
            TestAssignments.unassign(data, pantryPos);
            first.settler.discard();
            second.settler.discard();
            host.discard();
        });
    }

    /** Catch-up eats on the spot: a missed day takes food from the Pantry and from the pockets without a single tick passing. */
    private static void catchUpIsInstant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantryPos = pantry(helper);
        Container pantry = (Container) level.getBlockEntity(pantryPos);
        pantry.setItem(0, new ItemStack(Items.COOKED_BEEF, 32));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pantryPos).orElseThrow();
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;

        Settler fromPantry = settler(helper, new BlockPos(2, 1, 3), data, settlement);
        fromPantry.getWork().setCatchUp(dayStart - 24000L, dayStart);
        CatchUp.runNow(level, List.of(fromPantry));
        helper.assertTrue(pantry.getItem(0).getCount() < 32, "Caught up at the Pantry: beef left " + pantry.getItem(0).getCount());
        fromPantry.discard();

        pantry.setItem(0, ItemStack.EMPTY);
        Settler fromPockets = settler(helper, new BlockPos(2, 1, 3), data, settlement);
        fromPockets.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 8));
        fromPockets.getWork().setCatchUp(dayStart - 24000L, dayStart);
        CatchUp.runNow(level, List.of(fromPockets));
        helper.assertTrue(fromPockets.getInventory().getItem(Settler.POCKETS_START).getCount() < 8,
                "Caught up from the pockets: bread left " + fromPockets.getInventory().getItem(Settler.POCKETS_START).getCount());
        fromPockets.discard();
        helper.succeed();
    }
}
