package com.gilfort.civmod.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.BlockCountScan;
import com.gilfort.civmod.job.BlockSearch;
import com.gilfort.civmod.job.MinerProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The common Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md}): count the blocks of a tag
 * around the JobBlock, produce their drops. Built with {@link #builder}:
 *
 * <pre>{@code
 * Job.builder(JobCategory.RESOURCE_COLLECTION)
 *         .icon(() -> SAND_DIGGER_BLOCK.get())
 *         .profile(() -> SimpleCollectionProfile.builder(BlockTags.SAND)
 *                 .tool(ItemTags.SHOVELS, () -> Items.STONE_SHOVEL)
 *                 .whenNothingFound(BlockingReason.of("examplemod.blocked.no_sand").withIcon(Items.SAND))
 *                 .build())
 *         .build();
 * }</pre>
 * <p>
 * Behaviour:
 * <ul>
 * <li>Work place: the nearest exposed block of the tag (a settler can stand next to or on top of it) within 20 blocks of
 * the JobBlock, or a block of the tag set with the Scroll of Assignment. The world is never changed.</li>
 * <li>Scan: every day, around the first work point (else the JobBlock), radius by JobBlock level (default 10 × level),
 * 10 blocks up and down; each block type of the tag is counted, types found fewer than {@code minimumCount} times are
 * left out.</li>
 * <li>Roll: a found type weighted by its count, then the drops of its loot table with the settler's tool (Silk Touch and
 * Fortune apply). Types the tool cannot harvest are never rolled; types excluded at the JobBlock or at their Max at the
 * Home Clerk leave the roll. Every found type is a main resource.</li>
 * <li>Speed: the shared speed formula × {@code productivity} of the total count (default 1).</li>
 * <li>Nothing found: status "No resources", or with {@code whenNothingFound} status "Blocked" with that reason (also
 * when no work place is found).</li>
 * </ul>
 */
@ApiStatus.Experimental
public final class SimpleCollectionProfile implements ResourceCollectionProfile {

    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    /** How far up and down the scan and the work place search reach. */
    private static final int HALF_HEIGHT = 10;

    private final TagKey<Block> blocks;
    private final TagKey<Item> tool;
    private final Supplier<? extends ItemLike> toolIcon;
    private final @Nullable IntUnaryOperator radius;
    private final int minimumCount;
    private final DoubleUnaryOperator productivity;
    private final Optional<BlockingReason> nothingFound;

    private SimpleCollectionProfile(Builder builder) {
        this.blocks = builder.blocks;
        this.tool = Objects.requireNonNull(builder.tool, "tool");
        this.toolIcon = Objects.requireNonNull(builder.toolIcon, "tool icon");
        this.radius = builder.radius;
        this.minimumCount = builder.minimumCount;
        this.productivity = builder.productivity;
        this.nothingFound = Optional.ofNullable(builder.nothingFound);
    }

    /**
     * @param blocks the blocks the Job scans and produces the drops of
     * @return a builder
     */
    public static Builder builder(TagKey<Block> blocks) {
        return new Builder(blocks);
    }

    @Override
    public boolean isTool(ItemStack stack) {
        return stack.is(tool);
    }

    @Override
    public Item toolIcon() {
        return toolIcon.get().asItem();
    }

    @Override
    public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).is(blocks);
    }

    @Override
    public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(blocks);
    }

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock) {
        return BlockSearch.nearest(level, jobBlock, AUTO_SEARCH_HORIZONTAL, HALF_HEIGHT, state -> state.is(blocks),
                pos -> MinerProfile.isExposed(level, pos));
    }

    @Override
    public int scanRadius(int jobBlockLevel) {
        return radius == null ? ResourceCollectionProfile.super.scanRadius(jobBlockLevel) : radius.applyAsInt(jobBlockLevel);
    }

    @Override
    public boolean rescanDaily() {
        return true;
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        return new BlockCountScan(center, radius, HALF_HEIGHT, state -> state.is(blocks), minimumCount);
    }

    @Override
    public boolean needsResources() {
        return true;
    }

    @Override
    public Optional<BlockingReason> blockingReason(@Nullable ScanResult scan) {
        return scan == null || scan.isEmpty() ? nothingFound : Optional.empty();
    }

    @Override
    public double productivity(@Nullable ScanResult scan) {
        return productivity.applyAsDouble(counted(scan).total());
    }

    /** The scan of this profile ({@link #createScan}), empty while none is done. */
    private static BlockCountScan.Result counted(@Nullable ScanResult scan) {
        return scan instanceof BlockCountScan.Result counted ? counted : BlockCountScan.Result.EMPTY;
    }

    @Override
    public Optional<Produced> produce(Context context) {
        BlockCountScan.Result scan = counted(context.scan());
        List<Identifier> harvestable = scan.foundBlocks().stream().filter(type -> canObtain(context.tool(), type)).toList();
        if (harvestable.isEmpty()) {
            return Optional.empty();
        }
        Set<Identifier> leftOut = leftOut(context, harvestable);
        List<Identifier> group = harvestable.stream().filter(type -> !leftOut.contains(type)).toList();
        if (group.isEmpty()) {
            // Every type excluded or at Max: an empty roll still counts as work (EXP, tool wear).
            return Optional.of(new Produced(List.of(), context.tool()));
        }
        int total = group.stream().mapToInt(type -> scan.counts().get(type)).sum();
        int pick = context.level().getRandom().nextInt(total);
        Identifier rolled = group.getLast();
        for (Identifier type : group) {
            pick -= scan.counts().get(type);
            if (pick < 0) {
                rolled = type;
                break;
            }
        }
        BlockState state = BuiltInRegistries.BLOCK.getValue(rolled).defaultBlockState();
        List<ItemStack> drops = Block.getDrops(state, context.level(), context.poi(), null, context.settler(), context.tool());
        return Optional.of(new Produced(drops, context.tool()));
    }

    @Override
    public boolean canObtain(ItemStack tool, Identifier block) {
        BlockState state = BuiltInRegistries.BLOCK.getValue(block).defaultBlockState();
        return !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
    }

    /** The first item of the block's loot with the tool. */
    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        BlockState state = BuiltInRegistries.BLOCK.getValue(type).defaultBlockState();
        return Block.getDrops(state, level, pos, null, null, tool).stream().filter(stack -> !stack.isEmpty()).findFirst()
                .map(ItemStack::getItem).orElse(Items.AIR);
    }

    /**
     * Builds a {@link SimpleCollectionProfile}.
     */
    @ApiStatus.Experimental
    public static final class Builder {

        private final TagKey<Block> blocks;
        private @Nullable TagKey<Item> tool;
        private @Nullable Supplier<? extends ItemLike> toolIcon;
        private @Nullable IntUnaryOperator radius;
        private int minimumCount = 1;
        private DoubleUnaryOperator productivity = total -> 1.0;
        private @Nullable BlockingReason nothingFound;

        private Builder(TagKey<Block> blocks) {
            this.blocks = blocks;
        }

        /**
         * @param tool the items that count as the Job's tool; required
         * @param icon the tool shown in the "no tool" thought bubble (its simplest usable form, usually the JobBlock's
         *             starter tool)
         * @return this builder
         */
        public Builder tool(TagKey<Item> tool, Supplier<? extends ItemLike> icon) {
            this.tool = tool;
            this.toolIcon = icon;
            return this;
        }

        /**
         * @param byJobBlockLevel the horizontal scan radius per JobBlock level (1–5); default 10 × level
         * @return this builder
         */
        public Builder radius(IntUnaryOperator byJobBlockLevel) {
            this.radius = byJobBlockLevel;
            return this;
        }


        /**
         * @param minimumCount how often a block type must be in the scan to count as found (default 1)
         * @return this builder
         */
        public Builder minimumCount(int minimumCount) {
            this.minimumCount = minimumCount;
            return this;
        }

        /**
         * @param byTotalCount the production speed factor by the total number of counted blocks (default 1)
         * @return this builder
         */
        public Builder productivity(DoubleUnaryOperator byTotalCount) {
            this.productivity = byTotalCount;
            return this;
        }

        /**
         * @param reason the reason the Job reports when no block of the tag is in reach (status "Blocked"); without
         *               one the Job shows "No work place" / "No resources"
         * @return this builder
         */
        public Builder whenNothingFound(BlockingReason reason) {
            this.nothingFound = reason;
            return this;
        }

        /**
         * @return the profile
         */
        public SimpleCollectionProfile build() {
            return new SimpleCollectionProfile(this);
        }
    }
}
