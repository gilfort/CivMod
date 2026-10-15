package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Tools of a settler, a shared part used by the Job Category work loops (vault {@code Systems/Job Categories.md}):
 * the best tool on the belt, fetching tools from a container and tool wear.
 */
final class SettlerTools {

    /** Tool restrictions by skill level are not active in the PoC (see the open topic in the vault). */
    private static final int TOOL_LEVEL = SkillRules.MAX_LEVEL;

    private SettlerTools() {
    }

    /**
     * @param settler the settler
     * @param kind    the kind of tool
     * @return the best tool of that kind on the belt
     */
    static Optional<ItemStack> best(Settler settler, Predicate<ItemStack> kind) {
        ItemStack best = ItemStack.EMPTY;
        int maxTier = ToolTiers.maxAllowedTier(TOOL_LEVEL);
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (stack.isEmpty() || !kind.test(stack) || ToolTiers.tier(stack) > maxTier) {
                continue;
            }
            if (best.isEmpty() || ToolTiers.tier(stack) > ToolTiers.tier(best)) {
                best = stack;
            }
        }
        return best.isEmpty() ? Optional.empty() : Optional.of(best);
    }

    /**
     * Holds the best tool of a kind in the main hand (or nothing).
     *
     * @param settler the settler
     * @param kind    the kind of tool
     */
    static void show(Settler settler, Predicate<ItemStack> kind) {
        ItemStack tool = best(settler, kind).map(ItemStack::copy).orElse(ItemStack.EMPTY);
        if (!ItemStack.matches(tool, settler.getItemBySlot(EquipmentSlot.MAINHAND))) {
            settler.setItemSlot(EquipmentSlot.MAINHAND, tool);
        }
    }

    /**
     * @param level the level
     * @param pos   a container position
     * @param kind  the kind of tool
     * @return the slot of the best tool of that kind in the container
     */
    static Optional<Integer> findInContainer(ServerLevel level, BlockPos pos, Predicate<ItemStack> kind) {
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
        if (handler == null) {
            return Optional.empty();
        }
        int bestIndex = -1;
        int bestTier = -1;
        for (int i = 0; i < handler.size(); i++) {
            ItemResource resource = handler.getResource(i);
            if (resource.isEmpty()) {
                continue;
            }
            ItemStack stack = resource.toStack();
            if (kind.test(stack) && ToolTiers.tier(stack) <= ToolTiers.maxAllowedTier(TOOL_LEVEL) && ToolTiers.tier(stack) > bestTier) {
                bestTier = ToolTiers.tier(stack);
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? Optional.empty() : Optional.of(bestIndex);
    }

    /**
     * Takes the best tool of a kind from a container onto a free belt slot.
     *
     * @param level   the level
     * @param settler the settler
     * @param pos     the container position
     * @param kind    the kind of tool
     */
    static void take(ServerLevel level, Settler settler, BlockPos pos, Predicate<ItemStack> kind) {
        int freeSlot = -1;
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            if (settler.getInventory().getItem(i).isEmpty()) {
                freeSlot = i;
                break;
            }
        }
        Optional<Integer> index = findInContainer(level, pos, kind);
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
        if (freeSlot < 0 || index.isEmpty() || handler == null) {
            return;
        }
        ItemResource resource = handler.getResource(index.get());
        try (Transaction transaction = Transaction.openRoot()) {
            if (handler.extract(index.get(), resource, 1, transaction) == 1) {
                settler.getInventory().setItem(freeSlot, resource.toStack(1));
                transaction.commit();
            }
        }
    }

    /**
     * Wears a tool by the configured multiplier.
     *
     * @param level   the level
     * @param settler the settler using the tool
     * @param tool    the tool
     */
    static void wear(ServerLevel level, Settler settler, ItemStack tool) {
        double multiplier = CivModConfig.TOOL_WEAR_MULTIPLIER.get();
        int damage = (int) multiplier + (level.getRandom().nextDouble() < multiplier - Math.floor(multiplier) ? 1 : 0);
        if (damage > 0) {
            tool.hurtAndBreak(damage, level, settler, item -> { });
        }
    }
}
