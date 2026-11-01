package com.gilfort.civmod.block;

import java.util.Optional;
import java.util.function.Supplier;

import com.gilfort.civmod.api.Job;

import net.minecraft.resources.Identifier;

/**
 * A Center Block: the JobBlock of one race that founds a settlement and is its center (vault
 * {@code Systems/Settlement.md}). Everything else works like on every JobBlock.
 */
public final class CenterBlock extends JobBlock {

    private final Identifier race;

    /**
     * @param jobType    the offered job (resolved lazily, jobs register after blocks)
     * @param race       the race of this Center Block
     * @param properties the block properties
     */
    public CenterBlock(Supplier<Job> jobType, Identifier race, Properties properties) {
        super(jobType, properties);
        this.race = race;
    }

    @Override
    public Optional<Identifier> centerRace() {
        return Optional.of(race);
    }
}
