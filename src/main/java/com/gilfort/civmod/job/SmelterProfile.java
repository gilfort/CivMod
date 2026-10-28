package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Producing specifics of the Smelter tree (vault {@code Jobs/Smelter.md}): one cooking recipe type at its furnace
 * kind, any item; up to one furnace per JobBlock level at once, coal and charcoal as fuel. One item takes the recipe's
 * cooking time at level 20 (vanilla speed) per furnace; a smoker or blast furnace halves it with its vanilla cooking
 * speed (applied by the Producing loop at the station).
 * <ul>
 * <li>{@link #smelter()}: {@code smelting} at furnaces ({@code civmod:stations/smelter});</li>
 * <li>{@link #cook()}: {@code smoking} at smokers ({@code civmod:stations/cook}, vault {@code Jobs/Cook.md});</li>
 * <li>{@link #founder()}: {@code blasting} at blast furnaces ({@code civmod:stations/founder}, vault
 * {@code Jobs/Founder.md}).</li>
 * </ul>
 */
public final class SmelterProfile implements ProducingProfile {

    /** The Smelter's station: furnaces. */
    public static final TagKey<Block> STATION = station("smelter");
    /**
     * Ticks of cooking time per operation of the shared speed formula: at level 20 (0.25 operations per second) an
     * item takes its cooking time.
     */
    private static final double TICKS_PER_OPERATION = 80.0;

    private final RecipeType<?> recipeType;
    private final TagKey<Block> station;

    private SmelterProfile(RecipeType<?> recipeType, TagKey<Block> station) {
        this.recipeType = recipeType;
        this.station = station;
    }

    /** @return the Smelter's profile: {@code smelting} at furnaces */
    public static SmelterProfile smelter() {
        return new SmelterProfile(RecipeType.SMELTING, STATION);
    }

    /** @return the Cook's profile: {@code smoking} at smokers */
    public static SmelterProfile cook() {
        return new SmelterProfile(RecipeType.SMOKING, station("cook"));
    }

    /** @return the Founder's profile: {@code blasting} at blast furnaces */
    public static SmelterProfile founder() {
        return new SmelterProfile(RecipeType.BLASTING, station("founder"));
    }

    private static TagKey<Block> station(String job) {
        return TagKey.create(Registries.BLOCK, CivMod.id("stations/" + job));
    }

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(recipeType);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return station;
    }

    @Override
    public double workTime(RecipeHolder<?> recipe) {
        return recipe.value() instanceof AbstractCookingRecipe cooking ? cooking.cookingTime() / TICKS_PER_OPERATION : 1.0;
    }

    @Override
    public int parallelStations(int jobBlockLevel) {
        return Math.clamp(jobBlockLevel, 1, 5);
    }

    @Override
    public Optional<List<Item>> fuel() {
        return Optional.of(List.of(Items.COAL, Items.CHARCOAL));
    }
}
