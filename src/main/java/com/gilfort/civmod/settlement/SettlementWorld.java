package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Connects {@link SettlementManager} to the world: syncs JobBlock entities, notifies players and cleans up
 * JobBlocks that vanished while their chunk was unloaded.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlementWorld {

    /** Players within this distance are notified about automatic connections. */
    private static final double NOTIFY_DISTANCE = 32.0;

    /** Placement results of the current tick waiting for their placing player ({@code onPlace} came first). */
    private static final Map<GlobalPos, SettlementManager.Placement> PENDING_PLACEMENTS = new HashMap<>();
    /** Placing players of the current tick waiting for the placement result ({@code setPlacedBy} came first). */
    private static final Map<GlobalPos, UUID> PENDING_PLACERS = new HashMap<>();

    private SettlementWorld() {
    }

    /**
     * Registers a placed JobBlock in its dimension.
     *
     * @param level   the dimension
     * @param pos     the JobBlock position
     * @param jobType the offered job
     * @param jobLevel the JobBlock level
     * @return how the block was integrated
     */
    public static SettlementManager.Placement place(ServerLevel level, BlockPos pos, Job jobType, int jobLevel) {
        SettlementManager.Placement placement = SettlementManager.place(SettlementData.get(level), pos, jobType, jobLevel,
                level.getGameTime(), level.getRandom(), listener(level));
        GlobalPos key = GlobalPos.of(level.dimension(), pos.immutable());
        UUID placer = PENDING_PLACERS.remove(key);
        ServerPlayer player = placer == null ? null : level.getServer().getPlayerList().getPlayer(placer);
        if (player != null) {
            feedback(level, player, placement);
        } else {
            PENDING_PLACEMENTS.put(key, placement);
        }
        return placement;
    }

    /**
     * Handles a JobBlock placed by a player: applies the item level and gives feedback. Works in both orders of
     * {@code onPlace} and {@code setPlacedBy} (NeoForge defers {@code onPlace} for player placement).
     *
     * @param level    the dimension
     * @param pos      the JobBlock position
     * @param player   the placing player
     * @param jobLevel the level of the placed item
     */
    public static void placedByPlayer(ServerLevel level, BlockPos pos, ServerPlayer player, int jobLevel) {
        setLevel(level, pos, jobLevel);
        GlobalPos key = GlobalPos.of(level.dimension(), pos.immutable());
        SettlementManager.Placement placement = PENDING_PLACEMENTS.remove(key);
        if (placement != null) {
            feedback(level, player, placement);
        } else {
            PENDING_PLACERS.put(key, player.getUUID());
        }
    }

    private static void feedback(ServerLevel level, ServerPlayer player, SettlementManager.Placement placement) {
        notifyPlacement(player, placement);
        if (placement.outcome() == SettlementManager.Outcome.FOUNDED) {
            recordFounding(level, player);
        }
    }

    /**
     * Frees a JobBlock and removes the job from its settlers (if loaded; otherwise on their next membership check).
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock position
     */
    public static void unassign(ServerLevel level, BlockPos jobBlock) {
        JobAssignments.unassign(SettlementData.get(level), jobBlock).forEach(settler -> {
            if (level.getEntity(settler) instanceof Settler entity) {
                entity.removeJob(jobBlock);
            }
        });
    }

    /**
     * Removes one settler from one JobBlock: the block's assignee list, the settler's job list and its Workplace there
     * change together; on a Mission Board its mission assignments are removed (the missions stay locked).
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock position
     * @param settler  the settler
     * @return whether the settler worked the JobBlock
     */
    public static boolean unassign(ServerLevel level, BlockPos jobBlock, Settler settler) {
        boolean removed = JobAssignments.unassign(SettlementData.get(level), jobBlock, settler.getUUID());
        settler.removeJob(jobBlock);
        return removed;
    }

    /**
     * Frees all jobs of a settler (e.g. it died on a mission): every JobBlock forgets it and its job list is emptied.
     *
     * @param level   the dimension
     * @param settler the settler
     */
    public static void unassignAll(ServerLevel level, Settler settler) {
        SettlementData data = SettlementData.get(level);
        settler.getJobs().forEach(job -> JobAssignments.unassign(data, job.jobBlock(), settler.getUUID()));
        settler.setJobs(List.of());
    }

    /**
     * Replaces a settler's job list (the job list buttons of the settler window): the JobBlocks of removed jobs are
     * freed.
     *
     * @param level   the dimension
     * @param settler the settler
     * @param jobs    the new job list in priority order, a reordered subset of the current one
     */
    public static void setJobs(ServerLevel level, Settler settler, List<SettlerJob> jobs) {
        List<SettlerJob> before = settler.getJobs();
        settler.setJobs(jobs);
        SettlementData data = SettlementData.get(level);
        before.stream().filter(job -> !jobs.contains(job))
                .forEach(job -> JobAssignments.unassign(data, job.jobBlock(), settler.getUUID()));
    }

    /**
     * Falls a settler at a JobBlock back to the block's Base Job (its Job is no longer unlocked there, e.g. a replaced
     * Station Type). A settler holds each Job once: with the Base Job held at another JobBlock it leaves this one
     * instead. An unloaded settler catches up on its next membership check.
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock position
     * @param settler  the settler UUID
     */
    static void fallBackToBaseJob(ServerLevel level, BlockPos jobBlock, UUID settler) {
        fallBackToBaseJob(level, jobBlock, settler, level.getEntity(settler) instanceof Settler loaded ? Optional.of(loaded) : Optional.empty());
    }

    /**
     * The membership check's catch-up of {@link #fallBackToBaseJob(ServerLevel, BlockPos, UUID)} for a settler that was
     * unloaded when its Job fell back.
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock position
     * @param settler  the settler
     */
    public static void fallBackToBaseJob(ServerLevel level, BlockPos jobBlock, Settler settler) {
        fallBackToBaseJob(level, jobBlock, settler.getUUID(), Optional.of(settler));
    }

    private static void fallBackToBaseJob(ServerLevel level, BlockPos jobBlock, UUID id, Optional<Settler> settler) {
        SettlementData data = SettlementData.get(level);
        Optional<SettlementBlock> found = JobBlockSite.blockAt(data, jobBlock);
        if (found.isEmpty()) {
            return;
        }
        SettlementBlock block = found.get();
        Job base = block.jobType();
        if (JobAssignments.holdsElsewhere(data, jobBlock, id, base)) {
            JobAssignments.unassign(data, jobBlock, id);
            settler.ifPresent(loaded -> loaded.removeJob(jobBlock));
            return;
        }
        if (block.assignee(id).filter(assignee -> assignee.job() != base).isPresent()) {
            block.setAssigneeJob(id, base);
            data.changed();
        }
        settler.ifPresent(loaded -> loaded.replaceJob(jobBlock, base));
    }

    /**
     * Excludes or includes a found resource of a JobBlock; including one lets a settler paused with "All resources
     * excluded" check again at once.
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock position
     * @param type     the found resource type
     * @return whether the type is excluded now
     */
    public static boolean toggleExcluded(ServerLevel level, BlockPos jobBlock, net.minecraft.resources.Identifier type) {
        SettlementData data = SettlementData.get(level);
        boolean excluded = JobAssignments.toggleExcluded(data, jobBlock, type);
        if (!excluded) {
            JobBlockSite.blockAt(data, jobBlock).ifPresent(block -> block.assignees().forEach(assignee -> {
                if (level.getEntity(assignee.id()) instanceof Settler entity) {
                    entity.getWork().recheckExcluded(jobBlock);
                }
            }));
        }
        return excluded;
    }

    /**
     * Assigns one Job of a JobBlock to a settler: the one server path of the job picker and of automatic assignment
     * ({@link JobAssignments#assign(SettlementData, BlockPos, UUID, String, int, java.util.Collection, Job)}: place
     * limits, one Job per JobBlock; the Job is added directly above the Laborer and works the block's points).
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock position
     * @param settler  the settler
     * @param job      the Job
     * @return the result
     */
    public static JobAssignments.AssignResult assign(ServerLevel level, BlockPos jobBlock, Settler settler, Job job) {
        JobAssignments.AssignResult result = JobAssignments.assign(SettlementData.get(level), jobBlock, settler.getUUID(),
                settler.getName().getString(), settler.getSettlementId(), settler.getJobs().stream().map(SettlerJob::jobType).toList(), job);
        if (result == JobAssignments.AssignResult.OK) {
            settler.addJob(new SettlerJob(job, jobBlock));
        }
        return result;
    }

    /**
     * The job switcher's choices for one of a settler's Jobs ({@link JobAssignments#switchOptions}).
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock of the Job
     * @param settler  the settler
     * @return the Jobs it may switch to there
     */
    public static List<Job> switchOptions(ServerLevel level, BlockPos jobBlock, Settler settler) {
        return JobAssignments.switchOptions(SettlementData.get(level), jobBlock, settler.getUUID(), otherJobs(settler, jobBlock));
    }

    /**
     * Switches a settler's Job at a JobBlock (the job switcher of the settler window): the JobBlock link and the
     * priority stay, the work state at that JobBlock is forgotten like on any job switch (a running Producing round is
     * released, fetched inputs go into the own drop-off). The skill of the new Job is the settler's own in it.
     *
     * @param level    the dimension
     * @param jobBlock the JobBlock of the Job
     * @param settler  the settler
     * @param job      the new Job, one of {@link #switchOptions}
     * @return whether the Job was switched
     */
    public static boolean switchJob(ServerLevel level, BlockPos jobBlock, Settler settler, Job job) {
        if (!JobAssignments.switchJob(SettlementData.get(level), jobBlock, settler.getUUID(), otherJobs(settler, jobBlock), job)) {
            return false;
        }
        settler.replaceJob(jobBlock, job);
        return true;
    }

    private static List<Job> otherJobs(Settler settler, BlockPos jobBlock) {
        return settler.getJobs().stream().filter(held -> !held.jobBlock().equals(jobBlock)).map(SettlerJob::jobType).toList();
    }

    /**
     * Applies a JobBlock level to its settlement entry.
     *
     * @param level    the dimension
     * @param pos      the JobBlock position
     * @param jobLevel the level
     */
    public static void setLevel(ServerLevel level, BlockPos pos, int jobLevel) {
        SettlementManager.setLevel(SettlementData.get(level), pos, jobLevel, listener(level));
    }

    /**
     * The current mission offer of a Mission Board, refilled on the first access of a settler day (the day starts at
     * 06:00 overworld time, like the settler schedule).
     *
     * @param level the dimension
     * @param board the Mission Board position
     * @return the offer, empty if there is no Mission Board at that position
     */
    public static List<MissionOffer.Entry> missionOffer(ServerLevel level, BlockPos board) {
        long day = DayClock.day(level);
        return MissionOffers.refresh(SettlementData.get(level), board, day, Missions.all(level.registryAccess()), level.getRandom());
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        PENDING_PLACEMENTS.clear();
        PENDING_PLACERS.clear();
    }

    /**
     * Unregisters a removed JobBlock.
     *
     * @param level the dimension
     * @param pos   the JobBlock position
     */
    public static void remove(ServerLevel level, BlockPos pos) {
        SettlementManager.remove(SettlementData.get(level), pos, listener(level));
    }

    /**
     * Sends the result of a player placement as action bar message.
     *
     * @param player    the placing player
     * @param placement the placement result
     */
    public static void notifyPlacement(ServerPlayer player, SettlementManager.Placement placement) {
        Component message = switch (placement.outcome()) {
            case FOUNDED -> Component.translatable("civmod.settlement.founded");
            case JOINED -> Component.translatable("civmod.settlement.joined", placement.settlement().displayName());
            case UNCONNECTED -> Component.translatable("civmod.settlement.unconnected", placement.distance(), placement.required())
                    .withStyle(net.minecraft.ChatFormatting.RED);
        };
        player.sendOverlayMessage(message);
    }

    /**
     * @param level the dimension
     * @return a listener applying settlement side effects to that dimension
     */
    public static SettlementManager.Listener listener(ServerLevel level) {
        return new SettlementManager.Listener() {
            @Override
            public void blockChanged(BlockPos pos, int settlementId, boolean connected) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof JobBlockEntity jobBlock) {
                    jobBlock.setSettlement(settlementId, connected);
                }
            }

            @Override
            public void dissolved(Settlement settlement) {
                for (UUID member : settlement.members()) {
                    if (level.getEntity(member) instanceof Settler settler) {
                        settler.becomeHomeless();
                    }
                }
            }

            @Override
            public void jobRemoved(UUID settler, BlockPos jobBlock) {
                if (level.getEntity(settler) instanceof Settler entity) {
                    entity.removeJob(jobBlock);
                }
            }

            @Override
            public void autoConnected(BlockPos pos) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 15, 0.4, 0.4, 0.4, 0.0);
                for (ServerPlayer player : level.players()) {
                    if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= NOTIFY_DISTANCE * NOTIFY_DISTANCE) {
                        player.sendOverlayMessage(Component.translatable("civmod.settlement.connected"));
                    }
                }
            }
        };
    }

    /**
     * Enforces the optional founding limit (one settlement per player per in-game day).
     *
     * @param event the block place event
     */
    @SubscribeEvent
    static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!CivModConfig.LIMIT_FOUNDING_PER_DAY.get() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player) || !(event.getPlacedBlock().getBlock() instanceof JobBlock)) {
            return;
        }
        // For player placement NeoForge defers onPlace until after this event, so the block is not registered yet.
        SettlementData data = SettlementData.get(level);
        if (SettlementManager.wouldFound(data, event.getPos())
                && data.lastFoundingDay(player.getUUID()).filter(day -> day == day(level)).isPresent()) {
            event.setCanceled(true);
            player.sendOverlayMessage(Component.translatable("civmod.settlement.founding_limit").withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    /**
     * Records that a player founded a settlement (for the founding limit).
     *
     * @param level  the dimension
     * @param player the player
     */
    public static void recordFounding(ServerLevel level, ServerPlayer player) {
        SettlementData.get(level).recordFounding(player.getUUID(), day(level));
    }

    private static long day(ServerLevel level) {
        return DayClock.day(level.getGameTime());
    }

    /**
     * Removes recorded JobBlocks that no longer exist when their chunk loads (e.g. removed by external tools).
     *
     * @param event the chunk load event
     */
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        List<BlockPos> missing = new ArrayList<>();
        for (BlockPos pos : SettlementData.get(level).blocksInChunk(chunk.getPos())) {
            if (!(chunk.getBlockState(pos).getBlock() instanceof JobBlock)) {
                missing.add(pos);
            }
        }
        if (!missing.isEmpty()) {
            // Level interactions must not happen during chunk loading; defer to the next tick.
            level.getServer().execute(() -> missing.forEach(pos -> {
                CivMod.LOGGER.info("Removing missing JobBlock at {} from its settlement", pos);
                remove(level, pos);
            }));
        }
    }

    /**
     * @param level the dimension
     * @param pos   a JobBlock position
     * @return the connection status of that block
     */
    public static SettlementManager.Status status(ServerLevel level, BlockPos pos) {
        return SettlementManager.status(SettlementData.get(level), pos);
    }
}
