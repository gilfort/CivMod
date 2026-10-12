package com.gilfort.civmod.npc;

import net.minecraft.resources.Identifier;

/**
 * The skin pool of settlers in the PoC: the vanilla default player skins (9 characters, wide and slim arms).
 * Custom skins per race come with races (vault {@code Systems/Races.md}).
 */
public final class SettlerSkins {

    private static final String[] CHARACTERS = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

    /** Number of skins in the pool. */
    public static final int COUNT = CHARACTERS.length * 2;

    private SettlerSkins() {
    }

    /**
     * @param index a skin index
     * @return whether the skin uses slim arms
     */
    public static boolean isSlim(int index) {
        return Math.floorMod(index, COUNT) < CHARACTERS.length;
    }

    /**
     * @param index a skin index
     * @return the skin asset id (as used by vanilla default skins, e.g. {@code minecraft:entity/player/slim/alex})
     */
    public static Identifier asset(int index) {
        int normalized = Math.floorMod(index, COUNT);
        String type = isSlim(normalized) ? "slim" : "wide";
        return Identifier.withDefaultNamespace("entity/player/" + type + "/" + CHARACTERS[normalized % CHARACTERS.length]);
    }
}
