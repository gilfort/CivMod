package com.gilfort.civmod.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementRules;
import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * A kind of work a settler can hold (vault {@code Systems/Jobs.md}), registered in the {@code civmod:job} registry
 * ({@link CivModApi#JOB_REGISTRY}) and identified by its namespaced id. A Job declares its {@link JobCategory}, the
 * {@link CategoryProfile} that fills in the category's work loop, an optional parent Base Job (which makes it a
 * Specialization, unlocked at the parent's JobBlock by its unlock item in a Station Types slot; a Mission Job is offered by
 * the Mission Board from level 2), its unlock item, its icon and default balancing values.
 * <p>
 * Datapacks may override the balancing values per job id {@code <namespace>:<job>}: hunger factor, base EXP, job factor
 * and unlock item in {@code data/<namespace>/civmod_jobs/<job>.json} ({@code hunger_factor}, {@code base_exp},
 * {@code job_factor}, {@code unlock_item}, all optional; a Producing Job also {@code parallel_stations}, a list of the
 * stations worked at once per JobBlock level, see {@link ProducingProfile#parallelStations}), the JobBlock upgrade costs in
 * {@code data/<namespace>/civmod_upgrades/<job>.json}. The job's names come from the lang keys
 * {@code job.<namespace>.<job>} and {@code job.<namespace>.<job>.indefinite} ("an Adventurer"), its one-line description
 * in the Station Types overview from {@code job.<namespace>.<job>.description}.
 * <p>
 * Register a Job with the {@code DeferredRegister} from {@link CivModApi#jobs(String)}:
 *
 * <pre>{@code
 * public static final DeferredRegister<Job> JOBS = CivModApi.jobs("examplemod");
 * public static final DeferredHolder<Job, Job> SCOUT = JOBS.register("scout", () -> Job.builder(JobCategory.MISSION)
 *         .parent(CivModJobs.ADVENTURER).icon(() -> Items.SPYGLASS).baseExp(8).build());
 * }</pre>
 */
@ApiStatus.Experimental
public final class Job {

    /**
     * Save codec by namespaced id. Ids without a namespace (PoC saves, data files) are read as {@code civmod:}.
     */
    public static final Codec<Job> CODEC = Codec.STRING.comapFlatMap(Job::parse, job -> job.id().toString());

    /** Network codec by synced registry id. */
    public static final StreamCodec<ByteBuf, Job> STREAM_CODEC = ByteBufCodecs.idMapper(ModJobs.REGISTRY);

    private final JobCategory category;
    private final @Nullable Supplier<Job> parent;
    private final Supplier<? extends ItemLike> icon;
    private final @Nullable Supplier<? extends ItemLike> bubbleIcon;
    private final @Nullable Supplier<? extends ItemLike> displayItem;
    private final double hungerFactor;
    private final int baseExp;
    private final double jobFactor;
    private final @Nullable TagKey<Block> station;
    private final @Nullable Supplier<? extends ItemLike> unlockItem;
    private final @Nullable Supplier<CategoryProfile> profile;

    private Job(Builder builder) {
        this.category = builder.category;
        this.parent = builder.parent;
        this.icon = Objects.requireNonNull(builder.icon, "icon");
        this.bubbleIcon = builder.bubbleIcon;
        this.displayItem = builder.displayItem;
        this.hungerFactor = builder.hungerFactor;
        this.baseExp = builder.baseExp;
        this.jobFactor = builder.jobFactor;
        this.station = builder.station;
        this.unlockItem = builder.unlockItem;
        Supplier<? extends CategoryProfile> declared = builder.profile;
        this.profile = declared == null ? null : Suppliers.memoize(() -> requireOwnCategory(declared.get()));
    }

    /**
     * @param category the Job Category the Job is built on
     * @return a builder for a Job
     */
    public static Builder builder(JobCategory category) {
        return new Builder(category);
    }

    private static DataResult<Job> parse(String name) {
        Identifier id = name.indexOf(':') < 0 ? Identifier.tryBuild(CivMod.MOD_ID, name) : Identifier.tryParse(name);
        if (id == null) {
            return DataResult.error(() -> "Invalid job id: " + name);
        }
        Job job = ModJobs.REGISTRY.getValue(id);
        return job == null ? DataResult.error(() -> "Unknown job: " + id) : DataResult.success(job);
    }

    private CategoryProfile requireOwnCategory(CategoryProfile found) {
        if (found.category() != category) {
            throw new IllegalStateException("The profile of " + this + " belongs to " + found.category() + ", not to " + category);
        }
        return found;
    }

    /**
     * @return the namespaced id
     */
    public Identifier id() {
        return Objects.requireNonNull(ModJobs.REGISTRY.getKey(this), "Unregistered job");
    }

    /**
     * @return the Job Category that owns the work loop
     */
    public JobCategory category() {
        return category;
    }

    /**
     * @return the parent Base Job if this Job is a Specialization
     */
    public Optional<Job> parent() {
        return parent == null ? Optional.empty() : Optional.of(parent.get());
    }

    /**
     * The Jobs this Base Job's JobBlock offers at a level without any Station Type (vault {@code Systems/JobBlock.md}):
     * the Base Job itself; a Mission Job (the Mission Board) also its Specializations from level 2, in registry order.
     * Other Specializations are unlocked per JobBlock by their {@link #unlockItem()} in a Station Types slot.
     *
     * @param level the JobBlock level
     * @return the offered Jobs, this Job first
     */
    public List<Job> offeredAt(int level) {
        List<Job> offered = new ArrayList<>();
        offered.add(this);
        if (category == JobCategory.MISSION && level > SettlementRules.MIN_LEVEL) {
            ModJobs.REGISTRY.stream().filter(job -> job.parent().filter(this::equals).isPresent()).forEach(offered::add);
        }
        return List.copyOf(offered);
    }

    /**
     * @return the translated job name ({@code job.<namespace>.<path>})
     */
    public Component displayName() {
        return Component.translatable("job." + id().getNamespace() + "." + id().getPath());
    }

    /**
     * @return the translated job name with its indefinite article ({@code job.<namespace>.<path>.indefinite}, "an
     *         Adventurer")
     */
    public Component indefiniteName() {
        return Component.translatable("job." + id().getNamespace() + "." + id().getPath() + ".indefinite");
    }

    /**
     * @return the one-line description of the Station Types overview ({@code job.<namespace>.<path>.description}, empty
     *         without a lang entry)
     */
    public Component description() {
        return Component.translatableWithFallback("job." + id().getNamespace() + "." + id().getPath() + ".description", "");
    }

    /**
     * @return the item that unlocks this Specialization in a Station Types slot of its parent's JobBlock (datapack
     *         override {@code unlock_item} or default); a Base Job's unlock item is its station, shown in the first slot
     */
    public Optional<Item> unlockItem() {
        Optional<Item> override = JobBalancing.of(this).unlockItem();
        return override.isPresent() ? override : Optional.ofNullable(unlockItem).map(found -> found.get().asItem());
    }

    /**
     * @return the translated name of the job's work place ({@code <namespace>.scroll.work_place.<path>})
     */
    public Component workPlaceName() {
        return Component.translatable(id().getNamespace() + ".scroll.work_place." + id().getPath());
    }

    /**
     * @return the icon shown in GUIs
     */
    public ItemStack icon() {
        return new ItemStack(icon.get());
    }

    /**
     * @return the item in the settler's thought bubble when it switches to this Job (its typical item; the GUI icon if
     *         the Job sets none)
     */
    public ItemStack bubbleIcon() {
        return new ItemStack(bubbleIcon == null ? icon.get() : bubbleIcon.get());
    }

    /**
     * @return the display item a settler holds while working this Job, for Jobs without a real tool (only a display
     *         copy in the hand, never in an inventory)
     */
    public Optional<Item> displayItem() {
        return displayItem == null ? Optional.empty() : Optional.of(displayItem.get().asItem());
    }

    /**
     * The category specifics of the Job, if they are of the given type. A Mission Job always has a {@link MissionProfile}
     * (the {@link MissionProfile#NEUTRAL} one if it declares none).
     *
     * @param <P>  the profile type
     * @param type the profile type, e.g. {@code MissionProfile.class}
     * @return the profile, if the Job has one of that type
     * @throws IllegalStateException if the declared profile belongs to another Job Category
     */
    public <P extends CategoryProfile> Optional<P> profile(Class<P> type) {
        return profile == null ? Optional.empty() : Optional.of(profile.get()).filter(type::isInstance).map(type::cast);
    }

    /**
     * @param level the level
     * @param pos   a position
     * @return whether a player may set the block as the job's work place with the Scroll of Assignment
     */
    public boolean isValidWorkPlace(Level level, BlockPos pos) {
        if (station != null) {
            return level.getBlockState(pos).is(station);
        }
        Optional<ProducingProfile> producing = profile(ProducingProfile.class);
        if (producing.isPresent()) {
            return producing.get().recipeTypes().stream().anyMatch(type -> level.getBlockState(pos).is(producing.get().station(type)));
        }
        return profile(ResourceCollectionProfile.class).map(found -> found.isAssignableWorkPlace(level, pos)).orElse(false);
    }

    /**
     * @return the block tag of the job's station ({@code <namespace>:stations/<job>}), if it works at one (Producing
     *         Jobs: see {@link ProducingProfile#station})
     */
    public Optional<TagKey<Block>> station() {
        return Optional.ofNullable(station);
    }

    /**
     * @return the factor on the shared speed formula (datapack override or default)
     */
    public double jobFactor() {
        return JobBalancing.of(this).jobFactor().orElse(jobFactor);
    }

    /**
     * @return how much harder than standing around this job makes hungry per work minute (datapack override or default)
     */
    public double hungerFactor() {
        return JobBalancing.of(this).hungerFactor().orElse(hungerFactor);
    }

    /**
     * @return the base EXP per produced resource (datapack override or default)
     */
    public int baseExp() {
        return JobBalancing.of(this).baseExp().orElse(baseExp);
    }

    /**
     * @return the hunger factor declared by the Job
     */
    public double defaultHungerFactor() {
        return hungerFactor;
    }

    /**
     * @return the base EXP declared by the Job
     */
    public int defaultBaseExp() {
        return baseExp;
    }

    @Override
    public String toString() {
        Identifier id = ModJobs.REGISTRY.getKey(this);
        return id == null ? "Job[unregistered]" : id.toString();
    }

    /**
     * Builds a {@link Job}.
     */
    @ApiStatus.Experimental
    public static final class Builder {

        private final JobCategory category;
        private @Nullable Supplier<Job> parent;
        private @Nullable Supplier<? extends ItemLike> icon;
        private @Nullable Supplier<? extends ItemLike> bubbleIcon;
        private @Nullable Supplier<? extends ItemLike> displayItem;
        private double hungerFactor = 1.0;
        private int baseExp = 1;
        private double jobFactor = 1.0;
        private @Nullable TagKey<Block> station;
        private @Nullable Supplier<? extends ItemLike> unlockItem;
        private @Nullable Supplier<? extends CategoryProfile> profile;

        private Builder(JobCategory category) {
            this.category = category;
        }

        /**
         * @param parent the parent Base Job (makes this Job a Specialization: unlocked at the parent's JobBlock by its
         *               {@link #unlockItem(Supplier) unlock item}; a Mission Job is offered by the Mission Board from
         *               level 2)
         * @return this builder
         */
        public Builder parent(Supplier<Job> parent) {
            this.parent = parent;
            return this;
        }

        /**
         * @param icon the item shown as icon (usually the JobBlock); required
         * @return this builder
         */
        public Builder icon(Supplier<? extends ItemLike> icon) {
            this.icon = icon;
            return this;
        }

        /**
         * @param bubbleIcon the item shown in the thought bubble on a switch to this Job (default: the icon)
         * @return this builder
         */
        public Builder bubbleIcon(Supplier<? extends ItemLike> bubbleIcon) {
            this.bubbleIcon = bubbleIcon;
            return this;
        }

        /**
         * @param displayItem the item held while working, for Jobs without a real tool
         * @return this builder
         */
        public Builder displayItem(Supplier<? extends ItemLike> displayItem) {
            this.displayItem = displayItem;
            return this;
        }

        /**
         * @param hungerFactor default work hunger factor (default 1)
         * @return this builder
         */
        public Builder hungerFactor(double hungerFactor) {
            this.hungerFactor = hungerFactor;
            return this;
        }

        /**
         * @param baseExp default base EXP per produced resource (default 1)
         * @return this builder
         */
        public Builder baseExp(int baseExp) {
            this.baseExp = baseExp;
            return this;
        }

        /**
         * @param jobFactor default factor on the shared speed formula (default 1)
         * @return this builder
         */
        public Builder jobFactor(double jobFactor) {
            this.jobFactor = jobFactor;
            return this;
        }

        /**
         * @param station the block tag of the job's station (its work places); Producing Jobs name theirs per recipe type in
     *                their {@link ProducingProfile} instead
         * @return this builder
         */
        public Builder station(TagKey<Block> station) {
            this.station = station;
            return this;
        }

        /**
         * @param unlockItem the item that unlocks this Specialization in a Station Types slot of its parent's JobBlock
         *                   (several Jobs may name the same item; datapacks override it with {@code unlock_item}); for a
         *                   Base Job its station, shown in the first slot
         * @return this builder
         */
        public Builder unlockItem(Supplier<? extends ItemLike> unlockItem) {
            this.unlockItem = unlockItem;
            return this;
        }

        /**
         * @param profile the category specifics, created on first use (after registration); its
         *                {@link CategoryProfile#category()} must be the Job's category. A Mission Job without one gets
         *                {@link MissionProfile#NEUTRAL}; a Producing Job needs a {@link ProducingProfile}.
         * @return this builder
         */
        public Builder profile(Supplier<? extends CategoryProfile> profile) {
            this.profile = profile;
            return this;
        }

        /**
         * @return the Job
         */
        public Job build() {
            if (profile == null && category == JobCategory.MISSION) {
                profile = () -> MissionProfile.NEUTRAL;
            }
            if (profile == null && category == JobCategory.PRODUCING) {
                throw new IllegalStateException("A Producing Job needs a ProducingProfile");
            }
            return new Job(this);
        }
    }
}
