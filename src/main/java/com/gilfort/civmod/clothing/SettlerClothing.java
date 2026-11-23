package com.gilfort.civmod.clothing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a settler wears (vault {@code Systems/Needs.md}, decisions of 2026-11-21): the four armour slots of its inventory read as
 * clothing, putting pieces on, the daily wear and the questions the clothing need and the supply ask ({@link #pieces},
 * {@link #wants}). The pieces sit in the settler's inventory ({@link Settler#ARMOR_START}), so a piece that wears out or goes back
 * to the collect inventory is changed in one place.
 * <p>
 * Wear is one step per day: {@link #step} applies it at the first check of a day, in live play and in catch-up alike.
 */
public final class SettlerClothing {

    /** The collect inventory is searched at most once per in-game hour (ticks). */
    private static final long SEARCH_INTERVAL_TICKS = 1000L;
    /** Marks "no day". */
    private static final long NO_DAY = Long.MIN_VALUE;

    /** How one slot stands for a tier. */
    public enum State {
        /** A piece of the needed rank or a higher one is worn and in good shape. */
        MET,
        /** A piece of the needed rank or higher is worn but below {@value ClothingRules#WORN_OUT_PERCENT} %: it still counts. */
        WORN_OUT,
        /** The slot is empty or holds a piece that is not clothing of the needed rank. */
        MISSING
    }

    /**
     * One slot as the need sees it.
     *
     * @param slot  the slot
     * @param stack what is worn there (live, empty for none)
     * @param state how the slot stands
     */
    public record Piece(EquipmentSlot slot, ItemStack stack, State state) {
    }

    /**
     * A piece the settler wants, to ask for it.
     *
     * @param slot    the slot
     * @param minRank the lowest rank the replacement may have: the rank the tier needs, or that of the worn-out piece if higher
     */
    public record Want(EquipmentSlot slot, int minRank) {

        /**
         * @return the tag the wanted piece comes from
         */
        public TagKey<Item> tag() {
            return ClothingRules.tagOfRank(minRank);
        }
    }

    /**
     * The days wear is counted by, saved with the settler.
     *
     * @param checkedDay  the day wear was last applied up to, {@link #NO_DAY} before the first check
     * @param missionFrom the first day of the last mission, {@link #NO_DAY} for none
     * @param missionTo   the last day of the last mission
     */
    private record WearDays(long checkedDay, long missionFrom, long missionTo) {

        static final WearDays START = new WearDays(NO_DAY, NO_DAY, NO_DAY);

        static final Codec<WearDays> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("checked_day", NO_DAY).forGetter(WearDays::checkedDay),
                Codec.LONG.optionalFieldOf("mission_from", NO_DAY).forGetter(WearDays::missionFrom),
                Codec.LONG.optionalFieldOf("mission_to", NO_DAY).forGetter(WearDays::missionTo)
        ).apply(instance, WearDays::new));

        /**
         * @param first the first day
         * @param last  the last day
         * @return how many days of {@code first..last} the last mission covers
         */
        long missionDaysIn(long first, long last) {
            if (missionFrom == NO_DAY) {
                return 0;
            }
            return Math.max(0, Math.min(missionTo, last) - Math.max(missionFrom, first) + 1);
        }
    }

    private final Settler settler;
    private WearDays days = WearDays.START;
    /** The game time the collect inventory may be searched again (not saved). */
    private long searchAfter = Long.MIN_VALUE;

    /**
     * @param settler the settler that wears the clothing
     */
    public SettlerClothing(Settler settler) {
        this.settler = settler;
    }

    /**
     * @param slot a slot
     * @return what the settler wears there, empty for none
     */
    public ItemStack worn(EquipmentSlot slot) {
        return settler.getInventory().getItem(Settler.ARMOR_START + ClothingRules.SLOTS.indexOf(slot));
    }

    private void put(EquipmentSlot slot, ItemStack stack) {
        settler.getInventory().setItem(Settler.ARMOR_START + ClothingRules.SLOTS.indexOf(slot), stack);
    }

    /**
     * Server: lets the settler's body show what it wears. The worn pieces are copied into the entity's own equipment slots, which
     * vanilla sends to every player that tracks the settler and the armour layer of the renderer reads, so no packet of our own is
     * needed. Only Human settlers show clothes; another race keeps wearing them unseen until it gets a look of its own. Called when the
     * inventory changes, when the race changes and after loading; it changes nothing for a slot that already shows the right piece.
     */
    public void showWorn() {
        if (settler.level().isClientSide()) {
            return;
        }
        boolean visible = Races.HUMAN.equals(settler.getRace());
        for (EquipmentSlot slot : ClothingRules.SLOTS) {
            ItemStack wanted = visible ? worn(slot) : ItemStack.EMPTY;
            if (!ItemStack.matches(settler.getItemBySlot(slot), wanted)) {
                settler.setItemSlot(slot, wanted.copy());
            }
        }
    }

    /**
     * @param tier a need tier
     * @return the four slots as that tier's need sees them, in the order of {@link ClothingRules#SLOTS}
     */
    public List<Piece> pieces(int tier) {
        int required = ClothingRules.requiredRank(tier);
        List<Piece> pieces = new ArrayList<>(ClothingRules.SLOTS.size());
        for (EquipmentSlot slot : ClothingRules.SLOTS) {
            ItemStack stack = worn(slot);
            State state = stack.isEmpty() || ClothingRules.rank(stack) < Math.max(required, 1) ? State.MISSING
                    : ClothingRules.isWornOut(stack) ? State.WORN_OUT : State.MET;
            pieces.add(new Piece(slot, stack, state));
        }
        return pieces;
    }

    /**
     * @param tier a need tier
     * @return whether every slot holds a piece of that tier's rank or a higher one; a worn-out piece counts
     */
    public boolean meets(int tier) {
        return pieces(tier).stream().noneMatch(piece -> piece.state() == State.MISSING);
    }

    /**
     * The pieces to ask for: one per slot that is empty or holds too low a rank, and one per worn-out piece, whose replacement must
     * be of the same or a higher rank. Nothing at tier 1.
     *
     * @param tier a need tier
     * @return what the settler wants for that tier, in the order of {@link ClothingRules#SLOTS}
     */
    public List<Want> wants(int tier) {
        int required = ClothingRules.requiredRank(tier);
        if (required == ClothingRules.NO_RANK) {
            return List.of();
        }
        List<Want> wants = new ArrayList<>();
        for (Piece piece : pieces(tier)) {
            switch (piece.state()) {
                case MISSING -> wants.add(new Want(piece.slot(), required));
                case WORN_OUT -> wants.add(new Want(piece.slot(), Math.max(required, ClothingRules.rank(piece.stack()))));
                case MET -> { }
            }
        }
        return wants;
    }

    /**
     * Puts a piece on by hand (a player's right-click): it goes into its slot whatever is worn there.
     *
     * @param piece the piece to put on (it is copied)
     * @return the piece that was worn there, empty for none; no value if the stack is not a piece of clothing
     */
    public Optional<ItemStack> equip(ItemStack piece) {
        Optional<EquipmentSlot> slot = ClothingRules.slotOf(piece).filter(found -> ClothingRules.rank(piece) != ClothingRules.NO_RANK);
        if (slot.isEmpty()) {
            return Optional.empty();
        }
        ItemStack old = worn(slot.get());
        put(slot.get(), piece.copy());
        return Optional.of(old);
    }

    /**
     * Puts on the best fitting piece of the stock for every slot where one may replace the worn piece
     * ({@link ClothingRules#replaces}). A replaced piece goes into the stock; when it does not fit there, nothing changes for that slot.
     *
     * @param stock what the settler reaches at its collect inventory
     * @return whether it put anything on
     */
    public boolean dress(Stock stock) {
        boolean dressed = false;
        for (EquipmentSlot slot : ClothingRules.SLOTS) {
            dressed |= dress(stock, slot);
        }
        return dressed;
    }

    private boolean dress(Stock stock, EquipmentSlot slot) {
        ItemStack old = worn(slot);
        List<ItemStack> contents = stock.contents();
        int best = -1;
        for (int i = 0; i < contents.size(); i++) {
            ItemStack candidate = contents.get(i);
            if (!candidate.isEmpty() && ClothingRules.slotOf(candidate).filter(slot::equals).isPresent() && ClothingRules.replaces(candidate, old)
                    && (best < 0 || better(candidate, contents.get(best)))) {
                best = i;
            }
        }
        if (best < 0) {
            return false;
        }
        ItemStack taken = stock.takeOne(best);
        if (taken.isEmpty()) {
            return false;
        }
        if (!old.isEmpty() && !stock.insert(old.copy()).isEmpty()) {
            // The collect inventory has no room for the old piece: keep everything as it was.
            stock.insert(taken);
            return false;
        }
        put(slot, taken);
        return true;
    }

    /** The higher rank first, then the one with more durability left. */
    private static boolean better(ItemStack candidate, ItemStack other) {
        int byRank = Integer.compare(ClothingRules.rank(candidate), ClothingRules.rank(other));
        return byRank != 0 ? byRank > 0 : ClothingRules.durability(candidate) > ClothingRules.durability(other);
    }

    /**
     * The clothing step of the need check, in live play and in catch-up: wear of the days that passed, then putting on what the
     * collect inventories of the settler's Jobs hold (at most once an hour, and only while the settler wants something).
     *
     * @param level   the level
     * @param day     the day of the check
     * @param dayTime the day time of the check
     * @param tier    the settler's need tier
     */
    public void step(ServerLevel level, long day, long dayTime, int tier) {
        wearDays(day);
        long time = day * DayClock.DAY_TICKS + dayTime;
        if (time < searchAfter || wants(tier).isEmpty()) {
            return;
        }
        boolean dressed = false;
        for (BlockPos collect : collectInventories(level)) {
            dressed |= dress(ClerkStorage.stock(level, collect, dayTime, true));
        }
        if (!dressed) {
            // Nothing found: the next search waits an hour. A search that found something runs again at the next check.
            searchAfter = time + SEARCH_INTERVAL_TICKS;
        }
    }

    private List<BlockPos> collectInventories(ServerLevel level) {
        List<BlockPos> positions = new ArrayList<>();
        for (SettlerJob job : settler.getJobs()) {
            JobBlockSite.at(level, job.jobBlock()).map(JobBlockSite::collect).filter(pos -> !positions.contains(pos)).ifPresent(positions::add);
        }
        return positions;
    }

    /**
     * Wear for the days since the last check, one step per day: the worn pieces lose their share of the durability, a piece that
     * reaches 0 is gone. The first check of a settler only sets the day.
     *
     * @param day the day of the check
     */
    private void wearDays(long day) {
        if (days.checkedDay() == NO_DAY) {
            days = new WearDays(day, days.missionFrom(), days.missionTo());
            return;
        }
        if (day <= days.checkedDay()) {
            return;
        }
        long passed = day - days.checkedDay();
        long missionDays = days.missionDaysIn(days.checkedDay(), day - 1);
        wear(passed - missionDays, missionDays);
        days = new WearDays(day, days.missionFrom(), days.missionTo());
    }

    private void wear(long ordinaryDays, long missionDays) {
        for (EquipmentSlot slot : ClothingRules.SLOTS) {
            ItemStack piece = worn(slot);
            if (piece.isEmpty() || !piece.isDamageableItem()) {
                continue;
            }
            long damage = ordinaryDays * damagePerDay(piece, CivModConfig.CLOTHING_WEAR_PERCENT.get())
                    + missionDays * damagePerDay(piece, CivModConfig.CLOTHING_MISSION_WEAR_PERCENT.get());
            long total = piece.getDamageValue() + damage;
            if (total >= piece.getMaxDamage()) {
                put(slot, ItemStack.EMPTY);
            } else {
                piece.setDamageValue((int) total);
            }
        }
    }

    /** One day's wear in durability points: the share of the maximum, rounded, at least 1 while the share is above 0. */
    private static long damagePerDay(ItemStack piece, double percent) {
        return percent <= 0.0 ? 0 : Math.max(1L, Math.round(piece.getMaxDamage() * percent / 100.0));
    }

    /**
     * Records the days of a mission the settler leaves for: they wear at the mission rate.
     *
     * @param firstDay the day it leaves
     * @param lastDay  the day of its return
     */
    public void onMission(long firstDay, long lastDay) {
        days = new WearDays(days.checkedDay(), firstDay, lastDay);
    }

    /**
     * @param output the settler's save output
     */
    public void save(ValueOutput output) {
        output.store("clothing", WearDays.CODEC, days);
    }

    /**
     * @param input the settler's save input
     */
    public void load(ValueInput input) {
        days = input.read("clothing", WearDays.CODEC).orElse(WearDays.START);
    }
}
