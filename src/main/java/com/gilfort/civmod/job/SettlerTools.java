package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Tools of a settler, a shared part used by the Job Category work loops (vault {@code Systems/Job Categories.md}):
 * the best tool on the belt, fetching tools (and the inputs of innate recipes) from a container, the belt's free slots
 * and tool wear.
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
     * @param stock what the settler reaches at its collect inventory
     * @param kind  the kind of tool
     * @return the index (in {@link Stock#contents()}) of the best tool of that kind
     */
    static Optional<Integer> find(Stock stock, Predicate<ItemStack> kind) {
        List<ItemStack> contents = stock.contents();
        int bestIndex = -1;
        int bestTier = -1;
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (kind.test(stack) && ToolTiers.tier(stack) <= ToolTiers.maxAllowedTier(TOOL_LEVEL) && ToolTiers.tier(stack) > bestTier) {
                bestTier = ToolTiers.tier(stack);
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? Optional.empty() : Optional.of(bestIndex);
    }

    /**
     * Takes the best tool of a kind from the collect inventory onto a free belt slot.
     *
     * @param settler the settler
     * @param stock   what the settler reaches at its collect inventory
     * @param kind    the kind of tool
     * @return whether a tool was taken
     */
    static boolean take(Settler settler, Stock stock, Predicate<ItemStack> kind) {
        int freeSlot = freeBeltSlot(settler);
        Optional<Integer> index = find(stock, kind);
        if (freeSlot < 0 || index.isEmpty()) {
            return false;
        }
        ItemStack tool = stock.takeOne(index.get());
        if (tool.isEmpty()) {
            return false;
        }
        settler.getInventory().setItem(freeSlot, tool);
        return true;
    }

    /**
     * Takes up to a stack of a kind (an innate recipe's input) from the collect inventory onto a free belt slot; what
     * does not fit one stack goes back, or drops at the settler when the inventory is full.
     *
     * @param settler the settler
     * @param stock   what the settler reaches at its collect inventory
     * @param kind    the kind of item
     * @return whether anything was taken
     */
    static boolean takeStack(Settler settler, Stock stock, Predicate<ItemStack> kind) {
        int free = freeBeltSlot(settler);
        Optional<Integer> index = find(stock, kind);
        if (free < 0 || index.isEmpty()) {
            return false;
        }
        ItemStack sample = stock.contents().get(index.get());
        ItemStack belt = ItemStack.EMPTY;
        for (ItemStack taken : stock.take(sample.getItem(), sample.getMaxStackSize())) {
            if (belt.isEmpty()) {
                belt = taken.copy();
            } else if (ItemStack.isSameItemSameComponents(belt, taken) && belt.getCount() + taken.getCount() <= belt.getMaxStackSize()) {
                belt.grow(taken.getCount());
            } else {
                ItemStack rest = stock.insert(taken);
                if (!rest.isEmpty() && settler.level() instanceof ServerLevel level) {
                    settler.spawnAtLocation(level, rest);
                }
            }
        }
        if (belt.isEmpty()) {
            return false;
        }
        settler.getInventory().setItem(free, belt);
        return true;
    }

    /**
     * @param settler the settler
     * @param kind    the kind of item
     * @return how many items of the kind are on the belt
     */
    static int beltCount(Settler settler, Predicate<ItemStack> kind) {
        int count = 0;
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (!stack.isEmpty() && kind.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * @param settler the settler
     * @return the inventory index of the first empty belt slot, -1 if the belt is full
     */
    static int freeBeltSlot(Settler settler) {
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            if (settler.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
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
