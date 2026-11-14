package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.XpGain;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The production rules of a Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md},
 * {@code Systems/JobSkill Level.md}, catch-up "same rules as live play" in {@code Systems/Settlement.md}): advances a
 * job by a number of work ticks and reports what came out. Live play calls it every work tick, catch-up per simulated
 * hour; this is the only copy of speed × productivity × biome bonus, tool choice, tool wear, double drops, EXP and XP.
 * <p>
 * Production runs one unit at a time: the job's fractional accumulator (kept in its {@link Workplace} between calls)
 * fills at speed × productivity per second; each full unit is one {@link ResourceCollectionProfile#produce} roll with the best tool
 * on the belt. EXP is gained per roll that yields items or is an empty roll (the rolled group is fully excluded at the
 * JobBlock: the activity still counts), and a level-up speeds up the rest of the batch. The job ends when no tool is
 * left, the output is full, an empty scan means no resources or every main resource is excluded.
 * <p>
 * Where the output goes is the caller's {@link Output}; effects (swing, sounds, level-up message) stay with the
 * caller, which learns of a level-up from the {@link Result}.
 */
final class WorkRules {

    /**
     * Where tools come from during a batch: the belt, and in catch-up also the collect inventory.
     *
     * @param collect   what the settler reaches at its collect inventory, if missing tools are refilled from it
     * @param handedOut called when a working Clerk handed out a tool from its counter
     * @param time      the game time (as of the simulated moment in catch-up) the search throttle is judged at
     */
    record ToolSupply(Optional<Stock> collect, Runnable handedOut, long time) {

        /** Only the belt: live play, where the settler walks to the collect inventory itself. */
        static final ToolSupply BELT = new ToolSupply(Optional.empty(), () -> { }, 0L);

        /**
         * @param collect   what the settler reaches at its collect inventory
         * @param handedOut called when a working Clerk handed out a tool from its counter
         * @param time      the game time of the simulated moment
         * @return the belt, refilled from the collect inventory when a tool is missing: catch-up
         */
        static ToolSupply beltAnd(Stock collect, Runnable handedOut, long time) {
            return new ToolSupply(Optional.of(collect), handedOut, time);
        }
    }

    /** Why a batch stopped before its work ticks were used up. */
    enum End {
        /** The job can go on. */
        NONE,
        /** No tool on the belt (and none to refill). */
        NO_TOOL,
        /** The output (buffer, or drop-off and buffer) is full. */
        OUTPUT_FULL,
        /** The scan holds too little for a job that needs resources. */
        NO_RESOURCES,
        /** Every main resource the scan found is excluded at the JobBlock. */
        ALL_EXCLUDED,
        /** Every main resource the scan found is at its Max at the Home Clerk. */
        MAX_REACHED,
        /** Every main resource the scan found is excluded or at its Max, some of each. */
        NOTHING_TO_PRODUCE,
        /** The profile reports a blocking reason ({@link ResourceCollectionProfile#blockingReason}). */
        BLOCKED;

        /**
         * @return the status a job paused for this reason shows, empty for the other ends
         */
        Optional<JobStatus> pauseStatus() {
            return switch (this) {
                case ALL_EXCLUDED -> Optional.of(JobStatus.ALL_EXCLUDED);
                case MAX_REACHED -> Optional.of(JobStatus.MAX_REACHED);
                case NOTHING_TO_PRODUCE -> Optional.of(JobStatus.NOTHING_TO_PRODUCE);
                default -> Optional.empty();
            };
        }
    }

    /** Where produced items go; the caller's choice. */
    interface Output {

        /**
         * @return whether more output fits
         */
        boolean hasRoom();

        /**
         * Takes the items of one roll.
         *
         * @param stacks the produced items (not empty)
         * @return whether more output fits afterwards
         */
        boolean accept(List<ItemStack> stacks);
    }

    /**
     * What a batch produced.
     *
     * @param rolls     production rolls made
     * @param items     produced items
     * @param toolWear  times a tool was worn
     * @param exp       EXP gained
     * @param levelUp   whether the skill level rose
     * @param end       why the batch stopped early ({@link End#NONE} if it used all work ticks)
     * @param ticksLeft work ticks not used because the job ended
     */
    record Result(int rolls, int items, int toolWear, int exp, boolean levelUp, End end, double ticksLeft) {
    }

    /**
     * Who works on what: the fixed part of a call to {@link #work}.
     *
     * @param level     the level
     * @param settler   the settler
     * @param job       the job
     * @param profile   the job's profile
     * @param site      the job's JobBlock site
     * @param workplace the job's Workplace (scan, production accumulator)
     * @param supply    where tools come from
     */
    record Setup(ServerLevel level, Settler settler, SettlerJob job, ResourceCollectionProfile profile, JobBlockSite site, Workplace workplace,
            ToolSupply supply) {
    }

    private WorkRules() {
    }

    /**
     * Advances a job by some work ticks.
     *
     * @param setup     who works, on what job, and where tools come from
     * @param poi       the work place of the day
     * @param workTicks the work ticks (1 live, an hour's share in catch-up)
     * @param span      the simulated span in catch-up, {@link SimulatedSpan#LIVE} live (speed bonuses are averaged over it)
     * @param output    where produced items go
     * @return what came out
     */
    static Result work(Setup setup, BlockPos poi, double workTicks, SimulatedSpan span, Output output) {
        ServerLevel level = setup.level();
        Settler settler = setup.settler();
        SettlerJob job = setup.job();
        ResourceCollectionProfile profile = setup.profile();
        JobBlockSite site = setup.site();
        Workplace workplace = setup.workplace();
        ToolSupply supply = setup.supply();
        ScanResult scan = workplace.scan();
        if (profile.needsResources() && scan != null && !profile.hasResources(scan)) {
            return new Result(0, 0, 0, 0, false, End.NO_RESOURCES, workTicks);
        }
        Set<Identifier> excluded = site.block().excluded(job.jobType());
        // The recipes the scan offers: fixed while the scan is (filtered per roll by exclusion and Max).
        List<CollectionRecipes.Offer> offered = CollectionRecipes.offered(level, job.jobType(), profile, scan);
        if (allExcluded(profile, scan, excluded, offered)) {
            return new Result(0, 0, 0, 0, false, End.ALL_EXCLUDED, workTicks);
        }
        if (scan != null && profile.blockingReason(scan).isPresent()) {
            return new Result(0, 0, 0, 0, false, End.BLOCKED, workTicks);
        }
        Optional<ItemStack> tool = tool(level, settler, job, profile, site, supply, true);
        if (tool.isEmpty()) {
            return new Result(0, 0, 0, 0, false, End.NO_TOOL, workTicks);
        }
        if (!output.hasRoom()) {
            return new Result(0, 0, 0, 0, false, End.OUTPUT_FULL, workTicks);
        }
        int cap = site.skillCap();
        int skillLevel = settler.getWork().effectiveLevel(job);
        BiomeBonus bonus = workplace.biomeBonus(level, job.jobType());
        double speedFactor = SettlerSpeed.speedFactor(settler.getWork(), level, span);
        double accumulated = workplace.production();
        double ticks = workTicks;
        int rolls = 0;
        int items = 0;
        int wear = 0;
        int exp = 0;
        boolean levelUp = false;
        End end = End.NONE;
        while (true) {
            double perTick = SettlerSpeed.withBiome(profile.speed(skillLevel, tool.get()) * job.jobType().productivity(scan, site.level()), bonus, speedFactor) / 20.0;
            if (perTick <= 0.0 || (1.0 - accumulated) / perTick > ticks) {
                accumulated += Math.max(0.0, perTick) * ticks;
                ticks = 0.0;
                break;
            }
            // The Max check per roll: the Home Clerk's stock may have changed (catch-up delivers straight into it).
            Set<Item> atMax = StockRules.atMax(level, site);
            ItemStack rollTool = tool.get();
            End pause = pause(level, job.jobType(), profile, workplace, site, rollTool, atMax, offered);
            if (pause != End.NONE) {
                end = pause;
                break;
            }
            ticks -= (1.0 - accumulated) / perTick;
            accumulated = 0.0;
            ResourceCollectionProfile.Context context = new ResourceCollectionProfile.Context(level, settler, scan, poi, rollTool,
                    excluded, skillLevel, atMax, type -> workplace.dropped(level, profile, poi, type, rollTool),
                    bonus.rareChance().value(), site.level());
            List<CollectionRecipes.Offer> allowed = CollectionRecipes.allowed(offered, excluded, atMax);
            if (supply.collect().isPresent() && CollectionRecipes.refill(settler, allowed, supply.collect().get())
                    && supply.collect().get().counter()) {
                supply.handedOut().run();
            }
            // An innate recipe ready on the belt competes with the found types; it wears no tool and is never doubled.
            Optional<ItemStack> crafted = CollectionRecipes.roll(settler, profile, context, allowed);
            Optional<ResourceCollectionProfile.Produced> produced;
            if (crafted.isPresent()) {
                produced = Optional.of(new ResourceCollectionProfile.Produced(List.of(crafted.get()), ItemStack.EMPTY));
            } else if (profile instanceof SecondaryToolProfile withSecondary) {
                produced = withSecondary.produce(context, withSecondary.secondaryTool(site.block()).flatMap(kind -> SettlerTools.best(settler, kind)));
            } else if (profile instanceof FishingProfile fishing) {
                produced = fishing.produce(context, TreasureOption.of(site.block().treasure()));
            } else if (profile instanceof DropPoolProfile pooled) {
                // Entries with their own tool use it from the collect inventory, read only when one asks.
                produced = pooled.produce(context, JobDrops.Tools.of(level, settler, () -> supply.collect()
                        .orElseGet(() -> ClerkStorage.stock(level, site.collect(), DayClock.dayTime(level), true))));
            } else {
                produced = profile.produce(context);
            }
            rolls++;
            if (produced.isPresent()) {
                if (crafted.isEmpty()) {
                    SettlerTools.wear(level, settler, produced.get().worn());
                    wear++;
                }
                // A lost double drop (negative chance) yields nothing, but counts as work like an empty roll.
                List<ItemStack> stacks = crafted.isPresent() ? produced.get().stacks() : bonus.applyDoubleDrop(produced.get().stacks(), level.getRandom());
                // A roll that yields nothing earns no profile XP. Read before the output takes the stacks; after the wear, so the tool
                // can repair what this roll cost it.
                settler.getWork().collectXp(level, SettlerXp.gains(JobBalancing.of(job.jobType()).xp(), stacks.isEmpty() ? XpGain.NONE : produced.get().xp(), stacks),
                        crafted.isPresent() ? ItemStack.EMPTY : produced.get().worn());
                // An empty roll (fully excluded group) yields nothing but earns the EXP of the activity.
                boolean room = true;
                if (!stacks.isEmpty()) {
                    items += stacks.stream().mapToInt(ItemStack::getCount).sum();
                    room = output.accept(stacks);
                }
                int amount = job.jobType().baseExp() * site.level();
                if (settler.getSkills().addExp(job.jobType(), amount, cap, SkillRules.get()) > 0) {
                    levelUp = true;
                }
                exp += amount;
                skillLevel = settler.getWork().effectiveLevel(job);
                if (!room) {
                    end = End.OUTPUT_FULL;
                    break;
                }
            }
            tool = tool(level, settler, job, profile, site, supply, false);
            if (tool.isEmpty()) {
                end = End.NO_TOOL;
                break;
            }
        }
        workplace.setProduction(accumulated);
        return new Result(rolls, items, wear, exp, levelUp, end, ticks);
    }

    /**
     * @param profile  the job's profile
     * @param scan     the job's scan (null while the first scan runs)
     * @param excluded the types excluded for the job at its JobBlock
     * @param offered  the innate recipes the scan offers ({@link CollectionRecipes#offered})
     * @return whether the scan found main resources or offers recipes and every one of them is excluded at the JobBlock
     *         (the job pauses; decision 2026-10-29: recipes count like found types)
     */
    static boolean allExcluded(ResourceCollectionProfile profile, @Nullable ScanResult scan, Set<Identifier> excluded, List<CollectionRecipes.Offer> offered) {
        if (scan == null || excluded.isEmpty()) {
            return false;
        }
        List<Identifier> main = profile.mainResources(scan);
        return (!main.isEmpty() || !offered.isEmpty()) && excluded.containsAll(main)
                && offered.stream().allMatch(offer -> excluded.contains(CollectionRecipes.type(offer.holder())));
    }

    /**
     * Whether a job pauses because nothing is left to produce (vault {@code Systems/Resource Collection Jobs.md}):
     * every main resource is excluded at the JobBlock or every item it can give is at Max at the Home Clerk, and so is
     * every innate recipe the scan offers (excluded, or its result at Max; decision 2026-10-29).
     *
     * @param level     the level
     * @param job       the Job (its exclusions at the JobBlock)
     * @param profile   the job's profile
     * @param workplace the job's Workplace (its scan, null while the first scan runs, and the dropped items)
     * @param site      the JobBlock
     * @param tool      the tool the drops are checked with (may be empty)
     * @param atMax     the items at Max at the Home Clerk ({@link StockRules#atMax})
     * @param offered   the innate recipes the scan offers ({@link CollectionRecipes#offered})
     * @return {@link End#ALL_EXCLUDED}, {@link End#MAX_REACHED} or {@link End#NOTHING_TO_PRODUCE} for a pause, else
     *         {@link End#NONE}
     */
    static End pause(ServerLevel level, Job job, ResourceCollectionProfile profile, Workplace workplace, JobBlockSite site, ItemStack tool,
            Set<Item> atMax, List<CollectionRecipes.Offer> offered) {
        ScanResult scan = workplace.scan();
        Set<Identifier> excludedTypes = site.block().excluded(job);
        if (scan == null || (atMax.isEmpty() && excludedTypes.isEmpty())) {
            return End.NONE;
        }
        List<Identifier> main = profile.mainResources(scan);
        int entries = main.size() + offered.size();
        if (entries == 0) {
            return End.NONE;
        }
        int excluded = 0;
        for (Identifier type : main) {
            if (excludedTypes.contains(type)) {
                excluded++;
            } else if (!ResourceCollectionProfile.atMax(workplace.dropped(level, profile, site.pos(), type, tool), atMax)) {
                return End.NONE;
            }
        }
        for (CollectionRecipes.Offer offer : offered) {
            if (excludedTypes.contains(CollectionRecipes.type(offer.holder()))) {
                excluded++;
            } else if (!atMax.contains(CollectionRecipes.product(offer.holder().value()).getItem())) {
                return End.NONE;
            }
        }
        return excluded == entries ? End.ALL_EXCLUDED : excluded == 0 ? End.MAX_REACHED : End.NOTHING_TO_PRODUCE;
    }

    /**
     * The best tool of the job on the belt; with {@link ToolSupply#beltAnd} missing tools are taken from the collect
     * inventory first. A bare-handed job ({@link ResourceCollectionProfile#needsTool()}) works with an empty hand.
     */
    private static Optional<ItemStack> tool(ServerLevel level, Settler settler, SettlerJob job, ResourceCollectionProfile profile, JobBlockSite site,
            ToolSupply supply, boolean search) {
        if (!profile.needsTool()) {
            return Optional.of(ItemStack.EMPTY);
        }
        ToolStaircase.Rule rule = settler.getWork().toolRule(job, profile);
        Optional<ItemStack> tool = settler.getWork().tool(job, profile);
        // Catch-up: a tool that is missing or below the requirement is swapped for the collect inventory's best. The
        // inventory is searched when the batch starts (one simulated hour), after a roll only when no tool is left.
        if (supply.collect().isPresent() && (search || tool.isEmpty()) && (tool.isEmpty() || !rule.meets(tool.get()))) {
            // Every search of the collect inventory honors the once-per-hour throttle (ToolNeeds#search).
            settler.getWork().toolNeeds().search(job, supply.time(), () -> {
                boolean taken = refill(settler, job, profile, site, supply.collect().get());
                if (taken && supply.collect().get().counter()) {
                    supply.handedOut().run();
                }
                return taken;
            }, () -> ToolOrders.supply(level, settler, job, profile, site));
            settler.getWork().toolNeeds().recheck(job);
            ToolOrders.settled(level, settler, job, profile);
            tool = settler.getWork().tool(job, profile);
        }
        return tool;
    }

    /**
     * The tool the settler has to fetch before it can work (live play walks to the collect inventory for it): the
     * job's tool when none is on the belt, else the secondary tool when the JobBlock settings want one, none is on the
     * belt and the collect inventory has one.
     *
     * <p>
     * A tool Job of the staircase ({@link ToolStaircase}) also fetches when the belt has a tool but none meets the
     * requirement and the collect inventory has a better allowed one; that search runs at most once per in-game hour and
     * settler ({@link ToolNeeds#SEARCH_INTERVAL}), and only while the requirement is unmet.
     *
     * @param level   the level
     * @param settler the settler
     * @param job     the job
     * @param profile the job's profile
     * @param site    the job's JobBlock site
     * @param collect what the settler reaches at its collect inventory
     * @return the missing tool, empty if the belt is ready
     */
    static Optional<MissingTool> missingTool(ServerLevel level, Settler settler, SettlerJob job, ResourceCollectionProfile profile, JobBlockSite site,
            Stock collect) {
        if (profile.needsTool()) {
            ToolStaircase.Rule rule = settler.getWork().toolRule(job, profile);
            Optional<ItemStack> belt = settler.getWork().tool(job, profile);
            Predicate<ItemStack> kind = profile::isTool;
            ToolNeeds tools = settler.getWork().toolNeeds();
            long time = level.getGameTime();
            if (belt.isEmpty()) {
                // "No tool" waits an hour between searches, too.
                return Optional.of(new MissingTool(kind, tools.search(job, time, () -> SettlerTools.find(collect, kind, rule).isPresent(),
                        () -> ToolOrders.supply(level, settler, job, profile, site))));
            }
            if (!rule.meets(belt.get()) && tools.search(job, time, () -> SettlerTools.better(collect, kind, rule, belt),
                    () -> ToolOrders.supply(level, settler, job, profile, site))) {
                return Optional.of(new MissingTool(kind, true));
            }
        }
        return secondaryTool(profile, site.block()).filter(kind -> SettlerTools.best(settler, kind).isEmpty()
                && SettlerTools.find(collect, kind).isPresent()).map(kind -> new MissingTool(kind, true));
    }

    /**
     * @param profile the job's profile
     * @param block   the JobBlock
     * @return a second tool the job wants with the JobBlock's settings (the Lumberjack's shears for leaves), if any
     */
    static Optional<Predicate<ItemStack>> secondaryTool(ResourceCollectionProfile profile, SettlementBlock block) {
        return profile instanceof SecondaryToolProfile withSecondary ? withSecondary.secondaryTool(block) : Optional.empty();
    }


    /**
     * A tool the settler lacks for its job.
     *
     * @param kind      what counts as that tool
     * @param inCollect whether the collect inventory has one; without it the job cannot work ("No tool")
     */
    record MissingTool(Predicate<ItemStack> kind, boolean inCollect) {
    }

    /**
     * Takes the job's tool and its secondary tool (if the JobBlock settings want one) from the collect inventory onto
     * the belt, each only if none is on the belt yet.
     *
     * The job's tool is swapped ({@link SettlerTools#swap}) when none on the belt meets the staircase requirement.
     *
     * @param settler the settler
     * @param job     the job
     * @param profile the job's profile
     * @param site    the job's JobBlock site
     * @param collect what the settler reaches at its collect inventory
     * @return whether a tool was taken
     */
    static boolean refill(Settler settler, SettlerJob job, ResourceCollectionProfile profile, JobBlockSite site, Stock collect) {
        boolean taken = false;
        if (profile.needsTool()) {
            taken |= SettlerTools.swap(settler, collect, profile::isTool, settler.getWork().toolRule(job, profile));
        }
        Optional<Predicate<ItemStack>> secondary = secondaryTool(profile, site.block());
        if (secondary.isPresent() && SettlerTools.best(settler, secondary.get()).isEmpty()) {
            taken |= SettlerTools.take(settler, collect, secondary.get());
        }
        return taken;
    }
}
