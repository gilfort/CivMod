package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.api.RestPoints;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Composter's Producing specifics (vault {@code Jobs/Composter.md}): {@link InnateProfile#composter()} (innate recipes
 * at a composter) plus the point counter. An execution composts one item: its compost chance ({@link CompostChances}) is
 * added to the points the JobBlock kept ({@link CompostPoints}), and a bone meal goes into the buffer for every 7 points,
 * so most executions make nothing. The hoe loses 1 durability per bone meal made, not per item.
 */
public final class ComposterProfile implements ProducingProfile {

    private final InnateProfile base = InnateProfile.composter();

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return base.recipeTypes();
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return base.station(type);
    }

    @Override
    public Optional<TagKey<Item>> domain() {
        return base.domain();
    }

    @Override
    public List<ItemStack> execute(ServerLevel level, RecipeHolder<?> recipe, ItemStack result, List<ItemStack> consumed, RestPoints rest) {
        int chance = consumed.stream().mapToInt(item -> CompostChances.percent(level, item)).sum();
        CompostPoints.Step step = CompostPoints.add(rest.get(), chance);
        rest.set(step.rest());
        return step.boneMeal() == 0 ? List.of() : List.of(new ItemStack(Items.BONE_MEAL, step.boneMeal()));
    }

    @Override
    public int toolWear(List<ItemStack> produced) {
        return produced.stream().mapToInt(ItemStack::getCount).sum();
    }
}
