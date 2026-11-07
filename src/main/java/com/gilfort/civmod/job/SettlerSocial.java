package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.social.SocialRules;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;

/**
 * The social life of one settler (vault {@code Systems/Entertainment.md}, Conversations and greetings): in its decision
 * step it looks for company within the radius and rolls once per settler or player found. A greeting is a head turn
 * towards the other and one arm swing; the settler keeps walking or working. A conversation stops both settlers: the
 * settler whose roll succeeded walks up to the other, they talk for a few seconds, each gets its points, and both go back
 * to what they did.
 * <p>
 * The greeting cooldown of a pair is written on both settlers, so the other does not greet back at its own step. A
 * conversation is reserved on both settlers at the start, so two rolls at the same moment never pair a settler twice. The
 * settler that rolled drives the conversation every tick ({@link #tick}); the partner only stands still and faces it.
 * Nothing is saved and catch-up never runs the check: after a reload the pairs greet again and an unfinished conversation
 * is gone, without points and without a cooldown. The marks that do last (the cooldown, the day's count) live in
 * {@link com.gilfort.civmod.entertainment.Entertainment.Talk}.
 */
final class SettlerSocial {

    /** Ticks the head stays turned towards the one greeted (1.5 seconds). */
    static final int LOOK_TICKS = 30;
    /** How close the settler that rolled comes to its partner, in blocks. */
    static final double TALK_DISTANCE = 1.5;
    /** Ticks between two arm swings while talking; the two settlers swing in turn. */
    static final int SWING_TICKS = 40;
    /** Ticks between two speech bubbles while talking: the client shows one for 60 ticks. */
    static final int BUBBLE_TICKS = 60;

    private final Settler settler;
    /** The game time of the last greeting with each settler or player, until its cooldown is over. */
    private final Map<UUID, Long> greeted = new HashMap<>();
    /** The one greeted last, while the head is still turned towards it. */
    private @Nullable Look look;
    /** The conversation this settler is in or is reserved for, if any. */
    private @Nullable Conversation conversation;
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

    /**
     * One side of a conversation. The settler that rolled holds the clock; the other side only waits.
     *
     * @param partner the other settler
     * @param roller  whether this settler rolled the conversation (it walks and counts the time)
     * @param seated  whether this settler is a seated Tavern guest: it keeps its seat and its activity
     * @param resume  what to go back to afterwards, null for a seated guest
     */
    private static final class Conversation {
        private final Settler partner;
        private final boolean roller;
        private final boolean seated;
        private final SettlerWork.@Nullable Resume resume;
        /** True once the settlers talk; before that the roller still walks. */
        private boolean talking;
        /** The ticks of the walk, then of the talk (roller only). */
        private int ticks;

        private Conversation(Settler partner, boolean roller, boolean seated, SettlerWork.@Nullable Resume resume) {
            this.partner = partner;
            this.roller = roller;
            this.seated = seated;
            this.resume = resume;
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
     * @return the settler this one is in a conversation with (also while it still walks there), if any
     */
    Optional<Settler> talkingTo() {
        Conversation current = conversation;
        return current == null ? Optional.empty() : Optional.of(current.partner);
    }

    /**
     * @return whether the conversation has got to the talking, with the roller at the partner
     */
    boolean isTalking() {
        Conversation current = conversation;
        return current != null && current.talking;
    }

    /**
     * @return whether this settler is in a conversation or reserved for one
     */
    boolean inConversation() {
        return conversation != null;
    }

    /**
     * @return whether the conversation holds the settler's decisions: it stands still or walks to its partner. A seated
     *         guest decides as usual.
     */
    boolean holdsDecision() {
        Conversation current = conversation;
        return current != null && !current.seated;
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
     * sight; a settler greets or starts a conversation once per step. A conversation needs two settlers that can be
     * interrupted and are below their daily limit and past their cooldown; a player never takes part, and a seated Tavern
     * guest never starts one. The caller has checked the {@code socialLife} switch.
     *
     * @param level         the level
     * @param interruptible whether the settler may be interrupted (awake, not away, not serving, ...)
     * @param mayStart      whether the settler may start a conversation (not a seated Tavern guest)
     */
    void meet(ServerLevel level, boolean interruptible, boolean mayStart) {
        SocialRules rules = SocialRules.get();
        long now = level.getGameTime();
        greeted.values().removeIf(at -> rules.greetingReady(now - at));
        if (!interruptible) {
            return;
        }
        boolean mayTalk = mayStart && mayConverse(level, rules);
        AABB box = settler.getBoundingBox().inflate(rules.radius());
        List<Entity> found = level.getEntities(settler, box, other -> noticeable(other) && rules.inRange(settler.distanceToSqr(other)));
        found.sort(Comparator.comparingDouble(settler::distanceToSqr));
        for (Entity other : found) {
            if (onCooldown(other.getUUID(), now, rules)) {
                continue;
            }
            SocialRules.Outcome outcome = rules.outcome(roll(level));
            if (outcome == SocialRules.Outcome.CONVERSATION) {
                // A player never talks, so the roll counts as nothing there.
                if (mayTalk && other instanceof Settler partner && canTalkWith(level, partner, rules)) {
                    begin(level, partner);
                    return;
                }
            } else if (outcome == SocialRules.Outcome.GREETING && settler.hasLineOfSight(other)) {
                greet(other, now);
                return;
            }
        }
    }

    /** A settler of the same settlement that is awake and visible, or a player who plays. */
    private boolean noticeable(Entity other) {
        if (other instanceof Settler neighbour) {
            return awakeAndVisible(neighbour) && neighbour.getSettlementId() >= 0
                    && neighbour.getSettlementId() == settler.getSettlementId();
        }
        return other instanceof Player player && player.isAlive() && !player.isSpectator();
    }

    private double roll(ServerLevel level) {
        DoubleSupplier rolls = scripted;
        return rolls != null ? rolls.getAsDouble() : level.getRandom().nextDouble();
    }

    /** Whether this settler's cooldown is over and it is below the daily limit. */
    private boolean mayConverse(ServerLevel level, SocialRules rules) {
        Entertainment.Talk talk = settler.getWork().entertainment().talk();
        return rules.mayConverse(talk.elapsed(level.getGameTime()), talk.on(DayClock.day(level)));
    }

    /** Whether the other can be stopped now: free, below its limit and past its cooldown, and not catching up. */
    private boolean canTalkWith(ServerLevel level, Settler partner, SocialRules rules) {
        SettlerWork work = partner.getWork();
        return work.interruptible(level) && !work.hasPendingCatchUp() && work.social().mayConverse(level, rules);
    }

    private void greet(Entity other, long now) {
        greeted.put(other.getUUID(), now);
        if (other instanceof Settler neighbour) {
            neighbour.getWork().social().greeted.put(settler.getUUID(), now);
        }
        look = new Look(other);
        swing(settler);
        faceTarget();
    }

    /** Reserves the conversation on both settlers, stops the partner and sends this settler walking. */
    private void begin(ServerLevel level, Settler partner) {
        SettlerWork partnerWork = partner.getWork();
        boolean guest = partnerWork.seatedGuest();
        // The partner stops where it is; a seated guest keeps its seat and only turns its head.
        SettlerWork.Resume theirs = guest ? null : partnerWork.interruptForTalk(level, null);
        partnerWork.social().conversation = new Conversation(settler, false, guest, theirs);
        conversation = new Conversation(partner, true, false, settler.getWork().interruptForTalk(level, partner.blockPosition()));
        look = null;
    }

    /**
     * Every tick: keeps the head turned towards the one just greeted, and drives the conversation of the settler that
     * rolled it: walk, give up after the time allowed, talk, end. The partner only faces the roller. Something that
     * changes either settler (asleep, away, removed, sent elsewhere) drops the conversation without points.
     *
     * @param level the level
     */
    void tick(ServerLevel level) {
        tickLook();
        Conversation current = conversation;
        if (current == null) {
            return;
        }
        if (!holds(current)) {
            finish(level, false);
            return;
        }
        SocialRules rules = SocialRules.get();
        face(current.partner, !current.seated && (current.talking || !current.roller));
        if (!current.roller) {
            if (!current.seated) {
                settler.getNavigation().stop();
            }
            return;
        }
        current.ticks++;
        if (!current.talking) {
            if (arrived(current.partner)) {
                current.talking = true;
                current.ticks = 0;
                current.partner.getWork().social().markTalking();
                settler.getNavigation().stop();
                startTalk();
            } else if (current.ticks >= rules.walkGiveUp()) {
                // No way found in time: dropped, and it costs no cooldown.
                finish(level, false);
            }
            return;
        }
        if (current.ticks >= rules.conversationTicks()) {
            finish(level, true);
        } else if (current.ticks % BUBBLE_TICKS == 0) {
            bubbles(current);
        } else if (current.ticks % SWING_TICKS == 0) {
            swing(current.ticks / SWING_TICKS % 2 == 0 ? settler : current.partner);
        }
    }

    /** One arm swing, without a sound. */
    private static void swing(Settler who) {
        who.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    }

    private void markTalking() {
        Conversation current = conversation;
        if (current != null) {
            current.talking = true;
        }
    }

    /** The settlers reach each other: arm swing and the speech bubble above both. */
    private void startTalk() {
        Conversation current = conversation;
        if (current == null) {
            return;
        }
        swing(settler);
        swing(current.partner);
        bubbles(current);
    }

    private void bubbles(Conversation current) {
        settler.getWork().conversationBubble();
        current.partner.getWork().conversationBubble();
    }

    private boolean arrived(Settler partner) {
        double dx = settler.getX() - partner.getX();
        double dz = settler.getZ() - partner.getZ();
        return dx * dx + dz * dz <= TALK_DISTANCE * TALK_DISTANCE && Math.abs(settler.getY() - partner.getY()) <= TALK_DISTANCE;
    }

    /** Whether the conversation still holds for this settler: both alive and awake, both still in it, both where it left them. */
    private boolean holds(Conversation mine) {
        Settler partner = mine.partner;
        Conversation theirs = partner.getWork().social().conversation;
        return theirs != null && theirs.partner == settler && still(settler, mine) && still(partner, theirs);
    }

    /** Whether the settler is there to be met: alive, in the world, awake and not away (invisible). */
    private static boolean awakeAndVisible(Settler who) {
        return who.isAlive() && !who.isRemoved() && !who.isSleeping() && !who.isAway();
    }

    private static boolean still(Settler who, Conversation conversation) {
        if (!awakeAndVisible(who)) {
            return false;
        }
        // A seated guest must still sit; anyone else must still be in the conversation's activity (not sent elsewhere).
        return conversation.seated ? who.getWork().seatedGuest() : who.getWork().talking();
    }

    /**
     * Ends the conversation on both sides: both go back to their previous step, and if it was held to the end each gets
     * its points and the cooldown and the day's count start.
     */
    private void finish(ServerLevel level, boolean completed) {
        Conversation mine = conversation;
        if (mine == null) {
            return;
        }
        conversation = null;
        SettlerSocial other = mine.partner.getWork().social();
        Conversation theirs = other.conversation != null && other.conversation.partner == settler ? other.conversation : null;
        if (theirs != null) {
            other.conversation = null;
        }
        release(level, settler, mine, completed);
        if (theirs != null) {
            release(level, mine.partner, theirs, completed);
        }
    }

    private static void release(ServerLevel level, Settler who, Conversation side, boolean completed) {
        if (who.isRemoved()) {
            return;
        }
        SettlerWork work = who.getWork();
        if (side.resume != null) {
            work.resumeAfterTalk(level, side.resume);
        }
        if (completed) {
            work.setEntertainment(work.entertainment().talked(SocialRules.get().points(), DayClock.day(level), level.getGameTime()));
        }
    }

    /** Every tick: keeps the head turned towards the one greeted until the time is up, then lets go. */
    private void tickLook() {
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

    /** Turns the head towards the other, and the body too if the settler stands still. */
    private void face(Entity other, boolean body) {
        settler.getLookControl().setLookAt(other.getEyePosition());
        if (body) {
            double dx = other.getX() - settler.getX();
            double dz = other.getZ() - settler.getZ();
            settler.setYBodyRot((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
        }
    }
}
