package com.gilfort.civmod.logistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * A Craftsperson pattern (vault {@code Jobs/Craftsperson.md}), AE2 style: a 3×3 grid and an output, kept in one of the
 * 54 slots of the Craftsperson JobBlock's library. A pattern made from the output alone follows a crafting recipe
 * ({@link #recipe()}; tag ingredients stay flexible, the grid shows one item per ingredient); a pattern made from inputs
 * ({@link #exact()}) uses exactly the items in its grid. Only checked patterns are crafted. An innate pattern
 * ({@link #innate()}) follows an innate recipe of its Job ({@code civmod:job_crafting}): it sits locked in the first
 * library slots, only its checkmark and "Always produce" change. The pattern editor lays the grid out by the recipe type of its
 * {@link #layout()} ({@link PatternLayout}); short layouts use the first 1 or 3 cells. A checked pattern set to
 * {@link #always()} is worked in the Job's idle time as well, {@link #atOnce()} crafts per round (vault
 * {@code Systems/Producing Jobs.md}).
 *
 * @param slot    the library slot (0–53)
 * @param output  the crafted item, {@link Items#AIR} while the inputs match no recipe
 * @param count   how many one craft makes
 * @param grid    the 3×3 grid, row by row, {@link Items#AIR} for empty cells
 * @param recipe  the crafting recipe the pattern follows (or that its exact inputs match)
 * @param exact   whether exactly the grid items are used (substitutions off)
 * @param checked whether the Craftsperson crafts it
 * @param innate  whether it follows an innate recipe of the Job (locked)
 * @param layout  the recipe type whose layout the editor shows; empty in innate patterns and in patterns saved before
 *                layouts existed (they show their recipe's type, else the Job's first type)
 * @param always  whether it is worked without a request ("Always produce")
 * @param atOnce  crafts per round of "Always produce" ("Produce at once")
 */
public record Pattern(int slot, Item output, int count, List<Item> grid, Optional<ResourceKey<Recipe<?>>> recipe, boolean exact,
        boolean checked, boolean innate, Optional<RecipeType<?>> layout, boolean always, int atOnce) {

    /** Library slots per Craftsperson JobBlock (a double chest), the same at every level. */
    public static final int SLOTS = 54;
    /** Cells of the grid. */
    public static final int GRID = 9;
    /** "Produce at once" of a pattern that never set it. */
    public static final int DEFAULT_AT_ONCE = 1;
    /** The most "Produce at once" takes. */
    public static final int MAX_AT_ONCE = 999;

    /** Save codec. */
    public static final Codec<Pattern> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, SLOTS - 1).fieldOf("slot").forGetter(Pattern::slot),
            BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("output", Items.AIR).forGetter(Pattern::output),
            Codec.INT.optionalFieldOf("count", 1).forGetter(Pattern::count),
            BuiltInRegistries.ITEM.byNameCodec().listOf().optionalFieldOf("grid", List.of()).forGetter(Pattern::grid),
            ResourceKey.codec(Registries.RECIPE).optionalFieldOf("recipe").forGetter(Pattern::recipe),
            Codec.BOOL.optionalFieldOf("exact", false).forGetter(Pattern::exact),
            Codec.BOOL.optionalFieldOf("checked", false).forGetter(Pattern::checked),
            Codec.BOOL.optionalFieldOf("innate", false).forGetter(Pattern::innate),
            BuiltInRegistries.RECIPE_TYPE.byNameCodec().optionalFieldOf("layout").forGetter(Pattern::layout),
            Codec.BOOL.optionalFieldOf("always", false).forGetter(Pattern::always),
            Codec.INT.optionalFieldOf("at_once", DEFAULT_AT_ONCE).forGetter(Pattern::atOnce)
    ).apply(instance, Pattern::new));

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, Pattern> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Pattern::slot,
            ByteBufCodecs.registry(Registries.ITEM), Pattern::output,
            ByteBufCodecs.VAR_INT, Pattern::count,
            ByteBufCodecs.registry(Registries.ITEM).apply(ByteBufCodecs.list(GRID)), Pattern::grid,
            ByteBufCodecs.optional(ResourceKey.streamCodec(Registries.RECIPE)), Pattern::recipe,
            ByteBufCodecs.BOOL, Pattern::exact,
            ByteBufCodecs.BOOL, Pattern::checked,
            ByteBufCodecs.BOOL, Pattern::innate,
            ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.RECIPE_TYPE)), Pattern::layout,
            ByteBufCodecs.BOOL, Pattern::always,
            ByteBufCodecs.VAR_INT, Pattern::atOnce,
            Pattern::new);

    /**
     * A pattern the player made (not innate).
     *
     * @param slot    the library slot
     * @param output  the crafted item
     * @param count   how many one craft makes
     * @param grid    the grid (padded / cut to 9 cells)
     * @param recipe  the recipe
     * @param exact   whether exactly the grid items are used
     * @param checked whether it is crafted
     */
    public Pattern(int slot, Item output, int count, List<Item> grid, Optional<ResourceKey<Recipe<?>>> recipe, boolean exact, boolean checked) {
        this(slot, output, count, grid, recipe, exact, checked, false, Optional.empty());
    }

    /**
     * A pattern without a layout of its own (an innate one, or one saved before layouts existed).
     *
     * @param slot    the library slot
     * @param output  the crafted item
     * @param count   how many one craft makes
     * @param grid    the grid (padded / cut to 9 cells)
     * @param recipe  the recipe
     * @param exact   whether exactly the grid items are used
     * @param checked whether it is crafted
     * @param innate  whether it follows an innate recipe of the Job
     */
    public Pattern(int slot, Item output, int count, List<Item> grid, Optional<ResourceKey<Recipe<?>>> recipe, boolean exact, boolean checked,
            boolean innate) {
        this(slot, output, count, grid, recipe, exact, checked, innate, Optional.empty());
    }

    /**
     * A pattern worked only on request.
     *
     * @param slot    the library slot
     * @param output  the crafted item
     * @param count   how many one craft makes
     * @param grid    the grid (padded / cut to 9 cells)
     * @param recipe  the recipe
     * @param exact   whether exactly the grid items are used
     * @param checked whether it is crafted
     * @param innate  whether it follows an innate recipe of the Job
     * @param layout  the recipe type whose layout the editor shows
     */
    public Pattern(int slot, Item output, int count, List<Item> grid, Optional<ResourceKey<Recipe<?>>> recipe, boolean exact, boolean checked,
            boolean innate, Optional<RecipeType<?>> layout) {
        this(slot, output, count, grid, recipe, exact, checked, innate, layout, false, DEFAULT_AT_ONCE);
    }

    /**
     * @param slot    the library slot
     * @param output  the crafted item
     * @param count   how many one craft makes
     * @param grid    the grid (padded / cut to 9 cells)
     * @param recipe  the recipe
     * @param exact   whether exactly the grid items are used
     * @param checked whether it is crafted
     * @param innate  whether it follows an innate recipe of the Job
     * @param layout  the recipe type whose layout the editor shows
     * @param always  whether it is worked without a request
     * @param atOnce  crafts per round of "Always produce"
     */
    public Pattern {
        List<Item> cells = new ArrayList<>(grid.subList(0, Math.min(GRID, grid.size())));
        while (cells.size() < GRID) {
            cells.add(Items.AIR);
        }
        grid = Collections.unmodifiableList(cells);
        count = Math.max(1, count);
        atOnce = Math.clamp(atOnce, 1, MAX_AT_ONCE);
    }

    /**
     * @return whether the pattern makes something (an exact grid may match no recipe)
     */
    public boolean valid() {
        return output != Items.AIR;
    }

    /**
     * @param newChecked whether it is crafted
     * @return a copy with another checkmark; without one it is no longer "Always produce" either
     */
    public Pattern withChecked(boolean newChecked) {
        return new Pattern(slot, output, count, grid, recipe, exact, newChecked, innate, layout, newChecked && always, atOnce);
    }

    /**
     * @param newSlot the library slot
     * @return a copy in another slot
     */
    public Pattern withSlot(int newSlot) {
        return new Pattern(newSlot, output, count, grid, recipe, exact, checked, innate, layout, always, atOnce);
    }

    /**
     * @param newAlways whether it is worked without a request
     * @param newAtOnce crafts per round of "Always produce"
     * @return a copy with other "Always produce" settings
     */
    public Pattern withAlways(boolean newAlways, int newAtOnce) {
        return new Pattern(slot, output, count, grid, recipe, exact, checked, innate, layout, newAlways, newAtOnce);
    }
}
