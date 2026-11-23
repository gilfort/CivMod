package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.clothing.ClothingRules;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModClothing;
import com.gilfort.civmod.registry.ModClothing.ClothSet;
import com.gilfort.civmod.registry.ModEntityTypes;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the clothes settlers show (vault {@code Systems/Needs.md}, decisions of 2026-11-21 and 2026-11-23). The look itself is
 * not testable here; the seam is what reaches the client: the equipment slots of the settler entity, which vanilla syncs to every
 * player that tracks it and the armour layer reads. They hold what the settler wears, for Human settlers only.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClothingRenderGameTests {

    private static final int DYE_RGB = 0x3366CC;
    /** The durability of the worn test boots: a day of wear takes a visible share of it. */
    private static final int WEAR_MAX_DAMAGE = 100;
    /** Any day: wear counts the days between two checks. */
    private static final long WEAR_FIRST_DAY = 10L;
    private static final Identifier OTHER_RACE = CivMod.id("other_race_for_tests");

    private ClothingRenderGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "clothing_render_shows_worn_pieces", ClothingRenderGameTests::showsWornPieces);
            register(helper, "clothing_render_shows_dye", ClothingRenderGameTests::showsDye);
            register(helper, "clothing_render_follows_changes", ClothingRenderGameTests::followsChanges);
            register(helper, "clothing_render_survives_save", ClothingRenderGameTests::survivesSave);
            register(helper, "clothing_render_only_human", ClothingRenderGameTests::onlyHuman);
            register(helper, "clothing_render_drops_nothing", ClothingRenderGameTests::dropsNothing);
            register(helper, "clothing_render_follows_wear", ClothingRenderGameTests::followsWear);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Settler settler(GameTestHelper helper) {
        return ModEntityTypes.SETTLER.get().create(helper.getLevel(), EntitySpawnReason.EVENT);
    }

    private static ItemStack piece(ClothSet set, ArmorType type) {
        return new ItemStack(ModClothing.piece(set, type).get());
    }

    private static ItemStack shown(Settler settler, EquipmentSlot slot) {
        return settler.getItemBySlot(slot);
    }

    /** What the settler wears in its inventory shows on its body: put on by hand, any slot, any set. */
    private static void showsWornPieces(GameTestHelper helper) {
        Settler settler = settler(helper);
        ArmorType[] types = { ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS };
        for (int i = 0; i < types.length; i++) {
            helper.assertTrue(shown(settler, ClothingRules.SLOTS.get(i)).isEmpty(), "Nothing is shown before it is worn: " + i);
            settler.getClothing().equip(piece(i % 2 == 0 ? ClothSet.SIMPLE : ClothSet.HIGH, types[i]));
        }
        for (int i = 0; i < types.length; i++) {
            ItemStack expected = piece(i % 2 == 0 ? ClothSet.SIMPLE : ClothSet.HIGH, types[i]);
            helper.assertTrue(shown(settler, ClothingRules.SLOTS.get(i)).getItem() == expected.getItem(), "Shown on the body: " + i);
        }
        settler.discard();
        helper.succeed();
    }

    /** The colour of the Tailor's dye is part of the shown piece. */
    private static void showsDye(GameTestHelper helper) {
        Settler settler = settler(helper);
        ItemStack dyed = piece(ClothSet.MEDIUM, ArmorType.CHESTPLATE);
        dyed.set(DataComponents.DYED_COLOR, new DyedItemColor(DYE_RGB));
        settler.getClothing().equip(dyed);
        DyedItemColor colour = shown(settler, EquipmentSlot.CHEST).get(DataComponents.DYED_COLOR);
        helper.assertTrue(colour != null && colour.rgb() == DYE_RGB, "The dye shows: " + colour);
        settler.discard();
        helper.succeed();
    }

    /** A slot set directly in the inventory, a replaced piece and an emptied slot all show at once. */
    private static void followsChanges(GameTestHelper helper) {
        Settler settler = settler(helper);
        settler.getInventory().setItem(Settler.ARMOR_START + 3, piece(ClothSet.SIMPLE, ArmorType.BOOTS));
        helper.assertTrue(shown(settler, EquipmentSlot.FEET).getItem() == piece(ClothSet.SIMPLE, ArmorType.BOOTS).getItem(), "Set in the inventory");
        settler.getClothing().equip(piece(ClothSet.HIGH, ArmorType.BOOTS));
        helper.assertTrue(shown(settler, EquipmentSlot.FEET).getItem() == piece(ClothSet.HIGH, ArmorType.BOOTS).getItem(), "Replaced");
        settler.getInventory().setItem(Settler.ARMOR_START + 3, ItemStack.EMPTY);
        helper.assertTrue(shown(settler, EquipmentSlot.FEET).isEmpty(), "Taken off");
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.APPLE));
        helper.assertTrue(ClothingRules.SLOTS.stream().allMatch(slot -> shown(settler, slot).isEmpty()), "Other slots change nothing");
        settler.discard();
        helper.succeed();
    }

    /** A loaded settler shows what it wears, also from a save that holds the pieces only in the inventory. */
    private static void survivesSave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = settler(helper);
        settler.getClothing().equip(piece(ClothSet.SIMPLE, ArmorType.HELMET));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        Settler loaded = settler(helper);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        helper.assertTrue(shown(loaded, EquipmentSlot.HEAD).getItem() == piece(ClothSet.SIMPLE, ArmorType.HELMET).getItem(), "Shown after loading");

        CompoundTag old = tag.copy();
        old.remove("equipment");
        Settler fromOldSave = settler(helper);
        fromOldSave.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), old));
        helper.assertTrue(shown(fromOldSave, EquipmentSlot.HEAD).getItem() == piece(ClothSet.SIMPLE, ArmorType.HELMET).getItem(),
                "Shown after loading a save without the displayed copies");
        settler.discard();
        loaded.discard();
        fromOldSave.discard();
        helper.succeed();
    }

    /** Only Human settlers show their clothes; another race keeps wearing them, unseen, until it gets its own look. */
    private static void onlyHuman(GameTestHelper helper) {
        Settler settler = settler(helper);
        settler.setRace(OTHER_RACE);
        settler.getClothing().equip(piece(ClothSet.SIMPLE, ArmorType.HELMET));
        helper.assertTrue(!settler.getClothing().worn(EquipmentSlot.HEAD).isEmpty(), "It still wears the piece");
        helper.assertTrue(shown(settler, EquipmentSlot.HEAD).isEmpty(), "Another race shows nothing");
        settler.setRace(Races.HUMAN);
        helper.assertTrue(!shown(settler, EquipmentSlot.HEAD).isEmpty(), "Human shows it");
        settler.setRace(OTHER_RACE);
        helper.assertTrue(shown(settler, EquipmentSlot.HEAD).isEmpty(), "A change of race hides it again");
        settler.discard();
        helper.succeed();
    }

    /** The daily wear changes the worn piece in place: the shown copy follows, so no later change of the inventory re-copies it. */
    private static void followsWear(GameTestHelper helper) {
        Settler settler = settler(helper);
        ItemStack boots = piece(ClothSet.SIMPLE, ArmorType.BOOTS);
        boots.set(DataComponents.MAX_DAMAGE, WEAR_MAX_DAMAGE);
        settler.getClothing().equip(boots);
        settler.getClothing().step(helper.getLevel(), WEAR_FIRST_DAY, 0L, 1);
        settler.getClothing().step(helper.getLevel(), WEAR_FIRST_DAY + 1, 0L, 1);
        int worn = settler.getClothing().worn(EquipmentSlot.FEET).getDamageValue();
        helper.assertTrue(worn > 0, "A day of wear damaged the boots: " + worn);
        helper.assertTrue(shown(settler, EquipmentSlot.FEET).getDamageValue() == worn,
                "The shown boots wear with them: shown " + shown(settler, EquipmentSlot.FEET).getDamageValue() + ", worn " + worn);
        settler.discard();
        helper.succeed();
    }

    /** The shown pieces are copies for the look: they never drop, so a death does not duplicate clothes. */
    private static void dropsNothing(GameTestHelper helper) {
        Settler settler = settler(helper);
        settler.getClothing().equip(piece(ClothSet.SIMPLE, ArmorType.HELMET));
        for (EquipmentSlot slot : ClothingRules.SLOTS) {
            helper.assertTrue(settler.getDropChances().byEquipment(slot) == 0.0F, "No drop chance for " + slot);
        }
        settler.discard();
        helper.succeed();
    }
}
