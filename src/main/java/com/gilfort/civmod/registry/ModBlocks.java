package com.gilfort.civmod.registry;

import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlock;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for all blocks added by CivMod.
 * <p>
 * Every block needs a matching block item in {@link ModItems}, a blockstate, a model and a
 * {@code block.civmod.<name>} entry in {@code en_us.json}. JobBlocks come from the addon API's JobBlock factory
 * ({@link CivModApi#jobBlock}), like an addon's.
 */
public final class ModBlocks {

    /** Deferred register for blocks in the {@code civmod} namespace. */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CivMod.MOD_ID);

    /** JobBlock offering the Lumberjack job. */
    public static final DeferredBlock<JobBlock> LUMBERJACK_BLOCK = jobBlock("lumberjack_block", ModJobs.LUMBERJACK);
    /** JobBlock offering the Miner job. */
    public static final DeferredBlock<JobBlock> MINER_BLOCK = jobBlock("miner_block", ModJobs.MINER);
    /** JobBlock offering the Fisherman job. */
    public static final DeferredBlock<JobBlock> FISHERMAN_BLOCK = jobBlock("fisherman_block", ModJobs.FISHERMAN);

    /** The Mission Board: JobBlock of all Mission Jobs, offering the Adventurer and holding several settlers. */
    public static final DeferredBlock<JobBlock> MISSION_BOARD = jobBlock("mission_board", ModJobs.ADVENTURER);

    /** JobBlock offering the Clerk: the settlement's store and food point. */
    public static final DeferredBlock<JobBlock> CLERK_BLOCK = jobBlock("clerk_block", ModJobs.CLERK);

    /** JobBlock offering the Craftsperson: the pattern library of the first Producing Job. */
    public static final DeferredBlock<JobBlock> CRAFTSPERSON_BLOCK = jobBlock("craftsperson_block", ModJobs.CRAFTSPERSON);

    private ModBlocks() {
    }

    private static DeferredBlock<JobBlock> jobBlock(String name, Supplier<Job> jobType) {
        return BLOCKS.registerBlock(name, properties -> (JobBlock) CivModApi.jobBlock(jobType, properties), ModBlocks::jobBlockProperties);
    }

    private static BlockBehaviour.Properties jobBlockProperties(BlockBehaviour.Properties properties) {
        return properties.mapColor(MapColor.WOOD)
                .instrument(NoteBlockInstrument.BASS)
                .strength(2.5F)
                .sound(SoundType.WOOD)
                .ignitedByLava();
    }

    /**
     * Attaches the block register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
