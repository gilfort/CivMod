package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
public final class MinerProfile implements ResourceCollectionProfile {

    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    private static final int AUTO_SEARCH_VERTICAL = 30;

    @Override
    public Item toolIcon() {
        return Items.STONE_PICKAXE;
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
        return autoSearch(level, jobBlock, pos -> true);
    }

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock, Predicate<BlockPos> free) {
        // Ordered by Manhattan distance: the first match is (nearly) the nearest; stops early in typical terrain.
        for (BlockPos pos : BlockPos.withinBoxByManhattanDistance(jobBlock, AUTO_SEARCH_HORIZONTAL, AUTO_SEARCH_VERTICAL, AUTO_SEARCH_HORIZONTAL)) {
            if (level.isLoaded(pos) && isMinable(level.getBlockState(pos)) && BlockSearch.isExposed(level, pos) && free.test(pos)) {
                return pos.immutable();
            }
        }
        return null;
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

    /** Ores instead of stone: {@code rare_chance} multiplies the ore share. */
    @Override
    public Optional<Component> rareGroup() {
        return Optional.of(Component.translatable("civmod.rare_group.ore"));
    }

    @Override
    public boolean inRareGroup(ScanResult scan, Identifier type) {
        return scan instanceof MineScan.Result result && result.ores().contains(type);
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
                MinerWeights.get(), context.rareChanceFactor(), level.getRandom());
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
