/**
 * The public addon API of CivMod (vault {@code Systems/Jobs.md}, {@code Systems/Job Categories.md}): Job definition and
 * registration ({@link com.gilfort.civmod.api.Job}, {@link com.gilfort.civmod.api.CivModApi#jobs}), the category profiles
 * ({@link com.gilfort.civmod.api.ResourceCollectionProfile} with its builder
 * {@link com.gilfort.civmod.api.SimpleCollectionProfile}, {@link com.gilfort.civmod.api.ProducingProfile},
 * {@link com.gilfort.civmod.api.MissionProfile}, {@link com.gilfort.civmod.api.ServiceProfile} with its
 * {@link com.gilfort.civmod.api.ServiceAct}s and {@link com.gilfort.civmod.api.ServiceEffect}s, {@link com.gilfort.civmod.api.GuardProfile}) and the JobBlock factory
 * ({@link com.gilfort.civmod.api.CivModApi#jobBlock}), and the diary of a settler
 * ({@link com.gilfort.civmod.api.DiaryEntryType}, {@link com.gilfort.civmod.api.CivModApi#addDiaryEntry}). CivMod uses it
 * for its own Jobs; addons compile against this package only.
 * <p>
 * Experimental until the first release: breaking changes are allowed and noted in the pull request that makes them.
 */
@ApiStatus.Experimental
package com.gilfort.civmod.api;

import org.jetbrains.annotations.ApiStatus;
