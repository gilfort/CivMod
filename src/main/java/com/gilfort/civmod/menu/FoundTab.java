package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.JobCategory;
import com.gilfort.civmod.job.JobProfile;
import com.gilfort.civmod.job.SavedScan;
import com.gilfort.civmod.job.ScanResult;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * What the "Found" tab of a Resource Collection JobBlock shows (vault {@code Systems/Resource Collection Jobs.md}): the
 * resources of the latest scan with their exclusions, the tool marks for the settler working there and scan info lines
 * (the Fisherman's water and treasure chance). The server sends it while the menu is open whenever it changed
 * ({@link Sync}); the client excludes or includes an entry with {@link Toggle}.
 *
 * @param scanned whether the JobBlock has a scan yet ("Not scanned yet" otherwise)
 * @param entries the found resources
 * @param lines   scan info, one line per value
 */
public record FoundTab(boolean scanned, List<Entry> entries, List<Component> lines) {

    /** Not scanned yet (any other JobBlock, or before the first sync). */
    public static final FoundTab EMPTY = new FoundTab(false, List.of(), List.of());

    /**
     * One found resource.
     *
     * @param type      the type (key of the exclusion)
     * @param icon      the icon
     * @param needsTool whether the working settler's tool cannot obtain it (never without a settler)
     * @param excluded  whether it is excluded at the JobBlock
     */
    public record Entry(Identifier type, ItemStack icon, boolean needsTool, boolean excluded) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Entry::type,
                ItemStack.OPTIONAL_STREAM_CODEC, Entry::icon,
                ByteBufCodecs.BOOL, Entry::needsTool,
                ByteBufCodecs.BOOL, Entry::excluded,
                Entry::new);

        @Override
        public boolean equals(Object other) {
            // ItemStack has no value equality.
            return other instanceof Entry entry && type.equals(entry.type) && ItemStack.matches(icon, entry.icon) && needsTool == entry.needsTool
                    && excluded == entry.excluded;
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + (needsTool ? 2 : 0) + (excluded ? 1 : 0);
        }
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, FoundTab> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, FoundTab::scanned,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), FoundTab::entries,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), FoundTab::lines,
            FoundTab::new);

    /**
     * @param scanned whether the JobBlock has a scan
     * @param entries the found resources
     * @param lines   scan info
     */
    public FoundTab {
        entries = List.copyOf(entries);
        lines = List.copyOf(lines);
    }

    /**
     * @param block a JobBlock
     * @return whether it offers a Resource Collection Job (and so has a Found tab)
     */
    public static boolean hasTab(SettlementBlock block) {
        return isCollection(block.jobType().category());
    }

    /**
     * @param category a Job Category
     * @return whether its JobBlocks have a Found tab
     */
    public static boolean isCollection(JobCategory category) {
        return category == JobCategory.RESOURCE_COLLECTION;
    }

    /**
     * The tab of a JobBlock: the scan of the settler working it (else the last one the JobBlock kept), tool marks and
     * treasure chance for that settler.
     *
     * @param level the level
     * @param pos   the JobBlock position
     * @return the current tab ({@link #EMPTY} without a scan or for other JobBlocks)
     */
    public static FoundTab of(ServerLevel level, BlockPos pos) {
        Optional<SettlementBlock> found = JobBlockSite.blockAt(level, pos).filter(FoundTab::hasTab);
        Optional<JobProfile> profile = found.flatMap(block -> block.jobType().profile());
        if (found.isEmpty() || profile.isEmpty()) {
            return EMPTY;
        }
        SettlementBlock block = found.get();
        Settler settler = worker(level, block);
        ScanResult scan = settler == null ? null : settler.getWork().scan(pos).orElse(null);
        if (scan == null) {
            scan = block.foundScan().map(SavedScan::result).orElse(null);
        }
        if (scan == null) {
            return EMPTY;
        }
        Optional<JobProfile.Worker> worker = Optional.ofNullable(settler).flatMap(working -> working.getWork().worker(pos));
        List<Entry> entries = profile.get().found(scan, worker).stream()
                .map(entry -> new Entry(entry.type(), entry.icon(), !entry.available(), block.excluded().contains(entry.type())))
                .toList();
        return new FoundTab(true, entries, profile.get().foundLines(scan, worker));
    }

    /** The loaded settler working the JobBlock, null if none. */
    private static @Nullable Settler worker(ServerLevel level, SettlementBlock block) {
        for (SettlementBlock.Assignee assignee : block.assignees()) {
            if (level.getEntity(assignee.id()) instanceof Settler settler) {
                return settler;
            }
        }
        return null;
    }

    /**
     * @param type a resource type
     * @return the entry of that type, if listed
     */
    public Optional<Entry> entry(Identifier type) {
        return entries.stream().filter(entry -> entry.type().equals(type)).findFirst();
    }

    /**
     * Server → client: the Found tab of the open menu changed.
     *
     * @param containerId the menu's container id
     * @param tab         the tab
     */
    public record Sync(int containerId, FoundTab tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("found_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                FoundTab.STREAM_CODEC, Sync::tab,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: exclude or include a found resource of the open JobBlock menu.
     *
     * @param containerId the menu's container id
     * @param resource    the resource type
     */
    public record Toggle(int containerId, Identifier resource) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Toggle> TYPE = new Type<>(CivMod.id("toggle_found"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Toggle> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Toggle::containerId,
                Identifier.STREAM_CODEC, Toggle::resource,
                Toggle::new);

        @Override
        public Type<Toggle> type() {
            return TYPE;
        }
    }
}
