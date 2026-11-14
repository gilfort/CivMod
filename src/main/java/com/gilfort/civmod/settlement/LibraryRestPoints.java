package com.gilfort.civmod.settlement;

import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.RestPoints;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The {@link RestPoints} of a Job at its JobBlock (vault {@code Jobs/Composter.md}): they are read from and written to
 * the JobBlock's library of the Job and saved with it. A JobBlock that is gone keeps nothing.
 */
@ApiStatus.Internal
public final class LibraryRestPoints implements RestPoints {

    private final ServerLevel level;
    private final BlockPos pos;
    private final Job job;

    /**
     * @param level the level
     * @param pos   the JobBlock
     * @param job   the Producing Job whose library keeps the points
     */
    public LibraryRestPoints(ServerLevel level, BlockPos pos, Job job) {
        this.level = level;
        this.pos = pos;
        this.job = job;
    }

    @Override
    public int get() {
        return block(SettlementData.get(level)).map(block -> block.restPoints(job)).orElse(0);
    }

    @Override
    public void set(int points) {
        SettlementData data = SettlementData.get(level);
        block(data).map(block -> block.library(job)).filter(library -> library.restPoints() != points).ifPresent(library -> {
            library.setRestPoints(points);
            data.setDirty();
        });
    }

    private Optional<SettlementBlock> block(SettlementData data) {
        return data.settlementAt(pos).flatMap(settlement -> settlement.block(pos));
    }
}
