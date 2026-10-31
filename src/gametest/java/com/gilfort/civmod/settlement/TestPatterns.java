package com.gilfort.civmod.settlement;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.logistics.Pattern;

import net.minecraft.core.BlockPos;

/**
 * GameTest access to a JobBlock's pattern library, for patterns saved by an older version (game code makes patterns
 * through {@link Patterns}).
 */
public final class TestPatterns {

    private TestPatterns() {
    }

    /** Puts a pattern into a Job's library as it was saved, without resolving it again. */
    public static void put(SettlementData data, BlockPos jobBlock, Job job, Pattern pattern) {
        SettlementBlock block = data.settlementAt(jobBlock).flatMap(settlement -> settlement.block(jobBlock)).orElseThrow();
        block.library(job).mutablePatterns().removeIf(existing -> existing.slot() == pattern.slot());
        block.library(job).mutablePatterns().add(pattern);
        block.library(job).mutablePatterns().sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        data.setDirty();
    }
}
