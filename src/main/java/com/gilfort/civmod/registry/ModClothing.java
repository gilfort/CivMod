package com.gilfort.civmod.registry;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * The cloth sets (vault {@code Jobs/Tailor.md}): Simple, Medium and High Cloth, four wearable pieces each. They are
 * clothing for the settlers' needs (module 6), not armour: armour 1 per piece, no toughness, no knockback resistance.
 * Dyeable like leather armour; undyed they are white. Each set has an item tag that addons and packs can extend.
 */
public final class ModClothing {

    /** The durability factor of cloth, as thin as leather. */
    private static final int DURABILITY_FACTOR = 5;
    /** The armour value of every piece. */
    private static final int PIECE_DEFENSE = 1;
    /** The enchantability of cloth, like leather. */
    private static final int ENCHANTMENT_VALUE = 15;
    /** The pieces of a set. */
    private static final List<ArmorType> PIECES = List.of(ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS);

    /** The three cloth sets, from the plainest to the finest. */
    public enum ClothSet {
        /** White seams; the cheapest set. */
        SIMPLE("simple", "simple_clothing"),
        /** Blue and light blue trim at hem and sleeves. */
        MEDIUM("medium", "better_clothing"),
        /** A diamond-blue trim. */
        HIGH("high", "highest_clothing");

        private final String id;
        private final TagKey<Item> tag;

        ClothSet(String id, String tag) {
            this.id = id;
            this.tag = TagKey.create(Registries.ITEM, CivMod.id(tag));
        }

        /**
         * @return the id the item ids start with ({@code simple}, {@code medium}, {@code high})
         */
        public String id() {
            return id;
        }

        /**
         * @return the item tag that lists the pieces of the set ({@code civmod:simple_clothing},
         *         {@code civmod:better_clothing}, {@code civmod:highest_clothing})
         */
        public TagKey<Item> tag() {
            return tag;
        }
    }

    private static final Map<ClothSet, Map<ArmorType, DeferredItem<Item>>> PIECE_ITEMS = new EnumMap<>(ClothSet.class);

    static {
        for (ClothSet set : ClothSet.values()) {
            ArmorMaterial material = material(set);
            Map<ArmorType, DeferredItem<Item>> pieces = new EnumMap<>(ArmorType.class);
            for (ArmorType type : PIECES) {
                pieces.put(type, ModItems.ITEMS.registerItem(set.id() + "_cloth_" + type.getName(), Item::new,
                        properties -> properties.humanoidArmor(material, type)));
            }
            PIECE_ITEMS.put(set, pieces);
        }
    }

    private ModClothing() {
    }

    /**
     * @param set  a cloth set
     * @param type the piece
     * @return the item of that piece of the set
     */
    public static DeferredItem<Item> piece(ClothSet set, ArmorType type) {
        return PIECE_ITEMS.get(set).get(type);
    }

    /** Loads the class so its items are registered; called by {@link ModItems#register}. */
    static void init() {
        // The static initializer registers the items.
    }

    private static ArmorMaterial material(ClothSet set) {
        Map<ArmorType, Integer> defense = new EnumMap<>(ArmorType.class);
        for (ArmorType type : ArmorType.values()) {
            defense.put(type, PIECE_DEFENSE);
        }
        ResourceKey<EquipmentAsset> asset = ResourceKey.create(EquipmentAssets.ROOT_ID, CivMod.id(set.id() + "_cloth"));
        return new ArmorMaterial(DURABILITY_FACTOR, defense, ENCHANTMENT_VALUE, SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, ItemTags.WOOL, asset);
    }
}
