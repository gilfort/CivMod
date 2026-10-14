package com.gilfort.civmod.job;

/**
 * The behaviour templates Jobs are built on (vault {@code Systems/Job Categories.md}): the category owns the work loop,
 * the {@link Job} only fills in its specifics. Fixed in CivMod; addons cannot add categories.
 */
public enum JobCategory {

    /** Scan a work place, produce continuously, deliver to the drop-off (vault {@code Systems/Resource Collection Jobs.md}). */
    RESOURCE_COLLECTION,
    /** Wait for a request, process a recipe, deliver (vault {@code Systems/Producing Jobs.md}); not implemented yet. */
    PRODUCING,
    /** Leave the settlement, roll an outcome, return with loot (vault {@code Systems/Mission Jobs.md}); not implemented yet. */
    MISSION,
    /** Hold a post and fight threats; not implemented yet. */
    GUARD,
    /** Move items between containers; not implemented yet. */
    LOGISTICS,
    /** Stay at a POI and provide an effect to other settlers; not implemented yet. */
    SERVICE
}
