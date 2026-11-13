package com.gilfort.civmod.item;

import java.util.Locale;
import java.util.Optional;


import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.logistics.SlipSites;
import com.gilfort.civmod.menu.DeliverySlipMenu;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Delivery Slip (vault {@code Jobs/Deliveryman.md}): a written order for the Deliverymen of a Warehouse.
 * <ul>
 * <li>Right-click in the air: open the slip window (filter, variant, limits).</li>
 * <li>Shift + right-click a container: set it as the one source.</li>
 * <li>Right-click a container: add it as a target; right-click it again: remove it.</li>
 * </ul>
 * Source and targets lie in the same settlement. A slip with a source and a target is filled and goes into the Delivery
 * Orders tab of a Clerk JobBlock; the order itself is the {@link DeliverySlip} component.
 */
public class DeliverySlipItem extends Item {

    /** What setting a source or toggling a target did. */
    public enum Result {
        /** The source was set. */
        SOURCE_SET,
        /** The target was added. */
        TARGET_ADDED,
        /** The target was removed. */
        TARGET_REMOVED,
        /** The block is no container. */
        NOT_A_CONTAINER,
        /** The block lies outside every settlement. */
        OUTSIDE,
        /** The block lies in another settlement than the rest of the slip. */
        OTHER_SETTLEMENT,
        /** The source cannot be a target. */
        IS_SOURCE
    }

    /**
     * @param properties the item properties
     */
    public DeliverySlipItem(Properties properties) {
        super(properties);
    }

    /**
     * @param stack a Delivery Slip
     * @return its order, written onto it first if it is a plain slip
     */
    public static DeliverySlip order(ItemStack stack) {
        DeliverySlip slip = stack.get(ModDataComponents.DELIVERY_SLIP.get());
        if (slip == null) {
            slip = DeliverySlip.blank();
            stack.set(ModDataComponents.DELIVERY_SLIP.get(), slip);
        }
        return slip;
    }

    /**
     * Sets the source of a slip.
     *
     * @param level the level
     * @param stack the slip
     * @param pos   the clicked block
     * @return what happened
     */
    public static Result setSource(ServerLevel level, ItemStack stack, BlockPos pos) {
        DeliverySlip slip = order(stack);
        Optional<Result> refused = refusal(level, slip, pos);
        if (refused.isPresent()) {
            return refused.get();
        }
        stack.set(ModDataComponents.DELIVERY_SLIP.get(), slip.withSource(pos));
        return Result.SOURCE_SET;
    }

    /**
     * Adds a target to a slip, or removes it when the slip has it already.
     *
     * @param level the level
     * @param stack the slip
     * @param pos   the clicked block
     * @return what happened
     */
    public static Result toggleTarget(ServerLevel level, ItemStack stack, BlockPos pos) {
        DeliverySlip slip = order(stack);
        if (slip.source().filter(pos::equals).isPresent()) {
            return Result.IS_SOURCE;
        }
        if (slip.targets().contains(pos)) {
            stack.set(ModDataComponents.DELIVERY_SLIP.get(), slip.withTargetToggled(pos));
            return Result.TARGET_REMOVED;
        }
        Optional<Result> refused = refusal(level, slip, pos);
        if (refused.isPresent()) {
            return refused.get();
        }
        stack.set(ModDataComponents.DELIVERY_SLIP.get(), slip.withTargetToggled(pos));
        return Result.TARGET_ADDED;
    }

    /** Why a block cannot join the slip, empty if it can. */
    private static Optional<Result> refusal(ServerLevel level, DeliverySlip slip, BlockPos pos) {
        if (!SlipSites.isContainer(level, pos)) {
            return Optional.of(Result.NOT_A_CONTAINER);
        }
        SettlementData data = SettlementData.get(level);
        Optional<Settlement> settlement = data.settlementContaining(pos);
        if (settlement.isEmpty()) {
            return Optional.of(Result.OUTSIDE);
        }
        Optional<BlockPos> known = slip.source().or(() -> slip.targets().stream().findFirst());
        boolean elsewhere = known.flatMap(data::settlementContaining).filter(other -> other.id() != settlement.get().id()).isPresent();
        return elsewhere ? Optional.of(Result.OTHER_SETTLEMENT) : Optional.empty();
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            // The client cannot tell a container from the capability; the server decides.
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        if (!SlipSites.isContainer(level, pos)) {
            return InteractionResult.PASS;
        }
        // The hand's stack: the context may hold a copy in creative mode.
        ItemStack held = player.getItemInHand(context.getHand());
        Result result = player.isSecondaryUseActive() ? setSource(level, held, pos) : toggleTarget(level, held, pos);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendOverlayMessage(message(result));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        order(player.getItemInHand(hand));
        serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, opener) -> new DeliverySlipMenu(id, inventory, hand),
                Component.translatable("item.civmod.delivery_slip")), buffer -> buffer.writeBoolean(hand == InteractionHand.MAIN_HAND));
        return InteractionResult.SUCCESS;
    }

    private static Component message(Result result) {
        Component text = Component.translatable("civmod.slip.result." + result.name().toLowerCase(Locale.ROOT));
        return switch (result) {
            case SOURCE_SET, TARGET_ADDED, TARGET_REMOVED -> text;
            default -> text.copy().withStyle(ChatFormatting.RED);
        };
    }
}
