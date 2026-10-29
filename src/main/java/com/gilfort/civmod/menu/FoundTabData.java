package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
import com.gilfort.civmod.job.CollectionRecipes;
import com.gilfort.civmod.job.DropPoolProfile;
import com.gilfort.civmod.job.JobDrops;
import com.gilfort.civmod.job.SavedScan;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.StationTypes;
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
 * What the "Found" tab of a Resource Collection JobBlock shows (vault {@code Systems/Resource Collection Jobs.md}): one
 * {@link Section} per Job of the settlers assigned to the JobBlock (a Gatherer JobBlock hosts the Gatherer and its
 * Specializations), each with the resources of that Job's latest scan and the innate recipes it offers (milk while cows
 * were found, {@link CollectionRecipes}), its exclusions, the tool and input marks for the settler
 * working there and scan info lines (the Fisherman's water and treasure chance). The server sends it while the menu is
 * open whenever it changed ({@link Sync}); the client excludes or includes an entry of a section with {@link Toggle}.
 *
 * @param sections the sections, the Base Job first
 */
public record FoundTabData(List<Section> sections) {

    /** Not scanned yet (any other JobBlock, or before the first sync). */
    public static final FoundTabData EMPTY = new FoundTabData(List.of());

    /**
     * One found resource.
     *
     * @param type      the type (key of the exclusion)
     * @param icon      the icon
     * @param needsTool whether the working settler's tool cannot obtain it (never without a settler)
     * @param excluded  whether it is excluded for the section's Job at the JobBlock
     * @param maxAt       the Home Clerk whose stock rule Max its dropped item has reached, empty if it is produced
     * @param rareBonus   whether a biome bonus's rare chance acts on it (a star)
     * @param doubleBonus whether a biome bonus's double drop acts on its dropped item (a star; two offset stars with
     *                    {@code rareBonus})
     * @param bonusLines  the tooltip of the stars: each effect with its source
     * @param needsInput  for an innate recipe ({@code type} is the recipe id): the line of the input neither on the
     *                    working settler's belt nor in the collect inventory ("Needs: Bucket"; greyed out, left out of
     *                    the roll), empty when it has its inputs or for a found resource
     * @param dropLines   the extra drops of the Job's NPC drop pool for the type ("Also: Melon Seeds 5 %"), greyed out at
     *                    Max or without their tool ({@link JobDrops#lines})
     */
    public record Entry(Identifier type, ItemStack icon, boolean needsTool, boolean excluded, Optional<Component> maxAt, boolean rareBonus,
            boolean doubleBonus, List<Component> bonusLines, Optional<Component> needsInput, List<Component> dropLines) {

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
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs::optional), Entry::needsInput,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), Entry::dropLines,
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
         * @param needsInput  the missing input of an innate recipe
         * @param dropLines   the extra drops of the drop pool
         */
        public Entry {
            bonusLines = List.copyOf(bonusLines);
            dropLines = List.copyOf(dropLines);
        }

        @Override
        public boolean equals(Object other) {
            // ItemStack has no value equality.
            return other instanceof Entry entry && type.equals(entry.type) && ItemStack.matches(icon, entry.icon) && needsTool == entry.needsTool
                    && excluded == entry.excluded && maxAt.equals(entry.maxAt) && rareBonus == entry.rareBonus && doubleBonus == entry.doubleBonus
                    && bonusLines.equals(entry.bonusLines) && needsInput.equals(entry.needsInput) && dropLines.equals(entry.dropLines);
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + maxAt.hashCode() * 7 + bonusLines.hashCode() * 17 + needsInput.hashCode() * 13 + dropLines.hashCode() * 19
                    + (rareBonus ? 8 : 0)
                    + (doubleBonus ? 4 : 0) + (needsTool ? 2 : 0) + (excluded ? 1 : 0);
        }
    }

    /**
     * The part of the tab of one Job.
     *
     * @param job     the Job (its profile renders the entries, its exclusions mark them)
     * @param scanned whether the JobBlock has a scan of that Job yet ("Not scanned yet" otherwise)
     * @param entries the found resources
     * @param lines   scan info, one line per value
     */
    public record Section(Job job, boolean scanned, List<Entry> entries, List<Component> lines) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Section> STREAM_CODEC = StreamCodec.composite(
                Job.STREAM_CODEC, Section::job,
                ByteBufCodecs.BOOL, Section::scanned,
                Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), Section::entries,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), Section::lines,
                Section::new);

        /**
         * @param job     the Job
         * @param scanned whether it has a scan
         * @param entries the found resources
         * @param lines   scan info
         */
        public Section {
            entries = List.copyOf(entries);
            lines = List.copyOf(lines);
        }

        /**
         * @param type a resource type
         * @return the entry of that type, if listed
         */
        public Optional<Entry> entry(Identifier type) {
            return entries.stream().filter(entry -> entry.type().equals(type)).findFirst();
        }
    }

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, FoundTabData> STREAM_CODEC = StreamCodec.composite(
            Section.STREAM_CODEC.apply(ByteBufCodecs.list()), FoundTabData::sections,
            FoundTabData::new);

    /**
     * @param sections the sections
     */
    public FoundTabData {
        sections = List.copyOf(sections);
    }

    /**
     * @param jobType the Job of a JobBlock
     * @return whether it is a Resource Collection Job (and so its JobBlock has a Found tab, {@link JobBlockTabs})
     */
    static boolean hasTab(Job jobType) {
        return jobType.category() == JobCategory.RESOURCE_COLLECTION;
    }

    /**
     * The tab of a JobBlock: a section per Job of its assigned settlers (without settlers one per Job the JobBlock kept
     * a scan of, else the Base Job's), each with the scan of a settler working the JobBlock in that Job (else the last
     * one the JobBlock kept), tool marks and treasure chance for that settler.
     *
     * @param level the level
     * @param pos   the JobBlock position
     * @return the current tab ({@link #EMPTY} for other JobBlocks)
     */
    public static FoundTabData of(ServerLevel level, BlockPos pos) {
        // Absent when the JobBlock left its settlement since the menu opened: the menu syncs every tick.
        Optional<JobBlockSite> found = JobBlockSite.at(level, pos).filter(site -> hasTab(site.block().jobType()));
        if (found.isEmpty()) {
            return EMPTY;
        }
        JobBlockSite site = found.get();
        List<Section> sections = new ArrayList<>();
        for (Job job : jobs(site.block())) {
            job.profile(ResourceCollectionProfile.class).ifPresent(profile -> sections.add(section(level, site, job, profile)));
        }
        return new FoundTabData(sections);
    }

    /** The Jobs with a section: the Base Job first, then its Specializations in registry order. */
    private static List<Job> jobs(SettlementBlock block) {
        Set<Job> jobs = new LinkedHashSet<>();
        block.assignees().forEach(assignee -> jobs.add(assignee.job()));
        if (jobs.isEmpty()) {
            jobs.addAll(block.scannedJobs());
        }
        if (jobs.isEmpty()) {
            jobs.add(block.jobType());
        }
        List<Job> order = new ArrayList<>(List.of(block.jobType()));
        order.addAll(StationTypes.specializations(block.jobType()));
        return jobs.stream().sorted(Comparator.comparingInt(job -> order.contains(job) ? order.indexOf(job) : order.size())).toList();
    }

    /** The section of one Job. */
    private static Section section(ServerLevel level, JobBlockSite site, Job job, ResourceCollectionProfile profile) {
        BlockPos pos = site.pos();
        SettlementBlock block = site.block();
        Settler settler = worker(level, block, job);
        ScanResult scan = Optional.ofNullable(settler).flatMap(working -> working.getWork().scan(pos))
                .or(() -> block.foundScan(job).map(SavedScan::result)).orElse(null);
        if (scan == null) {
            return new Section(job, false, List.of(), List.of());
        }
        Optional<ResourceCollectionProfile.Worker> worker = Optional.ofNullable(settler).flatMap(working -> working.getWork().worker(pos));
        // Max: the items a type can give with the working settler's tool (without one the plain drop).
        Set<Item> atMax = StockRules.atMax(level, site);
        Optional<Component> clerk = block.homeClerk().flatMap(site.settlement()::block).map(ClerkStorage::name);
        ItemStack tool = worker.map(ResourceCollectionProfile.Worker::tool).orElse(ItemStack.EMPTY);
        // The biome bonus acting on the working settler today: stars at the drops it changes.
        BiomeBonus bonus = settler == null ? BiomeBonus.NONE : CivModApi.biomeBonus(settler, pos);
        boolean rareActs = !bonus.rareChance().sources().isEmpty();
        Set<Identifier> excluded = block.excluded(job);
        Stock collect = ClerkStorage.stock(level, site.collect(), DayClock.dayTime(level), true);
        // The extra drops of the Job's NPC drop pool, with the tools of the collect inventory.
        Optional<DropPoolProfile> pooled = profile instanceof DropPoolProfile dropPool ? Optional.of(dropPool) : Optional.empty();
        JobDrops.Tools tools = JobDrops.Tools.of(level, null, () -> collect);
        List<Entry> entries = new ArrayList<>(profile.found(scan, worker).stream().map(entry -> {
            // The dropped items only when needed: they roll the block's loot.
            boolean max = !atMax.isEmpty() && ResourceCollectionProfile.atMax(profile.droppedItems(level, pos, entry.type(), tool), atMax);
            boolean rare = rareActs && profile.inRareGroup(scan, entry.type());
            List<BiomeBonus.DoubleDrop> drops = bonus.doubleDrops().isEmpty() ? List.of()
                    : bonus.doubleDrops(new ItemStack(profile.dropped(level, pos, entry.type(), tool)));
            return new Entry(entry.type(), entry.icon(), !entry.available(), excluded.contains(entry.type()),
                    max ? clerk : Optional.empty(), rare, !drops.isEmpty(),
                    BiomeBonusText.sourceLines(job, bonus, rare, drops), Optional.empty(),
                    pooled.map(dropPool -> JobDrops.lines(dropPool.pool().ofType(entry.type(), dropPool.entityDrops()), atMax, tools)).orElse(List.of()));
        }).toList());
        // The innate recipes the scan offers, after the found resources (no biome bonus acts on them).
        for (CollectionRecipes.Listed recipe : CollectionRecipes.listed(level, job, profile, scan, settler, collect)) {
            entries.add(new Entry(recipe.type(), recipe.icon(), false, excluded.contains(recipe.type()),
                    atMax.contains(recipe.result()) ? clerk : Optional.empty(), false, false, List.of(), recipe.needs(), List.of()));
        }
        return new Section(job, true, entries, profile.foundLines(scan, worker));
    }

    /** The loaded settler working the JobBlock in that Job, null if none. */
    private static @Nullable Settler worker(ServerLevel level, SettlementBlock block, Job job) {
        for (SettlementBlock.Assignee assignee : block.assignees()) {
            if (assignee.job() == job && level.getEntity(assignee.id()) instanceof Settler settler) {
                return settler;
            }
        }
        return null;
    }

    /**
     * @return whether any section has a scan
     */
    public boolean scanned() {
        return sections.stream().anyMatch(Section::scanned);
    }

    /**
     * @param job a Job
     * @return its section, if the tab shows one
     */
    public Optional<Section> section(Job job) {
        return sections.stream().filter(section -> section.job() == job).findFirst();
    }

    /**
     * @return the found resources of every section, in section order
     */
    public List<Entry> entries() {
        return sections.stream().flatMap(section -> section.entries().stream()).toList();
    }

    /**
     * @return the scan info lines of every section, in section order
     */
    public List<Component> lines() {
        return sections.stream().flatMap(section -> section.lines().stream()).toList();
    }

    /**
     * @param type a resource type
     * @return the first entry of that type in any section, if listed
     */
    public Optional<Entry> entry(Identifier type) {
        return sections.stream().flatMap(section -> section.entry(type).stream()).findFirst();
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
     * Client → server: exclude or include a found resource of one section of the open JobBlock menu.
     *
     * @param containerId the menu's container id
     * @param job         the Job of the section
     * @param resource    the resource type
     */
    public record Toggle(int containerId, Job job, Identifier resource) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Toggle> TYPE = new Type<>(CivMod.id("toggle_found"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Toggle> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Toggle::containerId,
                Job.STREAM_CODEC, Toggle::job,
                Identifier.STREAM_CODEC, Toggle::resource,
                Toggle::new);

        @Override
        public Type<Toggle> type() {
            return TYPE;
        }
    }
}
