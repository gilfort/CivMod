package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.job.SavedScan;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
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
public record FoundTabData(boolean scanned, List<Entry> entries, List<Component> lines) {

    /** Not scanned yet (any other JobBlock, or before the first sync). */
    public static final FoundTabData EMPTY = new FoundTabData(false, List.of(), List.of());

    /**
     * One found resource.
     *
     * @param type      the type (key of the exclusion)
     * @param icon      the icon
     * @param needsTool whether the working settler's tool cannot obtain it (never without a settler)
     * @param excluded  whether it is excluded at the JobBlock
     * @param maxAt       the Home Clerk whose stock rule Max its dropped item has reached, empty if it is produced
     * @param rareBonus   whether a biome bonus's rare chance acts on it (a star)
     * @param doubleBonus whether a biome bonus's double drop acts on its dropped item (a star; two offset stars with
     *                    {@code rareBonus})
     * @param bonusLines  the tooltip of the stars: each effect with its source
     */
    public record Entry(Identifier type, ItemStack icon, boolean needsTool, boolean excluded, Optional<Component> maxAt, boolean rareBonus,
            boolean doubleBonus, List<Component> bonusLines) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Entry::type,
                ItemStack.OPTIONAL_STREAM_CODEC, Entry::icon,
                ByteBufCodecs.BOOL, Entry::needsTool,
                ByteBufCodecs.BOOL, Entry::excluded,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs::optional), Entry::maxAt,
                ByteBufCodecs.BOOL, Entry::rareBonus,
                ByteBufCodecs.BOOL, Entry::doubleBonus,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), Entry::bonusLines,
                Entry::new);

        /**
         * @param type        the type
         * @param icon        the icon
         * @param needsTool   whether the tool cannot obtain it
         * @param excluded    whether it is excluded
         * @param maxAt       the Home Clerk at its Max
         * @param rareBonus   whether the rare chance acts on it
         * @param doubleBonus whether a double drop acts on it
         * @param bonusLines  the stars' tooltip
         */
        public Entry {
            bonusLines = List.copyOf(bonusLines);
        }

        @Override
        public boolean equals(Object other) {
            // ItemStack has no value equality.
            return other instanceof Entry entry && type.equals(entry.type) && ItemStack.matches(icon, entry.icon) && needsTool == entry.needsTool
                    && excluded == entry.excluded && maxAt.equals(entry.maxAt) && rareBonus == entry.rareBonus && doubleBonus == entry.doubleBonus
                    && bonusLines.equals(entry.bonusLines);
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + maxAt.hashCode() * 7 + bonusLines.hashCode() * 17 + (rareBonus ? 8 : 0) + (doubleBonus ? 4 : 0)
                    + (needsTool ? 2 : 0) + (excluded ? 1 : 0);
        }
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, FoundTabData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, FoundTabData::scanned,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), FoundTabData::entries,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), FoundTabData::lines,
            FoundTabData::new);

    /**
     * @param scanned whether the JobBlock has a scan
     * @param entries the found resources
     * @param lines   scan info
     */
    public FoundTabData {
        entries = List.copyOf(entries);
        lines = List.copyOf(lines);
    }

    /**
     * @param jobType the Job of a JobBlock
     * @return whether it is a Resource Collection Job (and so its JobBlock has a Found tab, {@link JobBlockTabs})
     */
    static boolean hasTab(Job jobType) {
        return jobType.category() == JobCategory.RESOURCE_COLLECTION;
    }

    /**
     * The tab of a JobBlock: the scan of the settler working it (else the last one the JobBlock kept), tool marks and
     * treasure chance for that settler.
     *
     * @param level the level
     * @param pos   the JobBlock position
     * @return the current tab ({@link #EMPTY} without a scan or for other JobBlocks)
     */
    public static FoundTabData of(ServerLevel level, BlockPos pos) {
        // Absent when the JobBlock left its settlement since the menu opened: the menu syncs every tick.
        Optional<JobBlockSite> found = JobBlockSite.at(level, pos).filter(site -> hasTab(site.block().jobType()));
        Optional<ResourceCollectionProfile> profile = found.flatMap(site -> site.block().jobType().profile(ResourceCollectionProfile.class));
        if (found.isEmpty() || profile.isEmpty()) {
            return EMPTY;
        }
        JobBlockSite site = found.get();
        SettlementBlock block = site.block();
        Settler settler = worker(level, block);
        ScanResult scan = Optional.ofNullable(settler).flatMap(working -> working.getWork().scan(pos))
                .or(() -> block.foundScan().map(SavedScan::result)).orElse(null);
        if (scan == null) {
            return EMPTY;
        }
        Optional<ResourceCollectionProfile.Worker> worker = Optional.ofNullable(settler).flatMap(working -> working.getWork().worker(pos));
        // Max: the dropped item with the working settler's tool (without one the plain drop).
        Set<Item> atMax = StockRules.atMax(level, site);
        Optional<Component> clerk = block.homeClerk().flatMap(site.settlement()::block).map(ClerkStorage::name);
        ItemStack tool = worker.map(ResourceCollectionProfile.Worker::tool).orElse(ItemStack.EMPTY);
        // The biome bonus acting on the working settler today: stars at the drops it changes.
        BiomeBonus bonus = settler == null ? BiomeBonus.NONE : CivModApi.biomeBonus(settler, pos);
        Job job = Optional.ofNullable(settler).flatMap(working -> working.jobAt(pos)).map(SettlerJob::jobType).orElse(block.jobType());
        boolean rareActs = !bonus.rareChance().sources().isEmpty();
        List<Entry> entries = profile.get().found(scan, worker).stream().map(entry -> {
            // The dropped item only when needed: it rolls the block's loot.
            Item dropped = atMax.isEmpty() && bonus.doubleDrops().isEmpty() ? null : profile.get().dropped(level, pos, entry.type(), tool);
            boolean rare = rareActs && profile.get().inRareGroup(scan, entry.type());
            List<BiomeBonus.DoubleDrop> drops = dropped == null ? List.of() : bonus.doubleDrops(new ItemStack(dropped));
            return new Entry(entry.type(), entry.icon(), !entry.available(), block.excluded().contains(entry.type()),
                    !atMax.isEmpty() && atMax.contains(dropped) ? clerk : Optional.empty(), rare, !drops.isEmpty(),
                    BiomeBonusText.sourceLines(job, bonus, rare, drops));
        }).toList();
        return new FoundTabData(true, entries, profile.get().foundLines(scan, worker));
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
    public record Sync(int containerId, FoundTabData tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("found_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                FoundTabData.STREAM_CODEC, Sync::tab,
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
