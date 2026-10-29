package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
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
import net.minecraft.world.level.block.Block;
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
 * <li>NPC drop pool ({@link JobDrops}): the scan also counts the blocks of the Job's entries, weighted by count against
 * the hives in the roll; the entries of a rolled hive or block add to its drops.</li>
 * <li>Speed: the shared formula × the farm curve of the counted hives.</li>
 * </ul>
 */
public final class BeekeeperProfile extends FarmProfile implements DropPoolProfile {

    private static final Identifier JOB = CivMod.id("beekeeper");

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
        JobDrops.Pool pool = pool();
        return new BlockCountScan(center, radius, HALF_HEIGHT, state -> honey(state) >= 1 || pool.counts(state), 1);
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
    public Optional<Identifier> dropPool() {
        return Optional.of(JOB);
    }

    /**
     * A counted hive or entry block weighted by its count: a hive gives one honeycomb, an entry block its loot (unless an
     * entry replaces it), then the entries of the picked state roll.
     */
    @Override
    public Optional<Produced> produce(Context context, JobDrops.Tools tools) {
        BlockCountScan.Result scan = hives(context.scan());
        List<Identifier> types = scan.foundBlocks();
        if (types.isEmpty()) {
            return Optional.empty();
        }
        Set<Identifier> leftOut = leftOut(context, types);
        JobDrops.Pool pool = pool();
        Set<Identifier> group = types.stream().filter(type -> !leftOut.contains(type) && !pool.exhausted(type, false, context.atMax(), tools))
                .collect(Collectors.toSet());
        if (group.isEmpty()) {
            return Optional.of(new Produced(List.of(), context.tool()));
        }
        BlockState state = scan.pick(group, context.level().getRandom());
        List<JobDrops.Drop> entries = pool.of(state);
        List<ItemStack> drops = new ArrayList<>();
        if (!JobDrops.replaces(entries)) {
            drops.addAll(honey(state) >= 1 ? List.of(new ItemStack(Items.HONEYCOMB))
                    : Block.getDrops(state, context.level(), context.poi(), null, context.settler(), context.tool()));
        }
        drops.addAll(JobDrops.roll(context.level(), entries, context.tool(), context.atMax(), tools, new ItemStack(state.getBlock())));
        return Optional.of(new Produced(drops, context.tool()));
    }

    /** Honeycomb from every hive and nest; the first item of an entry block's loot. */
    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        Block block = BuiltInRegistries.BLOCK.getValue(type);
        if (block.defaultBlockState().is(BlockTags.BEEHIVES)) {
            return Items.HONEYCOMB;
        }
        return Block.getDrops(block.defaultBlockState(), level, pos, null, null, tool).stream().filter(stack -> !stack.isEmpty()).findFirst()
                .map(ItemStack::getItem).orElse(Items.AIR);
    }

    @Override
    public Set<Item> droppedItems(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return pool().droppedItems(type, false, () -> Set.of(dropped(level, pos, type, tool)));
    }
}
