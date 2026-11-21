package com.gilfort.civmod.origin;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.chronicle.Diary;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModAttachments;
import com.gilfort.civmod.settlement.Settlement;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Gives a settler its origin when it arrives (vault {@code Systems/Settler Origin.md}): rolls it from the data of the settler's
 * race ({@link OriginRoller}), records who took it in, names the settler from the rolled name parts and writes the arrival entry
 * into its diary. Every way a settler comes in calls one of the methods here; nothing else writes an origin.
 */
public final class Origins {

    private static @Nullable RandomSource scripted;

    private Origins() {
    }

    /**
     * Replaces the random source of every origin roll (tests). The order of the draws is described at {@link OriginRoller}.
     *
     * @param random the scripted source, or {@code null} for the level's own
     */
    public static void script(@Nullable RandomSource random) {
        scripted = random;
    }

    /**
     * Gives a settler that joins a settlement an origin: starting settlers and the Priest's pilgrims.
     *
     * @param level      the dimension
     * @param settler    the new settler, race already set
     * @param channel    how it comes in
     * @param settlement the settlement it joins
     */
    public static void assign(ServerLevel level, Settler settler, Channel channel, Settlement settlement) {
        apply(settler, arrival(level, settlement, channel, Optional.empty(), false), Optional.of(settlement), random(level.getRandom()));
    }

    /**
     * Gives a settler from a spawn egg an origin: a biome type of its race, no direction, nobody took it in.
     *
     * @param settler the new settler, race already set
     * @param random  the random source of the spawn
     */
    public static void assignEgg(Settler settler, RandomSource random) {
        apply(settler, new OriginRoller.Arrival(Channel.EGG, Map.of(), Optional.empty(), false, Set.of()), Optional.empty(), random(random));
    }

    /**
     * Gives a converted Villager an origin: the village at the spot, or the place of the mission it came with. A Villager with its own
     * name keeps it and gets no surname.
     *
     * @param level      the dimension
     * @param settler    the new settler, race already set
     * @param settlement the settlement the Villager joins
     * @param villager   the Villager that became the settler
     */
    public static void assignConverted(ServerLevel level, Settler settler, Settlement settlement, Villager villager) {
        Optional<Identifier> missionPlace = villager.getData(ModAttachments.MISSION_PLACE);
        Channel channel = missionPlace.isPresent() ? Channel.MISSION : Channel.VILLAGER;
        boolean keepName = villager.hasCustomName();
        if (keepName) {
            settler.setCustomName(villager.getCustomName());
        }
        apply(settler, arrival(level, settlement, channel, missionPlace, keepName), Optional.of(settlement), random(level.getRandom()));
    }

    /**
     * @param level      the dimension
     * @param settlement a settlement
     * @return the first names of its loaded settlers, which a new settler avoids; a settler without a rolled name counts with its name
     */
    public static Set<String> usedFirstNames(ServerLevel level, Settlement settlement) {
        Set<String> names = new HashSet<>();
        for (UUID member : settlement.members()) {
            if (level.getEntity(member) instanceof Settler settler) {
                names.add(settler.getOrigin().flatMap(SettlerOrigin::name).map(SettlerName::first).orElseGet(() -> settler.getName().getString()));
            }
        }
        return names;
    }

    private static OriginRoller.Arrival arrival(ServerLevel level, Settlement settlement, Channel channel, Optional<Identifier> missionPlace,
            boolean keepName) {
        return new OriginRoller.Arrival(channel, settlement.origins().places(), missionPlace, keepName, usedFirstNames(level, settlement));
    }

    private static RandomSource random(RandomSource own) {
        return scripted != null ? scripted : own;
    }

    private static void apply(Settler settler, OriginRoller.Arrival arrival, Optional<Settlement> settlement, RandomSource random) {
        Optional<RaceDefinition> race = OriginData.race(settler.getRace());
        if (race.isEmpty()) {
            if (!arrival.keepName()) {
                settler.setCustomName(Component.literal(OriginRoller.FALLBACK_NAME));
            }
            return;
        }
        String culture = CivModConfig.SETTLER_NAME_CULTURE.get();
        SettlerOrigin rolled = OriginRoller.roll(race.get(), OriginData.firstNames(race.get(), culture), arrival, random);
        Optional<Rescuer> rescuer = arrival.channel().founderRescues() ? settlement.flatMap(found -> found.origins().founder()) : Optional.empty();
        SettlerOrigin origin = rolled.withRescuer(rescuer);
        settler.setOrigin(origin);
        Diary.arrival(settler, origin);
        origin.name().map(OriginText::name).ifPresent(settler::setCustomName);
    }
}
