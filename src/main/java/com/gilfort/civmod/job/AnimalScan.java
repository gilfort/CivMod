package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * Scan of the Animal Farmer (vault {@code Jobs/Animal Farmer.md}): counts the adult animals of
 * {@code #civmod:farm_animals} per entity type in a box around the hay bale. One box query in a single {@link #step};
 * it sees the animals of loaded chunks only.
 */
public final class AnimalScan implements SectionScan<AnimalScan.Result> {

    /** The animals an Animal Farmer counts and collects from. */
    public static final TagKey<EntityType<?>> FARM_ANIMALS = TagKey.create(Registries.ENTITY_TYPE, CivMod.id("farm_animals"));
    /** How many animals a roll looks at to pick its sample (keeps the per-output query bounded). */
    private static final int SAMPLE_LIMIT = 8;

    /**
     * Result of a scan.
     *
     * @param counts adult animals per entity type id
     */
    public record Result(Map<Identifier, Integer> counts) implements ScanResult {

        /** Empty result. */
        public static final Result EMPTY = new Result(Map.of());

        /**
         * @return the number of counted animals
         */
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        /**
         * @param type an entity type
         * @return whether at least one adult of the type was found (e.g. a cow for milk)
         */
        public boolean has(EntityType<?> type) {
            return counts.getOrDefault(EntityType.getKey(type), 0) > 0;
        }

        /** The found entity type ids, sorted. */
        @Override
        public List<Identifier> foundBlocks() {
            return counts.keySet().stream().sorted().toList();
        }
    }

    private final AABB box;
    private boolean done;
    private Result result = Result.EMPTY;

    /**
     * @param center     the scan centre (the hay bale)
     * @param radius     the horizontal reach
     * @param halfHeight how far up and down
     */
    public AnimalScan(BlockPos center, int radius, int halfHeight) {
        this.box = box(center, radius, halfHeight);
    }

    /**
     * @param center     the centre
     * @param radius     the horizontal reach
     * @param halfHeight how far up and down
     * @return the box of the scan around the centre block
     */
    public static AABB box(BlockPos center, int radius, int halfHeight) {
        return new AABB(center).inflate(radius, halfHeight, radius);
    }

    /**
     * @param animal an animal
     * @return whether the Animal Farmer counts it: a living adult of {@link #FARM_ANIMALS}
     */
    public static boolean counts(Animal animal) {
        return animal.isAlive() && !animal.isBaby() && animal.getType().builtInRegistryHolder().is(FARM_ANIMALS);
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void step(ServerLevel level) {
        if (done) {
            return;
        }
        Map<Identifier, Integer> counts = new HashMap<>();
        for (Animal animal : level.getEntitiesOfClass(Animal.class, box, AnimalScan::counts)) {
            counts.merge(BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()), 1, Integer::sum);
        }
        result = new Result(Map.copyOf(counts));
        done = true;
    }

    @Override
    public Result result() {
        return result;
    }

    /**
     * A live adult of a type in the box, the loot context of one roll (sheep colour, chicken variant). One query that
     * stops after a few animals.
     *
     * @param level  the level
     * @param box    where to look
     * @param type   the rolled type
     * @param random the random source
     * @return one of the first animals found, empty if none is there (it walked off)
     */
    public static Optional<Animal> sample(ServerLevel level, AABB box, EntityType<?> type, RandomSource random) {
        List<Animal> found = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(Animal.class), box, animal -> animal.getType() == type && counts(animal), found, SAMPLE_LIMIT);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(random.nextInt(found.size())));
    }
}
