package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
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
     * @param marked  the new mark (unmarking also removes an assignment and its lock)
     * @return whether the entry was set
     */
    public static boolean setMarked(SettlementData data, BlockPos board, int index, Identifier mission, boolean marked) {
        return update(data, board, index, mission, entry -> Optional.of(entry.withMarked(marked)));
    }

    /**
     * Takes an offered mission off the board (a settler reserved it). Like {@link #setMarked}, nothing happens if the
     * entry at that index is no longer that mission.
     *
     * @param data    the dimension's settlement data
     * @param board   the Mission Board position
     * @param index   the entry index
     * @param mission the mission expected at that index
     * @return whether the entry was taken
     */
    public static boolean take(SettlementData data, BlockPos board, int index, Identifier mission) {
        Optional<SettlementBlock> block = missionBoard(data, board);
        if (block.isEmpty() || index < 0 || index >= block.get().missionOffer().entries().size()) {
            return false;
        }
        MissionOffer offer = block.get().missionOffer();
        if (!offer.entries().get(index).mission().equals(mission)) {
            return false;
        }
        List<MissionOffer.Entry> entries = new ArrayList<>(offer.entries());
        entries.remove(index);
        block.get().setMissionOffer(new MissionOffer(entries, offer.day()));
        data.setDirty();
        return true;
    }

    /**
     * Assigns a marked mission to one settler of the board; it is then locked for every other settler. Like
     * {@link #setMarked}, nothing happens if the entry at that index is no longer that mission.
     *
     * @param data       the dimension's settlement data
     * @param board      the Mission Board position
     * @param index      the entry index
     * @param mission    the mission expected at that index
     * @param settler    the settler
     * @param missionJob the settler's Mission Job
     * @param pool       all missions by id
     * @return whether the mission was assigned: the entry is marked, the settler works this board and the mission
     *         allows its Mission Job
     */
    public static boolean assign(SettlementData data, BlockPos board, int index, Identifier mission, UUID settler, Job missionJob,
            Map<Identifier, Mission> pool) {
        Mission found = pool.get(mission);
        if (found == null || !found.jobs().contains(missionJob)
                || missionBoard(data, board).filter(block -> block.isAssignee(settler)).isEmpty()) {
            return false;
        }
        return update(data, board, index, mission, entry -> entry.marked() ? Optional.of(entry.withAssignee(Optional.of(settler))) : Optional.empty());
    }

    /**
     * Revokes the assignment of a mission (or the lock left by a settler that died or left the board): every settler
     * may take it again.
     *
     * @param data    the dimension's settlement data
     * @param board   the Mission Board position
     * @param index   the entry index
     * @param mission the mission expected at that index
     * @return whether the entry was unlocked
     */
    public static boolean unlock(SettlementData data, BlockPos board, int index, Identifier mission) {
        return update(data, board, index, mission, entry -> Optional.of(entry.withAssignee(Optional.empty())));
    }

    /**
     * A settler left the board or died: its assignments are removed, the missions stay marked and locked until the
     * player assigns them again or unlocks them.
     *
     * @param data    the dimension's settlement data
     * @param board   the Mission Board position
     * @param settler the settler
     */
    static void removeAssignee(SettlementData data, BlockPos board, UUID settler) {
        Optional<SettlementBlock> block = missionBoard(data, board);
        Predicate<MissionOffer.Entry> affected = entry -> entry.assignee().filter(settler::equals).isPresent();
        if (block.isEmpty() || block.get().missionOffer().entries().stream().noneMatch(affected)) {
            return;
        }
        MissionOffer offer = block.get().missionOffer();
        List<MissionOffer.Entry> entries = offer.entries().stream().map(entry -> affected.test(entry) ? entry.withoutAssignee() : entry).toList();
        block.get().setMissionOffer(new MissionOffer(entries, offer.day()));
        data.setDirty();
    }

    /**
     * Puts a reserved mission back on the board as a marked entry, after the other marked ones.
     *
     * @param data     the dimension's settlement data
     * @param board    the Mission Board position
     * @param mission  the mission
     * @param assignee the settler it stays assigned to
     * @param locked   whether it stays locked (an assigned mission whose settler left the board)
     */
    public static void giveBack(SettlementData data, BlockPos board, Identifier mission, Optional<UUID> assignee, boolean locked) {
        Optional<SettlementBlock> block = missionBoard(data, board);
        if (block.isEmpty()) {
            return;
        }
        MissionOffer offer = block.get().missionOffer();
        List<MissionOffer.Entry> entries = new ArrayList<>(offer.entries());
        int index = 0;
        while (index < entries.size() && entries.get(index).marked()) {
            index++;
        }
        entries.add(index, new MissionOffer.Entry(mission, true, assignee, locked));
        block.get().setMissionOffer(new MissionOffer(entries, offer.day()));
        data.setDirty();
    }

    /**
     * Replaces one entry; nothing happens if the entry at that index is no longer the expected mission or the change
     * is refused (empty).
     */
    private static boolean update(SettlementData data, BlockPos board, int index, Identifier mission,
            Function<MissionOffer.Entry, Optional<MissionOffer.Entry>> change) {
        Optional<SettlementBlock> block = missionBoard(data, board);
        if (block.isEmpty() || index < 0 || index >= block.get().missionOffer().entries().size()) {
            return false;
        }
        MissionOffer offer = block.get().missionOffer();
        if (!offer.entries().get(index).mission().equals(mission)) {
            return false;
        }
        Optional<MissionOffer.Entry> changed = change.apply(offer.entries().get(index));
        if (changed.isEmpty()) {
            return false;
        }
        List<MissionOffer.Entry> entries = new ArrayList<>(offer.entries());
        entries.set(index, changed.get());
        block.get().setMissionOffer(new MissionOffer(entries, offer.day()));
        data.setDirty();
        return true;
    }

    private static Optional<SettlementBlock> missionBoard(SettlementData data, BlockPos board) {
        return JobBlockSite.blockAt(data, board).filter(block -> block.jobType().category() == JobCategory.MISSION);
    }
}
