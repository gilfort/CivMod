package com.gilfort.civmod.gametest;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ItemKey;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the item identity (#267): the relevant-component key, pure functions without a world.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ItemKeyGameTests {

    private ItemKeyGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "item_key_identity", ItemKeyGameTests::identity);
            TestFunctions.register(helper, "item_key_codec", ItemKeyGameTests::codec);
            TestFunctions.register(helper, "item_key_prototype", ItemKeyGameTests::prototype);
        });
    }

    /** A potion stack of the one item {@code minecraft:potion}. */
    static ItemStack potion(Holder<Potion> type) {
        return PotionContents.createItemStack(Items.POTION, type);
    }

    /** Components a stack only has as the item's prototype defaults are no part of its key. */
    private static void prototype(GameTestHelper helper) {
        helper.assertTrue(ItemKey.of(new ItemStack(Items.POTION)).equals(ItemKey.of(Items.POTION)), "A bare potion stack is the plain potion key");
        helper.assertTrue(ItemKey.of(new ItemStack(Items.POTION)).components().isEmpty(), "No components from prototype defaults");
        ItemStack reset = new ItemStack(Items.POTION);
        reset.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        helper.assertTrue(ItemKey.of(reset).equals(ItemKey.of(Items.POTION)), "Potion contents equal to the prototype's are dropped");
        helper.assertTrue(!ItemKey.of(potion(Potions.WATER)).equals(ItemKey.of(Items.POTION)), "A water bottle still differs from the bare potion");
        helper.succeed();
    }

    /** Potions of different types differ, a custom name does not matter, a plain item has no components. */
    private static void identity(GameTestHelper helper) {
        ItemStack healing = potion(Potions.HEALING);
        ItemStack water = potion(Potions.WATER);
        helper.assertTrue(!ItemKey.of(healing).equals(ItemKey.of(water)), "Healing and water bottle are different keys");
        helper.assertTrue(ItemKey.of(healing).equals(ItemKey.of(potion(Potions.HEALING))), "Two Potions of Healing are one key");
        ItemStack renamed = healing.copy();
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Elixir"));
        helper.assertTrue(ItemKey.of(renamed).equals(ItemKey.of(healing)), "A custom name is ignored");
        helper.assertTrue(ItemKey.of(healing).matches(renamed), "matches() ignores the custom name");
        helper.assertTrue(!ItemKey.of(healing).matches(water), "A water bottle is not a Potion of Healing");
        helper.assertTrue(ItemKey.of(new ItemStack(Items.STICK)).equals(ItemKey.of(Items.STICK)), "A plain stack is its item key");
        helper.assertTrue(ItemKey.of(ItemStack.EMPTY).isEmpty() && ItemKey.of(Items.AIR).equals(ItemKey.EMPTY), "Empty is EMPTY");
        ItemStack back = ItemKey.of(healing).toStack(3);
        helper.assertTrue(back.getCount() == 3 && ItemKey.of(back).equals(ItemKey.of(healing)), "toStack keeps the relevant components");
        helper.succeed();
    }

    /** The save codec round-trips a potion key and a plain item key; the plain item id of older saves is not read. */
    private static void codec(GameTestHelper helper) {
        ItemKey healing = ItemKey.of(potion(Potions.HEALING));
        JsonElement json = ItemKey.CODEC.encodeStart(JsonOps.INSTANCE, healing).getOrThrow();
        helper.assertTrue(ItemKey.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().equals(healing), "Round trip: " + json);
        ItemKey stick = ItemKey.of(Items.STICK);
        JsonElement plain = ItemKey.CODEC.encodeStart(JsonOps.INSTANCE, stick).getOrThrow();
        helper.assertTrue(ItemKey.CODEC.parse(JsonOps.INSTANCE, plain).getOrThrow().equals(stick), "Plain item round trip: " + plain);
        helper.assertTrue(ItemKey.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("minecraft:stick")).result().isEmpty(),
                "A plain item id is no longer read");
        helper.succeed();
    }
}
