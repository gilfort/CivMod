package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * Miner (vault {@code Jobs/Miner.md}): pickaxe, exposed stone / ore blocks as work places, palette scan of the
 * stone and ore types around the scan centre, drops from the vanilla loot table with the settler's pickaxe.
 */
public final class MinerProfile implements JobProfile {

    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    private static final int AUTO_SEARCH_VERTICAL = 30;

    @Override
    public net.minecraft.world.item.Item toolIcon() {
        return net.minecraft.world.item.Items.STONE_PICKAXE;
    }

    @Override
    public boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES);
    }

    /**
     * @param state a block state
     * @return whether it is a stone or ore block
     */
    static boolean isMinable(BlockState state) {
        return state.is(Tags.Blocks.STONES) || state.is(Tags.Blocks.ORES);
    }

    @Override
    public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && isMinable(level.getBlockState(pos));
    }

    @Override
    public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Tags.Blocks.STONES) || state.is(Tags.Blocks.ORES);
    }

    /**
     * Nearest exposed stone / ore block: a settler can stand next to it or on top of it.
     */
    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock) {
        // Ordered by Manhattan distance: the first match is (nearly) the nearest; stops early in typical terrain.
        for (BlockPos pos : BlockPos.withinBoxByManhattanDistance(jobBlock, AUTO_SEARCH_HORIZONTAL, AUTO_SEARCH_VERTICAL, AUTO_SEARCH_HORIZONTAL)) {
            if (level.isLoaded(pos) && isMinable(level.getBlockState(pos)) && isExposed(level, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    /**
     * @param level the level
     * @param pos   a block
     * @return whether a settler can stand next to or on top of it
     */
    public static boolean isExposed(ServerLevel level, BlockPos pos) {
        if (SettlerWork.canStand(level, pos.above())) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (level.isLoaded(side) && SettlerWork.canStand(level, side)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BlockPos scanCenter(SettlementBlock block, BlockPos poiOfDay) {
        return block.workPoints().isEmpty() ? block.pos() : block.workPoints().get(0);
    }

    @Override
    public boolean rescanDaily() {
        return false;
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        return new MineScan(center, radius);
    }

    @Override
    public boolean needsResources() {
        return true;
    }

    @Override
    public Optional<Produced> produce(Context context) {
        if (!(context.scan() instanceof MineScan.Result scan)) {
            return Optional.empty();
        }
        ServerLevel level = context.level();
        Identifier poi = BuiltInRegistries.BLOCK.getKey(level.getBlockState(context.poi()).getBlock());
        Set<Identifier> leftOut = leftOut(context, Stream.concat(scan.stones().stream(), scan.ores().stream()).toList());
        MinerOutput.Roll roll = MinerOutput.roll(scan, poi, type -> canObtain(context.tool(), type), leftOut, context.skillLevel(),
                MinerWeights.get(), level.getRandom());
        if (roll.emptyRoll()) {
            return Optional.of(new Produced(List.of(), context.tool()));
        }
        Optional<Identifier> rolled = roll.type();
        if (rolled.isEmpty()) {
            return Optional.empty();
        }
        BlockState state = BuiltInRegistries.BLOCK.getValue(rolled.get()).defaultBlockState();
        List<ItemStack> drops = Block.getDrops(state, level, context.poi(), null, context.settler(), context.tool());
        return Optional.of(new Produced(drops, context.tool()));
    }

    /** The first item of the block's loot with the tool: cobblestone for stone without Silk Touch, raw iron for iron ore. */
    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        BlockState state = BuiltInRegistries.BLOCK.getValue(type).defaultBlockState();
        return Block.getDrops(state, level, pos, null, null, tool).stream().filter(stack -> !stack.isEmpty()).findFirst()
                .map(ItemStack::getItem).orElse(Items.AIR);
    }

    @Override
    public boolean canObtain(ItemStack tool, Identifier block) {
        BlockState state = BuiltInRegistries.BLOCK.getValue(block).defaultBlockState();
        return !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
    }
}
