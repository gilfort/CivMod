package com.gilfort.civmod.inn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.settlement.InnSlots;
import com.gilfort.civmod.settlement.InnState;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Visit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The inns of the settlements (vault {@code Systems/NPC Population.md}, spec Inn and visitors). A Pantry with guest beds is an
 * inn. Three times a day (the checks of {@link InnSlots}) it draws visitors while a Tavern Host covers that time; each visitor stays
 * a few days, sleeps in a guest bed and leaves at a check. {@link #run} plays every check that has not been played yet, so an
 * unloaded settlement catches up when its Pantry and all its settlers are loaded: visitors whose stay still runs appear, those who
 * would be gone leave no trace. Once a second the level tick only compares two numbers per settlement with an inn.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class Inns {

    /** Server ticks between two looks at the settlements with an inn (one second). */
    private static final int TICK_INTERVAL = 20;

    /**
     * Whether a Tavern Host works a Pantry at a time. The shipped answer is the shift of a Tavern Host ({@link ClerkStorage#working});
     * tests script another one.
     */
    @FunctionalInterface
    public interface HostCover {

        /**
         * @param pantry  the Pantry
         * @param day     the day of the check
         * @param dayTime the day time of the check
         * @return whether a Tavern Host works the Pantry then
         */
        boolean covers(SettlementBlock pantry, long day, long dayTime);
    }

    private static HostCover cover = ClerkStorage::working;
    private static @Nullable RandomSource scripted;

    private Inns() {
    }

    /**
     * Replaces the random source of the arrival rolls, the days of a visit and the requests to stay longer (tests). Per check an inn draws the
     * share of one more visitor ({@code nextDouble}, only when there is a fraction), and per visitor the days it stays
     * ({@code nextInt}).
     *
     * @param random the scripted source, or {@code null} for the level's own
     */
    public static void script(@Nullable RandomSource random) {
        scripted = random;
    }

    /**
     * Replaces the question whether a Tavern Host covers a check (tests that give a Tavern Host a part-time shift).
     *
     * @param hostCover the question, or {@code null} for the shift of the Tavern Host
     */
    public static void scriptHostCover(@Nullable HostCover hostCover) {
        cover = hostCover != null ? hostCover : ClerkStorage::working;
    }

    static RandomSource random(ServerLevel level) {
        return scripted != null ? scripted : level.getRandom();
    }

    /**
     * @param settlement a settlement
     * @param pantry     a Pantry position
     * @return whether the Pantry counts as an inn: it has a guest bed and is among the first {@code maxInnsPerSettlement} Pantries
     *         of the settlement that have one
     */
    public static boolean isInn(Settlement settlement, BlockPos pantry) {
        return settlement.inn().pantries().stream().limit(CivModConfig.MAX_INNS_PER_SETTLEMENT.get()).anyMatch(pantry::equals);
    }

    /**
     * A guest bed was bound: an inn that starts now plays the checks from now on, not those of the time before it existed.
     *
     * @param level      the level
     * @param data       the dimension's settlement data
     * @param settlement the settlement of the inn
     */
    static void bedBound(ServerLevel level, SettlementData data, Settlement settlement) {
        if (settlement.inn().lastSlot() == InnState.NO_SLOT) {
            data.updateInn(settlement, inn -> inn.withLastSlot(InnSlots.latest(level.getOverworldClockTime())));
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % TICK_INTERVAL == 0) {
            // A copy of the few settlements with an inn: playing a check spawns entities, and nothing may change the list meanwhile.
            List<Settlement> withInn = SettlementData.get(level).settlements().stream()
                    .filter(settlement -> settlement.inn().lastSlot() != InnState.NO_SLOT).toList();
            withInn.forEach(settlement -> run(level, settlement));
        }
    }

    /**
     * Plays every arrival check of a settlement's inn that has not been played yet, up to now. The Pantries of its inns and every
     * settler of the settlement must be loaded: the reputation and the level cap of a visitor are read from all of them, and a
     * check never works from a part of the settlement. Otherwise the checks wait and are played when they are.
     *
     * @param level      the level
     * @param settlement the settlement
     */
    public static void run(ServerLevel level, Settlement settlement) {
        long now = InnSlots.latest(level.getOverworldClockTime());
        long last = settlement.inn().lastSlot();
        if (last == InnState.NO_SLOT || last >= now || !activeInns(settlement.inn(), settlement).stream().allMatch(level::isLoaded)
                || !settlersLoaded(level, settlement)) {
            return;
        }
        // A visitor who arrived before this window has left by now: the checks before it cannot leave anybody behind.
        long first = Math.max(last + 1, now - InnSlots.after(0, InnRules.get().maxStayDays()) + 1);
        new Pass(level, settlement).play(first, now);
    }

    /** @return whether every settler of the settlement stands in a loaded chunk, so a settler is not missing from what is counted */
    private static boolean settlersLoaded(ServerLevel level, Settlement settlement) {
        return settlement.members().stream().allMatch(member -> level.getEntity(member) instanceof Settler);
    }

    /**
     * The visitor looks at its visit now and then: a visit that is gone (it ended while the visitor was unloaded) removes the visitor
     * quietly, a settlement that is gone makes it vanish.
     *
     * @param level   the level
     * @param visitor the visitor
     */
    public static void checkVisit(ServerLevel level, Settler visitor) {
        Optional<Settlement> settlement = SettlementData.get(level).settlement(visitor.getVisitedSettlementId());
        if (settlement.isEmpty()) {
            Visitors.vanish(level, visitor);
        } else if (settlement.get().inn().visit(visitor.getUUID()).isEmpty()) {
            visitor.discard();
        }
    }

    /**
     * A settlement that has dissolved: its visitors vanish with a puff and a note, and never become homeless.
     *
     * @param level      the level
     * @param settlement the settlement that was dissolved
     */
    public static void dissolved(ServerLevel level, Settlement settlement) {
        settlement.inn().guestBeds().values().forEach(beds -> beds.forEach(bed -> SettlerBeds.releasePoi(level, bed)));
        for (UUID visitor : settlement.inn().visits().keySet()) {
            if (level.getEntity(visitor) instanceof Settler settler) {
                Visitors.vanish(level, settler);
            }
        }
    }

    /** @return the Pantries of the settlement that count as inns in this state of its inn: the first ones with a guest bed, up to the limit */
    private static List<BlockPos> activeInns(InnState inn, Settlement settlement) {
        return inn.pantries().stream().limit(CivModConfig.MAX_INNS_PER_SETTLEMENT.get())
                .filter(pantry -> ClerkStorage.pantryAt(settlement, pantry).isPresent()).toList();
    }

    /** One play of the checks between two points in time: it works on a copy of the inn state and writes it back at the end. */
    private static final class Pass {

        private final ServerLevel level;
        private final Settlement settlement;
        private final InnRules rules = InnRules.get();
        private final RandomSource random;
        private InnState state;
        /** The visitors that arrived and did not leave again in this pass: they step into the world at its end. */
        private final Set<UUID> arrived = new LinkedHashSet<>();
        /** The visits of visitors that already stood in the world and ended in this pass. */
        private final List<Visit> departed = new ArrayList<>();
        private @Nullable Double reputation;

        Pass(ServerLevel level, Settlement settlement) {
            this.level = level;
            this.settlement = settlement;
            this.random = random(level);
            this.state = settlement.inn();
        }

        void play(long first, long last) {
            fallAway(first);
            for (long slot = first; slot <= last; slot++) {
                depart(slot);
                arrive(slot);
            }
            finish(last);
        }

        /**
         * A Pantry that is gone, a guest bed that is no bed any more or one that was unbound: the visitor takes another free guest
         * bed of its Pantry, or stays until the next morning and leaves then.
         */
        private void fallAway(long slot) {
            for (BlockPos pantry : List.copyOf(state.guestBeds().keySet())) {
                if (ClerkStorage.pantryAt(settlement, pantry).isEmpty()) {
                    state.bedsOf(pantry).forEach(bed -> SettlerBeds.releasePoi(level, bed));
                    state = state.withoutPantry(pantry);
                    continue;
                }
                for (BlockPos bed : state.bedsOf(pantry)) {
                    if (level.isLoaded(bed) && SettlerBeds.head(level, bed).filter(bed::equals).isEmpty()) {
                        SettlerBeds.releasePoi(level, bed);
                        state = state.withoutBed(bed);
                    }
                }
            }
            for (Visit visit : List.copyOf(state.visits().values())) {
                if (visit.bed().isEmpty()) {
                    state = state.withVisit(freeBed(visit.pantry()).map(bed -> visit.sleepingIn(Optional.of(bed)))
                            .orElseGet(() -> visit.leavingAt(Math.min(visit.leaveSlot(), InnSlots.nextMorning(slot)))));
                }
            }
        }

        private void depart(long slot) {
            for (Visit visit : List.copyOf(state.visits().values())) {
                if (visit.leaveSlot() <= slot) {
                    state = state.withoutVisit(visit.id());
                    if (!arrived.remove(visit.id())) {
                        departed.add(visit);
                    }
                }
            }
        }

        private void arrive(long slot) {
            for (BlockPos pantry : activeInns(state, settlement)) {
                SettlementBlock block = settlement.block(pantry).orElseThrow();
                if (!cover.covers(block, InnSlots.day(slot), InnSlots.dayTime(slot))) {
                    continue;
                }
                List<BlockPos> free = freeBeds(block);
                if (free.isEmpty()) {
                    continue;
                }
                int count = Math.min(arrivals(block.level()), free.size());
                for (int i = 0; i < count; i++) {
                    int days = random.nextIntBetweenInclusive(rules.minStayDays(), rules.maxStayDays());
                    Visit visit = new Visit(UUID.randomUUID(), pantry, Optional.of(free.get(i)), InnSlots.after(slot, days));
                    state = state.withVisit(visit);
                    arrived.add(visit.id());
                }
            }
        }

        /** The whole part of the expected visitors is certain, the fraction is rolled. */
        private int arrivals(int pantryLevel) {
            double expected = rules.expectedArrivals(reputation(), pantryLevel);
            int whole = (int) expected;
            double fraction = expected - whole;
            return whole + (fraction > 0.0 && random.nextDouble() < fraction ? 1 : 0);
        }

        private double reputation() {
            if (reputation == null) {
                reputation = Reputation.of(level, settlement);
            }
            return reputation;
        }

        /** The guest beds of a Pantry that its level allows and nobody sleeps in, in binding order. */
        private List<BlockPos> freeBeds(SettlementBlock pantry) {
            return state.bedsOf(pantry.pos()).stream().limit(rules.bedCapacity(pantry.level()))
                    .filter(bed -> state.visitIn(bed).isEmpty()).toList();
        }

        private Optional<BlockPos> freeBed(BlockPos pantry) {
            return settlement.block(pantry).stream().flatMap(block -> freeBeds(block).stream()).findFirst();
        }

        private void finish(long last) {
            boolean empty = state.pantries().isEmpty() && state.visits().isEmpty();
            InnState done = state.withLastSlot(empty ? InnState.NO_SLOT : last);
            SettlementData data = SettlementData.get(level);
            data.updateInn(settlement, ignored -> done);
            departed.forEach(visit -> Visitors.leave(level, visit));
            if (arrived.isEmpty()) {
                return;
            }
            int cap = Visitors.levelCap(level, settlement);
            for (UUID id : arrived) {
                Visit visit = done.visits().get(id);
                if (!Visitors.arrive(level, settlement, visit, cap)) {
                    data.updateInn(settlement, inn -> inn.withoutVisit(id));
                }
            }
        }
    }
}
