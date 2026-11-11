package com.gilfort.civmod.job;

import java.util.Optional;

import com.gilfort.civmod.api.ResourceCollectionProfile;

/**
 * A built-in Resource Collection profile that fishes (CivMod's Fisherman, vault {@code Jobs/Fisherman.md}): it has a
 * treasure option at its JobBlock and the settler casts a line while it works. Internal: not part of the addon API, the
 * Work rules read the JobBlock's setting and hand it to {@link #produce(ResourceCollectionProfile.Context, boolean)}.
 */
interface FishingProfile extends ResourceCollectionProfile {

    /**
     * Produces one catch.
     *
     * @param context  the production context
     * @param treasure whether the roll may give treasure; off, the treasure chance is not rolled and every catch is a fish
     * @return the produced items and the tool to wear, empty if nothing was produced
     */
    Optional<Produced> produce(Context context, boolean treasure);

    @Override
    default Optional<Produced> produce(Context context) {
        return produce(context, true);
    }
}
