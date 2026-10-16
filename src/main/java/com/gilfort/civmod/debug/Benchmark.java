package com.gilfort.civmod.debug;

import java.util.List;
import java.util.function.Supplier;

import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerSkins;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the PoC benchmark (vault {@code PoC.md}): one settlement with 20 JobBlocks (8 Lumberjack, 7 Miner,
 * 5 Fisherman) in a chain along +X, their work places, a pond, a Clerk with 8 linked chests and 20 equipped settlers.
 */
public final class Benchmark {

    /** Number of settlers / JobBlocks. */
    public static final int SIZE = 20;
    private static final int SPACING = 6;
    private static final int LUMBERJACKS = 8;
    private static final int MINERS = 7;
    private static final int FLAGS = Block.UPDATE_CLIENTS;
    private static final int CLERK_LEVEL = 3;
    private static final int CLERK_CHESTS = 8;

    private static final Scenario LUMBERJACK = new Scenario(ModJobs.LUMBERJACK, ModBlocks.LUMBERJACK_BLOCK, new Vec3i(2, 0, 4),
            Blocks.OAK_LOG, 5, Items.IRON_AXE);
    private static final Scenario MINER = new Scenario(ModJobs.MINER, ModBlocks.MINER_BLOCK, new Vec3i(2, 0, 3), Blocks.GRANITE, 1,
            Items.IRON_PICKAXE);
    private static final Scenario FISHERMAN = new Scenario(ModJobs.FISHERMAN, ModBlocks.FISHERMAN_BLOCK, new Vec3i(0, -1, 8), Blocks.WATER, 0,
            Items.FISHING_ROD);

    private Benchmark() {
    }

    /**
     * Builds the benchmark at the player position and starts the profiler.
     *
     * @param player the player
     * @return the number of settlers spawned
     */
    public static int build(ServerPlayer player) {
        return build(player.level(), player.blockPosition());
    }

    /**
     * Builds the benchmark at a position and starts the profiler.
     *
     * @param level  the level
     * @param origin the position of the first JobBlock
     * @return the number of settlers spawned
     */
    public static int build(ServerLevel level, BlockPos origin) {
        int length = (SIZE - 1) * SPACING;
        // Ground: three layers of stone, air above.
        for (int x = -6; x <= length + 6; x++) {
            for (int z = -6; z <= 14; z++) {
                for (int y = -3; y <= 8; y++) {
                    BlockState state = y < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(origin.offset(x, y, z), state, FLAGS);
                }
            }
        }
        // Pond for the Fishermen: two deep, in front of the last JobBlocks.
        int firstFisher = (LUMBERJACKS + MINERS) * SPACING;
        for (int x = firstFisher - 3; x <= length + 3; x++) {
            for (int z = 5; z <= 11; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.WATER.defaultBlockState(), FLAGS);
                level.setBlock(origin.offset(x, -2, z), Blocks.WATER.defaultBlockState(), FLAGS);
            }
        }
        // JobBlocks (the first one founds the settlement) and their work places.
        for (int i = 0; i < SIZE; i++) {
            BlockPos pos = origin.offset(i * SPACING, 0, 0);
            Scenario scenario = scenario(i);
            level.setBlock(pos, scenario.jobBlock().get().defaultBlockState(), Block.UPDATE_ALL);
            for (int y = 0; y < scenario.workHeight(); y++) {
                level.setBlock(pos.offset(scenario.workPlace()).above(y), scenario.workBlock().defaultBlockState(), FLAGS);
            }
        }
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(origin).orElseThrow();
        data.settlersArrived(settlement);
        // A Clerk (level 3) full of bread, with 8 linked chests of bread: the settlement's food point.
        BlockPos clerk = origin.offset(-3, 0, 0);
        level.setBlock(clerk, ModBlocks.CLERK_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(clerk) instanceof JobBlockEntity clerkBlock) {
            clerkBlock.setJobLevel(CLERK_LEVEL);
        }
        SettlementWorld.setLevel(level, clerk, CLERK_LEVEL);
        fillWithBread(level, clerk);
        for (int i = 0; i < CLERK_CHESTS; i++) {
            BlockPos chest = origin.offset(-5, 0, -4 + i);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            fillWithBread(level, chest);
            JobAssignments.cycleClerkPoint(data, clerk, chest, true, false, net.minecraft.core.Direction.EAST);
        }
        int spawned = 0;
        for (int i = 0; i < SIZE; i++) {
            BlockPos jobBlock = origin.offset(i * SPACING, 0, 0);
            Scenario scenario = scenario(i);
            BlockPos workPlace = jobBlock.offset(scenario.workPlace());
            Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.COMMAND);
            if (settler == null) {
                continue;
            }
            Vec3 spawn = Vec3.atBottomCenterOf(jobBlock.offset(0, 0, 2));
            settler.snapTo(spawn.x, spawn.y, spawn.z, level.getRandom().nextFloat() * 360.0F, 0.0F);
            settler.setSkin(level.getRandom().nextInt(SettlerSkins.COUNT));
            String name = "Bench " + (i + 1);
            settler.setCustomName(Component.literal(name));
            level.addFreshEntity(settler);
            settler.joinSettlement(data, settlement);
            settler.getInventory().setItem(Settler.BELT_START, new ItemStack(scenario.tool()));
            settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 8));
            JobAssignments.cyclePoint(data, jobBlock, workPlace, true, false);
            JobAssignments.assign(data, jobBlock, settler.getUUID(), name, settlement.id(), List.of());
            settler.addJob(new SettlerJob(scenario.job().get(), jobBlock));
            spawned++;
        }
        CivProfiler.enable();
        return spawned;
    }

    private static void fillWithBread(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                container.setItem(slot, new ItemStack(Items.BREAD, 64));
            }
        }
    }

    private static Scenario scenario(int index) {
        if (index < LUMBERJACKS) {
            return LUMBERJACK;
        }
        return index < LUMBERJACKS + MINERS ? MINER : FISHERMAN;
    }

    /**
     * What one benchmark JobBlock needs.
     *
     * @param job        the job
     * @param jobBlock   its JobBlock
     * @param workPlace  the work place relative to the JobBlock
     * @param workBlock  the block placed as work place (stacked {@code workHeight} high)
     * @param workHeight how many work blocks are stacked (0: none, e.g. the pond)
     * @param tool       the tool the settler gets
     */
    private record Scenario(Supplier<Job> job, Supplier<? extends Block> jobBlock, Vec3i workPlace, Block workBlock, int workHeight,
            Item tool) {
    }
}
