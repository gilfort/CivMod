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

/**
 * A Craftsperson pattern (vault {@code Jobs/Craftsperson.md}), AE2 style: a 3×3 grid and an output, kept in one of the
 * 54 slots of the Craftsperson JobBlock's library. A pattern made from the output alone follows a crafting recipe
 * ({@link #recipe()}; tag ingredients stay flexible, the grid shows one item per ingredient); a pattern made from inputs
 * ({@link #exact()}) uses exactly the items in its grid. Only checked patterns are crafted.
 *
 * @param slot    the library slot (0–53)
 * @param output  the crafted item, {@link Items#AIR} while the inputs match no recipe
 * @param count   how many one craft makes
 * @param grid    the 3×3 grid, row by row, {@link Items#AIR} for empty cells
 * @param recipe  the crafting recipe the pattern follows (or that its exact inputs match)
 * @param exact   whether exactly the grid items are used (substitutions off)
 * @param checked whether the Craftsperson crafts it
 */
public record Pattern(int slot, Item output, int count, List<Item> grid, Optional<ResourceKey<Recipe<?>>> recipe, boolean exact,
        boolean checked) {

    /** Library slots per Craftsperson JobBlock (a double chest), the same at every level. */
    public static final int SLOTS = 54;
    /** Cells of the grid. */
    public static final int GRID = 9;

    /** Save codec. */
    public static final Codec<Pattern> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, SLOTS - 1).fieldOf("slot").forGetter(Pattern::slot),
            BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("output", Items.AIR).forGetter(Pattern::output),
            Codec.INT.optionalFieldOf("count", 1).forGetter(Pattern::count),
            BuiltInRegistries.ITEM.byNameCodec().listOf().optionalFieldOf("grid", List.of()).forGetter(Pattern::grid),
            ResourceKey.codec(Registries.RECIPE).optionalFieldOf("recipe").forGetter(Pattern::recipe),
            Codec.BOOL.optionalFieldOf("exact", false).forGetter(Pattern::exact),
            Codec.BOOL.optionalFieldOf("checked", false).forGetter(Pattern::checked)
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
            Pattern::new);

    /**
     * @param slot    the library slot
     * @param output  the crafted item
     * @param count   how many one craft makes
     * @param grid    the grid (padded / cut to 9 cells)
     * @param recipe  the recipe
     * @param exact   whether exactly the grid items are used
     * @param checked whether it is crafted
     */
    public Pattern {
        List<Item> cells = new ArrayList<>(grid.subList(0, Math.min(GRID, grid.size())));
        while (cells.size() < GRID) {
            cells.add(Items.AIR);
        }
        grid = Collections.unmodifiableList(cells);
        count = Math.max(1, count);
    }

    /**
     * @return whether the pattern makes something (an exact grid may match no recipe)
     */
    public boolean valid() {
        return output != Items.AIR;
    }

    /**
     * @param newChecked whether it is crafted
     * @return a copy with another checkmark
     */
    public Pattern withChecked(boolean newChecked) {
        return new Pattern(slot, output, count, grid, recipe, exact, newChecked);
    }
}
