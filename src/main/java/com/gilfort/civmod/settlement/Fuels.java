package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The fuel list of a JobBlock whose Job burns fuel (vault {@code Jobs/Smelter.md}, {@link ProducingProfile#fuel()}):
 * up to {@value #SLOTS} items in priority order, shared by all settlers of the block, the Job's default list until the
 * player changes it in the Fuel tab. Ghost slots: setting one does not consume the item; emptying one moves the
 * following ones up, so the list never has gaps.
 */
public final class Fuels {

    /** Fuel slots of a JobBlock. */
    public static final int SLOTS = 6;

    private Fuels() {
    }

    /**
     * @param job a Job
     * @return whether the Job burns fuel (its JobBlock has a Fuel tab)
     */
    public static boolean burnsFuel(Job job) {
        return job.profile(ProducingProfile.class).flatMap(ProducingProfile::fuel).isPresent();
    }

    /**
     * @param block a JobBlock
     * @return its fuel list in priority order (the Base Job's default list until the player changed it; empty if the
     *         Job burns no fuel)
     */
    public static List<Item> of(SettlementBlock block) {
        return block.fuel().orElseGet(() -> defaults(block.jobType()));
    }

    /**
     * @param job   the Job a settler works at the JobBlock
     * @param block the JobBlock
     * @return the block's fuel list in priority order if the Job burns fuel, else empty
     */
    public static Optional<List<Item>> forJob(Job job, SettlementBlock block) {
        return burnsFuel(job) ? Optional.of(of(block)) : Optional.empty();
    }

    /**
     * @param level the level
     * @param pos   a JobBlock
     * @return its fuel list in priority order, empty if there is no JobBlock burning fuel
     */
    public static List<Item> of(ServerLevel level, BlockPos pos) {
        return JobBlockSite.blockAt(SettlementData.get(level), pos).map(Fuels::of).orElse(List.of());
    }

    private static List<Item> defaults(Job job) {
        return job.profile(ProducingProfile.class).flatMap(ProducingProfile::fuel)
                .map(list -> List.copyOf(list.subList(0, Math.min(SLOTS, list.size())))).orElse(List.of());
    }

    /**
     * @param stack an item
     * @return whether it burns in a furnace (only such items go into a fuel slot)
     */
    public static boolean isFuel(ItemStack stack) {
        return stack.has(DataComponents.COOKING_FUEL);
    }

    /**
     * Puts an item into a fuel slot (not consumed). An item already listed moves there; a slot past the end of the list
     * appends the item.
     *
     * @param level the level
     * @param pos   the JobBlock
     * @param slot  the slot (0 to {@value #SLOTS} − 1)
     * @param item  the fuel item
     * @return whether the list changed
     */
    public static boolean set(ServerLevel level, BlockPos pos, int slot, Item item) {
        Optional<SettlementBlock> block = burning(level, pos);
        if (block.isEmpty() || slot < 0 || slot >= SLOTS || !isFuel(new ItemStack(item))) {
            return false;
        }
        List<Item> fuel = new ArrayList<>(of(block.get()));
        int old = fuel.indexOf(item);
        if (old == slot) {
            return false;
        }
        if (old >= 0) {
            fuel.remove(old);
        }
        int at = Math.min(slot, fuel.size());
        if (at < fuel.size() && old < 0) {
            fuel.set(at, item);
        } else {
            fuel.add(at, item);
        }
        return store(level, block.get(), fuel);
    }

    /**
     * Empties a fuel slot; the following items move up.
     *
     * @param level the level
     * @param pos   the JobBlock
     * @param slot  the slot
     * @return whether the list changed
     */
    public static boolean clear(ServerLevel level, BlockPos pos, int slot) {
        Optional<SettlementBlock> block = burning(level, pos);
        if (block.isEmpty()) {
            return false;
        }
        List<Item> fuel = new ArrayList<>(of(block.get()));
        if (slot < 0 || slot >= fuel.size()) {
            return false;
        }
        fuel.remove(slot);
        return store(level, block.get(), fuel);
    }

    /**
     * Swaps a fuel item with its left or right neighbour (its priority goes up or down by one).
     *
     * @param level     the level
     * @param pos       the JobBlock
     * @param slot      the slot of the item
     * @param direction −1 left (higher priority), +1 right
     * @return whether the list changed
     */
    public static boolean move(ServerLevel level, BlockPos pos, int slot, int direction) {
        Optional<SettlementBlock> block = burning(level, pos);
        if (block.isEmpty()) {
            return false;
        }
        List<Item> fuel = new ArrayList<>(of(block.get()));
        int other = slot + Integer.signum(direction);
        if (slot < 0 || slot >= fuel.size() || other < 0 || other >= fuel.size() || other == slot) {
            return false;
        }
        fuel.set(slot, fuel.set(other, fuel.get(slot)));
        return store(level, block.get(), fuel);
    }

    private static Optional<SettlementBlock> burning(ServerLevel level, BlockPos pos) {
        return JobBlockSite.blockAt(SettlementData.get(level), pos).filter(block -> burnsFuel(block.jobType()));
    }

    private static boolean store(ServerLevel level, SettlementBlock block, List<Item> fuel) {
        block.setFuel(fuel);
        SettlementData.get(level).changed();
        return true;
    }
}
