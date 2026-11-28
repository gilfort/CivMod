package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * The specifics of a Guard Job (vault {@code Jobs/Guard.md}): a settler that holds a post around the clock. The Guard loop
 * runs for every Job of the Guard category; the Job's JobBlock is its Guard Post, and the mode and the points of the post
 * (set in the JobBlock window and with the Scroll of Assignment) decide where the Guard stands.
 * <p>
 * There is no hook yet: every Guard behaves the same. Hooks (weapons, enemies, gear tiers) come with the combat of the
 * Guard. Like every profile, the interface has {@code default} methods only, so a hook added later does not break an
 * existing implementation.
 */
@ApiStatus.Experimental
public interface GuardProfile extends CategoryProfile {

    /** The profile of a Guard Job that needs no hook of its own, like CivMod's Guard. */
    GuardProfile DEFAULT = new GuardProfile() {
    };

    @Override
    default JobCategory category() {
        return JobCategory.GUARD;
    }
}
