package com.gilfort.civmod.api;

import java.util.function.Supplier;

import org.jetbrains.annotations.ApiStatus;

import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.block.StarterTools;
import com.gilfort.civmod.job.BiomeBonuses;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entry point of the CivMod addon API (vault {@code Systems/Jobs.md}): the Job registry, the JobBlock factory and the
 * query of the biome bonuses acting on a settler ({@link #biomeBonus}).
 * CivMod registers its own Jobs and JobBlocks through it, exactly like an addon. The getting-started guide is
 * {@code docs/api/getting-started.md} in the CivMod repository.
 * <p>
 * Everything in {@code com.gilfort.civmod.api} is experimental until the first release: breaking changes are allowed.
 */
@ApiStatus.Experimental
public final class CivModApi {

    /** CivMod's mod id; addons declare a dependency on it in their {@code neoforge.mods.toml}. */
    public static final String MOD_ID = "civmod";

    /** Key of the {@code civmod:job} registry: static, filled at mod load and synced to clients. */
    public static final ResourceKey<Registry<Job>> JOB_REGISTRY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(MOD_ID, "job"));

    private CivModApi() {
    }

    /**
     * @param namespace the namespace of the Jobs (the addon's mod id)
     * @return a deferred register for Jobs in that namespace; attach it to the mod event bus
     */
    public static DeferredRegister<Job> jobs(String namespace) {
        return DeferredRegister.create(JOB_REGISTRY, namespace);
    }

    /**
     * Creates a JobBlock (vault {@code Systems/JobBlock.md}): it offers the Job (and the Specializations its Station Types unlock)
     * to settlers, registers itself in a settlement when placed, opens the JobBlock window with the tabs of the Job's
     * category, keeps a level from 1 to 5 and a 27-slot inventory that hoppers and pipes reach. JobBlocks cannot be
     * pushed by pistons (the factory sets it).
     * <p>
     * Register it in your namespace together with an item from {@link #jobBlockItem}. You ship the blockstate, model,
     * texture, recipe, loot table (copying the {@code civmod:job_level} component keeps the level when the block is
     * broken), the settlers per level ({@code data/<namespace>/civmod_job_blocks/<job>.json}; 1 place on every level
     * without it) and lang keys. The JobBlock is upgraded with CivMod's JobBlock Upgrade items like every other one.
     * Specializations and Mission Jobs have no JobBlock of their own.
     *
     * @param job        the offered Base Job (resolved lazily, Jobs register after blocks)
     * @param properties the block properties
     * @return the JobBlock
     */
    public static Block jobBlock(Supplier<Job> job, BlockBehaviour.Properties properties) {
        return new JobBlock(job, properties.pushReaction(PushReaction.IMMOVEABLE));
    }

    /**
     * Creates the item of a JobBlock without a starter tool. It carries the JobBlock level ({@code civmod:job_level}),
     * so a broken JobBlock keeps its level when its loot table copies the component.
     *
     * @param block      the JobBlock from {@link #jobBlock}
     * @param properties the item properties
     * @return the JobBlock item
     */
    public static BlockItem jobBlockItem(Block block, Item.Properties properties) {
        return new BlockItem(block, properties.useBlockDescriptionPrefix());
    }

    /**
     * Creates the item of a JobBlock with a starter tool: a new item (crafted, creative, {@code /give}, loot) puts the
     * tool into the JobBlock's inventory when it is placed for the first time. The loot table of the block must remove
     * the {@code civmod:starter_tool} component from its drop ({@code "!civmod:starter_tool": {}}), or every break and
     * re-place adds another tool.
     *
     * @param block       the JobBlock from {@link #jobBlock}
     * @param starterTool the starter tool (resolved after registration)
     * @param properties  the item properties
     * @return the JobBlock item
     */
    public static BlockItem jobBlockItem(Block block, Supplier<? extends ItemLike> starterTool, Item.Properties properties) {
        BlockItem item = jobBlockItem(block, properties);
        StarterTools.register(item, starterTool);
        return item;
    }

    /**
     * The biome bonuses acting on a settler's Job at a JobBlock today (vault {@code Systems/JobBlock.md}): resolved
     * from the {@code civmod_biome_bonuses} files at the Job's biome position ({@link CategoryProfile#biomePosition()}).
     * The work place's biome is read once per day when the work place of the day is chosen.
     *
     * @param entity   a settler (another entity has no bonuses)
     * @param jobBlock the JobBlock of the Job
     * @return the bonuses, {@link BiomeBonus#NONE} if the entity is no settler holding a Job there, is on the client
     *         or has no work place yet
     */
    public static BiomeBonus biomeBonus(Entity entity, BlockPos jobBlock) {
        if (entity instanceof Settler settler && settler.level() instanceof ServerLevel level) {
            return settler.getWork().biomeBonus(level, jobBlock);
        }
        return BiomeBonus.NONE;
    }

    /**
     * The biome bonuses a Job would get at a position, whatever its biome position (a preview, e.g. for a work point
     * about to be set).
     *
     * @param level the level
     * @param job   the Job
     * @param pos   the position whose biome counts
     * @return the bonuses, with the biome even without an effect
     */
    public static BiomeBonus biomeBonusAt(ServerLevel level, Job job, BlockPos pos) {
        return BiomeBonuses.at(level, job, pos);
    }
}
