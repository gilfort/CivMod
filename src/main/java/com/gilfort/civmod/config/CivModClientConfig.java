package com.gilfort.civmod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client configuration of CivMod ({@code config/civmod-client.toml}, per player, no server effect).
 */
public final class CivModClientConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Which thought bubbles appear above settlers (vault {@code Systems/Thought Bubbles.md}). */
    public static final ModConfigSpec.EnumValue<ThoughtBubbles> SHOW_THOUGHT_BUBBLES = BUILDER
            .comment("Which thought bubbles appear above settlers: ALL, PROBLEMS_ONLY or OFF.")
            .defineEnum("showThoughtBubbles", ThoughtBubbles.ALL);

    /** The client config spec. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private CivModClientConfig() {
    }

    /** Values of "Show thought bubbles". */
    public enum ThoughtBubbles {
        /** Activities and problems. */
        ALL,
        /** Only problems (red "!"). */
        PROBLEMS_ONLY,
        /** None. */
        OFF
    }
}
