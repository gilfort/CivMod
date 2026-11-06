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

    /** How the Clerk's Storage tab sorts its items (vault {@code Jobs/Clerk.md}, terminal). */
    public static final ModConfigSpec.EnumValue<StorageSort> STORAGE_SORT = BUILDER
            .comment("How the Clerk's Storage tab sorts its items: AMOUNT or NAME.")
            .defineEnum("storageSort", StorageSort.AMOUNT);

    /** The client config spec. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private CivModClientConfig() {
    }

    /** Sort orders of the Storage tab. */
    public enum StorageSort {
        /** Largest amount first. */
        AMOUNT("#", "civmod.gui.storage.sort.amount"),
        /** A–Z by name. */
        NAME("A", "civmod.gui.storage.sort.name");

        private final String label;
        private final String tooltipKey;

        StorageSort(String label, String tooltipKey) {
            this.label = label;
            this.tooltipKey = tooltipKey;
        }

        /**
         * @return the text of the sort button
         */
        public String label() {
            return label;
        }

        /**
         * @return the lang key of the sort button's tooltip
         */
        public String tooltipKey() {
            return tooltipKey;
        }

        /**
         * @return the other sort order
         */
        public StorageSort next() {
            return this == AMOUNT ? NAME : AMOUNT;
        }
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
