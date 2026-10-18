package com.gilfort.civmod.block;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A JobBlock: offers one job to one NPC and anchors the settlement (vault {@code Systems/JobBlock.md}).
 * <p>
 * Registration in the settlement happens in {@link #onPlace} for every placement (players, commands, structures);
 * {@link #setPlacedBy} then applies the item level and gives the placing player feedback.
 */
public class JobBlock extends BaseEntityBlock {

    private final Supplier<Job> jobType;

    /**
     * @param jobType    the offered job (resolved lazily, jobs register after blocks)
     * @param properties the block properties
     */
    public JobBlock(Supplier<Job> jobType, Properties properties) {
        super(properties);
        this.jobType = jobType;
    }

    /**
     * @return the offered job
     */
    public Job getJobType() {
        return jobType.get();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new JobBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof JobBlockEntity jobBlock) {
            // The block entity writes the info for the client itself (JobBlockEntity#writeClientSideData).
            player.openMenu(jobBlock);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            // For commands / structures the block entity does not exist yet; its level is synced in onLoad.
            int jobLevel = level.getBlockEntity(pos) instanceof JobBlockEntity jobBlock ? jobBlock.getJobLevel() : SettlementRules.MIN_LEVEL;
            SettlementWorld.place(serverLevel, pos, getJobType(), jobLevel);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        super.setPlacedBy(level, pos, state, by, itemStack);
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof JobBlockEntity jobBlock) {
            if (by instanceof ServerPlayer player) {
                SettlementWorld.placedByPlayer(serverLevel, pos, player, jobBlock.getJobLevel());
            } else {
                SettlementWorld.setLevel(serverLevel, pos, jobBlock.getJobLevel());
            }
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        Containers.updateNeighboursAfterDestroy(state, level, pos);
    }
}
