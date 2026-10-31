package com.gilfort.civmod.job;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.npc.Settler;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * The need tier of a settler for the Settler Shell (vault {@code Systems/Needs.md}): the full check once per day at
 * wake-up, the cheap re-check in the 5 s decision step (only a settler that can climb asks its needs), the complaints,
 * and the limit on the level the settler works at. Live play and catch-up take the same {@link #step}.
 */
final class SettlerNeeds {

    /** Players within this distance get the message. */
    private static final double COMPLAIN_RANGE = 32.0;

    private final SettlerWork shell;
    private final Settler settler;
    private NeedState state = NeedState.START;
    /** The need the settler complains about (not saved: found again by the re-check of a grace day). */
    private @Nullable Need missing;

    /**
     * @param shell the Settler Shell
     */
    SettlerNeeds(SettlerWork shell) {
        this.shell = shell;
        this.settler = shell.settler();
    }

    /**
     * The check of a decision: the full one on the first decision of a day, else the re-check.
     *
     * @param level     the level
     * @param day       the day
     * @param foodPoint whether a food point has food for the settler now
     */
    void step(ServerLevel level, long day, BooleanSupplier foodPoint) {
        if (settler.isHomeless()) {
            return;
        }
        int tier = tier();
        boolean morning = day > state.checkedDay();
        if (!morning && state.settled(tier)) {
            return;
        }
        Optional<Need> unmet = Needs.firstUnmet(new Need.Context(level, settler, shell.meals(), shell.rest(), foodPoint));
        int highestMet = Needs.highestMet(unmet);
        state = morning ? state.morning(day, tier, highestMet) : state.climb(tier, highestMet);
        missing = state.inGrace() ? unmet.orElse(null) : null;
        if (morning && missing != null && !shell.quiet()) {
            complain(level, missing);
        }
    }

    private void complain(ServerLevel level, Need need) {
        Component message = Component.translatable("civmod.settler.need_message", settler.getName(), need.name());
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.distanceToSqr(settler) <= COMPLAIN_RANGE * COMPLAIN_RANGE) {
                player.sendOverlayMessage(message);
            }
        }
    }

    /**
     * @return the settler's tier: the highest capped target level among its current jobs decides
     */
    int tier() {
        return NeedState.tierOf(settler.getJobs().stream().mapToInt(shell::cappedLevel).max().orElse(1));
    }

    /**
     * @param level a level
     * @return the level the settler works at instead
     */
    int limit(int level) {
        return state.limit(level);
    }

    /**
     * @return the item of the need the settler complains about, if any
     */
    Optional<Item> missingIcon() {
        return Optional.ofNullable(missing).map(Need::icon);
    }

    /**
     * @param level     the level
     * @param foodPoint whether a food point has food for the settler now
     * @return all needs as they are now
     */
    NeedsReport report(ServerLevel level, BooleanSupplier foodPoint) {
        Need.Context context = new Need.Context(level, settler, shell.meals(), shell.rest(), foodPoint);
        return new NeedsReport(Math.min(state.workTier(), tier()), Needs.ALL.stream().map(need -> {
            Need.Check check = need.check(context);
            return new NeedsReport.Row(need.tier(), need.name(), check.met(), check.detail());
        }).toList());
    }

    /**
     * @return the state to save
     */
    NeedState state() {
        return state;
    }

    /**
     * @param saved the saved state
     */
    void restore(NeedState saved) {
        state = saved;
    }
}
