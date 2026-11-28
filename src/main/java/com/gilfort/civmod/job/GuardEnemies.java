package com.gilfort.civmod.job;

import com.gilfort.civmod.CivMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Which mobs a Guard fights (vault {@code Jobs/Guard.md}, "Enemies", "An angry neutral mob"): every mob with the vanilla
 * {@link Enemy} interface, creepers included, and every angry {@link NeutralMob} whoever provoked it. A calm neutral mob is
 * never an enemy, even when it is an {@code Enemy} too (an enderman, a zombified piglin): the neutral rule is asked first.
 * Mobs of the tag {@code civmod:guard_ignores} are left alone; it ships empty, addons fill it.
 */
public final class GuardEnemies {

    /** The mob types a Guard leaves alone. */
    public static final TagKey<EntityType<?>> IGNORED = TagKey.create(Registries.ENTITY_TYPE, CivMod.id("guard_ignores"));

    private GuardEnemies() {
    }

    /**
     * @param mob a mob
     * @return whether a Guard fights it: an angry neutral mob, else an {@link Enemy}, unless it is on the ignore tag or
     *         belongs to somebody (a tamed wolf that is angry at a skeleton is nobody's enemy)
     */
    public static boolean isEnemy(Mob mob) {
        if (mob.typeHolder().is(IGNORED) || mob instanceof OwnableEntity owned && owned.getOwnerReference() != null) {
            return false;
        }
        if (mob instanceof NeutralMob neutral) {
            return neutral.isAngry();
        }
        return mob instanceof Enemy;
    }
}
