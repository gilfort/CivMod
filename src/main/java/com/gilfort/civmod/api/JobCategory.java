package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * The behaviour templates Jobs are built on (vault {@code Systems/Job Categories.md}): the category owns the work loop,
 * the {@link Job} only fills in its specifics through a {@link CategoryProfile}. Fixed in CivMod; addons cannot add
 * categories.
 * <p>
 * Public profiles: Resource Collection ({@link ResourceCollectionProfile}), Producing ({@link ProducingProfile}), Mission
 * ({@link MissionProfile}), Service ({@link ServiceProfile}), Guard ({@link GuardProfile}). Logistics stays internal.
 */
@ApiStatus.Experimental
public enum JobCategory {

    /** Scan a work place, produce continuously, deliver to the drop-off (vault {@code Systems/Resource Collection Jobs.md}). */
    RESOURCE_COLLECTION,
    /** Wait for a request, process a recipe, deliver (vault {@code Systems/Producing Jobs.md}). */
    PRODUCING,
    /** Leave the settlement, roll an outcome, return with loot (vault {@code Systems/Mission Jobs.md}). */
    MISSION,
    /** Hold a post around the clock and keep the settlement safe (vault {@code Jobs/Guard.md}); the first step has no combat. */
    GUARD,
    /** Move items between containers; internal to CivMod (Clerk, Deliveryman). */
    LOGISTICS,
    /** Stand at the JobBlock and provide effects to the listeners within a radius (vault {@code Systems/Job Categories.md}). */
    SERVICE
}
