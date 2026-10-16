package com.gilfort.civmod.mission;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.job.SkillRules;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * A datapack-defined task a Mission Job settler leaves the settlement for (vault {@code Systems/Mission Jobs.md}),
 * loaded from {@code data/<namespace>/civmod/mission/<name>.json} into the {@code civmod:mission} registry
 * ({@link Missions}).
 *
 * @param title            the title shown on the board
 * @param description      the description shown in the tooltip
 * @param icon             the icon shown on the board (default: the icon of the first allowed Mission Job)
 * @param lootTables       the loot tables a successful settler rolls (any namespace)
 * @param days             the duration in game days
 * @param recommendedLevel the recommended skill level
 * @param rolls            the base number of loot rolls
 * @param jobs             the Mission Jobs that may take the mission
 * @param weight           the draw weight in the daily offer
 * @param maxPerOffer      how often the mission may appear in one offer
 */
public record Mission(Component title, Component description, Optional<Holder<Item>> icon, List<ResourceKey<LootTable>> lootTables, int days,
        int recommendedLevel, int rolls, List<Job> jobs, int weight, int maxPerOffer) {

    /** Data and network codec. */
    public static final Codec<Mission> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ComponentSerialization.CODEC.fieldOf("title").forGetter(Mission::title),
            ComponentSerialization.CODEC.optionalFieldOf("description", Component.empty()).forGetter(Mission::description),
            Item.CODEC.optionalFieldOf("icon").forGetter(Mission::icon),
            ResourceKey.codec(Registries.LOOT_TABLE).listOf(1, Integer.MAX_VALUE).fieldOf("loot_tables").forGetter(Mission::lootTables),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("days").forGetter(Mission::days),
            Codec.intRange(1, SkillRules.MAX_LEVEL).fieldOf("recommended_level").forGetter(Mission::recommendedLevel),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("rolls").forGetter(Mission::rolls),
            Job.CODEC.listOf(1, Integer.MAX_VALUE).fieldOf("jobs").forGetter(Mission::jobs),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("weight", 1).forGetter(Mission::weight),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("max_per_offer", 1).forGetter(Mission::maxPerOffer)
    ).apply(instance, Mission::new));

    /**
     * @return the icon shown on the board
     */
    public ItemStack iconStack() {
        return icon.map(ItemStack::new).orElseGet(() -> jobs.get(0).icon());
    }
}
