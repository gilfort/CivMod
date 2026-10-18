package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.common.Tags;

/**
 * Lumberjack (vault {@code Jobs/Lumberjack.md}): axe, logs as work places, daily wood scan, optional leaves with
 * shears.
 */
public final class LumberjackProfile implements JobProfile {

    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    private static final int AUTO_SEARCH_VERTICAL = 30;
    private static final Predicate<ItemStack> SHEARS = stack -> stack.is(Items.SHEARS) || stack.is(Tags.Items.TOOLS_SHEAR);

    @Override
    public net.minecraft.world.item.Item toolIcon() {
        return net.minecraft.world.item.Items.STONE_AXE;
    }

    @Override
    public boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.AXES);
    }

    @Override
    public Optional<Predicate<ItemStack>> secondaryTool(SettlementBlock block) {
        return block.collectLeaves() ? Optional.of(SHEARS) : Optional.empty();
    }

    @Override
    public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).is(BlockTags.LOGS);
    }

    @Override
    public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.LOGS);
    }

    @Override
    public boolean hasLeavesOption() {
        return true;
    }

    @Override
    public BlockPos scanCenter(SettlementBlock block, BlockPos poiOfDay) {
        return block.workPoints().isEmpty() ? poiOfDay : block.workPoints().get(0);
    }

    @Override
    public boolean rescanDaily() {
        return true;
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        return new WoodScan(center, radius);
    }

    @Override
    public double productivity(@Nullable ScanResult scan) {
        return LumberjackOutput.productivity(scan instanceof WoodScan.Result wood ? wood.totalLogs() : 0);
    }

    @Override
    public Optional<Produced> produce(Context context) {
        WoodScan.Result scan = context.scan() instanceof WoodScan.Result wood ? wood : WoodScan.Result.EMPTY;
        Identifier poiLog = BuiltInRegistries.BLOCK.getKey(context.level().getBlockState(context.poi()).getBlock());
        boolean silkTouch = hasSilkTouch(context.level(), context.tool());
        boolean leaves = context.block().collectLeaves() && context.secondary().isPresent();
        return LumberjackOutput.produce(scan, poiLog, leaves, silkTouch, context.block().excluded(), context.level().getRandom())
                .map(produced -> new Produced(produced.stack().isEmpty() ? List.of() : List.of(produced.stack()),
                produced.kind() == LumberjackOutput.Kind.LEAVES && !silkTouch ? context.secondary().orElse(context.tool()) : context.tool()));
    }

    private static boolean hasSilkTouch(ServerLevel level, ItemStack stack) {
        Holder<Enchantment> silkTouch = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        return stack.getEnchantmentLevel(silkTouch) > 0;
    }

    /**
     * Finds the nearest log ({@link WoodScan#isLog}: not of a building) around a JobBlock (±{@value #AUTO_SEARCH_HORIZONTAL} / ±{@value #AUTO_SEARCH_VERTICAL});
     * sections without logs are skipped via their palette.
     */
    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos center) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int sx = SectionPos.blockToSectionCoord(center.getX() - AUTO_SEARCH_HORIZONTAL);
                sx <= SectionPos.blockToSectionCoord(center.getX() + AUTO_SEARCH_HORIZONTAL); sx++) {
            for (int sz = SectionPos.blockToSectionCoord(center.getZ() - AUTO_SEARCH_HORIZONTAL);
                    sz <= SectionPos.blockToSectionCoord(center.getZ() + AUTO_SEARCH_HORIZONTAL); sz++) {
                if (!level.hasChunk(sx, sz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(sx, sz);
                for (int sy = SectionPos.blockToSectionCoord(center.getY() - AUTO_SEARCH_VERTICAL);
                        sy <= SectionPos.blockToSectionCoord(center.getY() + AUTO_SEARCH_VERTICAL); sy++) {
                    int index = level.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= chunk.getSectionsCount()) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(index);
                    if (section.hasOnlyAir() || !section.maybeHas(WoodScan::isLog)) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (!WoodScan.isLog(section.getBlockState(x, y, z))) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(SectionPos.sectionToBlockCoord(sx) + x, SectionPos.sectionToBlockCoord(sy) + y,
                                        SectionPos.sectionToBlockCoord(sz) + z);
                                if (Math.abs(pos.getX() - center.getX()) > AUTO_SEARCH_HORIZONTAL
                                        || Math.abs(pos.getZ() - center.getZ()) > AUTO_SEARCH_HORIZONTAL
                                        || Math.abs(pos.getY() - center.getY()) > AUTO_SEARCH_VERTICAL) {
                                    continue;
                                }
                                double distance = pos.distSqr(center);
                                if (distance < bestDistance) {
                                    bestDistance = distance;
                                    best = pos;
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}
