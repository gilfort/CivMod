package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.Tags;

/**
 * The Beekeeper (vault {@code Jobs/Beekeeper.md}), a Specialization of the Gatherer: collects honeycomb from the
 * beehives and bee nests that hold honey around its work place.
 * <ul>
 * <li>Tool: shears.</li>
 * <li>Work place: a beehive or bee nest set with the Scroll of Assignment, else the nearest within 20 blocks.</li>
 * <li>Scan: every day the hives and nests with honey level 1 or more within 5 × JobBlock level ({@link HiveScan}).</li>
 * <li>Roll: one honeycomb; a honey level is never changed.</li>
 * <li>Speed: the shared formula × the farm curve of the counted hives.</li>
 * </ul>
 */
public final class BeekeeperProfile implements ResourceCollectionProfile {

    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    /** How far up and down the scan and the work place search reach. */
    private static final int HALF_HEIGHT = 10;
    /** The scan radius per JobBlock level. */
    private static final int RADIUS_PER_LEVEL = 5;

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
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock) {
        return autoSearch(level, jobBlock, pos -> true);
    }

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock, Predicate<BlockPos> free) {
        return BlockSearch.nearest(level, jobBlock, AUTO_SEARCH_HORIZONTAL, HALF_HEIGHT, state -> state.is(BlockTags.BEEHIVES), free);
    }

    /** The hive of the day. */
    @Override
    public BlockPos scanCenter(BlockPos jobBlock, List<BlockPos> workPoints, BlockPos workPlace) {
        return workPlace;
    }

    @Override
    public int scanRadius(int jobBlockLevel) {
        return RADIUS_PER_LEVEL * jobBlockLevel;
    }

    @Override
    public boolean rescanDaily() {
        return true;
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        return new HiveScan(center, radius, HALF_HEIGHT);
    }

    @Override
    public boolean needsResources() {
        return true;
    }

    /** Every hive and nest with honey. */
    @Override
    public int farmCount(@Nullable ScanResult scan) {
        return hives(scan).total();
    }

    private static HiveScan.Result hives(@Nullable ScanResult scan) {
        return scan instanceof HiveScan.Result hives ? hives : HiveScan.Result.EMPTY;
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
