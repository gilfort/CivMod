package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.debug.CivProfiler;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

/**
 * The fight of one Guard (vault {@code Jobs/Guard.md}, "Scan and chase"): a goal of the settler that outranks its work goal
 * while an enemy is being fought. It scans for enemies every {@value #SCAN_INTERVAL_TICKS} ticks (staggered by the settler's id)
 * in a radius of {@value #SCAN_RADIUS_BLOCKS} blocks and takes the nearest one it can see and reach, walks to it and hits it
 * with the best weapon of its belt. Path finding runs only during a fight. Outside the border of its settlement the Guard goes at
 * most {@value #CHASE_PAST_BORDER_BLOCKS} blocks, then lets the mob go and the work goal brings it back to its post.
 * <p>
 * A fight starts only while the Guard works at its Guard Job ({@link #onDuty}) and has a weapon; the loop holds the decisions
 * of the Settler Shell while it lasts ({@link #active}).
 */
final class GuardFight extends Goal {

    /** Ticks between two scans for enemies (1 second). */
    static final int SCAN_INTERVAL_TICKS = 20;
    /** How far around itself a Guard looks for enemies, in blocks. */
    static final double SCAN_RADIUS_BLOCKS = 16.0;
    /** How far past the border of its settlement a Guard follows an enemy, in blocks. */
    static final double CHASE_PAST_BORDER_BLOCKS = 24.0;
    /** Ticks between two checks of a running fight: the limits, the weapon and the way to the mob (half a second). */
    private static final int CHECK_INTERVAL_TICKS = 10;
    /** Ticks a mob that cannot be reached, or lies too far out, is left alone (5 seconds). */
    private static final int IGNORE_TICKS = 100;
    /** Failed walks to the mob in a row after which the Guard gives up on it. */
    private static final int MAX_PATH_FAILS = 3;
    /** Walking speed factor of the chase, as of the navigation. */
    private static final double CHASE_SPEED = 1.0;
    /** Ticks without a decision after which the settler no longer counts as on duty (two decision intervals). */
    private static final long DUTY_TICKS = 2L * SettlerWork.DECISION_INTERVAL;
    /** Head turn per tick while it looks at the mob, in degrees. */
    private static final float LOOK_DEGREES = 30.0F;
    private static final long UNSET = Long.MIN_VALUE;

    private final SettlerWork shell;
    private final Settler settler;
    /** Called when a fight starts, to end a work break. */
    private final Runnable endBreak;
    /** Mobs left alone for a while (entity id to game time), because they are out of reach or too far out. */
    private final Map<Integer, Long> ignoredUntil = new HashMap<>();
    private @Nullable SettlerJob job;
    private long dutyUntil;
    private long nextScan = UNSET;
    private @Nullable Mob target;
    private long nextHit;
    private long nextCheck;
    private int pathFails;
    private boolean over;

    GuardFight(SettlerWork shell, Runnable endBreak) {
        this.shell = shell;
        this.settler = shell.settler();
        this.endBreak = endBreak;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /**
     * The settler works at a Guard Job: it may fight until the next decision tells again.
     *
     * @param guardJob the Guard Job
     * @param time     the game time
     */
    void onDuty(SettlerJob guardJob, long time) {
        job = guardJob;
        dutyUntil = time + DUTY_TICKS;
    }

    /**
     * @return whether a fight is going on: the loop holds the decisions of the shell meanwhile
     */
    boolean active() {
        return target != null;
    }

    /**
     * Forgets everything of a Guard Job that went away.
     *
     * @param jobBlock the Guard Post of the Job
     */
    void forget(BlockPos jobBlock) {
        SettlerJob current = job;
        if (current != null && current.jobBlock().equals(jobBlock)) {
            job = null;
            target = null;
            ignoredUntil.clear();
        }
    }

    // ---------------------------------------------------------------- goal

    @Override
    public boolean canUse() {
        SettlerJob guardJob = job;
        if (guardJob == null || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        long now = level.getGameTime();
        if (nextScan == UNSET) {
            nextScan = now + settler.getId() % SCAN_INTERVAL_TICKS;
        }
        if (now < nextScan) {
            return false;
        }
        nextScan = now + SCAN_INTERVAL_TICKS;
        if (now >= dutyUntil || !shell.isWorking(guardJob) || weapon(guardJob).isEmpty()) {
            return false;
        }
        long profile = CivProfiler.start();
        target = nearestEnemy(level, now).orElse(null);
        CivProfiler.stop(CivProfiler.Section.GUARD, profile);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        Mob mob = target;
        SettlerJob guardJob = job;
        return !over && mob != null && mob.isAlive() && guardJob != null && shell.isWorking(guardJob);
    }

    @Override
    public void start() {
        SettlerJob guardJob = job;
        if (guardJob == null) {
            return;
        }
        over = false;
        pathFails = 0;
        nextHit = 0L;
        nextCheck = 0L;
        endBreak.run();
        shell.setStatus(guardJob.jobBlock(), JobStatus.FIGHTING);
        weapon(guardJob).ifPresent(weapon -> shell.hold(weapon.copy()));
    }

    @Override
    public void stop() {
        target = null;
        over = false;
        settler.getNavigation().stop();
        SettlerJob guardJob = job;
        if (guardJob != null) {
            shell.setStatus(guardJob.jobBlock(), JobStatus.GUARDING);
        }
        // The next mob is looked for at once, not a second later.
        nextScan = 0L;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Mob mob = target;
        if (mob == null || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        long profile = CivProfiler.start();
        long now = level.getGameTime();
        settler.getLookControl().setLookAt(mob, LOOK_DEGREES, LOOK_DEGREES);
        if (now >= nextCheck) {
            nextCheck = now + CHECK_INTERVAL_TICKS;
            check(level, mob, now);
        }
        if (!over && settler.isWithinMeleeAttackRange(mob)) {
            settler.getNavigation().stop();
            if (now >= nextHit && settler.hasLineOfSight(mob)) {
                hit(level, mob, now);
            }
        }
        CivProfiler.stop(CivProfiler.Section.GUARD, profile);
    }

    // ---------------------------------------------------------------- choosing the target

    /** The nearest mob that is an enemy, that the Guard sees (vanilla line of sight), can reach and need not go too far out for. */
    private Optional<Mob> nearestEnemy(ServerLevel level, long now) {
        Optional<Settlement> settlement = SettlementData.get(level).settlement(settler.getSettlementId());
        if (settlement.isEmpty()) {
            return Optional.empty();
        }
        ignoredUntil.values().removeIf(until -> until <= now);
        AABB box = settler.getBoundingBox().inflate(SCAN_RADIUS_BLOCKS);
        double radiusSquared = SCAN_RADIUS_BLOCKS * SCAN_RADIUS_BLOCKS;
        List<Mob> enemies = level.getEntitiesOfClass(Mob.class, box, mob -> mob.isAlive() && !ignoredUntil.containsKey(mob.getId())
                && settler.distanceToSqr(mob) <= radiusSquared && GuardEnemies.isEnemy(mob));
        enemies.sort(Comparator.comparingDouble(settler::distanceToSqr));
        for (Mob mob : enemies) {
            if (settler.hasLineOfSight(mob) && fightable(settlement.get(), mob, now)) {
                return Optional.of(mob);
            }
        }
        return Optional.empty();
    }

    /** A mob too far out or without a way to it is left alone for a while. */
    private boolean fightable(Settlement settlement, Mob mob, long now) {
        boolean near = JobAssignments.distancePastBorder(settlement, mob.blockPosition()) <= CHASE_PAST_BORDER_BLOCKS;
        Path path = near ? settler.getNavigation().createPath(mob, 0) : null;
        boolean reachable = path != null && path.canReach();
        if (!reachable) {
            ignoredUntil.put(mob.getId(), now + IGNORE_TICKS);
        }
        return reachable;
    }

    // ---------------------------------------------------------------- the running fight

    /** Ends the fight when the mob stopped being an enemy, the weapon is gone or the Guard went too far out; else walks on. */
    private void check(ServerLevel level, Mob mob, long now) {
        SettlerJob guardJob = job;
        Optional<Settlement> settlement = SettlementData.get(level).settlement(settler.getSettlementId());
        if (guardJob == null || settlement.isEmpty() || !GuardEnemies.isEnemy(mob) || weapon(guardJob).isEmpty()) {
            over = true;
            return;
        }
        if (JobAssignments.distancePastBorder(settlement.get(), settler.blockPosition()) > CHASE_PAST_BORDER_BLOCKS) {
            ignoredUntil.put(mob.getId(), now + IGNORE_TICKS);
            over = true;
            return;
        }
        if (!settler.isWithinMeleeAttackRange(mob) && !settler.getNavigation().moveTo(mob, CHASE_SPEED) && ++pathFails >= MAX_PATH_FAILS) {
            ignoredUntil.put(mob.getId(), now + IGNORE_TICKS);
            over = true;
        }
    }

    private Optional<ItemStack> weapon(SettlerJob guardJob) {
        return shell.tool(guardJob, GuardWeapons.KIND);
    }

    // ---------------------------------------------------------------- a hit

    /** One hit: the swing and the sound of vanilla, the damage of the weapon plus the bonus of the level, the wear of the belt weapon. */
    private void hit(ServerLevel level, Mob mob, long now) {
        SettlerJob guardJob = job;
        Optional<ItemStack> belt = guardJob == null ? Optional.empty() : weapon(guardJob);
        if (guardJob == null || belt.isEmpty()) {
            over = true;
            return;
        }
        ItemStack weapon = belt.get();
        double damage = GuardCombat.damage(GuardCombat.weaponDamage(weapon), shell.effectiveLevel(guardJob));
        nextHit = now + GuardCombat.hitInterval(GuardCombat.weaponSpeed(weapon));
        settler.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        level.playSound(null, mob.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 1.0F, 1.0F);
        mob.hurtServer(level, level.damageSources().mobAttack(settler), (float) damage);
        SettlerTools.wear(level, settler, weapon);
        if (weapon.isEmpty()) {
            // The belt weapon broke: the hand shows nothing, the next decision orders a new one.
            shell.hold(ItemStack.EMPTY);
            over = true;
        }
        if (mob.isDeadOrDying()) {
            payKill(level, guardJob, mob);
        }
    }

    /** The EXP of a kill: half the max health per level of the Guard Post, as the job file says. */
    private void payKill(ServerLevel level, SettlerJob guardJob, Mob mob) {
        int jobLevel = shell.jobBlockLevel(guardJob);
        double perHealth = JobBalancing.of(guardJob.jobType()).guard().orElse(GuardNumbers.NONE).killExpPerHealthOrDefault();
        int exp = GuardCombat.killExp(mob.getMaxHealth(), jobLevel, perHealth);
        if (exp > 0) {
            shell.gainExp(level, guardJob, exp, SettlementRules.skillCap(jobLevel));
        }
    }
}
