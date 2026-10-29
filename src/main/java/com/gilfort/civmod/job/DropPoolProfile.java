package com.gilfort.civmod.job;

import java.util.Optional;

import com.gilfort.civmod.api.ResourceCollectionProfile;

import net.minecraft.resources.Identifier;

/**
 * A farming Job's profile that takes the entries of its NPC drop pool ({@link JobDrops}, vault
 * {@code Systems/Resource Collection Jobs.md}, decisions 2026-10-29): its scan counts every entry's {@code from}, its
 * roll adds the entries of the rolled type, and the items a type can give include their results. The {@link WorkRules}
 * call {@link #produce(ResourceCollectionProfile.Context, JobDrops.Tools)} with the tools of the collect inventory.
 */
public interface DropPoolProfile extends ResourceCollectionProfile {

    /**
     * @return the Job whose entries the profile takes, empty for none (an addon's {@code SimpleCollectionProfile})
     */
    Optional<Identifier> dropPool();

    /**
     * @return whether the found types are entity types (the Animal Farmer), else block ids
     */
    default boolean entityDrops() {
        return false;
    }

    /**
     * @return the entries of the Job's drop pool
     */
    default JobDrops.Pool pool() {
        return dropPool().map(JobDrops::pool).orElse(JobDrops.Pool.EMPTY);
    }

    /**
     * Produces one resource; entries with their own tool use it from the collect inventory.
     *
     * @param context the production context
     * @param tools   the tools of the collect inventory
     * @return the produced items and the tool to wear, empty if nothing was produced
     */
    Optional<Produced> produce(Context context, JobDrops.Tools tools);

    /** Without a collect inventory: entries with their own tool are skipped. */
    @Override
    default Optional<Produced> produce(Context context) {
        return produce(context, JobDrops.Tools.NONE);
    }
}
