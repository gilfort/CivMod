package com.gilfort.civmod.example;

import com.gilfort.civmod.api.BlockingReason;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.CivModJobs;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.MissionProfile;
import com.gilfort.civmod.api.SimpleCollectionProfile;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Example addon for CivMod's addon API: every Job here uses {@code com.gilfort.civmod.api} only (the build fails on any
 * other CivMod class).
 * <p>
 * The Scout is a Mission Job. Like every Mission Job it has no JobBlock: it is a Specialization of the Adventurer, so the
 * Mission Board offers it from level 2. Its mission {@code civmod_example:scouting_trip} names it in the datapack
 * ({@code data/civmod_example/civmod/mission/scouting_trip.json}); its names are the lang keys
 * {@code job.civmod_example.scout} and {@code job.civmod_example.scout.indefinite}.
 * <p>
 * The Stonecutter is a Producing Specialization of the Craftsperson: it has no JobBlock either; a stonecutter put into a
 * Station Types slot of a Craftsperson JobBlock unlocks it there, with its own pattern library, and it fills Clerk
 * requests from {@code stonecutting} recipes at a stonecutter in the world.
 * <p>
 * The Sand Digger is a Resource Collection Job built with {@link SimpleCollectionProfile}: it counts the sand around its
 * JobBlock and produces its drops with a shovel, without digging the world away. Its JobBlock comes from the factory;
 * the addon ships its blockstate, models, recipe, loot table, upgrade costs
 * ({@code data/civmod_example/civmod_upgrades/sand_digger.json}) and lang keys.
 */
@Mod(CivModExample.MOD_ID)
public final class CivModExample {

    /** The mod id. */
    public static final String MOD_ID = "civmod_example";

    /** Jobs in the {@code civmod_example} namespace. */
    public static final DeferredRegister<Job> JOBS = CivModApi.jobs(MOD_ID);
    /** Blocks in the {@code civmod_example} namespace. */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    /** Items in the {@code civmod_example} namespace. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    /** A careful Mission Job: half the death chance of the outcome formula. */
    public static final DeferredHolder<Job, Job> SCOUT = JOBS.register("scout", () -> Job.builder(JobCategory.MISSION)
            .parent(CivModJobs.ADVENTURER).icon(() -> Items.SPYGLASS).hungerFactor(1.0).baseExp(8).profile(ScoutProfile::new).build());

    /** The Sand Digger's JobBlock. */
    public static final DeferredBlock<Block> SAND_DIGGER_BLOCK = BLOCKS.registerBlock("sand_digger_block",
            properties -> CivModApi.jobBlock(CivModExample::sandDigger, properties),
            properties -> properties.mapColor(MapColor.SAND).strength(2.5F).sound(SoundType.WOOD));

    /** The Sand Digger's JobBlock item; a new one brings a stone shovel. */
    public static final DeferredItem<BlockItem> SAND_DIGGER_BLOCK_ITEM = ITEMS.registerItem("sand_digger_block",
            properties -> CivModApi.jobBlockItem(SAND_DIGGER_BLOCK.get(), () -> Items.STONE_SHOVEL, properties));

    /** Digs sand (and red sand) around its JobBlock; without sand in reach it is blocked with its own reason. */
    public static final DeferredHolder<Job, Job> SAND_DIGGER = JOBS.register("sand_digger", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .icon(SAND_DIGGER_BLOCK_ITEM).bubbleIcon(() -> Items.STONE_SHOVEL).hungerFactor(1.2).baseExp(1)
            .profile(() -> SimpleCollectionProfile.builder(BlockTags.SAND)
                    .tool(ItemTags.SHOVELS, () -> Items.STONE_SHOVEL)
                    .whenNothingFound(BlockingReason.of("civmod_example.blocked.no_sand").withIcon(Items.SAND))
                    .build())
            .build());

    /**
     * A Producing Specialization of the Craftsperson, unlocked at a Craftsperson JobBlock by a stonecutter in a Station
     * Types slot; it cuts stone at a stonecutter from {@code stonecutting} recipes ({@link StonecutterProfile}), four times
     * as fast as a Craftsperson crafts (job factor 1.2).
     */
    public static final DeferredHolder<Job, Job> STONECUTTER = JOBS.register("stonecutter", () -> Job.builder(JobCategory.PRODUCING)
            .parent(CivModJobs.CRAFTSPERSON).unlockItem(() -> Items.STONECUTTER).icon(() -> Items.STONECUTTER).bubbleIcon(() -> Items.STONE_BRICKS)
            .hungerFactor(0.5).baseExp(2).jobFactor(1.2).profile(StonecutterProfile::new).build());

    /**
     * Registers the addon's content.
     *
     * @param modEventBus the mod event bus
     */
    public CivModExample(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        JOBS.register(modEventBus);
        modEventBus.addListener(CivModExample::addToCreativeTab);
    }

    private static Job sandDigger() {
        return SAND_DIGGER.get();
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SAND_DIGGER_BLOCK_ITEM);
        }
    }

    /** The Scout's mission specifics: it avoids danger, so it dies half as often. */
    private static final class ScoutProfile implements MissionProfile {

        @Override
        public double deathChance(double chance, int recommendedLevel, int skill) {
            return chance / 2.0;
        }
    }
}
