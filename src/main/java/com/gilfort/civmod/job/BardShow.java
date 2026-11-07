package com.gilfort.civmod.job;

import com.gilfort.civmod.npc.Settler;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * What a playing Bard looks and sounds like (vault {@code Jobs/Bard.md}, decision of 2026-11-07): notes rise from it and a
 * short, quiet melody of note block sounds plays at it, for the players near it only. The goat horn in its hand and the
 * bubble are the shell's ({@code SettlerWork}). The instrument is a placeholder until the Look module.
 * <p>
 * Nothing here scans: a sound reaches the players within its range (the server's own distribution) and particles the players
 * who track the position, so the cost does not depend on how many players or settlers there are.
 */
public final class BardShow {

    /** Ticks from the start of one phrase to the start of the next. */
    static final int PHRASE_PERIOD = 300;
    /** Ticks between two notes. */
    static final int NOTE_GAP = 8;
    /** Ticks between two bursts of note particles. */
    private static final int PARTICLE_GAP = 10;
    /** A quiet melody: audible about 16 blocks around the Bard. */
    private static final float VOLUME = 0.4F;
    /** The phrase as note block notes, 0 to 24; note 12 is the pitch 1. */
    private static final int[] MELODY = {12, 16, 19, 16, 21, 19, 16, 12};
    private static final int NOTES_PER_OCTAVE = 12;
    private static final int PARTICLE_COUNT = 2;
    private static final double RISE_HEIGHT = 2.1;
    private static final double SPREAD = 0.3;

    private BardShow() {
    }

    /**
     * @param tick the ticks since the performance began
     * @return the note block note to play at that tick, or -1 for silence: the phrase starts every {@value #PHRASE_PERIOD} ticks
     *         and has one note every {@value #NOTE_GAP} ticks
     */
    public static int note(long tick) {
        long inPhrase = tick % PHRASE_PERIOD;
        if (inPhrase % NOTE_GAP != 0 || inPhrase / NOTE_GAP >= MELODY.length) {
            return -1;
        }
        return MELODY[(int) (inPhrase / NOTE_GAP)];
    }

    /**
     * @param note a note block note, 0 to 24
     * @return its pitch as the vanilla note block plays it (note 12 is 1.0, note 0 is 0.5, note 24 is 2.0)
     */
    public static float pitch(int note) {
        return (float) Math.pow(2.0, (note - NOTES_PER_OCTAVE) / (double) NOTES_PER_OCTAVE);
    }

    /**
     * Shows one tick of the performance: a burst of notes now and then, and the notes of the melody when one is due.
     *
     * @param level the level
     * @param bard  the playing Bard
     * @param tick  the ticks since the performance began (a stop counts from its start, a stage act from the game time)
     */
    static void show(ServerLevel level, Settler bard, long tick) {
        if (tick % PARTICLE_GAP == 0) {
            level.sendParticles(ParticleTypes.NOTE, bard.getX(), bard.getY() + RISE_HEIGHT, bard.getZ(), PARTICLE_COUNT, SPREAD, 0.0, SPREAD, 1.0);
        }
        int note = note(tick);
        if (note >= 0) {
            level.playSound(null, bard.getX(), bard.getY(), bard.getZ(), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.RECORDS, VOLUME, pitch(note));
        }
    }
}
