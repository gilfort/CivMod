package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.block.ScarecrowBlock;
import com.gilfort.civmod.job.BlockCountScan;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Veggy Farmer and the Scarecrow (vault {@code Jobs/Veggy Farmer.md}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VeggyFarmerGameTests {

    private static final Identifier CARROTS = Identifier.withDefaultNamespace("carrots");
    private static final Identifier NETHER_WART = Identifier.withDefaultNamespace("nether_wart");

    private VeggyFarmerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "veggy_farmer_only_mature_crops", VeggyFarmerGameTests::onlyMatureCrops);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * A grass floor (y 0) with farmland under the field (x 4–8, z 4–8); a level-2 Gatherer JobBlock at (1, 1, 1) with a
     * composter in slot 1.
     *
     * @return the JobBlock position (absolute)
     */
    private static BlockPos farm(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), x >= 4 && z >= 4 ? Blocks.FARMLAND : Blocks.GRASS_BLOCK);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.GATHERER_BLOCK.get());
        StationTypeGameTests.setLevel(helper, relative, 2);
        BlockPos jobBlock = helper.absolutePos(relative);
        TestAssignments.setStationType(SettlementData.get(helper.getLevel()), jobBlock, 1, Items.COMPOSTER);
        return jobBlock;
    }

    /** Places both halves of a scarecrow with its lower half at the relative position. */
    static void scarecrow(GameTestHelper helper, BlockPos relative) {
        BlockState lower = ModBlocks.SCARECROW.get().defaultBlockState();
        helper.setBlock(relative, lower);
        helper.setBlock(relative.above(), lower.setValue(ScarecrowBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static BlockState crop(Block block, int age) {
        return ((CropBlock) block).getStateForAge(age);
    }

    private static Settler veggyFarmer(GameTestHelper helper, BlockPos jobBlock) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 3), "Farmer");
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_HOE));
        TestAssignments.assign(data, jobBlock, settler.getUUID(), "Farmer", data.settlementAt(jobBlock).orElseThrow().id(), List.of(),
                ModJobs.VEGGY_FARMER.get());
        settler.addJob(new SettlerJob(ModJobs.VEGGY_FARMER.get(), jobBlock));
        return settler;
    }

    /**
     * Only mature crops of {@code #minecraft:crops} and mature nether wart count: young wheat, a grown melon stem (not a
     * crop) and a grown pitcher crop (two blocks, not a crop) do not. A field of young wheat gives nothing: the Veggy
     * Farmer reports "No resources".
     */
    private static void onlyMatureCrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = farm(helper);
        ResourceCollectionProfile profile = ModJobs.VEGGY_FARMER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        for (int x = 4; x < 7; x++) {
            helper.setBlock(new BlockPos(x, 1, 4), crop(Blocks.CARROTS, 7));
            helper.setBlock(new BlockPos(x, 1, 5), crop(Blocks.WHEAT, 3));
        }
        helper.setBlock(new BlockPos(4, 1, 6), Blocks.MELON_STEM.defaultBlockState().setValue(StemBlock.AGE, StemBlock.MAX_AGE));
        helper.setBlock(new BlockPos(8, 0, 8), Blocks.SOUL_SAND);
        helper.setBlock(new BlockPos(8, 1, 8), Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE));
        helper.setBlock(new BlockPos(8, 1, 6), Blocks.NETHER_WART);
        BlockPos center = helper.absolutePos(new BlockPos(5, 1, 5));
        ScanResult mixed = profile.createScan(center, 10).runAll(level);
        helper.assertValueEqual(((BlockCountScan.Result) mixed).counts(), Map.of(CARROTS, 3, NETHER_WART, 1), "Mature crops only");

        for (int x = 4; x < 9; x++) {
            for (int z = 4; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 1, z), crop(Blocks.WHEAT, 3));
            }
        }
        helper.assertTrue(profile.createScan(center, 10).runAll(level).isEmpty(), "Young wheat is not counted");
        scarecrow(helper, new BlockPos(6, 1, 2));
        Settler settler = veggyFarmer(helper, jobBlock);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.NO_RESOURCES, "Status " + settler.getWork().status(jobBlock));
            helper.assertTrue(settler.getWork().buffer(jobBlock).count() == 0, "Nothing collected");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
