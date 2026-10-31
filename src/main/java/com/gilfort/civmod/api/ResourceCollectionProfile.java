package com.gilfort.civmod.api;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.LumberjackOutput;
import com.gilfort.civmod.job.ToolTiers;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The specifics of a Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md}): tool, work places,
 * scan and output. The category's loop does everything else: fetching the tool, walking, fatigue, the buffer and its
 * delivery to the drop-off, food, EXP, catch-up, the JobBlock's Found tab with its exclusions and the Max of the Home
 * Clerk's stock rules.
 * <p>
 * Most collecting Jobs count blocks and produce their drops: {@link SimpleCollectionProfile#builder} builds such a
 * profile from a block tag and a tool tag. Implement this interface for Jobs with their own scan or roll (CivMod's
 * Lumberjack, Miner, Fisherman, Veggy Farmer, Animal Farmer and Beekeeper do).
 * <p>
 * A scan runs spread over ticks ({@link SectionScan}, one chunk section per step) whenever the scan centre or radius
 * change, or every day ({@link #rescanDaily()}); the settler keeps working with the previous result meanwhile.
 */
@ApiStatus.Experimental
public interface ResourceCollectionProfile extends CategoryProfile {

    @Override
    default JobCategory category() {
        return JobCategory.RESOURCE_COLLECTION;
    }

    /**
     * @param stack an item stack
     * @return whether it is the job's tool
     */
    boolean isTool(ItemStack stack);

    /**
     * @return the tool shown in the "no tool" thought bubble (the job's tool in its simplest usable form)
     */
    Item toolIcon();

    /**
     * @param level the level
     * @param pos   a position
     * @return whether it is a valid (loaded) work place
     */
    boolean isWorkPlace(ServerLevel level, BlockPos pos);

    /**
     * @param level the level
     * @param pos   a position
     * @return whether a player may set the block as work place with the Scroll of Assignment
     */
    boolean isAssignableWorkPlace(Level level, BlockPos pos);

    /**
     * Finds a work place when the JobBlock has no valid work point (at most once per decision without one).
     *
     * @param level    the level
     * @param jobBlock the JobBlock position
     * @return the work place, if any
     */
    @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock);

    /**
     * Finds a work place that is not occupied by another settler (vault {@code Systems/Jobs.md}). The loop calls this
     * one; by default it takes {@link #autoSearch(ServerLevel, BlockPos)} and gives up when that place is occupied.
     * Override it to find the next free place instead (CivMod's Jobs and {@link SimpleCollectionProfile} do).
     *
     * @param level    the level
     * @param jobBlock the JobBlock position
     * @param free     whether a position is free for the settler (not occupied by another settler today)
     * @return the free work place, if any
     */
    default @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock, Predicate<BlockPos> free) {
        BlockPos found = autoSearch(level, jobBlock);
        return found != null && free.test(found) ? found : null;
    }

    /**
     * @param jobBlock   the JobBlock position
     * @param workPoints the JobBlock's work points set with the Scroll of Assignment, in order
     * @param workPlace  the work place of the day
     * @return the centre of the scan, by default the first work point, else the JobBlock
     */
    default BlockPos scanCenter(BlockPos jobBlock, List<BlockPos> workPoints, BlockPos workPlace) {
        return workPoints.isEmpty() ? jobBlock : workPoints.get(0);
    }


    /**
     * @param jobBlockLevel the JobBlock level (1–5)
     * @return the horizontal radius of the scan, by default the settlement radius of the JobBlock (10 × level)
     */
    default int scanRadius(int jobBlockLevel) {
        return SettlementRules.joinRadius(jobBlockLevel);
    }

    /**
     * @return whether the scan is repeated every day (otherwise only when centre or radius change)
     */
    boolean rescanDaily();

    /**
     * @param center the scan centre
     * @param radius the horizontal radius ({@link #scanRadius})
     * @return a new scan
     */
    SectionScan<? extends ScanResult> createScan(BlockPos center, int radius);

    /**
     * @return whether a scan without resources ({@link #hasResources}) stops the job (status "No resources")
     */
    default boolean needsResources() {
        return false;
    }

    /**
     * Only read for a Job that {@link #needsResources()}.
     *
     * @param scan the scan result
     * @return whether the scan holds enough to work with, by default anything at all
     */
    default boolean hasResources(ScanResult scan) {
        return !scan.isEmpty();
    }

    /**
     * Why the Job cannot work, in its own words (vault {@code Systems/Job Categories.md}, status "Blocked"). Asked at
     * every work decision; a reason pauses the job like "Drop-off full": the settler works its next priority and
     * checks again after 30 s. The reason is the job's status text in the settler window, and the settler's problem
     * bubble shows its icon (or the generic "blocked" marker). Neutral by default.
     *
     * @param scan the finished scan, or null when no work place was found
     * @return the reason, empty when the Job can work
     */
    default Optional<BlockingReason> blockingReason(@Nullable ScanResult scan) {
        return Optional.empty();
    }

    /**
     * @param scan the scan result
     * @return production speed factor from the area (1 = normal)
     */
    default double productivity(@Nullable ScanResult scan) {
        return 1.0;
    }

    /**
     * The size of the farm in the scan that the Job's farm curve ({@link Job#farmCurve()}) runs on: more counted plants,
     * animals or hives make it faster, up to a cap per JobBlock level. Only read for a Job with a farm curve.
     *
     * @param scan the scan result (null while the first scan runs)
     * @return the counted size, 0 by default
     */
    default int farmCount(@Nullable ScanResult scan) {
        return 0;
    }

    /**
     * @return whether the Job needs a tool on the belt (status "No tool" without one); a Job without works bare-handed
     *         with {@link ItemStack#EMPTY} as its tool and wears nothing
     */
    default boolean needsTool() {
        return true;
    }

    /**
     * The rare group of the Job's roll (vault {@code Systems/JobBlock.md}, decisions on biome bonuses): a roll that first
     * picks a group and then a type in it has a rare group (Miner: ores instead of stone; Fisherman: treasure instead
     * of fish), whose chance a biome bonus's {@code rare_chance} multiplies. A profile with one applies the factor in
     * {@link #produce} with {@link Context#rareChance(double)}; without one (the default) {@code rare_chance} has no
     * effect on the Job.
     *
     * @return the name of the rare group ("Ore", "Treasure"), empty if the Job has none
     */
    default Optional<Component> rareGroup() {
        return Optional.empty();
    }

    /**
     * Whether a found type belongs to the {@link #rareGroup()}, so a {@code rare_chance} bonus acts on it (the Found tab
     * marks it with a star). None by default.
     *
     * @param scan the scan result
     * @param type a type the Found tab lists ({@link #found})
     * @return whether it is in the rare group (Miner: an ore)
     */
    default boolean inRareGroup(ScanResult scan, Identifier type) {
        return false;
    }

    /**
     * Produces one resource. The loop applies the biome bonus's {@code double_drop} to the returned stacks.
     *
     * @param context the production context
     * @return the produced items and the tool to wear, empty if nothing was produced
     */
    Optional<Produced> produce(Context context);

    /**
     * @param tool  the current tool (may be empty)
     * @param block a found block type
     * @return whether the tool can obtain that type (shown greyed out otherwise)
     */
    default boolean canObtain(ItemStack tool, Identifier block) {
        return true;
    }

    /**
     * @return whether the settler strikes its work place continuously (axe / pickaxe); otherwise the profile animates
     *         itself in {@link #produce}
     */
    default boolean swings() {
        return true;
    }

    /**
     * @return how close (horizontally) the settler must be to its work place
     */
    default double reach() {
        return 2.5;
    }

    /**
     * @param skillLevel the skill level
     * @param tool       the tool
     * @return resources per second (before the area productivity)
     */
    default double speed(int skillLevel, ItemStack tool) {
        return LumberjackOutput.speed(skillLevel) * ToolTiers.multiplier(ToolTiers.tier(tool));
    }

    /**
     * The main resources of the job in an area (logs, stones and ores, fish): when every one of them is excluded at
     * the JobBlock or at its Max at the Home Clerk the job pauses ("All resources excluded" / "Maximum reached" /
     * "Nothing to produce"); rare drops and treasure never keep it running.
     *
     * @param scan the scan result
     * @return the main resource types, by default the found block types
     */
    default List<Identifier> mainResources(ScanResult scan) {
        return scan.foundBlocks();
    }

    /**
     * The item a found type drops (vault {@code Systems/Resource Collection Jobs.md}): what the Max check of its Home
     * Clerk's stock rule looks at unless {@link #droppedItems} names more, and what a biome bonus's double drop is
     * checked against in the Found tab. By default the block's item.
     *
     * @param level the level
     * @param pos   where it would be mined (loot context origin)
     * @param type  a found type (block id, for the Fisherman the fish item id)
     * @param tool  the tool it would be obtained with (Silk Touch / Fortune considered; may be empty)
     * @return the dropped item ({@code minecraft:air} if it drops nothing)
     */
    default Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return BuiltInRegistries.BLOCK.getValue(type).asItem();
    }

    /**
     * Every item a found type can give, for the Max check of its Home Clerk's stock rule (vault
     * {@code Systems/Resource Collection Jobs.md}): the type is at its Max only when all of them are. By default the one
     * {@link #dropped} item; override it when a type's loot gives several items (CivMod's Animal Farmer: beef and
     * leather from a cow).
     *
     * @param level the level
     * @param pos   where it would be obtained (loot context origin)
     * @param type  a found type
     * @param tool  the tool it would be obtained with (may be empty)
     * @return the items it can give (never empty; {@code minecraft:air} alone if it gives nothing)
     */
    default Set<Item> droppedItems(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return Set.of(dropped(level, pos, type, tool));
    }

    /**
     * @param items the items a type can give ({@link #droppedItems})
     * @param atMax the items at their Max at the Home Clerk
     * @return whether the type is at its Max: every item it can give is
     */
    static boolean atMax(Set<Item> items, Set<Item> atMax) {
        return !items.isEmpty() && atMax.containsAll(items);
    }

    /**
     * The types a roll leaves out of its group (vault {@code Systems/Resource Collection Jobs.md}): those excluded at
     * the JobBlock and those at their Max at the Home Clerk (every item of {@link #droppedItems} at its Max).
     *
     * @param context the production context
     * @param types   the types of the rolled group
     * @return the types left out
     */
    default Set<Identifier> leftOut(Context context, Collection<Identifier> types) {
        Set<Identifier> excluded = context.excluded();
        if (context.atMax().isEmpty()) {
            return excluded;
        }
        Set<Identifier> left = new HashSet<>(excluded);
        for (Identifier type : types) {
            if (!left.contains(type) && atMax(context.dropped().apply(type), context.atMax())) {
                left.add(type);
            }
        }
        return left;
    }

    /**
     * How much of what an innate recipe of the Job requires ({@link JobCraftingRecipe#requires()}) the scan found (vault
     * {@code Systems/Resource Collection Jobs.md}, decision 2026-10-29): while it is more than 0 the recipe is a Found tab
     * entry and joins the roll with its weight × this count (20 cows: milk weighs 20). By default the number of found
     * block types whose default state matches a block requirement (the scan has no counts per block); entity
     * requirements never match. Override it for a scan that counts blocks, block states or entities
     * ({@link SimpleCollectionProfile} and CivMod's Animal Farmer and Beekeeper do).
     *
     * @param scan     the scan result
     * @param requires what the recipe requires
     * @return how many of it the scan found, 0 if none
     */
    default int count(ScanResult scan, JobCraftingRecipe.Requirement requires) {
        return requires.block().isEmpty() ? 0 : (int) scan.foundBlocks().stream()
                .filter(type -> requires.matches(BuiltInRegistries.BLOCK.getValue(type).defaultBlockState())).count();
    }

    /**
     * The weight of a found type in a roll that also offers innate recipes ({@link JobCraftingRecipe#weight()}): the loop
     * first picks between the found types (those not left out, each with this weight) and the offered recipes, and only
     * calls {@link #produce} when a found type won. 1 by default; the Animal Farmer weighs an animal type by its count.
     *
     * @param scan the scan result
     * @param type a found type ({@link ScanResult#foundBlocks()})
     * @return its weight (0 or more)
     */
    default int weight(ScanResult scan, Identifier type) {
        return 1;
    }

    /**
     * What the JobBlock's Found tab lists (by default the found block types); each entry can be excluded. The loop adds
     * an entry per offered innate recipe after them.
     *
     * @param scan   the scan result
     * @param worker the settler working the JobBlock, if any (without one no tool marks)
     * @return the entries
     */
    default List<Found> found(ScanResult scan, Optional<Worker> worker) {
        return scan.foundBlocks().stream()
                .map(block -> new Found(block, new ItemStack(BuiltInRegistries.BLOCK.getValue(block)),
                        worker.map(found -> canObtain(found.tool(), block)).orElse(true)))
                .filter(found -> !found.icon().isEmpty())
                .toList();
    }

    /**
     * Scan info the Found tab shows above the entries (e.g. the Fisherman's water and treasure chance).
     *
     * @param scan   the scan result
     * @param worker the settler working the JobBlock, if any
     * @return the lines, one per value
     */
    default List<Component> foundLines(ScanResult scan, Optional<Worker> worker) {
        return List.of();
    }

    /**
     * A found resource listed in the Found tab.
     *
     * @param type      the type (block id, for the Fisherman the fish item id); the key of an exclusion
     * @param icon      the icon
     * @param available whether the working settler's tool can obtain it (always without a settler)
     */
    @ApiStatus.Experimental
    record Found(Identifier type, ItemStack icon, boolean available) {
    }

    /**
     * The settler working a JobBlock, as far as the Found tab needs it.
     *
     * @param tool       its best tool of the job (empty without one)
     * @param skillLevel its effective skill level in the job
     */
    @ApiStatus.Experimental
    record Worker(ItemStack tool, int skillLevel) {
    }

    /**
     * What one production step needs.
     *
     * @param level      the level
     * @param settler    the settler
     * @param scan       the scan result (null while the first scan runs)
     * @param poi        the work place
     * @param tool       the job's tool
     * @param excluded   the types excluded at the JobBlock
     * @param skillLevel the skill level
     * @param atMax      the items at their Max at the Home Clerk (left out of the roll)
     * @param dropped    the items a resource type can give with the tool ({@link #droppedItems}, cached per scan)
     * @param rareChanceFactor the biome bonuses' factor on the chance of the {@link #rareGroup()} (1 without)
     * @param jobBlockLevel    the JobBlock level (1–5), the level the scan radius was taken for ({@link #scanRadius})
     */
    @ApiStatus.Experimental
    record Context(ServerLevel level, PathfinderMob settler, @Nullable ScanResult scan, BlockPos poi, ItemStack tool, Set<Identifier> excluded, int skillLevel, Set<Item> atMax, Function<Identifier, Set<Item>> dropped,
            double rareChanceFactor, int jobBlockLevel) {

        /**
         * A context without biome bonuses (rare chance factor 1) at JobBlock level 1.
         *
         * @param level      the level
         * @param settler    the settler
         * @param scan       the scan result
         * @param poi        the work place
         * @param tool       the job's tool
         * @param excluded   the types excluded at the JobBlock
         * @param skillLevel the skill level
         * @param atMax      the items at their Max at the Home Clerk
         * @param dropped    the items a resource type can give with the tool
         */
        public Context(ServerLevel level, PathfinderMob settler, @Nullable ScanResult scan, BlockPos poi, ItemStack tool, Set<Identifier> excluded,
                int skillLevel, Set<Item> atMax, Function<Identifier, Set<Item>> dropped) {
            this(level, settler, scan, poi, tool, excluded, skillLevel, atMax, dropped, 1.0, 1);
        }

        /**
         * @param baseChance the chance of the rare group without biome bonuses
         * @return the chance with the {@link #rareChanceFactor()}, capped at 1.0
         */
        public double rareChance(double baseChance) {
            return BiomeBonus.cappedChance(baseChance, rareChanceFactor);
        }
    }

    /**
     * Result of one production step.
     *
     * @param stacks the produced items
     * @param worn   the tool that loses durability
     */
    @ApiStatus.Experimental
    record Produced(List<ItemStack> stacks, ItemStack worn) {
    }
}
