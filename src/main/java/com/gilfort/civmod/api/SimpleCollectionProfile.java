package com.gilfort.civmod.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.BlockCountScan;
import com.gilfort.civmod.job.BlockSearch;
import com.gilfort.civmod.job.MinerProfile;
import com.gilfort.civmod.job.PlantStates;

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
 * <li>Tool: the items of the {@code tool} tag; without {@code tool(...)} the Job works bare-handed (no tool wear, never
 * "No tool").</li>
 * <li>Work place: the nearest exposed block of the tag (a settler can stand next to or on top of it) within 20 blocks of
 * the JobBlock, or a block of the tag set with the Scroll of Assignment. The world is never changed.</li>
 * <li>Scan: every day, around the work place of the day, radius by JobBlock level (default 10 × level), 10 blocks up and
 * down; each block type of the tag is counted, types found fewer than {@code minimumCount} times are left out. Plants
 * count only when ripe (crops at full age, berry bushes with berries, ripe cocoa, cave vines with glow berries) and a
 * two-block plant once.</li>
 * <li>Roll: a counted state weighted by its count (so a found type by its count), then the drops of its loot table (an
 * age-2 berry bush gives what an age-2 bush drops) with the settler's tool (Silk Touch and Fortune apply). Types the tool cannot harvest are never rolled; types excluded at the JobBlock or
 * at their Max at the Home Clerk leave the roll. Every found type is a main resource.</li>
 * <li>Speed: the shared speed formula × {@code productivity} of the total count (default 1) × the Job's farm curve, if it
 * has one ({@link Job#farmCurve()}).</li>
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
    private final @Nullable TagKey<Item> tool;
    private final @Nullable Supplier<? extends ItemLike> toolIcon;
    private final @Nullable IntUnaryOperator radius;
    private final int minimumCount;
    private final DoubleUnaryOperator productivity;
    private final Optional<BlockingReason> nothingFound;

    private SimpleCollectionProfile(Builder builder) {
        this.blocks = builder.blocks;
        this.tool = builder.tool;
        this.toolIcon = builder.toolIcon;
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
        return tool != null && stack.is(tool);
    }

    /** Without a tool: air (never shown, the Job never lacks a tool). */
    @Override
    public Item toolIcon() {
        return toolIcon == null ? Items.AIR : toolIcon.get().asItem();
    }

    @Override
    public boolean needsTool() {
        return tool != null;
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
        return autoSearch(level, jobBlock, pos -> true);
    }

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock, Predicate<BlockPos> free) {
        return BlockSearch.nearest(level, jobBlock, AUTO_SEARCH_HORIZONTAL, HALF_HEIGHT, state -> state.is(blocks),
                pos -> free.test(pos) && MinerProfile.isExposed(level, pos));
    }

    /** The work place of the day: a Specialization's work place, never another Job's work point. */
    @Override
    public BlockPos scanCenter(BlockPos jobBlock, List<BlockPos> workPoints, BlockPos workPlace) {
        return workPlace;
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
        return new BlockCountScan(center, radius, HALF_HEIGHT, state -> state.is(blocks) && PlantStates.counts(state), minimumCount);
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

    /** Every counted block. */
    @Override
    public int farmCount(@Nullable ScanResult scan) {
        return counted(scan).total();
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
        // Weighted by count per counted state: the roll gives the loot of the state it picks (an age-2 berry bush).
        Set<Identifier> rolledTypes = Set.copyOf(group);
        List<Map.Entry<BlockState, Integer>> states = scan.states().entrySet().stream()
                .filter(entry -> rolledTypes.contains(BuiltInRegistries.BLOCK.getKey(entry.getKey().getBlock()))).toList();
        int pick = context.level().getRandom().nextInt(states.stream().mapToInt(Map.Entry::getValue).sum());
        BlockState state = states.getLast().getKey();
        for (Map.Entry<BlockState, Integer> entry : states) {
            pick -= entry.getValue();
            if (pick < 0) {
                state = entry.getKey();
                break;
            }
        }
        List<ItemStack> drops = Block.getDrops(state, context.level(), context.poi(), null, context.settler(), context.tool());
        return Optional.of(new Produced(drops, context.tool()));
    }

    @Override
    public boolean canObtain(ItemStack tool, Identifier block) {
        BlockState state = PlantStates.rolled(BuiltInRegistries.BLOCK.getValue(block));
        return !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
    }

    /** The first item of the block's loot with the tool. */
    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        BlockState state = PlantStates.rolled(BuiltInRegistries.BLOCK.getValue(type));
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
         * @param tool the items that count as the Job's tool; without a tool the Job works bare-handed (no tool wear,
         *             never "No tool")
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
