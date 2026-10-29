package com.gilfort.civmod.registry;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CategoryProfile;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.job.AnimalFarmerProfile;
import com.gilfort.civmod.job.BeekeeperProfile;
import com.gilfort.civmod.job.CraftspersonProfile;
import com.gilfort.civmod.job.DomainProfile;
import com.gilfort.civmod.job.FarmingProfiles;
import com.gilfort.civmod.job.FishermanProfile;
import com.gilfort.civmod.job.LumberjackProfile;
import com.gilfort.civmod.job.MechanicProfile;
import com.gilfort.civmod.job.MinerProfile;
import com.gilfort.civmod.job.SmelterProfile;
import com.gilfort.civmod.job.SmithProfile;
import com.gilfort.civmod.job.VeggyFarmerProfile;

import net.minecraft.core.Registry;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The {@code civmod:job} registry (vault {@code Systems/Jobs.md}): static, filled at mod load and synced to clients.
 * CivMod registers its own Jobs through the addon API ({@link CivModApi}) like an addon does.
 */
public final class ModJobs {

    /** Registry key of {@code civmod:job}. */
    public static final ResourceKey<Registry<Job>> KEY = CivModApi.JOB_REGISTRY;

    /** Deferred register for jobs in the {@code civmod} namespace. */
    public static final DeferredRegister<Job> JOBS = CivModApi.jobs(CivMod.MOD_ID);

    /** The job registry. */
    public static final Registry<Job> REGISTRY = JOBS.makeRegistry(builder -> builder.sync(true));

    /** See vault {@code Jobs/Lumberjack.md}. */
    public static final DeferredHolder<Job, Job> LUMBERJACK = JOBS.register("lumberjack", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .icon(ModItems.LUMBERJACK_BLOCK).bubbleIcon(() -> Items.IRON_AXE).hungerFactor(1.0).baseExp(1).profile(LumberjackProfile::new).build());
    /** See vault {@code Jobs/Miner.md}. */
    public static final DeferredHolder<Job, Job> MINER = JOBS.register("miner", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .icon(ModItems.MINER_BLOCK).bubbleIcon(() -> Items.IRON_PICKAXE).hungerFactor(1.0).baseExp(1).profile(MinerProfile::new).build());
    /** See vault {@code Jobs/Fisherman.md}. */
    public static final DeferredHolder<Job, Job> FISHERMAN = JOBS.register("fisherman", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .icon(ModItems.FISHERMAN_BLOCK).bubbleIcon(() -> Items.COD).hungerFactor(0.5).baseExp(5).profile(FishermanProfile::new).build());
    /** See vault {@code Jobs/Gatherer.md}; collects what wild plants drop, bare-handed; the Base Job of the farming Specializations. */
    public static final DeferredHolder<Job, Job> GATHERER = JOBS.register("gatherer", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .icon(ModItems.GATHERER_BLOCK).bubbleIcon(() -> Items.SWEET_BERRIES).hungerFactor(1.0).baseExp(1).unlockItem(() -> Items.SWEET_BERRIES)
            .farmCurve(FarmingProfiles.PLANT_CURVE).profile(FarmingProfiles::gatherer).build());
    /** See vault {@code Jobs/Plantation Farmer.md}; a Specialization of the Gatherer, unlocked by bamboo. */
    public static final DeferredHolder<Job, Job> PLANTATION_FARMER = JOBS.register("plantation_farmer", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .parent(GATHERER).icon(() -> Items.BAMBOO).bubbleIcon(() -> Items.SUGAR_CANE).hungerFactor(1.0).baseExp(1).unlockItem(() -> Items.BAMBOO)
            .farmCurve(FarmingProfiles.PLANT_CURVE).profile(FarmingProfiles::plantationFarmer).build());
    /** See vault {@code Jobs/Veggy Farmer.md}; a Specialization of the Gatherer, unlocked by a composter; works the crops around a scarecrow. */
    public static final DeferredHolder<Job, Job> VEGGY_FARMER = JOBS.register("veggy_farmer", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .parent(GATHERER).icon(() -> Items.COMPOSTER).bubbleIcon(() -> Items.WHEAT).hungerFactor(1.0).baseExp(1).unlockItem(() -> Items.COMPOSTER)
            .farmCurve(FarmingProfiles.PLANT_CURVE).profile(VeggyFarmerProfile::new).build());
    /** See vault {@code Jobs/Adventurer.md}; the Base Job of the Mission Board. */
    public static final DeferredHolder<Job, Job> ADVENTURER = JOBS.register("adventurer", () -> Job.builder(JobCategory.MISSION)
            .icon(ModItems.MISSION_BOARD).bubbleIcon(() -> Items.MAP).hungerFactor(1.0).baseExp(10).build());
    /** See vault {@code Jobs/Bard.md}; a Mission Specialization of the Adventurer, unlocked by a note block on the Mission Board. */
    public static final DeferredHolder<Job, Job> BARD = JOBS.register("bard", () -> Job.builder(JobCategory.MISSION)
            .parent(ADVENTURER).icon(() -> Items.NOTE_BLOCK).bubbleIcon(() -> Items.NOTE_BLOCK).hungerFactor(1.0).baseExp(10)
            .unlockItem(() -> Items.NOTE_BLOCK).build());
    /** See vault {@code Jobs/Clerk.md}; the store and food point of the settlement, the first Logistics Job. */
    public static final DeferredHolder<Job, Job> CLERK = JOBS.register("clerk", () -> Job.builder(JobCategory.LOGISTICS)
            .icon(ModItems.CLERK_BLOCK).bubbleIcon(() -> Items.EMERALD).hungerFactor(0.5).baseExp(1).build());
    /** See vault {@code Jobs/Deliveryman.md}; its places are on the Clerk JobBlock ({@link com.gilfort.civmod.settlement.JobPlaces}). */
    public static final DeferredHolder<Job, Job> DELIVERYMAN = JOBS.register("deliveryman", () -> Job.builder(JobCategory.LOGISTICS)
            .icon(() -> Items.BUNDLE).bubbleIcon(() -> Items.BUNDLE).displayItem(() -> Items.BUNDLE).hungerFactor(1.0).baseExp(1).build());
    /** See vault {@code Jobs/Craftsperson.md}; crafts requested items from its patterns, the first Producing Job. */
    public static final DeferredHolder<Job, Job> CRAFTSPERSON = JOBS.register("craftsperson", () -> Job.builder(JobCategory.PRODUCING)
            .icon(ModItems.CRAFTSPERSON_BLOCK).bubbleIcon(ModItems.HAMMER).displayItem(ModItems.HAMMER).hungerFactor(0.5).baseExp(2).jobFactor(0.3)
            .unlockItem(() -> Items.CRAFTING_TABLE).profile(CraftspersonProfile::new).build());
    /** See vault {@code Jobs/Mechanic.md}; crafts redstone recipes fast at a Crafter, the first crafting Specialization. */
    public static final DeferredHolder<Job, Job> MECHANIC = JOBS.register("mechanic", () -> Job.builder(JobCategory.PRODUCING)
            .parent(CRAFTSPERSON).icon(() -> Items.CRAFTER).bubbleIcon(() -> Items.REDSTONE).displayItem(() -> Items.REDSTONE).hungerFactor(0.5)
            .baseExp(2).jobFactor(1.2).unlockItem(() -> Items.CRAFTER).profile(MechanicProfile::new).build());
    /** See vault {@code Jobs/Smith.md}; crafts metal recipes and netherite upgrades at a smithing table, fast with an anvil near it. */
    public static final DeferredHolder<Job, Job> SMITH = JOBS.register("smith", () -> Job.builder(JobCategory.PRODUCING)
            .parent(CRAFTSPERSON).icon(() -> Items.SMITHING_TABLE).bubbleIcon(() -> Items.ANVIL).displayItem(() -> Items.IRON_INGOT).hungerFactor(0.5)
            .baseExp(2).jobFactor(0.3).unlockItem(() -> Items.SMITHING_TABLE).profile(SmithProfile::new).build());
    /** See vault {@code Jobs/Fletcher.md}; crafts bows, arrows and string goods at a fletching table, fast with a target near it. */
    public static final DeferredHolder<Job, Job> FLETCHER = JOBS.register("fletcher", () -> Job.builder(JobCategory.PRODUCING)
            .parent(CRAFTSPERSON).icon(() -> Items.FLETCHING_TABLE).bubbleIcon(() -> Items.ARROW).displayItem(() -> Items.ARROW).hungerFactor(0.5)
            .baseExp(2).jobFactor(0.3).unlockItem(() -> Items.FLETCHING_TABLE).profile(DomainProfile::fletcher).build());
    /** See vault {@code Jobs/Stonemason.md}; crafts stone recipes and cuts stone at a stonecutter, fast with a grindstone near it. */
    public static final DeferredHolder<Job, Job> STONEMASON = JOBS.register("stonemason", () -> Job.builder(JobCategory.PRODUCING)
            .parent(CRAFTSPERSON).icon(() -> Items.STONECUTTER).bubbleIcon(() -> Items.STONE_BRICKS).displayItem(() -> Items.STONE_BRICKS).hungerFactor(0.5)
            .baseExp(2).jobFactor(0.3).unlockItem(() -> Items.STONECUTTER).profile(DomainProfile::stonemason).build());
    /** See vault {@code Jobs/Dyer.md}; makes dyes and dyes blocks at a loom, fast with a water cauldron near it. */
    public static final DeferredHolder<Job, Job> DYER = JOBS.register("dyer", () -> Job.builder(JobCategory.PRODUCING)
            .parent(CRAFTSPERSON).icon(() -> Items.LOOM).bubbleIcon(() -> Items.DYE.red()).displayItem(() -> Items.DYE.red()).hungerFactor(0.5)
            .baseExp(2).jobFactor(0.3).unlockItem(() -> Items.LOOM).profile(DomainProfile::dyer).build());
    /** See vault {@code Jobs/Smelter.md}; smelts requested items at furnaces, several in parallel, burning fuel. */
    public static final DeferredHolder<Job, Job> SMELTER = JOBS.register("smelter", () -> Job.builder(JobCategory.PRODUCING)
            .icon(ModItems.SMELTER_BLOCK).bubbleIcon(() -> Items.IRON_INGOT).displayItem(() -> Items.COAL).hungerFactor(0.5).baseExp(1)
            .jobFactor(1.0).unlockItem(() -> Items.FURNACE).profile(SmelterProfile::smelter).build());
    /** See vault {@code Jobs/Animal Farmer.md}; a Specialization of the Gatherer, unlocked by a hay bale. */
    public static final DeferredHolder<Job, Job> ANIMAL_FARMER = JOBS.register("animal_farmer", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .parent(GATHERER).icon(() -> Items.HAY_BLOCK).bubbleIcon(() -> Items.LEATHER).hungerFactor(1.0).baseExp(1).jobFactor(1.0)
            .unlockItem(() -> Items.HAY_BLOCK).farmCurve(FarmingProfiles.ANIMAL_CURVE).profile(AnimalFarmerProfile::new).build());
    /** See vault {@code Jobs/Beekeeper.md}; a Specialization of the Gatherer, unlocked by a beehive. */
    public static final DeferredHolder<Job, Job> BEEKEEPER = JOBS.register("beekeeper", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
            .parent(GATHERER).icon(() -> Items.BEEHIVE).bubbleIcon(() -> Items.HONEYCOMB).hungerFactor(1.0).baseExp(1)
            .unlockItem(() -> Items.BEEHIVE).farmCurve(FarmingProfiles.HIVE_CURVE).profile(BeekeeperProfile::new).build());
    /** See vault {@code Jobs/Cook.md}; cooks food at smokers, several in parallel, burning fuel; a Specialization of the Smelter. */
    public static final DeferredHolder<Job, Job> COOK = JOBS.register("cook", () -> Job.builder(JobCategory.PRODUCING)
            .parent(SMELTER).icon(() -> Items.SMOKER).bubbleIcon(() -> Items.COOKED_BEEF).displayItem(() -> Items.COOKED_BEEF).hungerFactor(0.5)
            .baseExp(1).jobFactor(1.0).unlockItem(() -> Items.SMOKER).profile(SmelterProfile::cook).build());
    /** See vault {@code Jobs/Founder.md}; smelts ores and metal at blast furnaces, several in parallel, burning fuel; a Specialization of the Smelter. */
    public static final DeferredHolder<Job, Job> FOUNDER = JOBS.register("founder", () -> Job.builder(JobCategory.PRODUCING)
            .parent(SMELTER).icon(() -> Items.BLAST_FURNACE).bubbleIcon(() -> Items.IRON_INGOT).displayItem(() -> Items.RAW_IRON).hungerFactor(0.5)
            .baseExp(1).jobFactor(1.0).unlockItem(() -> Items.BLAST_FURNACE).profile(SmelterProfile::founder).build());

    private ModJobs() {
    }

    /**
     * Attaches the job register (and the registry itself) to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        JOBS.register(modEventBus);
        modEventBus.addListener(ModJobs::checkProfiles);
    }

    /** Creates every Job's profile once, so a profile of the wrong Job Category fails at start-up, not in play. */
    private static void checkProfiles(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> REGISTRY.forEach(job -> job.profile(CategoryProfile.class)));
    }
}
