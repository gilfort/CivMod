package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * The production rules of a Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md},
 * {@code Systems/JobSkill Level.md}, catch-up "same rules as live play" in {@code Systems/Settlement.md}): advances a
 * job by a number of work ticks and reports what came out. Live play calls it every work tick, catch-up per simulated
 * hour; this is the only copy of speed × productivity, tool choice, tool wear and EXP.
 * <p>
 * Production runs one unit at a time: the job's fractional accumulator (kept in its {@link Workplace} between calls)
 * fills at speed × productivity per second; each full unit is one {@link JobProfile#produce} roll with the best tool
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
     */
    record ToolSupply(Optional<Stock> collect, Runnable handedOut) {

        /** Only the belt: live play, where the settler walks to the collect inventory itself. */
        static final ToolSupply BELT = new ToolSupply(Optional.empty(), () -> { });

        /**
         * @param collect   what the settler reaches at its collect inventory
         * @param handedOut called when a working Clerk handed out a tool from its counter
         * @return the belt, refilled from the collect inventory when a tool is missing: catch-up
         */
        static ToolSupply beltAnd(Stock collect, Runnable handedOut) {
            return new ToolSupply(Optional.of(collect), handedOut);
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
        /** The scan found nothing for a job that needs resources. */
        NO_RESOURCES,
        /** Every main resource the scan found is excluded at the JobBlock. */
        ALL_EXCLUDED
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

    private WorkRules() {
    }

    /**
     * Advances a job by some work ticks.
     *
     * @param level     the level
     * @param settler   the settler
     * @param job       the job
     * @param profile   the job's profile
     * @param site      the job's JobBlock site
     * @param workplace the job's Workplace (scan, production accumulator)
     * @param poi       the work place of the day
     * @param workTicks the work ticks (1 live, an hour's share in catch-up)
     * @param supply    where tools come from
     * @param output    where produced items go
     * @return what came out
     */
    static Result work(ServerLevel level, Settler settler, SettlerJob job, JobProfile profile, JobBlockSite site, Workplace workplace,
            BlockPos poi, double workTicks, ToolSupply supply, Output output) {
        ScanResult scan = workplace.scan();
        if (profile.needsResources() && scan != null && scan.isEmpty()) {
            return new Result(0, 0, 0, 0, false, End.NO_RESOURCES, workTicks);
        }
        if (allExcluded(profile, scan, site.block())) {
            return new Result(0, 0, 0, 0, false, End.ALL_EXCLUDED, workTicks);
        }
        Optional<ItemStack> tool = tool(level, settler, profile, site, supply);
        if (tool.isEmpty()) {
            return new Result(0, 0, 0, 0, false, End.NO_TOOL, workTicks);
        }
        if (!output.hasRoom()) {
            return new Result(0, 0, 0, 0, false, End.OUTPUT_FULL, workTicks);
        }
        int cap = site.skillCap();
        int skillLevel = settler.getSkills().effectiveLevel(job.jobType(), cap);
        double accumulated = workplace.production();
        double ticks = workTicks;
        int rolls = 0;
        int items = 0;
        int wear = 0;
        int exp = 0;
        boolean levelUp = false;
        End end = End.NONE;
        while (true) {
            double perTick = profile.speed(skillLevel, tool.get()) * profile.productivity(scan) / 20.0;
            if (perTick <= 0.0 || (1.0 - accumulated) / perTick > ticks) {
                accumulated += Math.max(0.0, perTick) * ticks;
                ticks = 0.0;
                break;
            }
            ticks -= (1.0 - accumulated) / perTick;
            accumulated = 0.0;
            Optional<ItemStack> secondary = profile.secondaryTool(site.block()).flatMap(kind -> SettlerTools.best(settler, kind));
            Optional<JobProfile.Produced> produced = profile.produce(new JobProfile.Context(level, settler, scan, poi, tool.get(), secondary,
                    site.block(), skillLevel));
            rolls++;
            if (produced.isPresent()) {
                SettlerTools.wear(level, settler, produced.get().worn());
                wear++;
                List<ItemStack> stacks = produced.get().stacks();
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
                skillLevel = settler.getSkills().effectiveLevel(job.jobType(), cap);
                if (!room) {
                    end = End.OUTPUT_FULL;
                    break;
                }
            }
            tool = tool(level, settler, profile, site, supply);
            if (tool.isEmpty()) {
                end = End.NO_TOOL;
                break;
            }
        }
        workplace.setProduction(accumulated);
        return new Result(rolls, items, wear, exp, levelUp, end, ticks);
    }

    /**
     * @param profile the job's profile
     * @param scan    the job's scan (null while the first scan runs)
     * @param block   the JobBlock
     * @return whether the scan found main resources and every one of them is excluded at the JobBlock (the job pauses)
     */
    static boolean allExcluded(JobProfile profile, @Nullable ScanResult scan, SettlementBlock block) {
        if (scan == null || block.excluded().isEmpty()) {
            return false;
        }
        List<Identifier> main = profile.mainResources(scan);
        return !main.isEmpty() && block.excluded().containsAll(main);
    }

    /**
     * The best tool of the job on the belt; with {@link ToolSupply#beltAnd} missing tools are taken from the collect
     * inventory first.
     */
    private static Optional<ItemStack> tool(ServerLevel level, Settler settler, JobProfile profile, JobBlockSite site, ToolSupply supply) {
        Optional<ItemStack> tool = SettlerTools.best(settler, profile::isTool);
        if (tool.isEmpty() && supply.collect().isPresent()) {
            if (refill(settler, profile, site, supply.collect().get()) && supply.collect().get().counter()) {
                supply.handedOut().run();
            }
            tool = SettlerTools.best(settler, profile::isTool);
        }
        return tool;
    }

    /**
     * The tool the settler has to fetch before it can work (live play walks to the collect inventory for it): the
     * job's tool when none is on the belt, else the secondary tool when the JobBlock settings want one, none is on the
     * belt and the collect inventory has one.
     *
     * @param settler the settler
     * @param profile the job's profile
     * @param site    the job's JobBlock site
     * @param collect what the settler reaches at its collect inventory
     * @return the missing tool, empty if the belt is ready
     */
    static Optional<MissingTool> missingTool(Settler settler, JobProfile profile, JobBlockSite site, Stock collect) {
        if (SettlerTools.best(settler, profile::isTool).isEmpty()) {
            Predicate<ItemStack> kind = profile::isTool;
            return Optional.of(new MissingTool(kind, SettlerTools.find(collect, kind).isPresent()));
        }
        return profile.secondaryTool(site.block()).filter(kind -> SettlerTools.best(settler, kind).isEmpty()
                && SettlerTools.find(collect, kind).isPresent()).map(kind -> new MissingTool(kind, true));
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
     * @param settler the settler
     * @param profile the job's profile
     * @param site    the job's JobBlock site
     * @param collect what the settler reaches at its collect inventory
     * @return whether a tool was taken
     */
    static boolean refill(Settler settler, JobProfile profile, JobBlockSite site, Stock collect) {
        boolean taken = false;
        if (SettlerTools.best(settler, profile::isTool).isEmpty()) {
            taken |= SettlerTools.take(settler, collect, profile::isTool);
        }
        Optional<Predicate<ItemStack>> secondary = profile.secondaryTool(site.block());
        if (secondary.isPresent() && SettlerTools.best(settler, secondary.get()).isEmpty()) {
            taken |= SettlerTools.take(settler, collect, secondary.get());
        }
        return taken;
    }
}
