package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.food.Pantries;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.JobType;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Story 7 (food need and pantries).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FoodGameTests {

    private FoodGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "food_exhaustion_and_appetite", FoodGameTests::exhaustionAndAppetite);
            register(helper, "food_choice_and_edibility", FoodGameTests::choiceAndEdibility);
            register(helper, "food_pantry_toggle_and_persistence", FoodGameTests::pantryToggleAndPersistence);
            register(helper, "food_morning_plan", FoodGameTests::morningPlan);
            register(helper, "food_player_feeding", FoodGameTests::playerFeeding);
            register(helper, "food_eats_at_pantry", FoodGameTests::eatsAtPantry);
            register(helper, "food_forages_without_food", FoodGameTests::foragesWithoutFood);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Exhaustion converts like vanilla (saturation first), hunger rate incl. work and appetite, appetite roll. */
    private static void exhaustionAndAppetite(GameTestHelper helper) {
        SettlerFood food = new SettlerFood();
        food.setSaturation(5.0F);
        food.exhaust(4.0F * 5);
        helper.assertTrue(food.level() == 20 && food.saturation() == 0.0F, "Saturation is used up first");
        food.exhaust(4.0F * 2);
        helper.assertTrue(food.level() == 18, "Then the food level drops: " + food.level());

        helper.assertTrue(Math.abs(SettlerFood.exhaustionFor(SettlerFood.AWAKE_TICKS, 0, 8.0, 0.6, 1.0) - 32.0F) < 1.0E-3,
                "Base hunger: 8 points per awake day");
        helper.assertTrue(Math.abs(SettlerFood.exhaustionFor(0, 1200, 8.0, 0.6, 1.0) - 2.4F) < 1.0E-3, "Work hunger: 0.6 per work minute");
        helper.assertTrue(Math.abs(SettlerFood.exhaustionFor(SettlerFood.AWAKE_TICKS, 1200, 8.0, 0.6, 1.25) - 1.25F * 34.4F) < 1.0E-3,
                "Appetite multiplies all hunger");

        RandomSource random = RandomSource.create(3L);
        double logSum = 0.0;
        for (int i = 0; i < 2000; i++) {
            float appetite = SettlerFood.rollAppetite(random, 0.8, 1.25);
            helper.assertTrue(appetite >= 0.8F - 1.0E-4F && appetite <= 1.25F + 1.0E-4F, "Appetite out of range: " + appetite);
            logSum += Math.log(appetite);
        }
        helper.assertTrue(Math.abs(logSum / 2000) < 0.02, "Appetite should be log-symmetric around 1");
        helper.assertTrue(SettlerFood.appetiteKey(0.85F).endsWith("light") && SettlerFood.appetiteKey(1.0F).endsWith("normal")
                && SettlerFood.appetiteKey(1.2F).endsWith("big"), "Appetite texts");
        helper.succeed();
    }

    /** Food choice closest to full, no overeating unless hungry, inedible tag, leftovers. */
    private static void choiceAndEdibility(GameTestHelper helper) {
        List<ItemStack> stacks = List.of(new ItemStack(Items.BREAD), new ItemStack(Items.COOKED_BEEF), new ItemStack(Items.CARROT),
                new ItemStack(Items.ROTTEN_FLESH), new ItemStack(Items.STONE));
        helper.assertTrue(SettlerFood.choose(stacks, 15).equals(Optional.of(0)), "Bread brings 15 to 20");
        helper.assertTrue(SettlerFood.choose(stacks, 18).isEmpty(), "Nothing fits at 18 without overeating");
        helper.assertTrue(SettlerFood.choose(stacks, 5).equals(Optional.of(1)), "Steak brings 5 closest to 20");
        helper.assertTrue(SettlerFood.choose(stacks, 9).equals(Optional.of(1)), "Below 10 overeating is allowed (steak: 17)");
        helper.assertTrue(SettlerFood.choose(List.of(new ItemStack(Items.ROTTEN_FLESH)), 0).isEmpty(), "Rotten flesh is never eaten");
        helper.assertFalse(SettlerFood.isEdible(new ItemStack(Items.PUFFERFISH)) || SettlerFood.isEdible(new ItemStack(Items.STONE)), "Inedible");
        helper.assertTrue(SettlerFood.isEdible(new ItemStack(Items.COOKED_COD)), "Fish is edible");
        helper.assertTrue(SettlerFood.remainder(new ItemStack(Items.MUSHROOM_STEW)).is(Items.BOWL), "Stew leaves a bowl");
        helper.succeed();
    }

    /** Pantry toggle, double chest as one pantry, persistence, border lookup. */
    private static void pantryToggleAndPersistence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = new SettlementData();
        Settlement settlement = SettlementManager.place(data, helper.absolutePos(new BlockPos(1, 1, 1)), JobType.LUMBERJACK, 1, 0L,
                RandomSource.create(0L), SettlementManager.Listener.NONE).settlement();
        BlockPos left = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockPos right = left.relative(Direction.EAST);
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        level.setBlockAndUpdate(left, chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
        level.setBlockAndUpdate(right, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));

        helper.assertTrue(data.settlementContaining(left).filter(s -> s.id() == settlement.id()).isPresent(), "Chest lies inside the border");
        helper.assertTrue(data.togglePantry(settlement, Pantries.canonical(level, settlement, left)), "Becomes a pantry");
        helper.assertTrue(Pantries.canonical(level, settlement, right).equals(left), "The other half maps to the same pantry");
        helper.assertTrue(data.settlementContaining(helper.absolutePos(new BlockPos(1, 1, 40))).isEmpty(), "Outside every border");

        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        Settlement loaded = Settlement.CODEC.parse(ops, Settlement.CODEC.encodeStart(ops, settlement).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.pantries().equals(settlement.pantries()) && loaded.pantries().contains(left), "Pantry survives saving");

        helper.assertFalse(data.togglePantry(settlement, Pantries.canonical(level, settlement, right)), "Second toggle removes it");
        data.togglePantry(settlement, left);
        helper.assertTrue(data.removePantry(left) && settlement.pantries().isEmpty(), "Destroyed container is removed");
        helper.succeed();
    }

    /** Morning: forage when hungry without any food, fetch provisions when short, otherwise work. */
    private static void morningPlan(GameTestHelper helper) {
        helper.assertTrue(SettlerWork.morningPlan(5, 0, 6, false) == SettlerWork.MorningPlan.FORAGE, "Hungry, no food anywhere");
        helper.assertTrue(SettlerWork.morningPlan(5, 3, 6, false) == SettlerWork.MorningPlan.WORK, "Hungry but has provisions");
        helper.assertTrue(SettlerWork.morningPlan(20, 0, 6, true) == SettlerWork.MorningPlan.FETCH_PROVISIONS, "Short of provisions");
        helper.assertTrue(SettlerWork.morningPlan(20, 10, 6, true) == SettlerWork.MorningPlan.WORK, "Enough provisions");
        helper.assertTrue(SettlerWork.morningPlan(20, 0, 6, false) == SettlerWork.MorningPlan.WORK, "Not hungry, no pantry: work");
        helper.succeed();
    }

    private static Settler settler(GameTestHelper helper, BlockPos relative) {
        Settler settler = ModEntityTypes.SETTLER.get().create(helper.getLevel(), EntitySpawnReason.EVENT);
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        settler.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        settler.setNoAi(true);
        helper.getLevel().addFreshEntity(settler);
        return settler;
    }

    /** Fed by a player: eaten when allowed, otherwise stored as provisions; full pockets refuse. */
    private static void playerFeeding(GameTestHelper helper) {
        Settler settler = settler(helper, new BlockPos(2, 1, 2));
        ServerLevel level = helper.getLevel();
        settler.getFood().setLevel(12);
        helper.assertTrue(settler.getWork().feed(level, new ItemStack(Items.BREAD)).isEmpty() && settler.getFood().level() == 17, "Eaten");
        helper.assertTrue(settler.getWork().feed(level, new ItemStack(Items.BREAD)).isEmpty() && settler.getFood().level() == 17
                && SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE) == 5,
                "Would overeat: stored as provisions");
        helper.assertTrue(settler.getWork().feed(level, new ItemStack(Items.MUSHROOM_STEW)).isEmpty(), "Stew stored");
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            settler.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        }
        helper.assertFalse(settler.getWork().feed(level, new ItemStack(Items.APPLE)).isEmpty(), "Pockets full: refused");
        settler.discard();
        helper.succeed();
    }

    /** In the world: a hungry settler walks to the pantry, eats and takes provisions. */
    private static void eatsAtPantry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        floor(helper);
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos pantryPos = helper.absolutePos(new BlockPos(6, 1, 6));
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.CHEST);
        Container chest = (ChestBlockEntity) level.getBlockEntity(pantryPos);
        chest.setItem(0, new ItemStack(Items.BREAD, 16));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow();
        data.togglePantry(settlement, pantryPos);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getFood().setLevel(5);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getFood().level() >= 15, "Not eaten yet: " + settler.getFood().level() + " (" + settler.statusText().getString() + ")");
            helper.assertTrue(SettlerFood.pocketNutrition(settler.getInventory(), Settler.POCKETS_START, Settler.POCKETS_SIZE) >= 6,
                    "No provisions taken");
            settler.discard();
            data.removePantry(pantryPos);
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** In the world: hungry without any food the settler goes foraging and vanishes. */
    private static void foragesWithoutFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        floor(helper);
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getFood().setLevel(4);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getFood().isForaging(), "Should forage (" + settler.statusText().getString() + ")");
            helper.assertTrue(settler.getFood().isAway() && settler.isInvisible() && !settler.isPickable(), "Should have vanished");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }
}
