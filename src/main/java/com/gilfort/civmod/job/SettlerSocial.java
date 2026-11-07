package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.social.SocialRules;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;

/**
 * The meeting check of one settler (vault {@code Systems/Entertainment.md}, Conversations and greetings): in its decision
 * step it looks for company within the radius and rolls once per settler or player found. A greeting is a head turn
 * towards the other and one arm swing; the settler keeps walking or working. The conversation share of the roll does
 * nothing yet.
 * <p>
 * The greeting cooldown of a pair is written on both settlers, so the other does not greet back at its own step. Nothing
 * is saved and catch-up never runs the check: after a reload the pairs greet again.
 */
final class SettlerSocial {

    /** Ticks the head stays turned towards the one greeted (1.5 seconds). */
    static final int LOOK_TICKS = 30;

    private final Settler settler;
    /** The game time of the last greeting with each settler or player, until its cooldown is over. */
    private final Map<UUID, Long> greeted = new HashMap<>();
    /** The one greeted last, while the head is still turned towards it. */
    private @Nullable Look look;
    /** Replaces the random roll in tests. */
    private @Nullable DoubleSupplier scripted;

    /** A head turn in progress. */
    private static final class Look {
        private final Entity target;
        private int ticks = LOOK_TICKS;

        private Look(Entity target) {
            this.target = target;
        }
    }

    SettlerSocial(Settler settler) {
        this.settler = settler;
    }

    /**
     * Scripts the rolls of the meeting check (tests).
     *
     * @param rolls the numbers to roll, null for random ones
     */
    void script(@Nullable DoubleSupplier rolls) {
        scripted = rolls;
    }

    /**
     * @return the settler or player the head is turned towards after a greeting
     */
    Optional<UUID> looking() {
        Look current = look;
        return current == null ? Optional.empty() : Optional.of(current.target.getUUID());
    }

    /**
     * @param other the other settler or player
     * @param now   the game time
     * @param rules the rules
     * @return whether this settler greeted the other less than the cooldown ago
     */
    boolean onCooldown(UUID other, long now, SocialRules rules) {
        Long at = greeted.get(other);
        return at != null && !rules.greetingReady(now - at);
    }

    /**
     * The meeting check: one roll per settler or player within the radius, the nearest first. A greeting needs line of
     * sight; a settler greets once per step.
     *
     * @param level         the level
     * @param interruptible whether the settler may be interrupted (awake, not away, not serving, ...)
     */
    void meet(ServerLevel level, boolean interruptible) {
        if (!CivModConfig.SOCIAL_LIFE.get()) {
            return;
        }
        SocialRules rules = SocialRules.get();
        long now = level.getGameTime();
        greeted.values().removeIf(at -> rules.greetingReady(now - at));
        if (!interruptible) {
            return;
        }
        AABB box = settler.getBoundingBox().inflate(rules.radius());
        List<Entity> found = level.getEntities(settler, box, other -> noticeable(other) && rules.inRange(settler.distanceToSqr(other)));
        found.sort(Comparator.comparingDouble(settler::distanceToSqr));
        for (Entity other : found) {
            if (onCooldown(other.getUUID(), now, rules)) {
                continue;
            }
            SocialRules.Outcome outcome = rules.outcome(roll(level));
            // The conversation share does nothing yet; a player never talks, so it counts as nothing there.
            if (outcome == SocialRules.Outcome.GREETING && settler.hasLineOfSight(other)) {
                greet(other, now);
                return;
            }
        }
    }

    /** A settler of the same settlement that is awake and visible, or a player who plays. */
    private boolean noticeable(Entity other) {
        if (other instanceof Settler neighbour) {
            return neighbour.isAlive() && neighbour.getSettlementId() >= 0 && neighbour.getSettlementId() == settler.getSettlementId()
                    && !neighbour.isSleeping() && !neighbour.isAway();
        }
        return other instanceof Player player && player.isAlive() && !player.isSpectator();
    }

    private double roll(ServerLevel level) {
        DoubleSupplier rolls = scripted;
        return rolls != null ? rolls.getAsDouble() : level.getRandom().nextDouble();
    }

    private void greet(Entity other, long now) {
        greeted.put(other.getUUID(), now);
        if (other instanceof Settler neighbour) {
            neighbour.getWork().social().greeted.put(settler.getUUID(), now);
        }
        look = new Look(other);
        settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        faceTarget();
    }

    /** Every tick: keeps the head turned towards the one greeted until the time is up, then lets go. */
    void tickLook() {
        Look current = look;
        if (current == null) {
            return;
        }
        if (current.ticks-- <= 0 || current.target.isRemoved() || settler.isSleeping() || settler.isAway()) {
            look = null;
            return;
        }
        faceTarget();
    }

    private void faceTarget() {
        Look current = look;
        if (current != null) {
            settler.getLookControl().setLookAt(current.target.getEyePosition());
        }
    }
}
