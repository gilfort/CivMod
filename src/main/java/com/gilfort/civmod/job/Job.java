package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;

/**
 * A kind of work a settler can hold (vault {@code Systems/Jobs.md}), registered in the {@code civmod:job} registry
 * ({@link ModJobs}) and identified by its namespaced id. A Job declares its {@link JobCategory}, an optional parent Base
 * Job, its icon and default balancing values; datapacks may override the balancing values per job id
 * ({@link JobBalancing}, {@link com.gilfort.civmod.block.UpgradeCosts}).
 */
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
    private final double hungerFactor;
    private final int baseExp;
    private final @Nullable Supplier<JobProfile> profile;

    private Job(Builder builder) {
        this.category = builder.category;
        this.parent = builder.parent;
        this.icon = Objects.requireNonNull(builder.icon, "icon");
        this.hungerFactor = builder.hungerFactor;
        this.baseExp = builder.baseExp;
        this.profile = builder.profile == null ? null : Suppliers.memoize(builder.profile::get);
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
     * The Jobs this Base Job's JobBlock offers at a level (vault {@code Systems/JobBlock.md}): the Base Job itself, plus
     * its Specializations from level 2, in registry order.
     *
     * @param level the JobBlock level
     * @return the offered Jobs, this Job first
     */
    public List<Job> offeredAt(int level) {
        List<Job> offered = new ArrayList<>();
        offered.add(this);
        if (level > SettlementRules.MIN_LEVEL) {
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
     * @return the Resource Collection specifics, if the Job belongs to that category
     */
    public Optional<JobProfile> profile() {
        return profile == null ? Optional.empty() : Optional.of(profile.get());
    }

    /**
     * @param level the level
     * @param pos   a position
     * @return whether a player may set the block as the job's work place with the Scroll of Assignment
     */
    public boolean isValidWorkPlace(Level level, BlockPos pos) {
        return profile().map(found -> found.isAssignableWorkPlace(level, pos)).orElse(false);
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
    public static final class Builder {

        private final JobCategory category;
        private @Nullable Supplier<Job> parent;
        private @Nullable Supplier<? extends ItemLike> icon;
        private double hungerFactor = 1.0;
        private int baseExp = 1;
        private @Nullable Supplier<? extends JobProfile> profile;

        private Builder(JobCategory category) {
            this.category = category;
        }

        /**
         * @param parent the parent Base Job (makes this Job a Specialization)
         * @return this builder
         */
        public Builder parent(Supplier<Job> parent) {
            this.parent = parent;
            return this;
        }

        /**
         * @param icon the item shown as icon (usually the JobBlock)
         * @return this builder
         */
        public Builder icon(Supplier<? extends ItemLike> icon) {
            this.icon = icon;
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
         * @param profile the Resource Collection specifics (created on first use, after registration)
         * @return this builder
         */
        public Builder profile(Supplier<? extends JobProfile> profile) {
            this.profile = profile;
            return this;
        }

        /**
         * @return the Job
         */
        public Job build() {
            if (profile != null && category != JobCategory.RESOURCE_COLLECTION) {
                throw new IllegalStateException("A job profile is the Resource Collection contract, not " + category);
            }
            return new Job(this);
        }
    }
}
