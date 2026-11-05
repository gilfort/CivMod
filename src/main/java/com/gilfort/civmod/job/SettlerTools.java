package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Tools of a settler, a shared part used by the Job Category work loops (vault {@code Systems/Job Categories.md}):
 * the best tool on the belt, fetching tools (and the inputs of innate recipes) from a container, the belt's free slots,
 * tool wear and Mending.
 */
final class SettlerTools {

    /** Durability one XP repairs, as in vanilla. */
    private static final int DURABILITY_PER_XP = 2;

    private SettlerTools() {
    }

    /**
     * @param settler the settler
     * @param kind    the kind of tool
     * @return the best tool of that kind on the belt
     */
    static Optional<ItemStack> best(Settler settler, Predicate<ItemStack> kind) {
        return best(settler, kind, ToolStaircase.Rule.NONE);
    }

    /**
     * @param settler the settler
     * @param kind    the kind of tool
     * @param rule    what the Job skill allows; a tool above the allowed tier is ignored
     * @return the best allowed tool of that kind on the belt: one that meets the requirement first, then the higher tier
     */
    static Optional<ItemStack> best(Settler settler, Predicate<ItemStack> kind, ToolStaircase.Rule rule) {
        return ToolStaircase.best(belt(settler), rule, kind);
    }

    /**
     * @param settler the settler
     * @return the stacks of the belt
     */
    static List<ItemStack> belt(Settler settler) {
        List<ItemStack> stacks = new ArrayList<>(Settler.BELT_SIZE);
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            stacks.add(settler.getInventory().getItem(i));
        }
        return stacks;
    }

    /**
     * @param stock what the settler reaches at its collect inventory
     * @param kind  the kind of tool
     * @return the index (in {@link Stock#contents()}) of the best tool of that kind
     */
    static Optional<Integer> find(Stock stock, Predicate<ItemStack> kind) {
        return find(stock, kind, ToolStaircase.Rule.NONE);
    }

    /**
     * @param stock what the settler reaches at its collect inventory
     * @param kind  the kind of tool
     * @param rule  what the Job skill allows
     * @return the index (in {@link Stock#contents()}) of the best allowed tool of that kind
     */
    static Optional<Integer> find(Stock stock, Predicate<ItemStack> kind, ToolStaircase.Rule rule) {
        List<ItemStack> contents = stock.contents();
        int bestIndex = -1;
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack.isEmpty() || !kind.test(stack) || !rule.allows(stack)) {
                continue;
            }
            if (bestIndex < 0 || rule.rank(stack) > rule.rank(contents.get(bestIndex))) {
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? Optional.empty() : Optional.of(bestIndex);
    }

    /**
     * Takes the best tool of a kind (any tier) from the collect inventory onto a free belt slot.
     *
     * @param settler the settler
     * @param stock   what the settler reaches at its collect inventory
     * @param kind    the kind of tool
     * @return whether a tool was taken
     */
    static boolean take(Settler settler, Stock stock, Predicate<ItemStack> kind) {
        return take(settler, stock, kind, ToolStaircase.Rule.NONE);
    }

    /**
     * Takes the best tool of a kind from the collect inventory onto a free belt slot.
     *
     * @param settler the settler
     * @param stock   what the settler reaches at its collect inventory
     * @param kind    the kind of tool
     * @param rule   what the Job skill allows
     * @return whether a tool was taken
     */
    static boolean take(Settler settler, Stock stock, Predicate<ItemStack> kind, ToolStaircase.Rule rule) {
        int freeSlot = freeBeltSlot(settler);
        Optional<Integer> index = find(stock, kind, rule);
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
     * Tool swap (vault {@code Systems/Needs.md}): when no tool of the kind on the belt meets the requirement, takes the
     * best allowed one from the collect inventory if it is better than the belt's best. With a full belt it first puts
     * the weakest tool of that kind back. Never swaps when the requirement is met.
     *
     * @param settler the settler
     * @param stock   what the settler reaches at its collect inventory
     * @param kind    the kind of tool
     * @param rule    what the Job skill allows and requires
     * @return whether a tool was taken
     */
    static boolean swap(Settler settler, Stock stock, Predicate<ItemStack> kind, ToolStaircase.Rule rule) {
        Optional<ItemStack> belt = best(settler, kind, rule);
        if (belt.isPresent() && rule.meets(belt.get())) {
            return false;
        }
        if (!better(stock, kind, rule, belt)) {
            return false;
        }
        int slot = freeBeltSlot(settler);
        int weakest = -1;
        if (slot < 0) {
            weakest = weakestBeltSlot(settler, kind, rule);
            if (weakest < 0) {
                return false;
            }
        }
        ItemStack tool = stock.takeOne(find(stock, kind, rule).orElseThrow());
        if (tool.isEmpty()) {
            return false;
        }
        if (weakest >= 0) {
            if (!stock.insert(settler.getInventory().getItem(weakest).copy()).isEmpty()) {
                // The collect inventory has no room for it: keep everything as it was.
                stock.insert(tool);
                return false;
            }
            slot = weakest;
        }
        settler.getInventory().setItem(slot, tool);
        return true;
    }

    /**
     * @param stock the collect inventory
     * @param kind  the kind of tool
     * @param rule  what the Job skill allows
     * @param belt  the best allowed tool on the belt, if any
     * @return whether the collect inventory has an allowed tool that beats the belt's best
     */
    static boolean better(Stock stock, Predicate<ItemStack> kind, ToolStaircase.Rule rule, Optional<ItemStack> belt) {
        Optional<Integer> index = find(stock, kind, rule);
        return index.isPresent() && (belt.isEmpty() || rule.rank(stock.contents().get(index.get())) > rule.rank(belt.get()));
    }

    /**
     * @return the belt slot of the weakest tool of the kind, -1 if there is none
     */
    private static int weakestBeltSlot(Settler settler, Predicate<ItemStack> kind, ToolStaircase.Rule rule) {
        int weakest = -1;
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (stack.isEmpty() || !kind.test(stack)) {
                continue;
            }
            if (weakest < 0 || rule.rank(stack) < rule.rank(settler.getInventory().getItem(weakest))) {
                weakest = i;
            }
        }
        return weakest;
    }

    /**
     * Takes a tool of a kind from a job's buffer onto a free belt slot: a tool the job collected itself.
     *
     * @param settler the settler
     * @param buffer  the job's buffer
     * @param kind    the kind of tool
     * @param rule    what the Job skill allows
     * @return whether a tool was taken
     */
    static boolean takeFromBuffer(Settler settler, JobBuffer buffer, Predicate<ItemStack> kind, ToolStaircase.Rule rule) {
        int freeSlot = freeBeltSlot(settler);
        if (freeSlot < 0) {
            return false;
        }
        ItemStack tool = buffer.takeOne(stack -> kind.test(stack) && rule.allows(stack));
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

    /**
     * Repairs Mending tools from newly collected XP (vault {@code Systems/Settler XP.md}): first the tool the action used,
     * then the others on the belt.
     *
     * @param settler the settler
     * @param used    the tool the action used (empty: none)
     * @param xp      the new XP
     * @return the XP left
     */
    static int mend(Settler settler, ItemStack used, int xp) {
        int rest = mend(used, xp);
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE && rest > 0; i++) {
            rest = mend(settler.getInventory().getItem(i), rest);
        }
        return rest;
    }

    private static int mend(ItemStack tool, int xp) {
        if (xp <= 0 || !tool.isDamaged() || !EnchantmentHelper.has(tool, EnchantmentEffectComponents.REPAIR_WITH_XP)) {
            return xp;
        }
        int repaired = (int) Math.min((long) xp * DURABILITY_PER_XP, tool.getDamageValue());
        tool.setDamageValue(tool.getDamageValue() - repaired);
        return xp - Math.ceilDiv(repaired, DURABILITY_PER_XP);
    }
}
