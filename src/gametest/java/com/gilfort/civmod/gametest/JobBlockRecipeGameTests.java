package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.registry.ModItems;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the JobBlock recipes (vault {@code Systems/JobBlock.md}, decision of 2026-10-31): every JobBlock and the
 * Mission Board use the shape {@code P S P} / {@code P B P}.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobBlockRecipeGameTests {

    private JobBlockRecipeGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> helper.register(
                ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id("job_block_recipes")), JobBlockRecipeGameTests::recipes));
    }

    /** The 2 rows of the shape, with the given items in the middle and at the bottom. */
    private static CraftingInput shape(Item special, Item bottom) {
        ItemStack planks = new ItemStack(Items.OAK_PLANKS);
        return CraftingInput.of(3, 2, List.of(planks, new ItemStack(special), planks, planks, new ItemStack(bottom), planks));
    }

    private static boolean crafts(ServerLevel level, CraftingInput input, Item result) {
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level)
                .filter(recipe -> recipe.value().assemble(input).is(result)).isPresent();
    }

    /** Each block crafts from the shape with its special item; the replaced items and the old shape craft nothing. */
    private static void recipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<Item, Item> specials = Map.of(
                ModItems.CLERK_BLOCK.get(), Items.PAPER,
                ModItems.CRAFTSPERSON_BLOCK.get(), Items.CRAFTING_TABLE,
                ModItems.LUMBERJACK_BLOCK.get(), Items.STONE_AXE,
                ModItems.MINER_BLOCK.get(), Items.STONE_PICKAXE,
                ModItems.FISHERMAN_BLOCK.get(), Items.COD,
                ModItems.GATHERER_BLOCK.get(), Items.WHEAT_SEEDS,
                ModItems.SMELTER_BLOCK.get(), Items.FURNACE,
                ModItems.MISSION_BOARD.get(), Items.LEATHER_BOOTS);
        specials.forEach((block, special) -> helper.assertTrue(crafts(level, shape(special, Items.BARREL), block), "Crafts " + block));

        helper.assertFalse(crafts(level, shape(Items.MAP, Items.BARREL), ModItems.MISSION_BOARD.get()), "The map is replaced");
        helper.assertFalse(crafts(level, shape(Items.SWEET_BERRIES, Items.BARREL), ModItems.GATHERER_BLOCK.get()), "Sweet berries are replaced");
        helper.assertFalse(crafts(level, shape(Items.PAPER, Items.CHEST), ModItems.CLERK_BLOCK.get()), "The Clerk chest is replaced");
        helper.assertFalse(crafts(level, shape(Items.CRAFTING_TABLE, Items.CHEST), ModItems.CRAFTSPERSON_BLOCK.get()), "The Craftsperson chest is replaced");
        ItemStack planks = new ItemStack(Items.OAK_PLANKS);
        CraftingInput threeRows = CraftingInput.of(3, 3, List.of(planks, new ItemStack(Items.STONE_AXE), planks, planks,
                new ItemStack(Items.BARREL), planks, planks, planks, planks));
        helper.assertFalse(crafts(level, threeRows, ModItems.LUMBERJACK_BLOCK.get()), "The third row of planks is gone");
        helper.succeed();
    }
}
