package com.gilfort.civmod.inn;

import java.util.List;

import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * The meal of a visitor (vault {@code Systems/NPC Population.md}): one piece of food from the Pantry's stock, free of charge. A
 * visitor has no food level and no appetite, so it takes the first food the Pantry holds and nothing else. A bowl or bottle that
 * is left goes back into the Pantry.
 */
final class VisitorMeal {

    /** Items that fly off the food in the visitor's hand. */
    private static final int PARTICLES = 6;

    private VisitorMeal() {
    }

    /**
     * @param level   the level
     * @param visitor the visitor that eats
     * @param pantry  the Pantry it eats from
     * @return whether the Pantry had food; false if there was nothing to take
     */
    static boolean eat(ServerLevel level, Settler visitor, BlockPos pantry) {
        Stock stock = ClerkStorage.stock(level, pantry, false);
        List<ItemStack> contents = stock.contents();
        for (int slot = 0; slot < contents.size(); slot++) {
            if (!SettlerFood.isEdible(contents.get(slot))) {
                continue;
            }
            ItemStack item = stock.takeOne(slot);
            if (item.isEmpty()) {
                continue;
            }
            ItemStack left = stock.insert(SettlerFood.remainder(item));
            if (!left.isEmpty()) {
                visitor.spawnAtLocation(level, left);
            }
            effects(level, visitor, item);
            return true;
        }
        return false;
    }

    private static void effects(ServerLevel level, Settler visitor, ItemStack item) {
        level.playSound(null, visitor.getX(), visitor.getY(), visitor.getZ(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6F,
                0.9F + level.getRandom().nextFloat() * 0.2F);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(item)), visitor.getX(),
                visitor.getEyeY() - 0.2, visitor.getZ(), PARTICLES, 0.2, 0.1, 0.2, 0.05);
    }
}
