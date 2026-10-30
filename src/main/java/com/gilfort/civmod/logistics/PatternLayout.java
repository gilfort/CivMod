package com.gilfort.civmod.logistics;

import java.util.Map;

import com.gilfort.civmod.api.ProducingProfile;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * How the pattern editor lays out a pattern of one recipe type (vault {@code Systems/Producing Jobs.md}): crafting a
 * 3 × 3 grid, stonecutting and the furnaces one input with the station icon left of it, smithing three slots
 * (template, base, addition), innate recipes a row of inputs. A modded type gets one input if each of its recipes has
 * one ingredient, else the 3 × 3 grid. The saved grid keeps 9 cells; short layouts use the first 1 or 3.
 *
 * @param type    the recipe type
 * @param kind    the layout
 * @param station the station's item: its icon on the layout button and its name in the library tooltip
 *                ({@link Items#AIR} if the type has none)
 */
public record PatternLayout(RecipeType<?> type, Kind kind, Item station) {

    /** The layouts. */
    public enum Kind {
        /** The 3 × 3 crafting grid. */
        GRID(Pattern.GRID),
        /** One input, the station icon left of it. */
        SINGLE(1),
        /** Template, base, addition. */
        SMITHING(3),
        /** The inputs of an innate recipe in a row, without shape (read only). */
        ROW(Pattern.GRID);

        /** Network codec. */
        public static final StreamCodec<ByteBuf, Kind> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], Kind::ordinal);

        private final int cells;

        Kind(int cells) {
            this.cells = cells;
        }

        /**
         * @return how many of the saved grid's cells the layout uses (the first ones)
         */
        public int cells() {
            return cells;
        }
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, PatternLayout> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.RECIPE_TYPE), PatternLayout::type,
            Kind.STREAM_CODEC, PatternLayout::kind,
            ByteBufCodecs.registry(Registries.ITEM), PatternLayout::station,
            PatternLayout::new);

    /** The vanilla types with a fixed layout and station. */
    private static final Map<RecipeType<?>, PatternLayout> VANILLA = Map.of(
            RecipeType.CRAFTING, new PatternLayout(RecipeType.CRAFTING, Kind.GRID, Items.CRAFTING_TABLE),
            RecipeType.STONECUTTING, new PatternLayout(RecipeType.STONECUTTING, Kind.SINGLE, Items.STONECUTTER),
            RecipeType.SMELTING, new PatternLayout(RecipeType.SMELTING, Kind.SINGLE, Items.FURNACE),
            RecipeType.SMOKING, new PatternLayout(RecipeType.SMOKING, Kind.SINGLE, Items.SMOKER),
            RecipeType.BLASTING, new PatternLayout(RecipeType.BLASTING, Kind.SINGLE, Items.BLAST_FURNACE),
            RecipeType.SMITHING, new PatternLayout(RecipeType.SMITHING, Kind.SMITHING, Items.SMITHING_TABLE));

    /**
     * @param level   the level
     * @param profile the Producing profile of the Job
     * @param type    a recipe type
     * @return its layout; the station of a modded type is the first item of the profile's station tag for it
     */
    public static PatternLayout of(ServerLevel level, ProducingProfile profile, RecipeType<?> type) {
        PatternLayout vanilla = VANILLA.get(type);
        if (vanilla != null) {
            return vanilla;
        }
        Item station = profile.recipeTypes().contains(type) ? firstItem(profile.station(type)) : Items.AIR;
        return new PatternLayout(type, PatternRecipes.singleInput(level, type) ? Kind.SINGLE : Kind.GRID, station);
    }

    /**
     * @param type    the innate recipe type ({@code civmod:job_crafting})
     * @param station the station tag the recipe is worked at
     * @return the row layout of an innate recipe
     */
    public static PatternLayout innate(RecipeType<?> type, TagKey<Block> station) {
        return new PatternLayout(type, Kind.ROW, firstItem(station));
    }

    /** The first block of a tag that has an item, {@link Items#AIR} if none. */
    private static Item firstItem(TagKey<Block> tag) {
        return BuiltInRegistries.BLOCK.get(tag).stream().flatMap(set -> set.stream()).map(Holder::value).map(Block::asItem)
                .filter(item -> item != Items.AIR).findFirst().orElse(Items.AIR);
    }
}
