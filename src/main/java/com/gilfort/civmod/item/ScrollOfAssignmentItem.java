package com.gilfort.civmod.item;

import java.util.Optional;

import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
 * <li>Bound to a JobBlock, right-click a block (or water): cycle work POI → collect → drop-off → removed.</li>
 * <li>Bound to a JobBlock, right-click a settler: assign the job.</li>
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
                if (!isContainer(level, pos)) {
                    return InteractionResult.PASS;
                }
                if (player instanceof ServerPlayer serverPlayer) {
                    togglePantry(serverPlayer, pos);
                }
                return InteractionResult.SUCCESS;
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

    private static void togglePantry(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        SettlementData data = SettlementData.get(level);
        Optional<com.gilfort.civmod.settlement.Settlement> settlement = data.settlementContaining(pos);
        if (settlement.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("civmod.scroll.point.outside").withStyle(ChatFormatting.RED));
            return;
        }
        boolean added = data.togglePantry(settlement.get(), com.gilfort.civmod.food.Pantries.canonical(level, settlement.get(), pos));
        player.sendOverlayMessage(Component.translatable(added ? "civmod.scroll.pantry.added" : "civmod.scroll.pantry.removed",
                settlement.get().displayName()));
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
        Optional<SettlementBlock> block = JobBlockSite.at(player.level(), jobBlock).map(JobBlockSite::block);
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
        boolean validWork = isValidWorkPlace(level, target, jobType);
        boolean container = isContainer(level, target);
        JobAssignments.PointResult result = JobAssignments.cyclePoint(SettlementData.get(level), jobBlock, target, validWork, container);
        Component message = switch (result) {
            case WORK -> Component.translatable("civmod.scroll.point.work", jobType.displayName());
            case COLLECT -> Component.translatable("civmod.scroll.point.collect");
            case DROP_OFF -> Component.translatable("civmod.scroll.point.drop_off");
            case REMOVED -> Component.translatable("civmod.scroll.point.removed");
            case NO_JOB_BLOCK -> Component.translatable("civmod.scroll.binding_lost").withStyle(ChatFormatting.RED);
            case OUTSIDE -> Component.translatable("civmod.scroll.point.outside").withStyle(ChatFormatting.RED);
            case TOO_MANY -> Component.translatable("civmod.scroll.point.too_many", SettlementBlock.MAX_WORK_POINTS).withStyle(ChatFormatting.RED);
            case INVALID -> Component.translatable("civmod.scroll.point.invalid", jobType.displayName(),
                    jobType.workPlaceName()).withStyle(ChatFormatting.RED);
        };
        player.sendOverlayMessage(message);
    }

    private static void assign(ItemStack stack, ServerPlayer player, BlockPos jobBlock, Settler settler) {
        Optional<SettlementBlock> block = boundBlock(stack, player, jobBlock);
        if (block.isEmpty()) {
            return;
        }
        SettlementData data = SettlementData.get(player.level());
        JobAssignments.AssignResult result = JobAssignments.assign(data, jobBlock, settler.getUUID(), settler.getName().getString(),
                settler.getSettlementId(), settler.getJobs().stream().map(SettlerJob::jobType).toList());
        Job jobType = block.get().jobType();
        Component message = switch (result) {
            case OK -> {
                settler.addJob(new SettlerJob(jobType, jobBlock));
                yield Component.translatable("civmod.scroll.assigned", jobType.displayName(), settler.getName());
            }
            case NO_JOB_BLOCK -> Component.translatable("civmod.scroll.binding_lost").withStyle(ChatFormatting.RED);
            case UNCONNECTED -> Component.translatable("civmod.scroll.assign.unconnected").withStyle(ChatFormatting.RED);
            case OCCUPIED -> Component.translatable("civmod.scroll.assign.occupied",
                    block.get().assignees().stream().findFirst().map(SettlementBlock.Assignee::name).orElse("?")).withStyle(ChatFormatting.RED);
            case FULL -> Component.translatable("civmod.scroll.assign.full", block.get().capacity()).withStyle(ChatFormatting.RED);
            case MISSION_JOB -> Component.translatable("civmod.scroll.assign.mission_job", settler.getName()).withStyle(ChatFormatting.RED);
            case OTHER_SETTLEMENT -> Component.translatable("civmod.scroll.assign.other_settlement", settler.getName()).withStyle(ChatFormatting.RED);
            case DUPLICATE -> Component.translatable("civmod.scroll.assign.duplicate", settler.getName(), jobType.displayName())
                    .withStyle(ChatFormatting.RED);
        };
        player.sendOverlayMessage(message);
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
     * @param pos   a position
     * @return whether the block exposes an item inventory
     */
    public static boolean isContainer(Level level, BlockPos pos) {
        return level.getCapability(Capabilities.Item.BLOCK, pos, null) != null;
    }
}
