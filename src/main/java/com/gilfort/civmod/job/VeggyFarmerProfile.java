package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.BlockingReason;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;
import com.gilfort.civmod.api.SimpleCollectionProfile;
import com.gilfort.civmod.block.ScarecrowBlock;
import com.gilfort.civmod.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Veggy Farmer (vault {@code Jobs/Veggy Farmer.md}): a Specialization of the Gatherer that works the crop field
 * around a {@link ScarecrowBlock Scarecrow}.
 * <ul>
 * <li>Work place: a scarecrow set with the Scroll of Assignment (up to 8, one a day), else a random scarecrow within 20
 * blocks of the JobBlock; none: status "Blocked", "No scarecrow".</li>
 * <li>Scan: every day the mature crops ({@link CropBlock} at maximum age) of {@code #minecraft:crops} plus mature nether
 * wart within 5 × JobBlock level of the scarecrow of the day. Stems and the pitcher crop are no {@link CropBlock} and
 * do not count.</li>
 * <li>Roll: a crop type weighted by its count, then the loot of its mature state with the hoe (Fortune applies). The
 * crops are never harvested.</li>
 * <li>Tool: a hoe ({@code #minecraft:hoes}); speed: the shared formula × the plant farm curve of the Job.</li>
 * <li>NPC drop pool ({@link JobDrops}): the scan also counts the blocks of the Job's entries, the roll adds them.</li>
 * </ul>
 * Roll, tool and speed are those of a {@link SimpleCollectionProfile}; this profile adds the scarecrow and the crop
 * filter.
 */
public final class VeggyFarmerProfile extends FarmProfile implements DropPoolProfile {

    /** The reason without a scarecrow. */
    private static final BlockingReason NO_SCARECROW = BlockingReason.of("civmod.blocked.no_scarecrow").withIcon(ModItems.SCARECROW.get());
    /** How far up and down the scan and the scarecrow search reach. */
    private static final int HALF_HEIGHT = 10;

    private final SimpleCollectionProfile crops = SimpleCollectionProfile.builder(BlockTags.CROPS)
            .tool(ItemTags.HOES, () -> Items.STONE_HOE).dropPool(CivMod.id("veggy_farmer")).build();

    /**
     * @param state a block state
     * @return whether the Veggy Farmer counts it: a mature crop of {@code #minecraft:crops} or mature nether wart
     */
    static boolean counts(BlockState state) {
        boolean crop = state.is(BlockTags.CROPS) && state.getBlock() instanceof CropBlock;
        return (crop || state.is(Blocks.NETHER_WART)) && PlantStates.counts(state);
    }

    @Override
    public boolean isTool(ItemStack stack) {
        return crops.isTool(stack);
    }

    @Override
    public Item toolIcon() {
        return crops.toolIcon();
    }

    @Override
    public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && ScarecrowBlock.isLowerHalf(level.getBlockState(pos));
    }

    /** The lower half of a scarecrow (one work point per scarecrow). */
    @Override
    public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
        return ScarecrowBlock.isLowerHalf(level.getBlockState(pos));
    }

    /** A random free scarecrow in reach. */
    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock, Predicate<BlockPos> free) {
        List<BlockPos> found = BlockSearch.all(level, jobBlock, AUTO_SEARCH_HORIZONTAL, HALF_HEIGHT, ScarecrowBlock::isLowerHalf).stream()
                .filter(free).toList();
        return found.isEmpty() ? null : found.get(level.getRandom().nextInt(found.size()));
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        JobDrops.Pool pool = pool();
        return new BlockCountScan(center, radius, HALF_HEIGHT, state -> counts(state) || pool.counts(state), 1);
    }

    /** "No scarecrow" without a work place; an empty field is "No resources". */
    @Override
    public Optional<BlockingReason> blockingReason(@Nullable ScanResult scan) {
        return scan == null ? Optional.of(NO_SCARECROW) : Optional.empty();
    }

    @Override
    public int farmCount(@Nullable ScanResult scan) {
        return crops.farmCount(scan);
    }

    @Override
    public int count(ScanResult scan, JobCraftingRecipe.Requirement requires) {
        return crops.count(scan, requires);
    }

    @Override
    public Optional<Identifier> dropPool() {
        return crops.dropPool();
    }

    @Override
    public Optional<Produced> produce(Context context, JobDrops.Tools tools) {
        return crops.produce(context, tools);
    }

    @Override
    public boolean canObtain(ItemStack tool, Identifier block) {
        return crops.canObtain(tool, block);
    }

    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return crops.dropped(level, pos, type, tool);
    }

    @Override
    public Set<Item> droppedItems(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return crops.droppedItems(level, pos, type, tool);
    }
}
