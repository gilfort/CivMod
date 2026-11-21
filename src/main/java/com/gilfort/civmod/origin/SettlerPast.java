package com.gilfort.civmod.origin;

import java.util.Optional;

import com.gilfort.civmod.chronicle.Chronicle;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a settler carries of its past (vault {@code Systems/Settler Origin.md}, {@code Systems/Chronicle.md}): the {@link SettlerOrigin}
 * it arrived with and its {@link Chronicle}. The settler holds one of these like it holds its skills and its food; the saving of both
 * values lives here.
 */
public final class SettlerPast {

    /** The origin of the settler; empty for settlers from before the origins and for hand-built test settlers. */
    private Optional<SettlerOrigin> origin = Optional.empty();
    /** The diary and the marks of what the settler has told; empty for a settler without a story yet. */
    private Chronicle chronicle = Chronicle.EMPTY;

    /**
     * @return the settler's origin, empty if it arrived before the origins existed
     */
    public Optional<SettlerOrigin> origin() {
        return origin;
    }

    /**
     * @param origin the settler's origin (set by {@code Origins} when the settler arrives)
     */
    public void setOrigin(SettlerOrigin origin) {
        this.origin = Optional.of(origin);
    }

    /**
     * @return the settler's diary and the marks of what it has told
     */
    public Chronicle chronicle() {
        return chronicle;
    }

    /**
     * @param chronicle the settler's chronicle (written by {@link com.gilfort.civmod.chronicle.Diary} and the talk)
     */
    public void setChronicle(Chronicle chronicle) {
        this.chronicle = chronicle;
    }

    /**
     * Writes the origin (if there is one) and the chronicle into the settler's save.
     *
     * @param output the save
     */
    public void save(ValueOutput output) {
        origin.ifPresent(value -> output.store("origin", SettlerOrigin.CODEC, value));
        output.store("chronicle", Chronicle.CODEC, chronicle);
    }

    /**
     * Reads the origin and the chronicle back; a save without them gives a settler without origin and an empty chronicle.
     *
     * @param input the save
     */
    public void load(ValueInput input) {
        origin = input.read("origin", SettlerOrigin.CODEC);
        chronicle = input.read("chronicle", Chronicle.CODEC).orElse(Chronicle.EMPTY);
    }
}
