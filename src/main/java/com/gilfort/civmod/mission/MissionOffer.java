package com.gilfort.civmod.mission;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * The daily mission offer of one Mission Board (vault {@code Systems/Mission Jobs.md}).
 *
 * @param entries the offered missions (a refill puts the marked ones first)
 * @param day     the day of the last refill, {@code -1} before the first one
 */
public record MissionOffer(List<Entry> entries, long day) {

    /** An offer that was never filled. */
    public static final MissionOffer EMPTY = new MissionOffer(List.of(), -1L);

    /**
     * One offered mission. The same mission may be offered several times (up to its {@link Mission#maxPerOffer()}).
     *
     * @param mission the mission id
     * @param marked  whether the player marked it to be done
     */
    public record Entry(Identifier mission, boolean marked) {

        /** Save codec. */
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("mission").forGetter(Entry::mission),
                Codec.BOOL.optionalFieldOf("marked", false).forGetter(Entry::marked)
        ).apply(instance, Entry::new));

        /** Network codec. */
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Entry::mission,
                ByteBufCodecs.BOOL, Entry::marked,
                Entry::new);

        /**
         * @param marked the new mark
         * @return this entry with that mark
         */
        public Entry withMarked(boolean marked) {
            return new Entry(mission, marked);
        }
    }

    /** Save codec. */
    public static final Codec<MissionOffer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(MissionOffer::entries),
            Codec.LONG.optionalFieldOf("day", -1L).forGetter(MissionOffer::day)
    ).apply(instance, MissionOffer::new));

    /**
     * @param entries the offered missions
     * @param day     the day of the last refill
     */
    public MissionOffer {
        entries = List.copyOf(entries);
    }
}
