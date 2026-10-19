package com.gilfort.civmod.logistics;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.job.JobProfile;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Pickups of harvests (vault {@code Jobs/Deliveryman.md}): when the drop-offs of Resource Collection Jobs get a
 * Delivery task, and what a Deliveryman takes there. Event-driven from CivMod's own deliveries – no polling of foreign
 * containers: a task is created when a settler delivers and the drop-off then holds at least one stack or is at least
 * half full, by the end-of-work sweep for every non-empty drop-off, and urgently when the drop-off is full. Drop-offs
 * that are a Clerk JobBlock get none. When the drop-off is also the job's collect inventory, the job's tools stay there.
 */
public final class Pickups {

    /** Items that make a drop-off worth a trip (one stack). */
    static final int STACK = 64;

    private Pickups() {
    }

    /**
     * @param items     the items a drop-off holds for pickup
     * @param usedSlots its slots holding items
     * @param slots     its slots
     * @return whether that is worth a pickup: at least one stack, or at least half of the slots used
     */
    public static boolean due(int items, int usedSlots, int slots) {
        return items >= STACK || (slots > 0 && usedSlots * 2 >= slots);
    }

    /**
     * What a drop-off holds for pickup.
     *
     * @param items     the items (the job's tools left out when it is the collect inventory too)
     * @param usedSlots slots holding such items
     * @param slots     all slots
     * @param stacks    the stacks a Deliveryman would carry away (for batching)
     */
    public record Contents(int items, int usedSlots, int slots, int stacks) {

        /** An unloaded or missing drop-off. */
        static final Contents NONE = new Contents(0, 0, 0, 0);
    }

    /**
     * @param level the level
     * @param site  a JobBlock
     * @return whether it is one of a Resource Collection Job and its drop-off gets pickups (it is not a Clerk JobBlock)
     */
    static boolean collects(ServerLevel level, JobBlockSite site) {
        return site.block().jobType().category() == JobCategory.RESOURCE_COLLECTION
                && JobBlockSite.blockAt(level, site.dropOff()).filter(ClerkStorage::isClerk).isEmpty();
    }

    /**
     * A settler of a Resource Collection Job delivered into its drop-off: a pickup is created if it is now full enough,
     * an urgent one if the drop-off took not everything ("Drop-off full").
     *
     * @param level the level
     * @param site  the JobBlock site
     * @param full  whether the drop-off took not everything
     */
    public static void delivered(ServerLevel level, JobBlockSite site, boolean full) {
        if (!collects(level, site)) {
            return;
        }
        Contents contents = contents(level, site, site.dropOff());
        if (full || due(contents.items(), contents.usedSlots(), contents.slots())) {
            Deliveries.request(SettlementData.get(level), site.settlement(), site.dropOff(), site.pos(), level.getGameTime(), full);
        }
    }

    /**
     * End-of-work sweep: a non-empty drop-off gets a pickup (items a player put in are only noticed here).
     *
     * @param level the level
     * @param site  the JobBlock site
     */
    public static void sweep(ServerLevel level, JobBlockSite site) {
        if (collects(level, site) && contents(level, site, site.dropOff()).items() > 0) {
            Deliveries.request(SettlementData.get(level), site.settlement(), site.dropOff(), site.pos(), level.getGameTime(), false);
        }
    }

    /**
     * @param level  the level
     * @param site   the JobBlock the drop-off belongs to
     * @param source the drop-off
     * @return what it holds for pickup (nothing while it is unloaded)
     */
    public static Contents contents(ServerLevel level, JobBlockSite site, BlockPos source) {
        ResourceHandler<ItemResource> handler = handler(level, source);
        if (handler == null) {
            return Contents.NONE;
        }
        Predicate<ItemStack> kept = kept(site, source);
        int items = 0;
        int used = 0;
        int stacks = 0;
        for (int i = 0; i < handler.size(); i++) {
            ItemResource resource = handler.getResource(i);
            long amount = handler.getAmountAsLong(i);
            if (resource.isEmpty() || amount <= 0 || kept.test(resource.toStack())) {
                continue;
            }
            items += (int) Math.min(Integer.MAX_VALUE - items, amount);
            used++;
            stacks += (int) Math.max(1, Math.ceil(amount / (double) resource.toStack().getMaxStackSize()));
        }
        return new Contents(items, used, handler.size(), stacks);
    }

    /**
     * Takes what fits out of a drop-off, slot by slot.
     *
     * @param level  the level
     * @param site   the JobBlock the drop-off belongs to
     * @param source the drop-off
     * @param room   how many of an item still fit into the carry inventory
     * @param into   receives each taken stack at once (so {@code room} sees it)
     * @return how many items were taken
     */
    public static int take(ServerLevel level, JobBlockSite site, BlockPos source, ToIntFunction<ItemStack> room, Consumer<ItemStack> into) {
        int taken = 0;
        ResourceHandler<ItemResource> handler = handler(level, source);
        if (handler == null) {
            return taken;
        }
        Predicate<ItemStack> kept = kept(site, source);
        for (int i = 0; i < handler.size(); i++) {
            ItemResource resource = handler.getResource(i);
            if (resource.isEmpty()) {
                continue;
            }
            ItemStack kind = resource.toStack();
            if (kept.test(kind)) {
                continue;
            }
            int fits = Math.min(room.applyAsInt(kind), (int) Math.min(Integer.MAX_VALUE, handler.getAmountAsLong(i)));
            if (fits <= 0) {
                continue;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                int extracted = handler.extract(i, resource, fits, transaction);
                transaction.commit();
                if (extracted > 0) {
                    into.accept(resource.toStack(extracted));
                    taken += extracted;
                }
            }
        }
        return taken;
    }

    /** The job's tools stay in a drop-off that is its collect inventory too. */
    private static Predicate<ItemStack> kept(JobBlockSite site, BlockPos source) {
        Optional<JobProfile> profile = site.block().jobType().profile(JobProfile.class);
        if (profile.isEmpty() || !site.collect().equals(source)) {
            return stack -> false;
        }
        return profile.get()::isTool;
    }

    private static @Nullable ResourceHandler<ItemResource> handler(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) ? level.getCapability(Capabilities.Item.BLOCK, pos, null) : null;
    }
}
