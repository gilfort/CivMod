package com.gilfort.civmod.api;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.resources.Identifier;

/**
 * Result of a Resource Collection Job's scan ({@link ResourceCollectionProfile#createScan}): what the job found around
 * its work place.
 * <p>
 * CivMod saves the results of its own scans and of {@link SimpleCollectionProfile} scans with the settler and on the
 * JobBlock; other result types are kept in memory only, so after a restart the JobBlock's Found tab waits for the next
 * scan and catch-up for the first live scan.
 */
@ApiStatus.Experimental
public interface ScanResult {

    /**
     * @return the block types found (listed in the JobBlock's Found tab)
     */
    List<Identifier> foundBlocks();

    /**
     * @return whether nothing usable was found
     */
    default boolean isEmpty() {
        return foundBlocks().isEmpty();
    }
}
