package com.gilfort.civmod.npc;

import com.gilfort.civmod.CivMod;

import net.minecraft.resources.Identifier;

/**
 * Known races. The PoC only has humans; races are referenced by id so datapacks can add more later
 * (vault {@code Systems/Races.md}).
 */
public final class Races {

    /** Humans – the only race in the PoC. */
    public static final Identifier HUMAN = CivMod.id("human");

    private Races() {
    }
}
