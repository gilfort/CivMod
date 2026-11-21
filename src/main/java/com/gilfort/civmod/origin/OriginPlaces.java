package com.gilfort.civmod.origin;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.BiomeBonuses;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementOrigins;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Looks up the real places the origins of a settlement point to (vault {@code Systems/Settler Origin.md}): for each biome type of
 * the settlement's race the nearest biome within {@value #SEARCH_RADIUS_BLOCKS} blocks, as a direction and a distance. The search
 * reads the climate noise of the world generator and loads no chunk. It runs once per settlement, one biome type per tick, and
 * resumes after a restart because the settlement saves what is left to search ({@link SettlementOrigins#todo()}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class OriginPlaces {

    /** How far around the settlement a biome is looked for. */
    public static final int SEARCH_RADIUS_BLOCKS = 5000;
    /** The sampling grid of the search: coarse on purpose, one search of a type can touch tens of thousands of columns. */
    private static final int HORIZONTAL_STEP_BLOCKS = 64;
    private static final int VERTICAL_STEP_BLOCKS = 128;

    private static @Nullable BiomeLookup scripted;

    private OriginPlaces() {
    }

    /**
     * The search for the nearest biome of a biome type; a test swaps it for a scripted one because the GameTest world is not
     * controlled.
     */
    @FunctionalInterface
    public interface BiomeLookup {

        /**
         * @param level     the dimension
         * @param origin    where the search starts
         * @param biomeType the biome type, with the biomes it covers
         * @return where the nearest biome of the type is, empty if there is none within the radius
         */
        Optional<BlockPos> nearest(ServerLevel level, BlockPos origin, OriginEntry biomeType);
    }

    /**
     * Replaces the search with a scripted one (tests).
     *
     * @param lookup the scripted search, or {@code null} for the real one
     */
    public static void script(@Nullable BiomeLookup lookup) {
        scripted = lookup;
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            step(level);
        }
    }

    /**
     * Does at most one search for one settlement of the dimension: the first one whose lookup is not done and that has a JobBlock
     * to measure from.
     *
     * @param level the dimension
     * @return whether a search ran
     */
    public static boolean step(ServerLevel level) {
        SettlementData data = SettlementData.get(level);
        for (Settlement settlement : data.settlements()) {
            if (advance(level, data, settlement)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does one search for one settlement: the next biome type of its race. The first call picks the biome types of the race.
     *
     * @param level      the dimension
     * @param data       the settlement data the settlement is saved in
     * @param settlement the settlement
     * @return whether a search ran, false if the lookup is done or the settlement has no JobBlock to measure from
     */
    public static boolean advance(ServerLevel level, SettlementData data, Settlement settlement) {
        Optional<BlockPos> anchor = settlement.anchor().map(SettlementBlock::pos);
        if (settlement.origins().lookupDone() || anchor.isEmpty()) {
            return false;
        }
        Optional<RaceDefinition> race = OriginData.race(raceOf(settlement));
        if (settlement.origins().todo().isEmpty()) {
            List<Identifier> types = race.map(definition -> definition.places().stream().map(OriginEntry::id).toList()).orElse(List.of());
            data.updateOrigins(settlement, origins -> origins.withLookup(types));
        }
        searchNext(level, data, settlement, anchor.get(), race);
        return true;
    }

    private static Identifier raceOf(Settlement settlement) {
        return settlement.blocks().stream().map(SettlementBlock::centerRace).flatMap(Optional::stream).findFirst().orElse(Races.HUMAN);
    }

    private static void searchNext(ServerLevel level, SettlementData data, Settlement settlement, BlockPos anchor, Optional<RaceDefinition> race) {
        List<Identifier> todo = settlement.origins().todo().orElseThrow();
        if (todo.isEmpty()) {
            return;
        }
        Optional<OriginEntry> type = race.flatMap(definition -> definition.place(todo.get(0)));
        Optional<PlaceHit> hit = type.flatMap(entry -> search(level, anchor, entry)).map(found -> hitAt(anchor, found));
        data.updateOrigins(settlement, origins -> origins.searched(hit));
    }

    private static Optional<BlockPos> search(ServerLevel level, BlockPos anchor, OriginEntry type) {
        if (scripted != null) {
            return scripted.nearest(level, anchor, type);
        }
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(biome -> BiomeBonuses.matches(type.biomes(), biome, Registries.BIOME), anchor,
                SEARCH_RADIUS_BLOCKS, HORIZONTAL_STEP_BLOCKS, VERTICAL_STEP_BLOCKS);
        return found == null ? Optional.empty() : Optional.of(found.getFirst());
    }

    private static PlaceHit hitAt(BlockPos anchor, BlockPos found) {
        double dx = found.getX() - anchor.getX();
        double dz = found.getZ() - anchor.getZ();
        return new PlaceHit(Compass.of(dx, dz), (int) Math.round(Math.sqrt(dx * dx + dz * dz)));
    }
}
