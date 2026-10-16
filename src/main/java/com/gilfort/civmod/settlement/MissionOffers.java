package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.job.JobCategory;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * The daily mission offer of each Mission Board (vault {@code Systems/Mission Jobs.md}), kept in its
 * {@link SettlementBlock}. The offer is refilled on the first access of a new day, so idle boards cost nothing.
 */
public final class MissionOffers {

    private MissionOffers() {
    }

    /**
     * Refills the board's offer once per day ({@link MissionRules#refill}): marked entries stay, unmarked ones are
     * replaced; the size follows the board level at the time of the refill.
     *
     * @param data   the dimension's settlement data
     * @param board  the Mission Board position
     * @param day    the current day
     * @param pool   all missions by id
     * @param random the random source
     * @return the current offer, empty if there is no Mission Board at that position
     */
    public static List<MissionOffer.Entry> refresh(SettlementData data, BlockPos board, long day, Map<Identifier, Mission> pool,
            RandomSource random) {
        Optional<SettlementBlock> block = missionBoard(data, board);
        if (block.isEmpty()) {
            return List.of();
        }
        SettlementBlock entry = block.get();
        if (entry.missionOffer().day() != day) {
            List<MissionOffer.Entry> entries = MissionRules.refill(entry.missionOffer().entries(), pool,
                    entry.jobType().offeredAt(entry.level()), MissionRules.offerSize(entry.level()), random);
            entry.setMissionOffer(new MissionOffer(entries, day));
            data.setDirty();
        }
        return entry.missionOffer().entries();
    }

    /**
     * Marks or unmarks an offered mission. The caller names the mission it saw at that index; if the offer changed
     * since (a refill, another player), nothing happens, so a stale GUI never changes another entry.
     *
     * @param data    the dimension's settlement data
     * @param board   the Mission Board position
     * @param index   the entry index
     * @param mission the mission expected at that index
     * @param marked  the new mark
     * @return whether the entry was set
     */
    public static boolean setMarked(SettlementData data, BlockPos board, int index, Identifier mission, boolean marked) {
        Optional<SettlementBlock> block = missionBoard(data, board);
        if (block.isEmpty() || index < 0 || index >= block.get().missionOffer().entries().size()) {
            return false;
        }
        MissionOffer offer = block.get().missionOffer();
        if (!offer.entries().get(index).mission().equals(mission)) {
            return false;
        }
        List<MissionOffer.Entry> entries = new ArrayList<>(offer.entries());
        entries.set(index, entries.get(index).withMarked(marked));
        block.get().setMissionOffer(new MissionOffer(entries, offer.day()));
        data.setDirty();
        return true;
    }

    private static Optional<SettlementBlock> missionBoard(SettlementData data, BlockPos board) {
        return data.settlementAt(board).flatMap(s -> s.block(board)).filter(block -> block.jobType().category() == JobCategory.MISSION);
    }
}
