package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the three cloth sets (Simple, Medium, High; vault {@code Jobs/Tailor.md}): four wearable pieces each
 * with armour 1, no toughness and no knockback resistance, listed in one item tag per set, and dyed at a crafting
 * table like leather armour.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClothSetGameTests {

    private static final List<String> SETS = List.of("simple", "medium", "high");
    private static final List<String> TAGS = List.of("simple_clothing", "better_clothing", "highest_clothing");
    private static final List<String> PIECES = List.of("helmet", "chestplate", "leggings", "boots");
    private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private ClothSetGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "cloth_sets_are_wearable_clothing", ClothSetGameTests::wearableClothing);
            register(helper, "cloth_dyes_like_leather", ClothSetGameTests::dyesLikeLeather);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static ItemStack piece(String set, String piece) {
        Item item = BuiltInRegistries.ITEM.getValue(CivMod.id(set + "_cloth_" + piece));
        return new ItemStack(item);
    }

    /**
     * Every set has a helmet, chestplate, leggings and boots that go in their slot, give armour 1 without toughness or
     * knockback resistance and sit in the item tag of their set and in no other.
     */
    private static void wearableClothing(GameTestHelper helper) {
        for (int set = 0; set < SETS.size(); set++) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, CivMod.id(TAGS.get(set)));
            for (int i = 0; i < PIECES.size(); i++) {
                ItemStack stack = piece(SETS.get(set), PIECES.get(i));
                String name = SETS.get(set) + " cloth " + PIECES.get(i);
                helper.assertFalse(stack.isEmpty(), name + " exists");
                helper.assertTrue(stack.get(DataComponents.EQUIPPABLE) != null && stack.get(DataComponents.EQUIPPABLE).slot() == SLOTS.get(i),
                        name + " is worn in " + SLOTS.get(i));
                helper.assertTrue(attribute(stack, Attributes.ARMOR.value()) == 1.0, name + " gives armour 1: " + attribute(stack, Attributes.ARMOR.value()));
                helper.assertTrue(attribute(stack, Attributes.ARMOR_TOUGHNESS.value()) == 0.0, name + " has no toughness");
                helper.assertTrue(attribute(stack, Attributes.KNOCKBACK_RESISTANCE.value()) == 0.0, name + " has no knockback resistance");
                helper.assertTrue(stack.is(tag), name + " is in #" + tag.location());
                for (int other = 0; other < TAGS.size(); other++) {
                    if (other != set) {
                        helper.assertFalse(stack.is(TagKey.create(Registries.ITEM, CivMod.id(TAGS.get(other)))), name + " is not in #" + TAGS.get(other));
                    }
                }
            }
        }
        helper.succeed();
    }

    private static double attribute(ItemStack stack, net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return modifiers.modifiers().stream().filter(entry -> entry.attribute().value() == attribute)
                .mapToDouble(entry -> entry.modifier().amount()).sum();
    }

    /**
     * A cloth piece and a dye in a crafting grid give the piece dyed to the colour leather armour of the same dye gets;
     * undyed cloth has no colour yet (it renders white).
     */
    private static void dyesLikeLeather(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (String set : SETS) {
            for (String piece : PIECES) {
                ItemStack cloth = piece(set, piece);
                ItemStack leather = new ItemStack(BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("leather_" + piece)));
                ItemStack dye = new ItemStack(Items.DYE.red());
                DyedItemColor expected = dyed(helper, level, leather, dye);
                DyedItemColor actual = dyed(helper, level, cloth, dye);
                helper.assertTrue(expected.rgb() == actual.rgb(), set + " cloth " + piece + " dyes like leather: " + actual.rgb() + " vs " + expected.rgb());
                helper.assertTrue(cloth.get(DataComponents.DYED_COLOR) == null, "Undyed cloth has no colour");
            }
        }
        helper.succeed();
    }

    private static DyedItemColor dyed(GameTestHelper helper, ServerLevel level, ItemStack target, ItemStack dye) {
        CraftingInput input = CraftingInput.of(2, 1, List.of(target, dye));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(recipe.isPresent(), target + " and a dye match a recipe");
        ItemStack result = recipe.orElseThrow().value().assemble(input);
        DyedItemColor color = result.get(DataComponents.DYED_COLOR);
        helper.assertTrue(color != null && result.is(target.getItem()), "The dyed " + target + " carries a colour: " + result);
        return color;
    }
}
