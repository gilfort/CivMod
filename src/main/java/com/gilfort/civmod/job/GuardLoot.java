package com.gilfort.civmod.job;

import java.util.Collection;
import java.util.Iterator;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * The loot of a Guard's kill (vault {@code Jobs/Guard.md}, "Mob drops go into the Guard's job inventory"): what a mob drops
 * when a Guard kills it goes into the buffer of the Guard's job, up to the buffer's capacity; what does not fit falls to the
 * ground where the mob died. The Guard takes the buffer to its drop-off point like every job ({@link GuardLoop}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GuardLoot {

    private GuardLoot() {
    }

    @SubscribeEvent
    static void onDrops(LivingDropsEvent event) {
        if (event.getSource().getEntity() instanceof Settler settler) {
            settler.getJobs().stream().filter(job -> job.jobType().category() == JobCategory.GUARD).findFirst()
                    .ifPresent(job -> take(settler.getWork().buffer(job.jobBlock()), event.getDrops()));
        }
    }

    /**
     * Moves drops into a buffer as far as it has room; a stack that does not fit completely stays in the drops with what is left.
     *
     * @param buffer the Guard's job buffer
     * @param drops  the drops of a mob (what is taken is removed or shrunk)
     */
    public static void take(JobBuffer buffer, Collection<ItemEntity> drops) {
        for (Iterator<ItemEntity> each = drops.iterator(); each.hasNext();) {
            ItemEntity drop = each.next();
            ItemStack stack = drop.getItem();
            int fits = Math.min(stack.getCount(), JobBuffer.CAPACITY - buffer.count());
            if (fits <= 0) {
                continue;
            }
            buffer.add(stack.copyWithCount(fits));
            if (fits == stack.getCount()) {
                each.remove();
            } else {
                drop.setItem(stack.copyWithCount(stack.getCount() - fits));
            }
        }
    }
}
