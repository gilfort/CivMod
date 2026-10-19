package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * The behaviour templates Jobs are built on (vault {@code Systems/Job Categories.md}): the category owns the work loop,
 * the {@link Job} only fills in its specifics through a {@link CategoryProfile}. Fixed in CivMod; addons cannot add
 * categories.
 * <p>
 * Public profiles: Resource Collection ({@link ResourceCollectionProfile}), Producing ({@link ProducingProfile}), Mission
 * ({@link MissionProfile}). Logistics stays internal; Guard and Service get their profile with their loop.
 */
@ApiStatus.Experimental
public enum JobCategory {

    /** Scan a work place, produce continuously, deliver to the drop-off (vault {@code Systems/Resource Collection Jobs.md}). */
    RESOURCE_COLLECTION,
    /** Wait for a request, process a recipe, deliver (vault {@code Systems/Producing Jobs.md}). */
    PRODUCING,
    /** Leave the settlement, roll an outcome, return with loot (vault {@code Systems/Mission Jobs.md}). */
    MISSION,
    /** Hold a post and fight threats; not implemented yet. */
    GUARD,
    /** Move items between containers; internal to CivMod (Clerk, Deliveryman). */
    LOGISTICS,
    /** Stay at a POI and provide an effect to other settlers; not implemented yet. */
    SERVICE
}
