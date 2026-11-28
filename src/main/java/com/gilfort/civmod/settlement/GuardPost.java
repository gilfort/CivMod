package com.gilfort.civmod.settlement;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * What a Guard Post keeps besides its guard points, which are the JobBlock's work points (vault {@code Jobs/Guard.md}): its
 * {@linkplain GuardMode mode}, the guard point each Guard picked for the current half day, and which of the points a Guard
 * found out of reach. The picks and the mode are saved with the JobBlock, the reach checks are not. The rules that use this
 * state are in {@link GuardPosts}.
 */
public final class GuardPost {

    /**
     * The guard point a Guard picked.
     *
     * @param period the half day of the pick ({@link GuardPosts#period})
     * @param point  the position of the guard point
     */
    public record Pick(long period, BlockPos point) {
    }

    /**
     * One saved pick with its Guard.
     *
     * @param guard  the Guard
     * @param period the half day of the pick
     * @param point  the position of the guard point
     */
    record SavedPick(UUID guard, long period, BlockPos point) {

        static final Codec<SavedPick> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("guard").forGetter(SavedPick::guard),
                Codec.LONG.fieldOf("period").forGetter(SavedPick::period),
                BlockPos.CODEC.fieldOf("point").forGetter(SavedPick::point)
        ).apply(instance, SavedPick::new));
    }

    /**
     * The saved part of the state.
     *
     * @param mode  the mode of the Guard Post
     * @param picks the guard point picks of its Guards
     */
    record Saved(GuardMode mode, List<SavedPick> picks) {

        static final Codec<Saved> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                GuardMode.CODEC.optionalFieldOf("mode", GuardMode.GUARD).forGetter(Saved::mode),
                SavedPick.CODEC.listOf().optionalFieldOf("picks", List.of()).forGetter(Saved::picks)
        ).apply(instance, Saved::new));

        static final Saved NONE = new Saved(GuardMode.GUARD, List.of());
    }

    /**
     * The result of a path check of a guard point.
     *
     * @param day         the day of the check
     * @param unreachable whether the Guard's path search ended short of the point
     */
    record Reach(long day, boolean unreachable) {
    }

    private GuardMode mode;
    private final Map<UUID, Pick> picks = new LinkedHashMap<>();
    /** Not saved: the Guards check a point again after a reload. */
    private final Map<BlockPos, Reach> reach = new HashMap<>();

    GuardPost(Saved saved) {
        this.mode = saved.mode();
        saved.picks().forEach(pick -> picks.put(pick.guard(), new Pick(pick.period(), pick.point())));
    }

    Saved saved() {
        return new Saved(mode, picks.entrySet().stream()
                .map(entry -> new SavedPick(entry.getKey(), entry.getValue().period(), entry.getValue().point())).toList());
    }

    /**
     * @return the mode of the Guard Post
     */
    public GuardMode mode() {
        return mode;
    }

    void setMode(GuardMode mode) {
        this.mode = mode;
    }

    Map<UUID, Pick> mutablePicks() {
        return picks;
    }

    /**
     * @param guard a Guard of the block
     * @return the guard point it picked for its half day, empty if it has none
     */
    public Optional<Pick> pickOf(UUID guard) {
        return Optional.ofNullable(picks.get(guard));
    }

    /**
     * @param point a guard point
     * @return whether a Guard found the point out of reach in its last check
     */
    public boolean unreachable(BlockPos point) {
        Reach found = reach.get(point);
        return found != null && found.unreachable();
    }

    Optional<Reach> reachOf(BlockPos point) {
        return Optional.ofNullable(reach.get(point));
    }

    Map<BlockPos, Reach> mutableReach() {
        return reach;
    }

    /**
     * Forgets everything this Guard Post kept for a Guard that left it.
     *
     * @param guard the Guard
     */
    void forget(UUID guard) {
        picks.remove(guard);
    }
}
