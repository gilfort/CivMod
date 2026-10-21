package com.gilfort.civmod.item;

import java.util.Optional;

import com.gilfort.civmod.api.BiomePosition;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.job.BiomeBonuses;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.menu.BiomeBonusText;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * The Scroll of Assignment (vault {@code Systems/Scroll of Assignment.md}).
 * <ul>
 * <li>Shift + right-click a JobBlock: bind to it.</li>
 * <li>Bound to a JobBlock, right-click a block (or water): cycle work POI → collect → drop-off → removed; bound to a
 * Clerk JobBlock: a container cycles storage → removed, any other block stand spot → removed.</li>
 * <li>Bound to a Clerk JobBlock (a Warehouse), right-click another Clerk JobBlock: link / unlink it as Served Clerk.</li>
 * <li>Bound to a JobBlock, right-click a settler: open the {@link JobPicker job picker} and assign the chosen Job.</li>
 * <li>Shift + right-click a settler: bind to the settler (NPC settings, e.g. bed later).</li>
 * <li>Shift + right-click air: clear the binding.</li>
 * </ul>
 * Scroll actions take priority over normal block use ({@link #onItemUseFirst}).
 */
public class ScrollOfAssignmentItem extends Item {

    /**
     * @param properties the item properties
     */
    public ScrollOfAssignmentItem(Properties properties) {
        super(properties);
    }

    /**
     * @param stack a scroll
     * @return its binding
     */
    public static Optional<ScrollBinding> binding(ItemStack stack) {
        return Optional.ofNullable(stack.get(ModDataComponents.SCROLL_BINDING.get()));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModDataComponents.SCROLL_BINDING.get());
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            if (!(level.getBlockState(pos).getBlock() instanceof JobBlock jobBlock)) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                bindJobBlock(stack, serverPlayer, pos, jobBlock.getJobType());
            }
            return InteractionResult.SUCCESS;
        }
        Optional<java.util.UUID> boundSettler = binding(stack).flatMap(ScrollBinding::settler);
        if (boundSettler.isPresent()) {
            if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.AbstractBedBlock)) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                assignBed(stack, serverPlayer, boundSettler.get(), pos);
            }
            return InteractionResult.SUCCESS;
        }
        Optional<BlockPos> bound = binding(stack).flatMap(ScrollBinding::jobBlock);
        if (bound.isEmpty()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            cyclePoint(stack, serverPlayer, bound.get(), pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (player.isSecondaryUseActive()) {
            if (hit.getType() == HitResult.Type.MISS && binding(stack).isPresent()) {
                stack.remove(ModDataComponents.SCROLL_BINDING.get());
                player.sendOverlayMessage(Component.translatable("civmod.scroll.cleared"));
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }
        // Water is not a block hit for onItemUseFirst; handle Fisherman work places here.
        Optional<BlockPos> bound = binding(stack).flatMap(ScrollBinding::jobBlock);
        if (bound.isPresent() && hit.getType() == HitResult.Type.BLOCK && level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) {
            if (player instanceof ServerPlayer serverPlayer) {
                cyclePoint(stack, serverPlayer, bound.get(), hit.getBlockPos());
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Settler settler)) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            // interactLivingEntity may receive a copy in creative mode; edit the stack in the hand.
            ItemStack held = serverPlayer.getItemInHand(hand);
            if (serverPlayer.isSecondaryUseActive()) {
                held.set(ModDataComponents.SCROLL_BINDING.get(), ScrollBinding.settler(settler.getUUID(), settler.getName().getString()));
                serverPlayer.sendOverlayMessage(Component.translatable("civmod.scroll.bound_settler", settler.getName()));
            } else {
                binding(held).flatMap(ScrollBinding::jobBlock).ifPresentOrElse(
                        jobBlock -> assign(held, serverPlayer, jobBlock, settler),
                        () -> serverPlayer.sendOverlayMessage(Component.translatable("civmod.scroll.not_bound").withStyle(ChatFormatting.RED)));
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void assignBed(ItemStack stack, ServerPlayer player, java.util.UUID settler, BlockPos clicked) {
        ServerLevel level = player.level();
        SettlementData data = SettlementData.get(level);
        Optional<com.gilfort.civmod.settlement.Settlement> settlement = data.settlements().stream()
                .filter(s -> s.members().contains(settler)).findFirst();
        Optional<java.util.UUID> previous = SettlerBeds.head(level, clicked)
                .flatMap(head -> settlement.map(s -> s.beds().get(head)));
        String label = binding(stack).map(ScrollBinding::label).orElse("?");
        SettlerBeds.AssignResult result = SettlerBeds.assign(level, data, settlement, settler, clicked);
        Component message = switch (result) {
            case ASSIGNED -> Component.translatable("civmod.scroll.bed.assigned", label);
            case TRANSFERRED -> Component.translatable("civmod.scroll.bed.transferred", label,
                    previous.map(level::getEntity).map(entity -> entity.getName()).orElse(Component.literal("?")));
            case RELEASED -> Component.translatable("civmod.scroll.bed.released");
            case NOT_A_BED -> Component.translatable("civmod.scroll.bed.not_a_bed").withStyle(ChatFormatting.RED);
            case OUTSIDE -> Component.translatable("civmod.scroll.point.outside").withStyle(ChatFormatting.RED);
            case SPAWN_BED -> Component.translatable("civmod.scroll.bed.spawn_bed").withStyle(ChatFormatting.RED);
            case HOMELESS -> Component.translatable("civmod.scroll.bed.homeless", label).withStyle(ChatFormatting.RED);
        };
        player.sendOverlayMessage(message);
    }

    private static void bindJobBlock(ItemStack stack, ServerPlayer player, BlockPos pos, Job jobType) {
        if (JobBlockSite.at(player.level(), pos).isEmpty()) {
            player.sendOverlayMessage(Component.translatable("civmod.scroll.job_block_unregistered").withStyle(ChatFormatting.RED));
            return;
        }
        stack.set(ModDataComponents.SCROLL_BINDING.get(), ScrollBinding.jobBlock(pos, jobType.displayName().getString()));
        player.sendOverlayMessage(Component.translatable("civmod.scroll.bound_job_block", jobType.displayName(), pos.toShortString()));
    }

    private static Optional<SettlementBlock> boundBlock(ItemStack stack, ServerPlayer player, BlockPos jobBlock) {
        Optional<SettlementBlock> block = JobBlockSite.blockAt(player.level(), jobBlock);
        if (block.isEmpty()) {
            stack.remove(ModDataComponents.SCROLL_BINDING.get());
            player.sendOverlayMessage(Component.translatable("civmod.scroll.binding_lost").withStyle(ChatFormatting.RED));
        }
        return block;
    }

    private static void cyclePoint(ItemStack stack, ServerPlayer player, BlockPos jobBlock, BlockPos target) {
        Optional<SettlementBlock> block = boundBlock(stack, player, jobBlock);
        if (block.isEmpty()) {
            return;
        }
        ServerLevel level = player.level();
        Job jobType = block.get().jobType();
        if (ClerkStorage.isClerk(block.get())) {
            if (!target.equals(jobBlock) && JobBlockSite.blockAt(level, target).filter(ClerkStorage::isClerk).isPresent()) {
                linkServedClerk(player, jobBlock, target);
            } else {
                cycleClerkPoint(player, jobBlock, target);
            }
            return;
        }
        // A station of a Specialization offered here is a work point too; each Job then works only at its own stations.
        Optional<Job> workJob = workPlaceJob(level, block.get(), target);
        boolean container = isContainer(level, target);
        JobAssignments.PointResult result = JobAssignments.cyclePoint(SettlementData.get(level), jobBlock, target, workJob.isPresent(), container);
        Component message = switch (result) {
            case WORK -> workPointMessage(level, workJob.orElse(jobType), target);
            case COLLECT -> Component.translatable("civmod.scroll.point.collect");
            case DROP_OFF -> Component.translatable("civmod.scroll.point.drop_off");
            case REMOVED -> Component.translatable("civmod.scroll.point.removed");
            case NO_JOB_BLOCK -> Component.translatable("civmod.scroll.binding_lost").withStyle(ChatFormatting.RED);
            case OUTSIDE -> Component.translatable("civmod.scroll.point.outside").withStyle(ChatFormatting.RED);
            case TOO_MANY -> Component.translatable("civmod.scroll.point.too_many", SettlementBlock.MAX_WORK_POINTS).withStyle(ChatFormatting.RED);
            case INVALID -> Component.translatable("civmod.scroll.point.invalid", jobType.displayName(),
                    jobType.workPlaceName()).withStyle(ChatFormatting.RED);
            case STORAGE, STAND_SPOT, STORAGE_FULL, TOO_MANY_SPOTS, TAKEN -> throw new IllegalStateException("Clerk result for " + jobType);
        };
        player.sendOverlayMessage(message);
    }

    /**
     * The action bar text of a new work point (vault {@code Systems/JobBlock.md}, decisions on biome bonuses): the Job,
     * then the point's biome and the effects its biome bonuses give the Job ("Work place for Miner added · Desert · Ore
     * chance ×1.5"); a Job whose biome is not read at its work place gets no biome.
     *
     * @param level  the level
     * @param job    the Job working at the point
     * @param target the work point
     * @return the text
     */
    public static Component workPointMessage(ServerLevel level, Job job, BlockPos target) {
        Component added = Component.translatable("civmod.scroll.point.work", job.displayName());
        if (BiomeBonuses.position(job) != BiomePosition.WORK_PLACE) {
            return added;
        }
        return BiomeBonusText.line(job, CivModApi.biomeBonusAt(level, job, target))
                .<Component>map(line -> Component.empty().append(added).append(" · ").append(line)).orElse(added);
    }

    /** Bound to a Clerk JobBlock (a Warehouse), right-click another Clerk JobBlock: links or unlinks it as Served Clerk. */
    private static void linkServedClerk(ServerPlayer player, BlockPos warehouse, BlockPos target) {
        ServerLevel level = player.level();
        SettlementData data = SettlementData.get(level);
        Warehouses.LinkResult result = Warehouses.link(data, warehouse, target);
        Optional<Settlement> settlement = data.settlementAt(warehouse);
        Component targetName = JobBlockSite.blockAt(level, target).map(ClerkStorage::name).orElse(Component.literal("?"));
        Component message = switch (result) {
            case LINKED -> Component.translatable("civmod.scroll.served.linked", targetName, JobBlockSite.blockAt(level, warehouse)
                    .map(block -> block.served().size()).orElse(0), JobBlockSite.blockAt(level, warehouse)
                    .map(block -> SettlementRules.servedClerks(block.level())).orElse(0));
            case UNLINKED -> Component.translatable("civmod.scroll.served.unlinked", targetName);
            case NOT_A_CLERK, SELF -> Component.translatable("civmod.scroll.served.not_a_clerk").withStyle(ChatFormatting.RED);
            case OWN_DELIVERYMEN -> Component.translatable("civmod.scroll.served.own_deliverymen", targetName).withStyle(ChatFormatting.RED);
            case TAKEN -> Component.translatable("civmod.scroll.served.taken", settlement.flatMap(found -> Warehouses.servedBy(found, target))
                    .map(ClerkStorage::name).orElse(Component.literal("?"))).withStyle(ChatFormatting.RED);
            case CHAIN -> Component.translatable("civmod.scroll.served.chain").withStyle(ChatFormatting.RED);
            case FULL -> Component.translatable("civmod.scroll.served.full", JobBlockSite.blockAt(level, warehouse)
                    .map(block -> SettlementRules.servedClerks(block.level())).orElse(0)).withStyle(ChatFormatting.RED);
        };
        player.sendOverlayMessage(message);
    }

    /** Clerk points: a container cycles storage → removed, any other block stand spot → removed. */
    private static void cycleClerkPoint(ServerPlayer player, BlockPos clerk, BlockPos target) {
        ServerLevel level = player.level();
        SettlementData data = SettlementData.get(level);
        Direction facing = player.getDirection().getOpposite();
        JobAssignments.PointResult result = JobAssignments.cycleClerkPoint(data, clerk, target, isContainer(level, target),
                isStandable(level, target), facing);
        Component message = switch (result) {
            case STORAGE -> Component.translatable("civmod.scroll.point.storage", JobBlockSite.blockAt(level, clerk)
                    .map(block -> block.storage().size()).orElse(0), JobBlockSite.blockAt(level, clerk)
                    .map(block -> SettlementRules.clerkStorage(block.level())).orElse(0));
            case STAND_SPOT -> Component.translatable("civmod.scroll.point.stand_spot", JobBlockSite.blockAt(level, clerk)
                    .map(block -> block.standSpots().size()).orElse(0), facing.getName());
            case REMOVED -> Component.translatable("civmod.scroll.point.removed");
            case NO_JOB_BLOCK -> Component.translatable("civmod.scroll.binding_lost").withStyle(ChatFormatting.RED);
            case OUTSIDE -> Component.translatable("civmod.scroll.point.outside").withStyle(ChatFormatting.RED);
            case STORAGE_FULL -> Component.translatable("civmod.scroll.point.storage_full", JobBlockSite.blockAt(level, clerk)
                    .map(block -> SettlementRules.clerkStorage(block.level())).orElse(0)).withStyle(ChatFormatting.RED);
            case TOO_MANY_SPOTS -> Component.translatable("civmod.scroll.point.too_many_spots", SettlementRules.MAX_STAND_SPOTS)
                    .withStyle(ChatFormatting.RED);
            case TAKEN -> Component.translatable("civmod.scroll.point.taken", JobAssignments.storageOwner(data, target)
                    .map(ClerkStorage::name).orElse(Component.literal("?"))).withStyle(ChatFormatting.RED);
            case INVALID, WORK, COLLECT, DROP_OFF, TOO_MANY -> Component.translatable("civmod.scroll.point.clerk_invalid")
                    .withStyle(ChatFormatting.RED);
        };
        player.sendOverlayMessage(message);
    }

    /**
     * @param level the level
     * @param pos   a position
     * @return whether a settler can stand on the block (sturdy top, two blocks of air above)
     */
    public static boolean isStandable(Level level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty();
    }

    /** Bound to a JobBlock, right-click a settler: opens the job picker if the settler may join the block. */
    private static void assign(ItemStack stack, ServerPlayer player, BlockPos jobBlock, Settler settler) {
        Optional<SettlementBlock> block = boundBlock(stack, player, jobBlock);
        if (block.isEmpty()) {
            return;
        }
        JobAssignments.AssignResult result = JobAssignments.check(SettlementData.get(player.level()), jobBlock, settler.getUUID(),
                settler.getSettlementId());
        if (result != JobAssignments.AssignResult.OK) {
            player.sendOverlayMessage(message(result, block.get(), settler, block.get().jobType()));
            return;
        }
        JobPicker.open(player, block.get(), settler);
    }

    /**
     * Assigns the Job chosen in the job picker and tells the player the result.
     *
     * @param player   the player
     * @param jobBlock the JobBlock the scroll is bound to
     * @param settler  the settler
     * @param job      the chosen Job
     */
    static void assign(ServerPlayer player, BlockPos jobBlock, Settler settler, Job job) {
        Optional<SettlementBlock> block = JobBlockSite.blockAt(player.level(), jobBlock);
        if (block.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("civmod.scroll.binding_lost").withStyle(ChatFormatting.RED));
            return;
        }
        JobAssignments.AssignResult result = SettlementWorld.assign(player.level(), jobBlock, settler, job);
        player.sendOverlayMessage(message(result, block.get(), settler, job));
    }

    private static Component message(JobAssignments.AssignResult result, SettlementBlock block, Settler settler, Job job) {
        return switch (result) {
            case OK -> Component.translatable("civmod.scroll.assigned", job.displayName(), settler.getName());
            case NO_JOB_BLOCK -> Component.translatable("civmod.scroll.binding_lost").withStyle(ChatFormatting.RED);
            case UNCONNECTED -> Component.translatable("civmod.scroll.assign.unconnected").withStyle(ChatFormatting.RED);
            case OCCUPIED -> Component.translatable("civmod.scroll.assign.occupied",
                    JobPlaces.holders(block, job).stream().findFirst().map(SettlementBlock.Assignee::name).orElse("?")).withStyle(ChatFormatting.RED);
            case FULL -> Component.translatable("civmod.scroll.assign.full", job.displayName(), JobPlaces.taken(block, job),
                    JobPlaces.places(block.jobType(), job, block.level())).withStyle(ChatFormatting.RED);
            case NOT_OFFERED -> Component.translatable("civmod.scroll.assign.not_offered", job.displayName()).withStyle(ChatFormatting.RED);
            case SAME_BLOCK -> Component.translatable("civmod.scroll.assign.same_block", settler.getName(),
                    block.assignee(settler.getUUID()).map(assignee -> assignee.job().displayName()).orElse(Component.literal("?"))).withStyle(ChatFormatting.RED);
            case MISSION_JOB -> Component.translatable("civmod.scroll.assign.mission_job", settler.getName()).withStyle(ChatFormatting.RED);
            case OTHER_SETTLEMENT -> Component.translatable("civmod.scroll.assign.other_settlement", settler.getName()).withStyle(ChatFormatting.RED);
            case DUPLICATE -> Component.translatable("civmod.scroll.assign.duplicate", settler.getName(), job.displayName())
                    .withStyle(ChatFormatting.RED);
        };
    }

    /**
     * @param level   the level
     * @param pos     a position
     * @param jobType a job
     * @return whether the block is a valid work place for the job
     */
    public static boolean isValidWorkPlace(Level level, BlockPos pos, Job jobType) {
        return jobType.isValidWorkPlace(level, pos);
    }

    /**
     * @param level the level
     * @param block a JobBlock
     * @param pos   a position
     * @return the first Job the JobBlock offers at its level (its Base Job first, then its Specializations) for which the
     *         block at the position is a valid work place
     */
    public static Optional<Job> workPlaceJob(Level level, SettlementBlock block, BlockPos pos) {
        return JobPlaces.lines(block).stream().map(JobPlaces.Line::job).filter(job -> job.isValidWorkPlace(level, pos)).findFirst();
    }

    /**
     * @param level the level
     * @param pos   a position
     * @return whether the block exposes an item inventory
     */
    public static boolean isContainer(Level level, BlockPos pos) {
        return level.getCapability(Capabilities.Item.BLOCK, pos, null) != null;
    }
}
