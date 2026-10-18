package com.gilfort.civmod.settlement;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;

/**
 * Warehouses, Served Clerks and Home Clerks (vault {@code Jobs/Deliveryman.md}). A Clerk JobBlock with Deliverymen is
 * a Warehouse; with the scroll it links other Clerk JobBlocks of its settlement as Served Clerks (2 / 4 / 6 / 8 / 12 by
 * level, extra ones kept but inactive after a downgrade). Every JobBlock belongs to its Home Clerk, the nearest Clerk
 * JobBlock of its settlement; the Warehouse serving that Clerk handles the JobBlock's transports.
 * <p>
 * Pure logic on {@link SettlementData}, testable without a world.
 */
public final class Warehouses {

    private Warehouses() {
    }

    /** Result of linking a Clerk JobBlock to a Warehouse with the scroll. */
    public enum LinkResult {
        /** Linked as Served Clerk. */
        LINKED,
        /** It was a Served Clerk of this Warehouse and is unlinked. */
        UNLINKED,
        /** The bound block or the target is not a registered Clerk JobBlock of the same settlement. */
        NOT_A_CLERK,
        /** The target is the Warehouse itself. */
        SELF,
        /** The target has own Deliverymen: it serves itself. */
        OWN_DELIVERYMEN,
        /** The target is served by another Warehouse. */
        TAKEN,
        /** The target serves other Clerks, or the bound block is a Served Clerk itself (no chains of Warehouses). */
        CHAIN,
        /** The Warehouse links as many Served Clerks as its level allows. */
        FULL
    }

    /**
     * @param block a JobBlock
     * @return whether it is a Clerk JobBlock
     */
    static boolean isClerk(SettlementBlock block) {
        return block.jobType() == ModJobs.CLERK.get();
    }

    /**
     * @param clerk a Clerk JobBlock
     * @return whether it has own Deliverymen (it is a Warehouse and serves itself)
     */
    public static boolean hasDeliverymen(SettlementBlock clerk) {
        return isClerk(clerk) && !JobPlaces.holders(clerk, ModJobs.DELIVERYMAN.get()).isEmpty();
    }

    /**
     * @param warehouse a Warehouse
     * @return its Served Clerks in use: as many as its level allows, the first linked ones
     */
    public static List<BlockPos> activeServed(SettlementBlock warehouse) {
        List<BlockPos> served = warehouse.served();
        return served.subList(0, Math.min(served.size(), SettlementRules.servedClerks(warehouse.level())));
    }

    /**
     * @param settlement a settlement
     * @param clerk      a Clerk JobBlock
     * @return the Warehouse that links it as Served Clerk (active or not)
     */
    public static Optional<SettlementBlock> servedBy(Settlement settlement, BlockPos clerk) {
        return settlement.blocks().stream().filter(block -> block.served().contains(clerk)).findFirst();
    }

    /**
     * The Warehouse whose Deliverymen handle the transports of a Clerk: the Clerk itself if it has own Deliverymen or
     * serves other Clerks, else the Warehouse that links it as an active Served Clerk.
     *
     * @param settlement a settlement
     * @param clerk      a Clerk JobBlock
     * @return the Warehouse, empty if none serves the Clerk ("No Deliveryman")
     */
    public static Optional<SettlementBlock> warehouseOf(Settlement settlement, BlockPos clerk) {
        Optional<SettlementBlock> self = settlement.block(clerk).filter(Warehouses::isClerk);
        if (self.isEmpty()) {
            return Optional.empty();
        }
        if (hasDeliverymen(self.get()) || !self.get().served().isEmpty()) {
            return self;
        }
        return servedBy(settlement, clerk).filter(warehouse -> activeServed(warehouse).contains(clerk));
    }

    /**
     * @param settlement a settlement
     * @param warehouse  a Warehouse
     * @return the Clerk JobBlocks whose transports it handles: itself and its active Served Clerks without own
     *         Deliverymen
     */
    public static List<BlockPos> handled(Settlement settlement, SettlementBlock warehouse) {
        return settlement.blocks().stream().filter(Warehouses::isClerk)
                .filter(clerk -> warehouseOf(settlement, clerk.pos()).filter(warehouse::equals).isPresent())
                .map(SettlementBlock::pos).toList();
    }

    /**
     * @param settlement a settlement
     * @param pos        a position
     * @return the nearest Clerk JobBlock of the settlement (a Clerk JobBlock itself is its own)
     */
    static Optional<BlockPos> nearestClerk(Settlement settlement, BlockPos pos) {
        return settlement.blocks().stream().filter(Warehouses::isClerk).map(SettlementBlock::pos)
                .min(Comparator.comparingDouble(clerk -> clerk.distSqr(pos)));
    }

    /**
     * Stores the Home Clerk of every JobBlock of a settlement (a Clerk JobBlock was placed or removed).
     *
     * @param settlement the settlement
     */
    static void assignHomeClerks(Settlement settlement) {
        settlement.blocks().forEach(block -> assignHomeClerk(settlement, block));
    }

    /**
     * Stores the Home Clerk of one JobBlock (it was added).
     *
     * @param settlement its settlement
     * @param block      the JobBlock
     */
    static void assignHomeClerk(Settlement settlement, SettlementBlock block) {
        block.setHomeClerk(nearestClerk(settlement, block.pos()));
    }

    /**
     * Links or unlinks a Clerk JobBlock as Served Clerk of a Warehouse (vault {@code Systems/Scroll of Assignment.md}).
     *
     * @param data      the dimension's settlement data
     * @param warehouse the Clerk JobBlock the scroll is bound to
     * @param target    the clicked Clerk JobBlock
     * @return the result
     */
    public static LinkResult link(SettlementData data, BlockPos warehouse, BlockPos target) {
        Optional<JobBlockSite> site = JobBlockSite.at(data, warehouse).filter(found -> isClerk(found.block()));
        Settlement settlement = site.map(JobBlockSite::settlement).orElse(null);
        Optional<SettlementBlock> clerk = settlement == null ? Optional.empty() : settlement.block(target).filter(Warehouses::isClerk);
        if (site.isEmpty() || clerk.isEmpty()) {
            return LinkResult.NOT_A_CLERK;
        }
        SettlementBlock block = site.get().block();
        if (target.equals(warehouse)) {
            return LinkResult.SELF;
        }
        if (block.mutableServed().remove(target)) {
            data.changed();
            return LinkResult.UNLINKED;
        }
        if (hasDeliverymen(clerk.get())) {
            return LinkResult.OWN_DELIVERYMEN;
        }
        if (servedBy(settlement, target).isPresent()) {
            return LinkResult.TAKEN;
        }
        if (!clerk.get().served().isEmpty() || servedBy(settlement, warehouse).isPresent()) {
            return LinkResult.CHAIN;
        }
        if (block.served().size() >= SettlementRules.servedClerks(block.level())) {
            return LinkResult.FULL;
        }
        block.mutableServed().add(target.immutable());
        data.changed();
        return LinkResult.LINKED;
    }
}
