package com.gilfort.civmod.mission;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.SkillRules;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * A datapack-defined task a Mission Job settler leaves the settlement for (vault {@code Systems/Mission Jobs.md}),
 * loaded from {@code data/<namespace>/civmod/mission/<name>.json} into the {@code civmod:mission} registry
 * ({@link Missions}). Its result is either loot ({@code loot_tables} and {@code rolls}) or vanilla Villagers
 * ({@code villagers}), never both.
 *
 * @param title            the title shown on the board
 * @param description      the description shown in the tooltip
 * @param icon             the icon shown on the board (default: the icon of the first allowed Mission Job)
 * @param lootTables       the loot tables a successful settler rolls (any namespace), empty for a Villager mission
 * @param villagers        the Villagers a successful settler brings, empty for a loot mission
 * @param days             the duration in game days
 * @param recommendedLevel the recommended skill level
 * @param rolls            the base number of loot rolls
 * @param jobs             the Mission Jobs that may take the mission
 * @param weight           the draw weight in the daily offer
 * @param maxPerOffer      how often the mission may appear in one offer
 */
public record Mission(Component title, Component description, Optional<Holder<Item>> icon, List<ResourceKey<LootTable>> lootTables,
        Optional<Villagers> villagers, int days, int recommendedLevel, int rolls, List<Job> jobs, int weight, int maxPerOffer) {

    private static final Codec<Mission> FIELDS = RecordCodecBuilder.create(instance -> instance.group(
            ComponentSerialization.CODEC.fieldOf("title").forGetter(Mission::title),
            ComponentSerialization.CODEC.optionalFieldOf("description", Component.empty()).forGetter(Mission::description),
            Item.CODEC.optionalFieldOf("icon").forGetter(Mission::icon),
            ResourceKey.codec(Registries.LOOT_TABLE).listOf().optionalFieldOf("loot_tables", List.of()).forGetter(Mission::lootTables),
            Villagers.CODEC.optionalFieldOf("villagers").forGetter(Mission::villagers),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("days").forGetter(Mission::days),
            Codec.intRange(1, SkillRules.MAX_LEVEL).fieldOf("recommended_level").forGetter(Mission::recommendedLevel),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("rolls", 1).forGetter(Mission::rolls),
            Job.CODEC.listOf(1, Integer.MAX_VALUE).fieldOf("jobs").forGetter(Mission::jobs),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("weight", 1).forGetter(Mission::weight),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("max_per_offer", 1).forGetter(Mission::maxPerOffer)
    ).apply(instance, Mission::new));

    /** Data and network codec; a mission needs exactly one result, loot tables or Villagers. */
    public static final Codec<Mission> CODEC = FIELDS.validate(Mission::checkResult);

    /**
     * A loot mission.
     *
     * @param title            the title shown on the board
     * @param description      the description shown in the tooltip
     * @param icon             the icon shown on the board
     * @param lootTables       the loot tables a successful settler rolls (at least one)
     * @param days             the duration in game days
     * @param recommendedLevel the recommended skill level
     * @param rolls            the base number of loot rolls
     * @param jobs             the Mission Jobs that may take the mission
     * @param weight           the draw weight in the daily offer
     * @param maxPerOffer      how often the mission may appear in one offer
     */
    public Mission(Component title, Component description, Optional<Holder<Item>> icon, List<ResourceKey<LootTable>> lootTables, int days,
            int recommendedLevel, int rolls, List<Job> jobs, int weight, int maxPerOffer) {
        this(title, description, icon, lootTables, Optional.empty(), days, recommendedLevel, rolls, jobs, weight, maxPerOffer);
    }

    private static DataResult<Mission> checkResult(Mission mission) {
        boolean loot = !mission.lootTables.isEmpty();
        if (loot && mission.villagers.isPresent()) {
            return DataResult.error(() -> "A mission has loot_tables or villagers, not both");
        }
        if (!loot && mission.villagers.isEmpty()) {
            return DataResult.error(() -> "A mission needs loot_tables or villagers");
        }
        return DataResult.success(mission);
    }

    /**
     * @return the icon shown on the board
     */
    public ItemStack iconStack() {
        return icon.map(ItemStack::new).orElseGet(() -> jobs.get(0).icon());
    }

    /**
     * The result of a Villager mission (vault {@code Jobs/Bard.md}): how many vanilla Villagers come and of which biome
     * type, and the nitwit chance per Villager ({@link MissionRules#nitwitChance}).
     *
     * @param count          the number of Villagers (the skill does not change it)
     * @param type           the Villager biome type
     * @param nitwitBase     the nitwit chance at the mission level
     * @param nitwitPerLevel how much each skill level above the mission level lowers it (below: raises it)
     * @param nitwitMin      the lowest nitwit chance
     * @param nitwitMax      the highest nitwit chance
     */
    public record Villagers(int count, Holder<VillagerType> type, double nitwitBase, double nitwitPerLevel, double nitwitMin,
            double nitwitMax) {

        private static final double NITWIT_BASE = 0.25;
        private static final double NITWIT_PER_LEVEL = 0.01;
        private static final double NITWIT_MIN = 0.0;
        private static final double NITWIT_MAX = 0.60;

        private static final Codec<Villagers> FIELDS = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("count").forGetter(Villagers::count),
                BuiltInRegistries.VILLAGER_TYPE.holderByNameCodec().fieldOf("type").forGetter(Villagers::type),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("nitwit_base", NITWIT_BASE).forGetter(Villagers::nitwitBase),
                Codec.DOUBLE.optionalFieldOf("nitwit_per_level", NITWIT_PER_LEVEL).forGetter(Villagers::nitwitPerLevel),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("nitwit_min", NITWIT_MIN).forGetter(Villagers::nitwitMin),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("nitwit_max", NITWIT_MAX).forGetter(Villagers::nitwitMax)
        ).apply(instance, Villagers::new));

        /** Data and network codec. */
        public static final Codec<Villagers> CODEC = FIELDS.validate(villagers -> villagers.nitwitMin() <= villagers.nitwitMax()
                ? DataResult.success(villagers) : DataResult.error(() -> "nitwit_min must not be above nitwit_max"));

        /**
         * @param count the number of Villagers
         * @param type  the Villager biome type
         * @return Villagers with the default nitwit chance
         */
        public static Villagers of(int count, ResourceKey<VillagerType> type) {
            return new Villagers(count, BuiltInRegistries.VILLAGER_TYPE.getOrThrow(type), NITWIT_BASE, NITWIT_PER_LEVEL, NITWIT_MIN, NITWIT_MAX);
        }
    }
}
