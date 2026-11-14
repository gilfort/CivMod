package com.gilfort.civmod.job;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The colour of wool and the choice of one colour out of many pieces of wool (vault {@code Jobs/Tailor.md}): the Tailor
 * dyes a piece of cloth with the colour of the wool it used.
 */
public final class WoolColors {

    private static final Map<Item, DyeColor> WOOL = new HashMap<>();

    private WoolColors() {
    }

    /**
     * Picks the colour that appears most often; on a tie the colour that comes first in the list wins.
     *
     * @param colours the colours of the wool in the order of the recipe slots
     * @return the chosen colour, empty without any colour
     */
    public static Optional<DyeColor> dominant(List<DyeColor> colours) {
        Map<DyeColor, Integer> counts = new LinkedHashMap<>();
        for (DyeColor colour : colours) {
            counts.merge(colour, 1, Integer::sum);
        }
        DyeColor best = null;
        int bestCount = 0;
        for (Map.Entry<DyeColor, Integer> entry : counts.entrySet()) {
            // Strictly greater, so the colour listed first keeps a tie.
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
        return Optional.ofNullable(wool().get(stack.getItem()));
    }

    private static synchronized Map<Item, DyeColor> wool() {
        if (WOOL.isEmpty()) {
            for (DyeColor colour : DyeColor.values()) {
                BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(colour.getName() + "_wool")).ifPresent(item -> WOOL.put(item, colour));
            }
        }
        return WOOL;
    }
}
