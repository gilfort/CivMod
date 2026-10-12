package com.gilfort.civmod.food;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Settlement pantries (vault {@code Systems/Settlement.md}): containers settlers take food from. Searched only when a
 * settler decides to eat; containers that no longer exist are removed as pantry.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class Pantries {

    private Pantries() {
    }

    /**
     * The position a container is stored under as pantry: the other half of a double chest if that one already is a
     * pantry, so a double chest counts as one pantry.
     *
     * @param level      the level
     * @param settlement the settlement
     * @param pos        the clicked container
     * @return the position to toggle
     */
    public static BlockPos canonical(Level level, Settlement settlement, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction connected = ChestBlock.getConnectedDirection(state);
            BlockPos other = pos.relative(connected);
            if (settlement.pantries().contains(other)) {
                return other;
            }
        }
        return pos;
    }

    /**
     * @param level      the level
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     * @param from       the settler position
     * @return the nearest pantry that contains edible food
     */
    public static Optional<BlockPos> nearestWithFood(ServerLevel level, SettlementData data, Settlement settlement, BlockPos from) {
        List<BlockPos> pantries = new ArrayList<>(settlement.pantries());
        pantries.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));
        for (BlockPos pos : pantries) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
            if (handler == null) {
                data.removePantry(pos);
                continue;
            }
            if (!contents(handler).stream().filter(SettlerFood::isEdible).toList().isEmpty()) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    /**
     * @param handler a container handler
     * @return one stack (count 1) per slot, empty stacks for empty slots
     */
    public static List<ItemStack> contents(ResourceHandler<ItemResource> handler) {
        List<ItemStack> stacks = new ArrayList<>(handler.size());
        for (int i = 0; i < handler.size(); i++) {
            ItemResource resource = handler.getResource(i);
            stacks.add(resource.isEmpty() ? ItemStack.EMPTY : resource.toStack());
        }
        return stacks;
    }

    /**
     * @param level the level
     * @param pos   the pantry
     * @return the item handler of the pantry, if it still exists
     */
    public static Optional<ResourceHandler<ItemResource>> handler(ServerLevel level, BlockPos pos) {
        return Optional.ofNullable(level.getCapability(Capabilities.Item.BLOCK, pos, null));
    }

    /**
     * Takes one item out of a slot.
     *
     * @param handler the container
     * @param slot    the slot
     * @return the item, empty if nothing could be taken
     */
    public static ItemStack takeOne(ResourceHandler<ItemResource> handler, int slot) {
        ItemResource resource = handler.getResource(slot);
        if (resource.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            if (handler.extract(slot, resource, 1, transaction) == 1) {
                transaction.commit();
                return resource.toStack(1);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Puts a stack into a container.
     *
     * @param handler the container
     * @param stack   the stack
     * @return what did not fit
     */
    public static ItemStack insert(ResourceHandler<ItemResource> handler, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int inserted = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(stack), stack.getCount(), transaction);
            transaction.commit();
            ItemStack rest = stack.copy();
            rest.shrink(inserted);
            return rest;
        }
    }

    /**
     * Removes destroyed containers as pantry right away (others are removed when a settler finds them missing).
     */
    @SubscribeEvent
    static void onBreak(BreakBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SettlementData.get(level).removePantry(event.getPos());
        }
    }
}
