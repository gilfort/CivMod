package com.gilfort.civmod.job;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * The numbers of a Guard's fight (vault {@code Jobs/Guard.md}, "Damage", "EXP"): what a hit does, how often the Guard hits and
 * what a kill is worth. Pure rules; the fight ({@link GuardFight}) applies them.
 */
public final class GuardCombat {

    /** A Guard gains one point of damage for every this many levels of its Job skill. */
    public static final int LEVELS_PER_DAMAGE_POINT = 25;
    /** The attack damage of a bare hand: what the vanilla attribute modifiers of a weapon are added to. */
    static final double BARE_HAND_DAMAGE = 1.0;
    /** The attack speed of a bare hand (hits per second): what the vanilla attribute modifiers of a weapon are added to. */
    static final double BARE_HAND_SPEED = 4.0;
    /** The ticks of one second. */
    private static final double TICKS_PER_SECOND = 20.0;
    /** The shortest time between two hits, whatever the weapon says (ticks). */
    public static final int MIN_HIT_INTERVAL_TICKS = 5;
    /** The longest time between two hits, whatever the weapon says (ticks). */
    public static final int MAX_HIT_INTERVAL_TICKS = 60;
    /** The share of a mob's max health that one point of the JobBlock level of a kill is worth in EXP (the ledger: half). */
    public static final double DEFAULT_KILL_EXP_PER_HEALTH = 0.5;

    private GuardCombat() {
    }

    /**
     * @param weapon a weapon
     * @return the damage the weapon does in vanilla: the bare hand plus the attack damage of its attribute modifiers, without
     *         enchantments
     */
    public static double weaponDamage(ItemStack weapon) {
        return modifiers(weapon).compute(Attributes.ATTACK_DAMAGE, BARE_HAND_DAMAGE, EquipmentSlot.MAINHAND);
    }

    /**
     * @param weapon a weapon
     * @return how often the weapon hits in vanilla, in hits per second
     */
    public static double weaponSpeed(ItemStack weapon) {
        return modifiers(weapon).compute(Attributes.ATTACK_SPEED, BARE_HAND_SPEED, EquipmentSlot.MAINHAND);
    }

    private static ItemAttributeModifiers modifiers(ItemStack weapon) {
        return weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
    }

    /**
     * @param weaponDamage the vanilla damage of the weapon
     * @param skill        the Guard's level in its Job
     * @return the damage of a hit: the weapon's damage plus one point for every {@value #LEVELS_PER_DAMAGE_POINT} levels
     */
    public static double damage(double weaponDamage, int skill) {
        return weaponDamage + Math.max(0, skill) / LEVELS_PER_DAMAGE_POINT;
    }

    /**
     * @param health a mob's health
     * @param damage the damage of a hit
     * @return how many hits it takes to kill the mob
     */
    public static int hitsToKill(double health, double damage) {
        return damage <= 0.0 ? Integer.MAX_VALUE : (int) Math.ceil(health / damage);
    }

    /**
     * @param weaponSpeed the weapon's hits per second
     * @return the ticks between two hits: the vanilla cooldown of the weapon, within sane bounds
     */
    public static int hitInterval(double weaponSpeed) {
        if (weaponSpeed <= 0.0) {
            return MAX_HIT_INTERVAL_TICKS;
        }
        return (int) Math.clamp(Math.round(TICKS_PER_SECOND / weaponSpeed), MIN_HIT_INTERVAL_TICKS, MAX_HIT_INTERVAL_TICKS);
    }

    /**
     * @param maxHealth    the max health of the killed mob
     * @param jobLevel     the level of the Guard Post
     * @param perHealth    the EXP one point of max health is worth per level of the post
     * @return the EXP of a kill: the share of the max health times the level of the Guard Post, rounded down
     */
    public static int killExp(double maxHealth, int jobLevel, double perHealth) {
        return (int) Math.max(0.0, maxHealth * perHealth * jobLevel);
    }
}
