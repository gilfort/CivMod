package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Producing specifics of the crafting Specializations that work innate recipes only: one station, and a domain
 * that no item belongs to ({@code civmod:domains/none}, empty on purpose), so no pattern a player makes from a
 * crafting recipe passes it. Innate recipes ignore the domain (vault {@code Systems/Producing Jobs.md}), they come
 * from {@code civmod:job_crafting} recipes in the datapack.
 * <ul>
 * <li>{@link #crusher()}: the station is a grindstone ({@code civmod:stations/crusher}, vault
 * {@code Jobs/Crusher.md});</li>
 * <li>{@link #concreteMixer()}: the station is a water cauldron ({@code civmod:stations/water_cauldron}, the one the
 * Dyer bleaches at; vault {@code Jobs/Concrete Mixer.md}).</li>
 * </ul>
 */
public final class InnateProfile implements ProducingProfile {

    /** The domain no item belongs to. */
    private static final TagKey<Item> NO_DOMAIN = TagKey.create(Registries.ITEM, CivMod.id("domains/none"));

    private final TagKey<Block> station;

    private InnateProfile(String station) {
        this.station = TagKey.create(Registries.BLOCK, CivMod.id("stations/" + station));
    }

    /** @return the Crusher's profile: innate recipes at a grindstone */
    public static InnateProfile crusher() {
        return new InnateProfile("crusher");
    }

    /** @return the Concrete Mixer's profile: innate recipes at a water cauldron */
    public static InnateProfile concreteMixer() {
        return new InnateProfile("water_cauldron");
    }

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.CRAFTING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return station;
    }

    @Override
    public Optional<TagKey<Item>> domain() {
        return Optional.of(NO_DOMAIN);
    }
}
