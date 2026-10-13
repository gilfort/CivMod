package com.gilfort.civmod.debug;

import java.util.List;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.SettlerSkins;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobType;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the PoC benchmark (vault {@code PoC.md}): one settlement with 20 JobBlocks (8 Lumberjack, 7 Miner,
 * 5 Fisherman) in a chain along +X, their work places, a pond, a pantry and 20 equipped settlers.
 */
public final class Benchmark {

    /** Number of settlers / JobBlocks. */
    public static final int SIZE = 20;
    private static final int SPACING = 6;
    private static final int LUMBERJACKS = 8;
    private static final int MINERS = 7;
    private static final int FLAGS = Block.UPDATE_CLIENTS;

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
            level.setBlock(pos, jobType(i) == JobType.LUMBERJACK ? ModBlocks.LUMBERJACK_BLOCK.get().defaultBlockState()
                    : jobType(i) == JobType.MINER ? ModBlocks.MINER_BLOCK.get().defaultBlockState()
                    : ModBlocks.FISHERMAN_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            if (jobType(i) == JobType.LUMBERJACK) {
                for (int y = 0; y < 5; y++) {
                    level.setBlock(pos.offset(2, y, 4), Blocks.OAK_LOG.defaultBlockState(), FLAGS);
                }
            } else if (jobType(i) == JobType.MINER) {
                level.setBlock(pos.offset(2, 0, 3), Blocks.GRANITE.defaultBlockState(), FLAGS);
            }
        }
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(origin).orElseThrow();
        data.settlersArrived(settlement);
        // Pantry full of bread.
        BlockPos pantry = origin.offset(-3, 0, 0);
        level.setBlock(pantry, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(pantry) instanceof Container chest) {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                chest.setItem(slot, new ItemStack(Items.BREAD, 64));
            }
        }
        data.togglePantry(settlement, pantry);
        int spawned = 0;
        for (int i = 0; i < SIZE; i++) {
            BlockPos jobBlock = origin.offset(i * SPACING, 0, 0);
            JobType type = jobType(i);
            BlockPos workPlace = switch (type) {
                case LUMBERJACK -> jobBlock.offset(2, 0, 4);
                case MINER -> jobBlock.offset(2, 0, 3);
                case FISHERMAN -> jobBlock.offset(0, -1, 8);
            };
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
            settler.getInventory().setItem(Settler.BELT_START, new ItemStack(switch (type) {
                case LUMBERJACK -> Items.IRON_AXE;
                case MINER -> Items.IRON_PICKAXE;
                case FISHERMAN -> Items.FISHING_ROD;
            }));
            settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 8));
            JobAssignments.cyclePoint(data, jobBlock, workPlace, true, false);
            JobAssignments.assign(data, jobBlock, settler.getUUID(), name, settlement.id(), List.of());
            settler.addJob(new SettlerJob(type, jobBlock));
            spawned++;
        }
        CivProfiler.enable();
        return spawned;
    }

    private static JobType jobType(int index) {
        if (index < LUMBERJACKS) {
            return JobType.LUMBERJACK;
        }
        return index < LUMBERJACKS + MINERS ? JobType.MINER : JobType.FISHERMAN;
    }
}
