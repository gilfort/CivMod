package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import net.neoforged.neoforge.common.Tags;

/**
 * Lumberjack (vault {@code Jobs/Lumberjack.md}): axe, logs as work places, daily wood scan, optional leaves with
 * shears.
 */
public final class LumberjackProfile implements SecondaryToolProfile {

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

    /**
     * The Lumberjack's leaves option (vault {@code Jobs/Lumberjack.md}): with "collect leaves" set at the JobBlock it
     * also wants shears.
     */
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
    public BlockPos scanCenter(BlockPos jobBlock, List<BlockPos> workPoints, BlockPos workPlace) {
        return workPoints.isEmpty() ? workPlace : workPoints.get(0);
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
    public Optional<Produced> produce(Context context, Optional<ItemStack> shears) {
        WoodScan.Result scan = context.scan() instanceof WoodScan.Result wood ? wood : WoodScan.Result.EMPTY;
        Identifier poiLog = BuiltInRegistries.BLOCK.getKey(context.level().getBlockState(context.poi()).getBlock());
        boolean silkTouch = hasSilkTouch(context.level(), context.tool());
        // The shears are only looked up while "collect leaves" is set (secondaryTool).
        boolean leaves = shears.isPresent();
        return LumberjackOutput.produce(scan, poiLog, leaves, silkTouch, leftOut(context, scan.logs().keySet()), context.level().getRandom())
                .map(produced -> new Produced(produced.stack().isEmpty() ? List.of() : List.of(produced.stack()),
                produced.kind() == LumberjackOutput.Kind.LEAVES && !silkTouch ? shears.orElse(context.tool())
 : context.tool()));
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
        return BlockSearch.nearest(level, center, AUTO_SEARCH_HORIZONTAL, AUTO_SEARCH_VERTICAL, WoodScan::isLog);
    }
}
