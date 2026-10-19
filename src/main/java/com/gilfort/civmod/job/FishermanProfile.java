package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * Fisherman (vault {@code Jobs/Fisherman.md}): fishing rod, open water with a shore in reach as work place, water
 * count scan; a catch is treasure (vanilla treasure loot table) by the treasure chance, otherwise a fish of the
 * {@link FishTable} among those not excluded at the JobBlock.
 */
public final class FishermanProfile implements ResourceCollectionProfile {

    /** Fishing reach: the settler stands on the shore within this distance. */
    public static final double FISHING_REACH = 6.0;
    /** Open water for full productivity. */
    public static final int WATER_THRESHOLD = 50;
    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    private static final int AUTO_SEARCH_VERTICAL = 15;
    private static final ResourceKey<LootTable> TREASURE = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.withDefaultNamespace("gameplay/fishing/treasure"));

    @Override
    public Item toolIcon() {
        return Items.FISHING_ROD;
    }

    @Override
    public boolean isTool(ItemStack stack) {
        return stack.is(Tags.Items.TOOLS_FISHING_ROD);
    }

    @Override
    public double reach() {
        return FISHING_REACH;
    }

    // ---------------------------------------------------------------- formulas

    /**
     * @param skillLevel the skill level
     * @param lure       the Lure level
     * @return catches per second
     */
    public static double speed(int skillLevel, int lure) {
        return 0.05 * Math.pow(2, 2.0 * (skillLevel - 1) / 99.0) * (1.0 + 0.15 * lure);
    }

    /**
     * @param skillLevel the skill level
     * @param luck       the Luck of the Sea level
     * @return the treasure chance (0–1)
     */
    public static double treasureChance(int skillLevel, int luck) {
        return Math.clamp(0.25 * (skillLevel - 1) / 99.0 + 0.02 * luck, 0.0, 1.0);
    }

    /**
     * @param water open water blocks found
     * @return productivity factor (−1 % per block below {@value #WATER_THRESHOLD})
     */
    public static double waterProductivity(int water) {
        return Math.clamp(1.0 - 0.01 * (WATER_THRESHOLD - water), 0.0, 1.0);
    }

    @Override
    public double speed(int skillLevel, ItemStack tool) {
        return speed(skillLevel, enchantmentLevel(tool, Enchantments.LURE));
    }

    @Override
    public double productivity(@Nullable ScanResult scan) {
        return scan instanceof WaterScan.Result water ? waterProductivity(water.water()) : 1.0;
    }

    private static int enchantmentLevel(ItemStack stack, ResourceKey<Enchantment> key) {
        for (var entry : stack.getEnchantments().entrySet()) {
            Holder<Enchantment> holder = entry.getKey();
            if (holder.is(key)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- work places

    /**
     * Open water like vanilla open-water fishing: a source block at the surface, at least 2 deep, only water at water
     * level and only air above it within ±2.
     *
     * @param level the level
     * @param pos   a position
     * @return whether it is open water
     */
    public static boolean isOpenWater(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || !isSource(level, pos) || !isSource(level, pos.below())) {
            return false;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos column = pos.offset(dx, 0, dz);
                if (!level.isLoaded(column) || !level.getFluidState(column).is(FluidTags.WATER) || !level.getBlockState(column.above()).isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSource(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER) && level.getFluidState(pos).isSource();
    }

    /**
     * @param level the level
     * @param pos   a water block
     * @return whether a settler can stand on land within the fishing reach
     */
    public static boolean hasShore(ServerLevel level, BlockPos pos) {
        int search = (int) FISHING_REACH;
        for (BlockPos spot : BlockPos.betweenClosed(pos.offset(-search, -2, -search), pos.offset(search, 3, search))) {
            int dx = spot.getX() - pos.getX();
            int dz = spot.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= FISHING_REACH * FISHING_REACH && level.isLoaded(spot) && SettlerWork.canStand(level, spot)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
        return isOpenWater(level, pos) && hasShore(level, pos);
    }

    @Override
    public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER) && level.getFluidState(pos).isSource();
    }

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock) {
        for (BlockPos pos : BlockPos.withinBoxByManhattanDistance(jobBlock, AUTO_SEARCH_HORIZONTAL, AUTO_SEARCH_VERTICAL, AUTO_SEARCH_HORIZONTAL)) {
            if (isWorkPlace(level, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- scan

    @Override
    public BlockPos scanCenter(BlockPos jobBlock, List<BlockPos> workPoints, BlockPos workPlace) {
        return workPoints.isEmpty() ? jobBlock : workPoints.get(0);
    }

    @Override
    public boolean rescanDaily() {
        return false;
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        return new WaterScan(center, radius);
    }

    @Override
    public boolean needsResources() {
        return true;
    }

    // ---------------------------------------------------------------- output

    @Override
    public boolean swings() {
        return false;
    }

    @Override
    public Optional<Produced> produce(Context context) {
        ServerLevel level = context.level();
        boolean treasure = level.getRandom().nextDouble()
                < treasureChance(context.skillLevel(), enchantmentLevel(context.tool(), Enchantments.LUCK_OF_THE_SEA));
        List<ItemStack> items;
        if (treasure) {
            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(context.poi()))
                    .withParameter(LootContextParams.TOOL, context.tool())
                    .withOptionalParameter(LootContextParams.THIS_ENTITY, context.settler())
                    .create(LootContextParamSets.FISHING);
            items = level.getServer().reloadableRegistries().getLootTable(TREASURE).getRandomItems(params);
        } else {
            // A fish roll with every fish excluded catches nothing (an empty roll), never treasure instead.
            items = FishTable.get().pick(leftOut(context, FishTable.get().ids()), level.getRandom())
                    .map(fish -> List.of(fish.caught(level, Vec3.atCenterOf(context.poi()), context.tool(), context.settler())))
                    .orElse(List.of());
        }
        context.settler().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        Vec3 surface = Vec3.atCenterOf(context.poi()).add(0.0, 0.5, 0.0);
        level.sendParticles(ParticleTypes.SPLASH, surface.x, surface.y, surface.z, 12, 0.3, 0.0, 0.3, 0.1);
        level.playSound(null, context.poi(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 0.6F, 1.0F);
        return Optional.of(new Produced(items, context.tool()));
    }

    /** A fish type is the fish item itself. */
    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return BuiltInRegistries.ITEM.getValue(type);
    }

    @Override
    public List<Identifier> mainResources(ScanResult scan) {
        return FishTable.get().ids();
    }

    @Override
    public List<Found> found(ScanResult scan, Optional<Worker> worker) {
        return FishTable.get().fish().stream().map(fish -> new Found(fish.id(), new ItemStack(fish.item()), true)).toList();
    }

    @Override
    public List<Component> foundLines(ScanResult scan, Optional<Worker> worker) {
        int water = scan instanceof WaterScan.Result result ? result.water() : 0;
        List<Component> lines = new ArrayList<>(List.of(Component.translatable("civmod.gui.found.water", water)));
        if (water < WATER_THRESHOLD) {
            lines.add(Component.translatable("civmod.gui.found.water_penalty", Math.round((1.0 - waterProductivity(water)) * 100)));
        }
        lines.add(worker.<Component>map(found -> Component.translatable("civmod.gui.found.treasure",
                        Math.round(treasureChance(found.skillLevel(), enchantmentLevel(found.tool(), Enchantments.LUCK_OF_THE_SEA)) * 1000) / 10.0))
                .orElseGet(() -> Component.translatable("civmod.gui.found.treasure_none")));
        return lines;
    }
}
