package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.registry.ModAttachments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;

/**
 * The real furnaces a fuel-burning Producing Job works at (vault {@code Jobs/Smelter.md}): smelting is simulated in the
 * settler's buffer, the furnace only shows its lit state and keeps the <i>Remaining Heat</i> of the burnt fuel
 * ({@link ModAttachments#REMAINING_HEAT}). Stations without a furnace block entity count as idle and keep no heat.
 */
final class Furnaces {

    /** Heat of one execution of a recipe that is no cooking recipe: one vanilla smelting. */
    static final int STANDARD_HEAT = AbstractFurnaceBlockEntity.BURN_TIME_STANDARD;

    private Furnaces() {
    }

    /**
     * A furnace is free for a settler only while it is idle: its inventory is empty and it does not burn on its own (a
     * player's smelting is never disturbed). Its lit state alone says nothing: settlers light furnaces themselves.
     *
     * @param level the level
     * @param pos   a station
     * @return whether it is idle (true for a station that is no furnace)
     */
    static boolean isIdle(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return true;
        }
        return furnace.isEmpty() && furnace.saveCustomOnly(level.registryAccess()).getIntOr("lit_time_remaining", 0) <= 0;
    }

    /**
     * @param recipe the recipe of an execution
     * @return the heat one execution needs, in ticks of burn time: the cooking time (fuel per item stays vanilla)
     */
    static int heatOf(RecipeHolder<?> recipe) {
        return recipe.value() instanceof AbstractCookingRecipe cooking ? cooking.cookingTime() : STANDARD_HEAT;
    }

    /**
     * @param level the level
     * @param pos   a station
     * @return its Remaining Heat in ticks of burn time (0 without a block entity)
     */
    static int heat(ServerLevel level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        return entity == null ? 0 : entity.getData(ModAttachments.REMAINING_HEAT);
    }

    private static void setHeat(ServerLevel level, BlockPos pos, int heat) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            entity.setData(ModAttachments.REMAINING_HEAT, heat);
            entity.setChanged();
        }
    }

    /**
     * Pays the heat of one execution at a station: its Remaining Heat first, then fuel from the buffer in the list's
     * order, each burnt for its vanilla burn time; what is left over stays with the station.
     *
     * @param level  the level
     * @param pos    the station
     * @param cost   the heat needed
     * @param fuels  the fuel list in priority order
     * @param buffer the settler's buffer
     * @return whether the heat was paid (false: the buffer holds too little fuel; burnt fuel stays as heat)
     */
    static boolean pay(ServerLevel level, BlockPos pos, int cost, List<Item> fuels, JobBuffer buffer) {
        int heat = heat(level, pos);
        while (heat < cost) {
            Optional<ItemStack> fuel = fuels.stream().map(item -> buffer.takeOne(stack -> stack.is(item))).filter(stack -> !stack.isEmpty()).findFirst();
            if (fuel.isEmpty()) {
                setHeat(level, pos, heat);
                return false;
            }
            int burn = burnTime(level, pos, fuel.get());
            if (burn <= 0) {
                // Not burnable here after all: back into the buffer, it is no fuel.
                buffer.add(fuel.get());
                setHeat(level, pos, heat);
                return false;
            }
            heat += burn;
            // A lava bucket leaves its bucket, like in a furnace.
            ItemStackTemplate remainder = fuel.get().getCraftingRemainder();
            if (remainder != null) {
                buffer.add(remainder.create());
            }
        }
        setHeat(level, pos, heat - cost);
        return true;
    }

    /**
     * The vanilla burn time of a fuel item in a furnace (as the furnace at the station would burn it).
     *
     * @param level the level
     * @param pos   the station
     * @param fuel  the fuel item
     * @return its burn time in ticks, 0 if it does not burn
     */
    static int burnTime(ServerLevel level, BlockPos pos, ItemStack fuel) {
        if (!fuel.has(DataComponents.COOKING_FUEL)) {
            return 0;
        }
        BaseContainerBlockEntity container = level.getBlockEntity(pos) instanceof BaseContainerBlockEntity found ? found
                : new FurnaceBlockEntity(pos, Blocks.FURNACE.defaultBlockState());
        LootContext context = new LootContext.Builder(new LootParams.Builder(level)
                .withParameter(LootContextParams.BLOCK_STATE, container.getBlockState())
                .withParameter(LootContextParams.BLOCK_ENTITY, container)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.CONTAINER, container)
                .withOptionalParameter(net.neoforged.neoforge.common.loot.NeoForgeLootContextParams.QUERIED_STACK, fuel)
                .create(LootContextParamSets.CONTAINER_PROCESS)).create(Optional.empty());
        return ResolvableInt.getFromItem(fuel, DataComponents.COOKING_FUEL, CookingFuel::burnTime, context, 0);
    }

    /**
     * Shows or ends a station's lit state (stations without one are left alone).
     *
     * @param level the level
     * @param pos   the station
     * @param lit   whether it is lit
     */
    static void light(ServerLevel level, BlockPos pos, boolean lit) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT) != lit) {
            level.setBlock(pos, state.setValue(BlockStateProperties.LIT, lit), Block.UPDATE_ALL);
        }
    }
}
