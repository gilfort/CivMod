package com.gilfort.civmod.job;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.registry.ModJobs;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.advancements.predicates.StatePropertiesPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;

/**
 * NPC drop pools (vault {@code Systems/Resource Collection Jobs.md}, decisions 2026-10-29): extra drops only settlers
 * get, one datapack file per entry at {@code data/<namespace>/civmod_job_drops/<name>.json}. Only the farming Jobs
 * (profiles that are a {@link DropPoolProfile}) use entries; their scan counts every entry's {@code from} and their roll
 * adds the entries of the rolled type ({@link #roll}).
 *
 * <pre>{@code
 * {
 *   "job": "civmod:gatherer",
 *   "from": "minecraft:bush",
 *   "state": { "age": "3" },
 *   "result": { "id": "minecraft:melon_seeds", "count": { "min": 1, "max": 2 } },
 *   "chance": 0.05,
 *   "mode": "add",
 *   "tool": "#c:tools/shear"
 * }
 * }</pre>
 * <ul>
 * <li>{@code job}: a Job id or a list; {@code []} turns the entry off (a pack overrides a default by the same path).
 * Entries for other Jobs than the farming ones are ignored with a log warning.</li>
 * <li>{@code from}: a block id, {@code #block tag} or a list; for the Animal Farmer entity type ids or tags.</li>
 * <li>{@code state} (optional, blocks only): the block-state properties the counted block must have (exact values or
 * {@code {"min": .., "max": ..}}).</li>
 * <li>{@code result}: an item id, or {@code {"id": .., "count": ..}} with a count or a {@code {"min", "max"}} range.</li>
 * <li>{@code chance} (optional, 0–1, default 1): the entry's own chance per roll of its type.</li>
 * <li>{@code mode} (optional): {@code add} (default) keeps the type's normal loot, {@code replace} skips it for the
 * Job.</li>
 * <li>{@code tool} (optional): an item id or {@code #item tag} the settler uses from its collect inventory for this
 * entry (1 durability when the entry drops); without it the entry is skipped. Without {@code tool} the Job's tool
 * counts. Fortune (blocks) and Looting (entities) of the tool add 0–1 items per level; Silk Touch gives the block
 * itself, once per output.</li>
 * </ul>
 * An unknown Job, block, entity or item skips the file with a log warning.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobDrops {

    /** Reload listener id. */
    private static final Identifier ID = CivMod.id("job_drops");
    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_job_drops");
    private static final Codec<List<ExtraCodecs.TagOrElementLocation>> IDS_OR_TAGS = ExtraCodecs.compactListCodec(ExtraCodecs.TAG_OR_ELEMENT_ID,
            ExtraCodecs.nonEmptyList(ExtraCodecs.TAG_OR_ELEMENT_ID.listOf()));
    private static final StreamCodec<ByteBuf, ExtraCodecs.TagOrElementLocation> TAG_OR_ELEMENT_STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, ExtraCodecs.TagOrElementLocation::id,
            ByteBufCodecs.BOOL, ExtraCodecs.TagOrElementLocation::tag,
            ExtraCodecs.TagOrElementLocation::new);

    private static Loaded loaded = Loaded.EMPTY;

    private JobDrops() {
    }

    /** Whether an entry adds to the type's normal loot or replaces it. */
    public enum Mode implements StringRepresentable {
        /** The type's normal loot is rolled as well. */
        ADD("add"),
        /** The type's normal loot is skipped for the Job. */
        REPLACE("replace");

        /** Datapack codec. */
        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * How many items an entry gives: a fixed number or a range.
     *
     * @param min the least
     * @param max the most
     */
    public record Count(int min, int max) {

        private static final Codec<Count> RANGE = RecordCodecBuilder.<Count>create(instance -> instance.group(
                Codec.intRange(1, 64).fieldOf("min").forGetter(Count::min),
                Codec.intRange(1, 64).fieldOf("max").forGetter(Count::max)
        ).apply(instance, Count::new)).validate(count -> count.min <= count.max ? DataResult.success(count)
                : DataResult.error(() -> "count: min " + count.min + " is above max " + count.max));
        /** Datapack codec: a number or {@code {"min", "max"}}. */
        public static final Codec<Count> CODEC = Codec.withAlternative(RANGE, Codec.intRange(1, 64).xmap(n -> new Count(n, n), Count::min));
        /** Network codec. */
        public static final StreamCodec<ByteBuf, Count> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Count::min,
                ByteBufCodecs.VAR_INT, Count::max,
                Count::new);
        /** One item. */
        public static final Count ONE = new Count(1, 1);

        /**
         * @param random the random source
         * @return a count in the range
         */
        int roll(RandomSource random) {
            return min + random.nextInt(max - min + 1);
        }
    }

    /**
     * What an entry gives.
     *
     * @param item  the item
     * @param count how many
     */
    public record Result(Item item, Count count) {

        private static final Codec<Item> ITEM = BuiltInRegistries.ITEM.byNameCodec().validate(item -> item == Items.AIR
                ? DataResult.error(() -> "result: air is no item") : DataResult.success(item));
        private static final Codec<Result> FULL = RecordCodecBuilder.create(instance -> instance.group(
                ITEM.fieldOf("id").forGetter(Result::item),
                Count.CODEC.optionalFieldOf("count", Count.ONE).forGetter(Result::count)
        ).apply(instance, Result::new));
        /** Datapack codec: an item id or {@code {"id", "count"}}. */
        public static final Codec<Result> CODEC = Codec.withAlternative(FULL, ITEM.xmap(item -> new Result(item, Count.ONE), Result::item));
        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.registry(Registries.ITEM), Result::item,
                Count.STREAM_CODEC, Result::count,
                Result::new);
    }

    /**
     * One drop pool file.
     *
     * @param jobs   the Jobs it gives to (empty: turned off)
     * @param from   the block (for the Animal Farmer entity type) ids and tags it drops from
     * @param state  the block state the block must have, if any
     * @param result what it gives
     * @param chance its chance per roll of its type (0–1)
     * @param mode   whether the type's normal loot is kept
     * @param tool   the tool the settler uses from its collect inventory for it, if any
     */
    public record Entry(List<Identifier> jobs, List<ExtraCodecs.TagOrElementLocation> from, Optional<StatePropertiesPredicate> state, Result result,
            double chance, Mode mode, Optional<ExtraCodecs.TagOrElementLocation> tool) {

        /** Datapack codec. */
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ExtraCodecs.compactListCodec(Identifier.CODEC, Identifier.CODEC.listOf()).fieldOf("job").forGetter(Entry::jobs),
                IDS_OR_TAGS.fieldOf("from").forGetter(Entry::from),
                StatePropertiesPredicate.CODEC.optionalFieldOf("state").forGetter(Entry::state),
                Result.CODEC.fieldOf("result").forGetter(Entry::result),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("chance", 1.0).forGetter(Entry::chance),
                Mode.CODEC.optionalFieldOf("mode", Mode.ADD).forGetter(Entry::mode),
                ExtraCodecs.TAG_OR_ELEMENT_ID.optionalFieldOf("tool").forGetter(Entry::tool)
        ).apply(instance, Entry::new));

        /** Network codec (recipe viewers). */
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), Entry::jobs,
                TAG_OR_ELEMENT_STREAM_CODEC.apply(ByteBufCodecs.list()), Entry::from,
                ByteBufCodecs.optional(StatePropertiesPredicate.STREAM_CODEC), Entry::state,
                Result.STREAM_CODEC, Entry::result,
                ByteBufCodecs.DOUBLE, Entry::chance,
                NeoForgeStreamCodecs.enumCodec(Mode.class), Entry::mode,
                ByteBufCodecs.optional(TAG_OR_ELEMENT_STREAM_CODEC), Entry::tool,
                Entry::new);

        /**
         * @param state a block state
         * @return whether the entry drops from it ({@code from} and {@code state})
         */
        public boolean matches(BlockState state) {
            return BiomeBonuses.matches(from, BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock()), Registries.BLOCK)
                    && this.state.map(predicate -> predicate.matches(state)).orElse(true);
        }

        /**
         * @param type an entity type
         * @return whether the entry drops from it
         */
        public boolean matches(EntityType<?> type) {
            return BiomeBonuses.matches(from, BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type), Registries.ENTITY_TYPE);
        }

        /**
         * @param stack an item stack
         * @return whether it is the entry's tool (false without a {@code tool})
         */
        public boolean isTool(ItemStack stack) {
            return tool.isPresent() && !stack.isEmpty() && BiomeBonuses.matches(List.of(tool.get()), BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem()), Registries.ITEM);
        }

        /**
         * @return the chance as the tooltips show it ("5", "12.5")
         */
        public String percent() {
            double percent = Math.round(chance * 1000) / 10.0;
            return percent == Math.rint(percent) ? Integer.toString((int) percent) : Double.toString(percent);
        }
    }

    /**
     * A loaded entry.
     *
     * @param id    the file id
     * @param entry the entry
     */
    public record Drop(Identifier id, Entry entry) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Drop> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Drop::id,
                Entry.STREAM_CODEC, Drop::entry,
                Drop::new);
    }

    /**
     * The entries of one farming Job, with the matches per block state and entity type cached until the next reload.
     */
    public static final class Pool {

        /** A Job without entries. */
        public static final Pool EMPTY = new Pool(List.of());

        private final List<Drop> drops;
        private final Map<BlockState, List<Drop>> byState = new HashMap<>();
        private final Map<EntityType<?>, List<Drop>> byEntity = new HashMap<>();
        private final Map<Identifier, List<Drop>> byType = new HashMap<>();

        Pool(List<Drop> drops) {
            this.drops = List.copyOf(drops);
        }

        /**
         * @return the entries, ordered by file id
         */
        public List<Drop> drops() {
            return drops;
        }

        /**
         * @param state a block state
         * @return the entries that drop from it
         */
        public List<Drop> of(BlockState state) {
            if (drops.isEmpty()) {
                return List.of();
            }
            return byState.computeIfAbsent(state, key -> drops.stream().filter(drop -> drop.entry().matches(key)).toList());
        }

        /**
         * @param type an entity type
         * @return the entries that drop from it
         */
        public List<Drop> of(EntityType<?> type) {
            if (drops.isEmpty()) {
                return List.of();
            }
            return byEntity.computeIfAbsent(type, key -> drops.stream().filter(drop -> drop.entry().matches(key)).toList());
        }

        /**
         * @param type     a found type: a block id, for an entity Job an entity type id
         * @param entities whether the Job's types are entity types
         * @return the entries that drop from the type in any state
         */
        public List<Drop> ofType(Identifier type, boolean entities) {
            if (drops.isEmpty()) {
                return List.of();
            }
            return byType.computeIfAbsent(type, key -> drops.stream().filter(drop -> entities
                    ? BuiltInRegistries.ENTITY_TYPE.getOptional(key).map(drop.entry()::matches).orElse(false)
                    : BuiltInRegistries.BLOCK.getOptional(key).map(block -> BiomeBonuses.matches(drop.entry().from(), BuiltInRegistries.BLOCK.wrapAsHolder(block),
                            Registries.BLOCK)).orElse(false)).toList());
        }

        /**
         * @param state a block state
         * @return whether an entry drops from it (the scan counts it)
         */
        public boolean counts(BlockState state) {
            return !of(state).isEmpty();
        }

        /**
         * @param state a block state
         * @return whether an entry drops from the block in any state (it is a work place of the Job)
         */
        public boolean names(BlockState state) {
            return !drops.isEmpty() && !ofType(BuiltInRegistries.BLOCK.getKey(state.getBlock()), false).isEmpty();
        }

        /**
         * The items a type can give ({@link ResourceCollectionProfile#droppedItems}): its normal loot unless an entry
         * replaces it, and every entry's result.
         *
         * @param type     a found type
         * @param entities whether the Job's types are entity types
         * @param loot     the items of the type's normal loot
         * @return the items it can give ({@code minecraft:air} alone if none)
         */
        public Set<Item> droppedItems(Identifier type, boolean entities, Supplier<Set<Item>> loot) {
            List<Drop> entries = ofType(type, entities);
            if (entries.isEmpty()) {
                return loot.get();
            }
            Set<Item> items = new LinkedHashSet<>();
            if (!replaces(entries)) {
                items.addAll(loot.get());
            }
            entries.forEach(drop -> items.add(drop.entry().result().item()));
            if (items.size() > 1) {
                items.remove(Items.AIR);
            }
            return items.isEmpty() ? Set.of(Items.AIR) : Collections.unmodifiableSet(items);
        }

        /**
         * Whether a type has nothing left to give (it leaves the roll): an entry replaces its normal loot and every
         * entry is at its Max or misses its tool.
         *
         * @param type     a found type
         * @param entities whether the Job's types are entity types
         * @param atMax    the items at Max at the Home Clerk
         * @param tools    the tools of the collect inventory
         * @return whether nothing is left
         */
        public boolean exhausted(Identifier type, boolean entities, Set<Item> atMax, Tools tools) {
            List<Drop> entries = ofType(type, entities);
            return replaces(entries) && entries.stream().noneMatch(drop -> available(drop.entry(), atMax, tools));
        }
    }

    /**
     * @param entries entries of a type
     * @return whether one of them replaces the type's normal loot
     */
    public static boolean replaces(List<Drop> entries) {
        return entries.stream().anyMatch(drop -> drop.entry().mode() == Mode.REPLACE);
    }

    /** An entry can drop: its result is not at Max and its own tool, if any, is in the collect inventory. */
    private static boolean available(Entry entry, Set<Item> atMax, Tools tools) {
        return !atMax.contains(entry.result().item()) && (entry.tool().isEmpty() || tools.find(entry::isTool).isPresent());
    }

    /**
     * The tools of the collect inventory an entry with its own {@code tool} uses for one roll, without the settler
     * switching tools.
     */
    public interface Tools {

        /** No collect inventory: entries with their own tool are skipped. */
        Tools NONE = new Tools() {
            @Override
            public Optional<ItemStack> find(Predicate<ItemStack> kind) {
                return Optional.empty();
            }

            @Override
            public void wear(Predicate<ItemStack> kind) {
            }
        };

        /**
         * @param kind the kind of tool
         * @return the best such tool in the collect inventory (to read, not to change)
         */
        Optional<ItemStack> find(Predicate<ItemStack> kind);

        /**
         * Wears the best tool of a kind in the collect inventory by 1 durability.
         *
         * @param kind the kind of tool
         */
        void wear(Predicate<ItemStack> kind);

        /**
         * @param level   the level
         * @param settler the settler using the tools (null: the tools are only looked at, never worn)
         * @param collect what the settler reaches at its collect inventory, read when an entry first asks for a tool
         * @return the tools of the collect inventory
         */
        static Tools of(ServerLevel level, @Nullable LivingEntity settler, Supplier<Stock> collect) {
            return new Tools() {
                private @Nullable Stock stock;

                private Stock stock() {
                    if (stock == null) {
                        stock = collect.get();
                    }
                    return stock;
                }

                @Override
                public Optional<ItemStack> find(Predicate<ItemStack> kind) {
                    return SettlerTools.find(stock(), kind).map(index -> stock().contents().get(index));
                }

                @Override
                public void wear(Predicate<ItemStack> kind) {
                    Optional<Integer> index = SettlerTools.find(stock(), kind);
                    if (settler == null || index.isEmpty()) {
                        return;
                    }
                    ItemStack tool = stock().takeOne(index.get());
                    if (tool.isEmpty()) {
                        return;
                    }
                    tool.hurtAndBreak(1, level, settler, item -> { });
                    if (!tool.isEmpty()) {
                        ItemStack rest = stock().insert(tool);
                        if (!rest.isEmpty()) {
                            settler.spawnAtLocation(level, rest);
                        }
                    }
                }
            };
        }
    }

    /**
     * Rolls the entries of a rolled type, each on its own with its {@code chance}: an entry whose result is at its Max
     * or whose own tool is missing is skipped; Fortune (blocks) or Looting (entities) of the tool used adds 0–1 items per
     * level; Silk Touch on a block gives the block itself once instead of the entries' results (not while the block is
     * at Max).
     *
     * @param level   the level
     * @param entries the entries of the rolled block state or entity type
     * @param jobTool the Job's tool (empty bare-handed)
     * @param atMax   the items at Max at the Home Clerk
     * @param tools   the tools of the collect inventory
     * @param block   the rolled block's item for Silk Touch, empty for an entity
     * @return the items, empty when nothing dropped
     */
    public static List<ItemStack> roll(ServerLevel level, List<Drop> entries, ItemStack jobTool, Set<Item> atMax, Tools tools, ItemStack block) {
        if (entries.isEmpty()) {
            return List.of();
        }
        RandomSource random = level.getRandom();
        ResourceKey<Enchantment> bonus = block.isEmpty() ? Enchantments.LOOTING : Enchantments.FORTUNE;
        List<ItemStack> stacks = new ArrayList<>();
        boolean silk = false;
        for (Drop drop : entries) {
            Entry entry = drop.entry();
            if (atMax.contains(entry.result().item())) {
                continue;
            }
            ItemStack tool = jobTool;
            if (entry.tool().isPresent()) {
                Optional<ItemStack> own = tools.find(entry::isTool);
                if (own.isEmpty()) {
                    continue;
                }
                tool = own.get().copy();
            }
            if (random.nextDouble() >= entry.chance()) {
                continue;
            }
            if (entry.tool().isPresent()) {
                tools.wear(entry::isTool);
            }
            if (!block.isEmpty() && enchantmentLevel(level, tool, Enchantments.SILK_TOUCH) > 0) {
                silk = true;
                continue;
            }
            int count = entry.result().count().roll(random);
            for (int i = enchantmentLevel(level, tool, bonus); i > 0; i--) {
                count += random.nextInt(2);
            }
            Item item = entry.result().item();
            int max = new ItemStack(item).getMaxStackSize();
            for (; count > 0; count -= max) {
                stacks.add(new ItemStack(item, Math.min(count, max)));
            }
        }
        if (silk && !atMax.contains(block.getItem())) {
            stacks.add(block.copyWithCount(1));
        }
        return stacks;
    }

    private static int enchantmentLevel(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key) {
        if (stack.isEmpty()) {
            return 0;
        }
        Holder<Enchantment> enchantment = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
    }

    /**
     * The Found tab tooltip lines of a type's entries ("Also: Melon Seeds 5 %"): greyed out when the result is at its
     * Max or the entry's own tool is in no reach, the latter with "Needs: Shears".
     *
     * @param entries the entries of the type ({@link Pool#ofType})
     * @param atMax   the items at Max at the Home Clerk
     * @param tools   the tools of the collect inventory
     * @return the lines, one per entry
     */
    public static List<Component> lines(List<Drop> entries, Set<Item> atMax, Tools tools) {
        List<Component> lines = new ArrayList<>();
        for (Drop drop : entries) {
            Entry entry = drop.entry();
            MutableComponent line = Component.translatable("civmod.gui.found.also", new ItemStack(entry.result().item()).getHoverName(), entry.percent());
            boolean missing = entry.tool().isPresent() && tools.find(entry::isTool).isEmpty();
            if (missing) {
                line.append(" – ").append(Component.translatable("civmod.gui.found.needs_input", toolName(entry.tool().get())));
            }
            lines.add(line.withStyle(missing || atMax.contains(entry.result().item()) ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
        }
        return lines;
    }

    /**
     * @param tool an entry's {@code tool}
     * @return its name: the item's, or the tag's (named after its first item)
     */
    private static Component toolName(ExtraCodecs.TagOrElementLocation tool) {
        if (!tool.tag()) {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(tool.id())).getHoverName();
        }
        TagKey<Item> tag = TagKey.create(Registries.ITEM, tool.id());
        Component first = BuiltInRegistries.ITEM.get(tag).flatMap(items -> items.stream().findFirst())
                .map(item -> new ItemStack(item).getHoverName()).orElse(Component.literal("#" + tool.id()));
        return PatternRecipes.tagName(tag, first);
    }

    /**
     * @param job a Job id
     * @return the Job's entries ({@link Pool#EMPTY} for a Job that is not a farming Job)
     */
    public static Pool pool(Identifier job) {
        return loaded.pools().getOrDefault(job, Pool.EMPTY);
    }

    /**
     * @return every loaded entry by file id (turned-off entries without Jobs)
     */
    public static List<Drop> all() {
        return loaded.all();
    }

    /**
     * @param json a drop pool file
     * @return the parsed entry, or the error that makes it invalid
     */
    public static DataResult<Entry> parse(JsonElement json) {
        return Entry.CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * @param job a Job
     * @return whether it takes drop pool entries (a farming Job), and whether its types are entity types
     */
    public static Optional<Boolean> entityDrops(Job job) {
        return job.profile(ResourceCollectionProfile.class).filter(DropPoolProfile.class::isInstance).map(DropPoolProfile.class::cast)
                .filter(profile -> profile.dropPool().filter(job.id()::equals).isPresent()).map(DropPoolProfile::entityDrops);
    }

    /**
     * Checks an entry against the registries and the Jobs: an unknown Job, block, entity type or item makes it invalid;
     * Jobs that take no drop pool entries are left out with a warning.
     *
     * @param id    the file id
     * @param entry the parsed entry
     * @return the entry with the farming Jobs only, or the error
     */
    private static DataResult<Entry> validate(Identifier id, Entry entry) {
        List<Identifier> jobs = new ArrayList<>();
        for (Identifier jobId : entry.jobs()) {
            Optional<Job> job = ModJobs.REGISTRY.getOptional(jobId);
            if (job.isEmpty()) {
                return DataResult.error(() -> "unknown Job " + jobId);
            }
            Optional<Boolean> entities = entityDrops(job.get());
            if (entities.isEmpty()) {
                CivMod.LOGGER.warn("Job drop pool {}: Job {} takes no drop pool entries (only the farming Jobs do), ignored for it", id, jobId);
                continue;
            }
            for (ExtraCodecs.TagOrElementLocation from : entry.from()) {
                boolean known = from.tag() || (entities.get() ? BuiltInRegistries.ENTITY_TYPE.containsKey(from.id()) : BuiltInRegistries.BLOCK.containsKey(from.id()));
                if (!known) {
                    return DataResult.error(() -> "unknown " + (entities.get() ? "entity type " : "block ") + from.id() + " for Job " + jobId);
                }
            }
            if (entities.get() && entry.state().isPresent()) {
                return DataResult.error(() -> "\"state\" only goes with blocks, not with the entities of Job " + jobId);
            }
            jobs.add(jobId);
        }
        if (entry.tool().filter(tool -> !tool.tag() && !BuiltInRegistries.ITEM.containsKey(tool.id())).isPresent()) {
            return DataResult.error(() -> "unknown tool item " + entry.tool().get().id());
        }
        return DataResult.success(new Entry(List.copyOf(jobs), entry.from(), entry.state(), entry.result(), entry.chance(), entry.mode(), entry.tool()));
    }

    /**
     * Server → client: every loaded entry, for the recipe viewers' "Settler Gathering" category.
     *
     * @param drops the entries, ordered by file id
     */
    public record Sync(List<Drop> drops) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("job_drops"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                Drop.STREAM_CODEC.apply(ByteBufCodecs.list()), Sync::drops,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    /** Sends the entries before the recipes, so the recipe viewers have them when they reload on the recipes. */
    @SubscribeEvent
    static void onDatapackSync(OnDatapackSyncEvent event) {
        Sync sync = new Sync(loaded.all());
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, sync));
    }

    /**
     * The loaded state.
     *
     * @param all   every entry by file id
     * @param pools the entries per farming Job id
     */
    private record Loaded(List<Drop> all, Map<Identifier, Pool> pools) {

        static final Loaded EMPTY = new Loaded(List.of(), Map.of());

        static Loaded of(Map<Identifier, Entry> entries) {
            List<Drop> all = new ArrayList<>();
            Map<Identifier, List<Drop>> perJob = new LinkedHashMap<>();
            entries.forEach((id, entry) -> {
                Drop drop = new Drop(id, entry);
                all.add(drop);
                entry.jobs().forEach(job -> perJob.computeIfAbsent(job, key -> new ArrayList<>()).add(drop));
            });
            Map<Identifier, Pool> pools = new HashMap<>();
            perJob.forEach((job, drops) -> pools.put(job, new Pool(drops)));
            return new Loaded(List.copyOf(all), Map.copyOf(pools));
        }
    }

    private static final class Loader extends SimplePreparableReloadListener<Map<Identifier, Entry>> {

        @Override
        protected Map<Identifier, Entry> prepare(ResourceManager manager, ProfilerFiller profiler) {
            // Sorted by id: the order of the entries does not depend on the packs.
            Map<Identifier, Entry> entries = new TreeMap<>();
            // The top pack's file per id: a pack overrides a single entry by shipping the same path.
            FILES.listMatchingResources(manager).forEach((path, resource) -> {
                Identifier id = FILES.fileToId(path);
                read(id, resource).ifPresent(entry -> entries.put(id, entry));
            });
            return entries;
        }

        private static Optional<Entry> read(Identifier id, Resource resource) {
            try (Reader reader = resource.openAsReader()) {
                DataResult<Entry> parsed = parse(JsonParser.parseReader(reader)).flatMap(entry -> validate(id, entry));
                parsed.error().ifPresent(error -> CivMod.LOGGER.warn("Ignoring invalid job drop pool {} from pack {}: {}", id, resource.sourcePackId(),
                        error.message()));
                return parsed.result();
            } catch (Exception e) {
                CivMod.LOGGER.warn("Ignoring unreadable job drop pool {} from pack {}", id, resource.sourcePackId(), e);
                return Optional.empty();
            }
        }

        @Override
        protected void apply(Map<Identifier, Entry> prepared, ResourceManager manager, ProfilerFiller profiler) {
            loaded = Loaded.of(prepared);
            CivMod.LOGGER.info("Loaded {} job drop pool entr{}", prepared.size(), prepared.size() == 1 ? "y" : "ies");
        }
    }
}
