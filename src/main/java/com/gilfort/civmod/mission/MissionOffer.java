package com.gilfort.civmod.mission;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * The daily mission offer of one Mission Board (vault {@code Systems/Mission Jobs.md}).
 *
 * @param entries the offered missions (a refill puts the marked ones first)
 * @param day     the day of the last refill, {@code -1} before the first one
 * @param auto    the missions the player ticked for auto-enable: a refill marks their new entries at once
 */
public record MissionOffer(List<Entry> entries, long day, Set<Identifier> auto) {

    /** An offer that was never filled. */
    public static final MissionOffer EMPTY = new MissionOffer(List.of(), -1L, Set.of());

    /**
     * One offered mission. The same mission may be offered several times (up to its {@link Mission#maxPerOffer()}).
     * The player may assign a marked mission to one settler of the board; it is then locked for every other settler.
     * The lock stays when the settler dies or leaves the board (the assignee is cleared) until the player assigns the
     * mission again or unlocks it.
     *
     * @param mission  the mission id
     * @param marked   whether the player marked it to be done
     * @param assignee the settler the player assigned it to
     * @param locked   whether only the assignee may take it (always set while there is an assignee)
     */
    public record Entry(Identifier mission, boolean marked, Optional<UUID> assignee, boolean locked) {

        /** Save codec. */
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("mission").forGetter(Entry::mission),
                Codec.BOOL.optionalFieldOf("marked", false).forGetter(Entry::marked),
                UUIDUtil.CODEC.optionalFieldOf("assignee").forGetter(Entry::assignee),
                Codec.BOOL.optionalFieldOf("locked", false).forGetter(Entry::locked)
        ).apply(instance, Entry::new));

        /** Network codec. */
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Entry::mission,
                ByteBufCodecs.BOOL, Entry::marked,
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), Entry::assignee,
                ByteBufCodecs.BOOL, Entry::locked,
                Entry::new);

        /**
         * @param mission  the mission id
         * @param marked   whether the player marked it to be done
         * @param assignee the settler the player assigned it to
         * @param locked   whether only the assignee may take it
         */
        public Entry {
            locked = locked || assignee.isPresent();
        }

        /**
         * An entry nobody is assigned to.
         *
         * @param mission the mission id
         * @param marked  whether the player marked it to be done
         */
        public Entry(Identifier mission, boolean marked) {
            this(mission, marked, Optional.empty(), false);
        }

        /**
         * @param marked the new mark
         * @return this entry with that mark; an unmarked entry is neither assigned nor locked
         */
        public Entry withMarked(boolean marked) {
            return marked ? new Entry(mission, true, assignee, locked) : new Entry(mission, false);
        }

        /**
         * @param settler the new assignee, empty to unlock the entry
         * @return this entry assigned to that settler (and locked), or unlocked
         */
        public Entry withAssignee(Optional<UUID> settler) {
            return new Entry(mission, marked, settler, settler.isPresent());
        }

        /**
         * @return this entry without its assignee, still locked: the assigned settler died or left the board
         */
        public Entry withoutAssignee() {
            return new Entry(mission, marked, Optional.empty(), locked);
        }

        /**
         * @param settler a settler
         * @return whether that settler may take this entry: it is marked and not locked for others
         */
        public boolean takeableBy(UUID settler) {
            return marked && (!locked || assignee.filter(settler::equals).isPresent());
        }
    }

    /** Save codec. */
    public static final Codec<MissionOffer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(MissionOffer::entries),
            Codec.LONG.optionalFieldOf("day", -1L).forGetter(MissionOffer::day),
            Identifier.CODEC.listOf().<Set<Identifier>>xmap(Set::copyOf, List::copyOf).optionalFieldOf("auto", Set.of())
                    .forGetter(MissionOffer::auto)
    ).apply(instance, MissionOffer::new));

    /**
     * @param entries the offered missions
     * @param day     the day of the last refill
     * @param auto    the ticked missions
     */
    public MissionOffer {
        entries = List.copyOf(entries);
        auto = Set.copyOf(auto);
    }

    /**
     * @param entries the new entries
     * @return this offer with those entries, the same day and ticks
     */
    public MissionOffer withEntries(List<Entry> entries) {
        return new MissionOffer(entries, day, auto);
    }

    /**
     * @param auto the new ticks
     * @return this offer with those ticks, the same entries and day
     */
    public MissionOffer withAuto(Set<Identifier> auto) {
        return new MissionOffer(entries, day, auto);
    }
}
