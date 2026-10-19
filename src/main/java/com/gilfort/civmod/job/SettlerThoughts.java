package com.gilfort.civmod.job;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModItems;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The thought bubbles of one settler, server side (vault {@code Systems/Thought Bubbles.md}): compares what the settler
 * does now with what it did at the last look and sends a bubble only on a change (the Job it works, the activity it
 * starts) and "problem on" / "problem off" when a problem starts or ends. The client shows each bubble for a few
 * seconds and repeats problems by itself, so a settler sends only a few packets per day. While the settler catches up
 * nothing is sent; the problems stay as the clients know them, so the first look afterwards sends what changed.
 */
final class SettlerThoughts {

    /** How many sent bubbles are remembered (for tests and debugging). */
    private static final int RECENT = 16;

    private final Settler settler;
    /** The Job the settler worked last (kept while it eats or sleeps: coming back to it is no switch). */
    private @Nullable SettlerJob lastJob;
    private @Nullable ThoughtBubble activity;
    /** The problems the clients know as on, with the icon they were sent with. */
    private final Map<ThoughtBubble, Item> problems = new EnumMap<>(ThoughtBubble.class);
    private final Deque<ThoughtBubble.Show> recent = new ArrayDeque<>();

    /**
     * @param settler the settler
     */
    SettlerThoughts(Settler settler) {
        this.settler = settler;
    }

    /**
     * What the settler does now.
     *
     * @param job      the Job it works on, if any
     * @param activity the activity bubble of what it does, if it does something that has one
     * @param problems the problems that are on, with their icons
     */
    record Now(@Nullable SettlerJob job, @Nullable ThoughtBubble activity, Map<ThoughtBubble, Item> problems) {
    }

    /**
     * Sends the bubbles of what changed since the last look.
     *
     * @param now  what the settler does now
     * @param send false while caught up: Job and activity follow quietly, problems wait for the next look that sends
     */
    void update(Now now, boolean send) {
        if (now.job() != null && !now.job().equals(lastJob)) {
            lastJob = now.job();
            emit(ThoughtBubble.JOB, now.job().jobType().bubbleIcon().getItem(), true, send);
        }
        if (now.activity() != activity) {
            activity = now.activity();
            if (activity != null) {
                emit(activity, icon(activity), true, send);
            }
        }
        if (!send) {
            return;
        }
        for (ThoughtBubble ended : List.copyOf(problems.keySet())) {
            if (!now.problems().containsKey(ended)) {
                emit(ended, problems.remove(ended), false, send);
            }
        }
        now.problems().forEach((problem, icon) -> {
            if (problems.get(problem) != icon) {
                problems.put(problem, icon);
                emit(problem, icon, true, send);
            }
        });
    }

    /**
     * The settler was automatically assigned to a Job: its bubble shows at once (and not again when it starts working).
     * While caught up nothing is sent and the bubble shows when the settler starts working, like a Job switch.
     *
     * @param job  the new job
     * @param send false while caught up
     */
    void assigned(SettlerJob job, boolean send) {
        if (!send) {
            return;
        }
        lastJob = job;
        emit(ThoughtBubble.JOB, job.jobType().bubbleIcon().getItem(), true, true);
    }

    /**
     * Sends the problems that are on to a player that starts seeing the settler (it missed their "problem on").
     *
     * @param player the player
     */
    void showProblemsTo(net.minecraft.server.level.ServerPlayer player) {
        problems.forEach((problem, icon) -> PacketDistributor.sendToPlayer(player, new ThoughtBubble.Show(settler.getId(), problem, icon, true)));
    }

    /**
     * @return the last bubbles sent, oldest first
     */
    List<ThoughtBubble.Show> recent() {
        return List.copyOf(recent);
    }

    private void emit(ThoughtBubble bubble, Item icon, boolean on, boolean send) {
        if (!send) {
            return;
        }
        ThoughtBubble.Show show = new ThoughtBubble.Show(settler.getId(), bubble, icon, on);
        if (recent.size() == RECENT) {
            recent.removeFirst();
        }
        recent.addLast(show);
        PacketDistributor.sendToPlayersTrackingEntity(settler, show);
    }

    /**
     * @param activity an activity bubble
     * @return its icon
     */
    private static Item icon(ThoughtBubble activity) {
        return switch (activity) {
            case DROP_OFF, DROP_OFF_FULL -> ModItems.DROP_OFF_MARKER.get();
            case COLLECT -> ModItems.COLLECT_MARKER.get();
            case CRAFTING, MISSING_INPUTS -> Items.CRAFTING_TABLE;
            case EATING, HUNGRY_NO_FOOD -> Items.BREAD;
            case BED -> Items.BED.pick(net.minecraft.world.item.DyeColor.RED);
            case MISSION -> Items.MAP;
            case JOB, NO_TOOL, NO_WORK_PLACE -> throw new IllegalArgumentException("Icon depends on the job: " + activity);
        };
    }

    /**
     * @param problem a problem bubble with a fixed icon
     * @return its icon
     */
    static Item problemIcon(ThoughtBubble problem) {
        return icon(problem);
    }
}
