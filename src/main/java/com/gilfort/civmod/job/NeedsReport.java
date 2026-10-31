package com.gilfort.civmod.job;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * The needs of a settler as the Needs tab shows them.
 *
 * @param tier the tier the settler works in now
 * @param rows one row per need
 */
public record NeedsReport(int tier, List<Row> rows) {

    /** No needs to show. */
    public static final NeedsReport NONE = new NeedsReport(1, List.of());

    /**
     * One need.
     *
     * @param tier   its tier
     * @param name   its name
     * @param met    whether it is met
     * @param detail the detail text
     * @param icons  items shown with the detail, may be empty
     */
    public record Row(int tier, Component name, boolean met, Component detail, List<ItemStack> icons) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Row::tier,
                ComponentSerialization.STREAM_CODEC, Row::name,
                ByteBufCodecs.BOOL, Row::met,
                ComponentSerialization.STREAM_CODEC, Row::detail,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), Row::icons,
                Row::new);

        /**
         * @param tier   its tier
         * @param name   its name
         * @param met    whether it is met
         * @param detail the detail text
         * @param icons  items shown with the detail, may be empty
         */
        public Row {
            icons = List.copyOf(icons);
        }
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, NeedsReport> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NeedsReport::tier,
            Row.STREAM_CODEC.apply(ByteBufCodecs.list()), NeedsReport::rows,
            NeedsReport::new);

    /**
     * @param tier the tier the settler works in now
     * @param rows one row per need
     */
    public NeedsReport {
        rows = List.copyOf(rows);
    }

    /**
     * @param tier a tier
     * @return the rows of that tier (empty: the Needs tab hides it)
     */
    public List<Row> rows(int tier) {
        return rows.stream().filter(row -> row.tier() == tier).toList();
    }
}
