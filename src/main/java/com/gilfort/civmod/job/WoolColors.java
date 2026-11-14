package com.gilfort.civmod.job;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The colour of wool and the choice of one colour out of many pieces of wool (vault {@code Jobs/Tailor.md}): the Tailor
 * dyes a piece of cloth with the colour of the wool it used.
 */
public final class WoolColors {

    private static final Map<Item, DyeColor> WOOL = wool();

    private WoolColors() {
    }

    /**
     * Picks the colour that appears most often; on a tie the colour with the lower {@link DyeColor} id wins (white,
     * orange, ... black), so the choice does not depend on the order of the wool.
     *
     * @param colours the colours of the wool
     * @return the chosen colour, empty without any colour
     */
    public static Optional<DyeColor> dominant(List<DyeColor> colours) {
        Map<DyeColor, Integer> counts = new EnumMap<>(DyeColor.class);
        for (DyeColor colour : colours) {
            counts.merge(colour, 1, Integer::sum);
        }
        DyeColor best = null;
        int bestCount = 0;
        // An EnumMap iterates by dye id, so strictly greater keeps the lower id on a tie.
        for (Map.Entry<DyeColor, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * @param stack any stack
     * @return the colour of the wool block it is, empty for everything else (carpets and modded wool included)
     */
    public static Optional<DyeColor> of(ItemStack stack) {
        return Optional.ofNullable(WOOL.get(stack.getItem()));
    }

    private static Map<Item, DyeColor> wool() {
        Map<Item, DyeColor> wool = new HashMap<>();
        for (DyeColor colour : DyeColor.values()) {
            wool.put(Items.WOOL.pick(colour), colour);
        }
        return Map.copyOf(wool);
    }
}
