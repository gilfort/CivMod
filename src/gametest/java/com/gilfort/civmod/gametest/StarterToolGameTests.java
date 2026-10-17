package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the starter tool of JobBlocks and the Fisherman Block recipe (#60, vault {@code Systems/JobBlock.md}).
 * <p>
 * JobBlocks are placed from their item by a mock survival player, the way a player places them.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class StarterToolGameTests {

    private StarterToolGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "starter_tool_on_first_placement", StarterToolGameTests::firstPlacement);
            register(helper, "starter_tool_none_for_jobs_without_tool", StarterToolGameTests::jobsWithoutTool);
            register(helper, "starter_tool_not_after_break_and_replace", StarterToolGameTests::breakAndReplace);
            register(helper, "starter_tool_not_in_any_drop", StarterToolGameTests::noToolInAnyDrop);
            register(helper, "starter_tool_fisherman_recipe_uses_raw_fish", StarterToolGameTests::fishermanRecipe);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A new Lumberjack, Miner and Fisherman Block puts its starter tool into its inventory when placed. */
    private static void firstPlacement(GameTestHelper helper) {
        expectToolAfterPlacement(helper, ModItems.LUMBERJACK_BLOCK.get(), new BlockPos(1, 1, 1), Items.STONE_AXE);
        expectToolAfterPlacement(helper, ModItems.MINER_BLOCK.get(), new BlockPos(3, 1, 1), Items.STONE_PICKAXE);
        expectToolAfterPlacement(helper, ModItems.FISHERMAN_BLOCK.get(), new BlockPos(5, 1, 1), Items.FISHING_ROD);
        remove(helper, new BlockPos(1, 1, 1), new BlockPos(3, 1, 1), new BlockPos(5, 1, 1));
        helper.succeed();
    }

    private static void expectToolAfterPlacement(GameTestHelper helper, Item jobBlockItem, BlockPos pos, Item tool) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        place(helper, player, new ItemStack(jobBlockItem), pos);
        List<ItemStack> contents = contents(helper, pos);
        helper.assertTrue(contents.size() == 1 && contents.getFirst().is(tool) && contents.getFirst().getCount() == 1,
                jobBlockItem + " should hold exactly one " + tool + ", holds " + contents);
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Placing should use up the JobBlock item");
    }

    /** Clerk, Craftsperson and Mission Board carry no starter tool and start with an empty inventory. */
    private static void jobsWithoutTool(GameTestHelper helper) {
        List<Item> items = List.of(ModItems.CLERK_BLOCK.get(), ModItems.CRAFTSPERSON_BLOCK.get(), ModItems.MISSION_BOARD.get());
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            helper.assertTrue(!new ItemStack(item).has(ModDataComponents.STARTER_TOOL.get()), item + " should carry no starter tool");
            BlockPos pos = new BlockPos(1 + 2 * i, 1, 1);
            place(helper, helper.makeMockPlayer(GameType.SURVIVAL), new ItemStack(item), pos);
            helper.assertTrue(contents(helper, pos).isEmpty(), item + " should start empty, holds " + contents(helper, pos));
        }
        remove(helper, new BlockPos(1, 1, 1), new BlockPos(3, 1, 1), new BlockPos(5, 1, 1));
        helper.succeed();
    }

    /** A broken JobBlock drops without its starter tool (keeping its level); re-placing it adds no tool. */
    private static void breakAndReplace(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(pos);
        ItemStack fresh = new ItemStack(ModItems.MINER_BLOCK.get());
        fresh.set(ModDataComponents.JOB_LEVEL.get(), 2);
        place(helper, helper.makeMockPlayer(GameType.SURVIVAL), fresh, pos);
        helper.getBlockEntity(pos, JobBlockEntity.class).clearContent();

        // Check the loot table result directly; item entities are unreliable in non-ticking test chunks.
        List<ItemStack> drops = Block.getDrops(helper.getLevel().getBlockState(absolute), helper.getLevel(), absolute,
                helper.getLevel().getBlockEntity(absolute));
        helper.assertTrue(drops.size() == 1 && drops.getFirst().is(ModItems.MINER_BLOCK.get()), "Expected the Miner Block as drop: " + drops);
        ItemStack drop = drops.getFirst();
        helper.assertTrue(!drop.has(ModDataComponents.STARTER_TOOL.get()), "Dropped JobBlock still carries a starter tool");
        helper.assertTrue(drop.getOrDefault(ModDataComponents.JOB_LEVEL.get(), 1) == 2, "Dropped JobBlock lost its level");

        helper.getLevel().destroyBlock(absolute, false);
        place(helper, helper.makeMockPlayer(GameType.SURVIVAL), drop, pos);
        helper.assertTrue(contents(helper, pos).isEmpty(), "Re-placed JobBlock created a tool: " + contents(helper, pos));
        remove(helper, pos);
        helper.succeed();
    }

    /**
     * Every JobBlock with a starter tool drops without it: guards the loot table's starter tool removal for each entry of
     * {@link ModItems#STARTER_TOOLS}, also for jobs added later.
     */
    private static void noToolInAnyDrop(GameTestHelper helper) {
        int x = 1;
        for (var entry : ModItems.STARTER_TOOLS.entrySet()) {
            Item jobBlockItem = entry.getKey().get();
            BlockPos pos = new BlockPos(x, 1, 1);
            BlockPos absolute = helper.absolutePos(pos);
            x += 2;
            ItemStack fresh = new ItemStack(jobBlockItem);
            helper.assertTrue(fresh.has(ModDataComponents.STARTER_TOOL.get()), jobBlockItem + " should carry its starter tool");
            place(helper, helper.makeMockPlayer(GameType.SURVIVAL), fresh, pos);
            helper.getBlockEntity(pos, JobBlockEntity.class).clearContent();
            List<ItemStack> drops = Block.getDrops(helper.getLevel().getBlockState(absolute), helper.getLevel(), absolute,
                    helper.getLevel().getBlockEntity(absolute));
            helper.assertTrue(drops.size() == 1 && drops.getFirst().is(jobBlockItem), "Expected " + jobBlockItem + " as drop: " + drops);
            helper.assertTrue(!drops.getFirst().has(ModDataComponents.STARTER_TOOL.get()),
                    jobBlockItem + " drops with its starter tool: its loot table must remove civmod:starter_tool");
            remove(helper, pos);
        }
        helper.succeed();
    }

    /** The Fisherman Block is crafted with any raw fish on top of the barrel instead of a fishing rod. */
    private static void fishermanRecipe(GameTestHelper helper) {
        for (Item fish : List.of(Items.COD, Items.SALMON, Items.TROPICAL_FISH, Items.PUFFERFISH)) {
            helper.assertTrue(craftsFishermanBlock(helper, fish), "Fisherman Block should be crafted with " + fish);
        }
        helper.assertTrue(!craftsFishermanBlock(helper, Items.FISHING_ROD), "Fisherman Block should no longer need a fishing rod");
        helper.succeed();
    }

    private static boolean craftsFishermanBlock(GameTestHelper helper, Item top) {
        ItemStack planks = new ItemStack(Items.OAK_PLANKS);
        CraftingInput input = CraftingInput.of(3, 3, List.of(
                planks, new ItemStack(top), planks,
                planks, new ItemStack(Items.BARREL), planks,
                planks, planks, planks));
        return helper.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                .map(holder -> holder.value().assemble(input).is(ModItems.FISHERMAN_BLOCK.get()))
                .orElse(false);
    }

    private static void place(GameTestHelper helper, Player player, ItemStack stack, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        // The test area outside the empty structure is barrier; clear the target so the click places into it.
        helper.setBlock(pos, Blocks.AIR);
        helper.placeAt(player, stack, pos.below(), Direction.UP);
    }

    /** Removes placed JobBlocks so they cannot join the settlements of other tests in the shared test level. */
    private static void remove(GameTestHelper helper, BlockPos... positions) {
        for (BlockPos pos : positions) {
            helper.getBlockEntity(pos, JobBlockEntity.class).clearContent();
            helper.getLevel().destroyBlock(helper.absolutePos(pos), false);
        }
    }

    private static List<ItemStack> contents(GameTestHelper helper, BlockPos pos) {
        JobBlockEntity jobBlock = helper.getBlockEntity(pos, JobBlockEntity.class);
        return IntStream.range(0, jobBlock.getContainerSize())
                .mapToObj(jobBlock::getItem)
                .filter(stack -> !stack.isEmpty())
                .toList();
    }
}
