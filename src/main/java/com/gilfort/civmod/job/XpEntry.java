package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.XpGain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;

/**
 * One entry of a Job's {@code xp} list in {@code civmod_jobs/<job>.json} (vault {@code Systems/Settler XP.md}).
 *
 * @param items the item ids and {@code #item tags} matched against what the action produced; empty: every action counts
 * @param gain  the XP of a matching action
 */
public record XpEntry(Optional<List<ExtraCodecs.TagOrElementLocation>> items, XpGain gain) {

    /** Data codec. */
    static final Codec<XpEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BiomeBonuses.IDS_OR_TAGS.optionalFieldOf("items").forGetter(XpEntry::items),
            XpGain.MAP_CODEC.forGetter(XpEntry::gain)
    ).apply(instance, XpEntry::new));

    /**
     * @param produced what the action produced
     * @return whether the entry counts for the action (once, however many stacks match)
     */
    boolean matches(List<ItemStack> produced) {
        return items.isEmpty() || produced.stream().anyMatch(stack -> BiomeBonuses.matches(items.get(), stack.typeHolder(), Registries.ITEM));
    }
}
