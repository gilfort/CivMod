package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.api.XpGain;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
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
    /** Whether a recipe's {@code experience} is earned (vault {@code Systems/Settler XP.md}: the Smelter and the Cook). */
    private final boolean recipeXp;

    private SmelterProfile(RecipeType<?> recipeType, TagKey<Block> station, boolean recipeXp) {
        this.recipeType = recipeType;
        this.station = station;
        this.recipeXp = recipeXp;
    }

    /** @return the Smelter's profile: {@code smelting} at furnaces */
    public static SmelterProfile smelter() {
        return new SmelterProfile(RecipeType.SMELTING, STATION, true);
    }

    /** @return the Cook's profile: {@code smoking} at smokers */
    public static SmelterProfile cook() {
        return new SmelterProfile(RecipeType.SMOKING, station("cook"), true);
    }

    /** @return the Founder's profile: {@code blasting} at blast furnaces */
    public static SmelterProfile founder() {
        return new SmelterProfile(RecipeType.BLASTING, station("founder"), false);
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

    /** The recipe's {@code experience}; the fraction is a chance for one more point, as at the vanilla furnace. */
    @Override
    public XpGain xp(ServerLevel level, RecipeHolder<?> recipe) {
        return recipeXp && recipe.value() instanceof AbstractCookingRecipe cooking ? XpGain.fractional(cooking.experience()) : XpGain.NONE;
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
