package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * The points a Producing Job keeps at its JobBlock between executions (the Composter's rest toward its next bone meal,
 * vault {@code Jobs/Composter.md}). They are saved with the JobBlock. Passed to
 * {@link ProducingProfile#execute(net.minecraft.server.level.ServerLevel, net.minecraft.world.item.crafting.RecipeHolder, net.minecraft.world.item.ItemStack, java.util.List, RestPoints)}.
 * <p>
 * This is an interface because the {@code api} package must not depend on the settlement package that stores the
 * points; the implementation is {@code LibraryRestPoints}, and a test or an addon can pass its own.
 */
@ApiStatus.Experimental
public interface RestPoints {

    /** @return the kept points, in hundredths of a point (0 if none are kept yet) */
    int get();

    /**
     * @param points the points to keep, in hundredths of a point
     */
    void set(int points);
}
