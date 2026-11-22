package com.gilfort.civmod.inn;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Visit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * The stroll of a settler on its feet (vault {@code Systems/NPC Population.md}): vanilla's water-avoiding stroll, which a settler that
 * is no visitor keeps. A visitor strolls through the whole settlement inside the border, hop by hop like a homeless settler, and
 * with a light pull to the Pantry: a share of its hops ({@link #PULL_CHANCE}) heads for the Pantry, the others go anywhere. A hop
 * that would end outside the border is dropped. In the evening, and while the visitor walks to its meal or bed or sleeps, the
 * stroll gives way ({@link VisitorDay}); the evening stroll is vanilla's, held around the Pantry by the visitor's home.
 */
public final class VisitorStroll extends WaterAvoidingRandomStrollGoal {

    /** Share of the hops of a visitor that head for the Pantry. */
    static final double PULL_CHANCE = 0.25;
    /** The reach of one hop in blocks, like vanilla's stroll. */
    private static final int HORIZONTAL_DISTANCE = 10;
    private static final int VERTICAL_DISTANCE = 7;
    /** Tries to find a hop inside the border before the visitor lets this stroll go. */
    private static final int ATTEMPTS = 5;

    private static @Nullable RandomSource scripted;

    private final Settler settler;

    /**
     * Replaces the random source of the pull to the Pantry (tests). It is not the source of {@link Inns#script}, so a stroll never
     * eats up the rolls that script an arrival or a request to stay longer.
     *
     * @param random the scripted source ({@code nextDouble} answers whether a hop is pulled), or {@code null} for the level's own
     */
    public static void script(@Nullable RandomSource random) {
        scripted = random;
    }

    private static RandomSource random(ServerLevel level) {
        return scripted != null ? scripted : level.getRandom();
    }

    /**
     * @param settler       the settler that strolls
     * @param speedModifier the walking speed
     */
    public VisitorStroll(Settler settler, double speedModifier) {
        super(settler, speedModifier);
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        return (!settler.isVisitor() || settler.visitorDay().mayStroll()) && super.canUse();
    }

    @Override
    protected @Nullable Vec3 getPosition() {
        if (!settler.isVisitor() || settler.visitorDay().isEvening()) {
            return super.getPosition();
        }
        return hop(settler).orElse(null);
    }

    /**
     * One hop of a visitor's stroll in the daytime.
     *
     * @param visitor the visitor
     * @return where it strolls to next: inside the border of its settlement, towards the Pantry if the pull rolled; empty if no hop
     *         inside the border was found
     */
    public static Optional<Vec3> hop(Settler visitor) {
        if (!(visitor.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        Optional<Settlement> settlement = SettlementData.get(level).settlement(visitor.getVisitedSettlementId());
        Optional<Visit> visit = settlement.flatMap(found -> found.inn().visit(visitor.getUUID()));
        if (visit.isEmpty()) {
            return Optional.empty();
        }
        boolean pull = random(level).nextDouble() < PULL_CHANCE;
        Vec3 pantry = Vec3.atCenterOf(visit.get().pantry());
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Vec3 spot = pull ? LandRandomPos.getPosTowards(visitor, HORIZONTAL_DISTANCE, VERTICAL_DISTANCE, pantry)
                    : LandRandomPos.getPos(visitor, HORIZONTAL_DISTANCE, VERTICAL_DISTANCE);
            if (spot != null && JobAssignments.insideBorder(settlement.get(), BlockPos.containing(spot))) {
                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }
}
