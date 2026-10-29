package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * The Beekeeper (vault {@code Jobs/Beekeeper.md}), a Specialization of the Gatherer: collects honeycomb from the
 * beehives and bee nests that hold honey around its work place.
 * <ul>
 * <li>Tool: shears.</li>
 * <li>Work place: a beehive or bee nest set with the Scroll of Assignment, else the nearest within 20 blocks.</li>
 * <li>Scan: every day the hives and nests with honey level 1 or more within 5 × JobBlock level, counted per block and
 * honey level ({@link BlockCountScan}; a full hive is one at honey level 5).</li>
 * <li>Roll: one honeycomb; a honey level is never changed.</li>
 * <li>Speed: the shared formula × the farm curve of the counted hives.</li>
 * </ul>
 */
public final class BeekeeperProfile extends FarmProfile {

    /** How far up and down the scan and the work place search reach. */
    private static final int HALF_HEIGHT = 10;

    @Override
    public boolean isTool(ItemStack stack) {
        return stack.is(Items.SHEARS) || stack.is(Tags.Items.TOOLS_SHEAR);
    }

    @Override
    public Item toolIcon() {
        return Items.SHEARS;
    }

    @Override
    public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).is(BlockTags.BEEHIVES);
    }

    @Override
    public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.BEEHIVES);
    }

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock, Predicate<BlockPos> free) {
        return BlockSearch.nearest(level, jobBlock, AUTO_SEARCH_HORIZONTAL, HALF_HEIGHT, state -> state.is(BlockTags.BEEHIVES), free);
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        return new BlockCountScan(center, radius, HALF_HEIGHT, state -> honey(state) >= 1, 1);
    }

    /** Every hive and nest with honey. */
    @Override
    public int farmCount(@Nullable ScanResult scan) {
        return hives(scan).total();
    }

    private static BlockCountScan.Result hives(@Nullable ScanResult scan) {
        return scan instanceof BlockCountScan.Result hives ? hives : BlockCountScan.Result.EMPTY;
    }

    /**
     * @param state a block state
     * @return its honey level if it is a beehive or bee nest, else -1
     */
    static int honey(BlockState state) {
        return state.is(BlockTags.BEEHIVES) && state.hasProperty(BeehiveBlock.HONEY_LEVEL) ? state.getValue(BeehiveBlock.HONEY_LEVEL) : -1;
    }

    /** The counted hives and nests in a state an innate recipe requires (a full beehive counts for beehives only). */
    @Override
    public int count(ScanResult scan, JobCraftingRecipe.Requirement requires) {
        return requires.block().isEmpty() ? 0 : hives(scan).matching(requires::matches);
    }

    @Override
    public Optional<Produced> produce(Context context) {
        List<Identifier> types = hives(context.scan()).foundBlocks();
        if (types.isEmpty()) {
            return Optional.empty();
        }
        Set<Identifier> leftOut = leftOut(context, types);
        boolean any = types.stream().anyMatch(type -> !leftOut.contains(type));
        return Optional.of(new Produced(any ? List.of(new ItemStack(Items.HONEYCOMB)) : List.of(), context.tool()));
    }

    /** Honeycomb from every hive and nest. */
    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return Items.HONEYCOMB;
    }
}
