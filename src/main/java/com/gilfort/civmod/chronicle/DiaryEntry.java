package com.gilfort.civmod.chronicle;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * One line of a settler's diary (vault {@code Systems/Chronicle.md}): the id of its type, the parameters the type builds the words
 * from, and the settler day it was written on (counted from 0; the window shows it plus 1). The words are not saved.
 *
 * @param type   the id of the entry type in the {@code civmod:diary_entry_type} registry
 * @param params the text parameters
 * @param day    the settler day
 */
public record DiaryEntry(Identifier type, List<String> params, long day) {

    /** Save codec. */
    public static final Codec<DiaryEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("type").forGetter(DiaryEntry::type),
            Codec.STRING.listOf().optionalFieldOf("params", List.of()).forGetter(DiaryEntry::params),
            Codec.LONG.fieldOf("day").forGetter(DiaryEntry::day)
    ).apply(instance, DiaryEntry::new));

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, DiaryEntry> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, DiaryEntry::type,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DiaryEntry::params,
            ByteBufCodecs.VAR_LONG, DiaryEntry::day,
            DiaryEntry::new);

    /**
     * @param type   the id of the entry type
     * @param params the text parameters
     * @param day    the settler day
     */
    public DiaryEntry {
        params = List.copyOf(params);
    }
}
