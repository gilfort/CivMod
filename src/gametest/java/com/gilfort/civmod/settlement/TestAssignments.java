package com.gilfort.civmod.settlement;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.gilfort.civmod.api.Job;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * GameTest access to the JobBlock side of {@link JobAssignments}, for tests that build settlement data without settler
 * entities. Game code goes through {@link SettlementWorld}, the one owner of assignments.
 */
public final class TestAssignments {

    private TestAssignments() {
    }

    /** {@link JobAssignments#assign(SettlementData, BlockPos, UUID, String, int, Collection)} */
    public static JobAssignments.AssignResult assign(SettlementData data, BlockPos jobBlock, UUID settler, String settlerName, int settlementId,
            Collection<Job> currentJobs) {
        return JobAssignments.assign(data, jobBlock, settler, settlerName, settlementId, currentJobs);
    }

    /** {@link JobAssignments#assign(SettlementData, BlockPos, UUID, String, int, Collection, Job)} */
    public static JobAssignments.AssignResult assign(SettlementData data, BlockPos jobBlock, UUID settler, String settlerName, int settlementId,
            Collection<Job> currentJobs, Job job) {
        return JobAssignments.assign(data, jobBlock, settler, settlerName, settlementId, currentJobs, job);
    }

    /** {@link JobAssignments#switchJob(SettlementData, BlockPos, UUID, Collection, Job)} */
    public static boolean switchJob(SettlementData data, BlockPos jobBlock, UUID settler, Collection<Job> otherJobs, Job job) {
        return JobAssignments.switchJob(data, jobBlock, settler, otherJobs, job);
    }

    /** Fills a Station Types slot directly, without the GUI ({@link StationTypes#put} needs a JobBlock in the world). */
    public static void setStationType(SettlementData data, BlockPos jobBlock, int slot, Item item) {
        JobBlockSite.blockAt(data, jobBlock).orElseThrow().setStationType(slot, item);
    }

    /** {@link JobAssignments#unassign(SettlementData, BlockPos)} */
    public static List<UUID> unassign(SettlementData data, BlockPos jobBlock) {
        return JobAssignments.unassign(data, jobBlock);
    }

    /** {@link JobAssignments#unassign(SettlementData, BlockPos, UUID)} */
    public static boolean unassign(SettlementData data, BlockPos jobBlock, UUID settler) {
        return JobAssignments.unassign(data, jobBlock, settler);
    }
}
