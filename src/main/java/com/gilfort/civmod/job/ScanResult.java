package com.gilfort.civmod.job;

import java.util.List;

import net.minecraft.resources.Identifier;

/**
 * Result of a job scan: what the job found around its work place.
 */
public interface ScanResult {

    /**
     * @return the block types found (shown in the settler's job tab)
     */
    List<Identifier> foundBlocks();

    /**
     * @return whether nothing usable was found
     */
    default boolean isEmpty() {
        return foundBlocks().isEmpty();
    }
}
