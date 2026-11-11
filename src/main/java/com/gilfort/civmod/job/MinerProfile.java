package com.gilfort.civmod.job;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;
import com.gilfort.civmod.api.XpGain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * Miner (vault {@code Jobs/Miner.md}): pickaxe, exposed stone / ore blocks as work places, palette scan of the
 * stone and ore types around the scan centre, drops from the vanilla loot table with the settler's pickaxe.
 */
public final class MinerProfile implements ResourceCollectionProfile {

    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    private static final int AUTO_SEARCH_VERTICAL = 30;
    /** Below this chance (0.05 %) the Found tab shows "&lt;0.1" instead of 0.0. */
    private static final double MIN_SHOWN_CHANCE = 0.0005;
    /** Redstone ore is no {@link DropExperienceBlock}; its range is fixed in vanilla. */
    private static final XpGain REDSTONE_ORE_XP = XpGain.of(1, 5);

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

    /**
     * Each type named with its chance; the block counts stay out of the tab, so it is no soil sample. The chance is at
     * the working settler's skill level and tool (skill 1 and no tool limit without a settler).
     */
    @Override
    public List<Found> found(ScanResult scan, Optional<Worker> worker) {
        List<Found> found = ResourceCollectionProfile.super.found(scan, worker);
        if (!(scan instanceof MineScan.Result mined)) {
            return found;
        }
        Map<Identifier, Double> chances = MinerOutput.chances(mined, type -> worker.map(working -> canObtain(working.tool(), type)).orElse(true),
                worker.map(Worker::skillLevel).orElse(1), MinerWeights.get());
        return found.stream().map(entry -> {
            Double chance = chances.get(entry.type());
            if (chance == null) {
                return entry;
            }
            ItemStack icon = entry.icon().copy();
            icon.set(DataComponents.ITEM_NAME, Component.translatable("civmod.gui.found.chance",
                    BuiltInRegistries.BLOCK.getValue(entry.type()).getName(), chanceText(chance)));
            return new Found(entry.type(), icon, entry.available());
        }).toList();
    }

    /** A chance in percent with one decimal; "&lt;0.1" below that. */
    static String chanceText(double chance) {
        return chance < MIN_SHOWN_CHANCE ? "<0.1" : Double.toString(Math.round(chance * 1000) / 10.0);
    }

    @Override
    public boolean inRareGroup(ScanResult scan, Identifier type) {
        return scan instanceof MineScan.Result result && result.ores().containsKey(type);
    }

    @Override
    public Optional<Produced> produce(Context context) {
        if (!(context.scan() instanceof MineScan.Result scan)) {
            return Optional.empty();
        }
        ServerLevel level = context.level();
        Identifier poi = BuiltInRegistries.BLOCK.getKey(level.getBlockState(context.poi()).getBlock());
        Set<Identifier> leftOut = leftOut(context, scan.foundBlocks());
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
        return Optional.of(new Produced(drops, context.tool(), blockXp(level, state, context.tool())));
    }

    /**
     * The block XP of vanilla mining (vault {@code Systems/Settler XP.md}).
     *
     * @param level the level
     * @param state the mined block
     * @param tool  the tool
     * @return the XP range of an ore, none for other blocks and with Silk Touch
     */
    static XpGain blockXp(ServerLevel level, BlockState state, ItemStack tool) {
        if (EnchantmentHelper.processBlockExperience(level, tool, 1) <= 0) {
            return XpGain.NONE;
        }
        if (state.getBlock() instanceof DropExperienceBlock ore) {
            return XpGain.of(ore.xpRange.minInclusive(), ore.xpRange.maxInclusive());
        }
        return state.getBlock() instanceof RedStoneOreBlock ? REDSTONE_ORE_XP : XpGain.NONE;
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
