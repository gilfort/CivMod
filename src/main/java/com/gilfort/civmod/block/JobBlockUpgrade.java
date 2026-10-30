package com.gilfort.civmod.block;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Upgrading a JobBlock with a JobBlock Upgrade item (vault {@code Systems/JobBlock.md}, decisions of 2026-10-29): the
 * Lv N item raises a block at Lv N − 1 by one level, but only once a settler currently assigned to the block has reached
 * the block's skill cap in a Job it holds there (Base Job or Specialization). Settlers at other blocks and former
 * workers do not count; settlers that are not loaded do not either.
 */
public final class JobBlockUpgrade {

    private JobBlockUpgrade() {
    }

    /**
     * Upgrades the JobBlock or tells the player why not (action bar); on success the item is used (not with infinite
     * materials), with sounds and particles.
     *
     * @param level       the dimension
     * @param pos         the JobBlock position
     * @param jobBlock    its block entity
     * @param player      the player clicking
     * @param stack       the Upgrade item stack
     * @param targetLevel the level the item upgrades to
     * @return whether the block was upgraded
     */
    static boolean use(ServerLevel level, BlockPos pos, JobBlockEntity jobBlock, Player player, ItemStack stack, int targetLevel) {
        int current = jobBlock.getJobLevel();
        Optional<Component> refusal = refusal(level, pos, current, targetLevel);
        if (refusal.isPresent()) {
            player.sendOverlayMessage(refusal.get().copy().withStyle(ChatFormatting.RED));
            return false;
        }
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        jobBlock.setJobLevel(targetLevel);
        SettlementWorld.setLevel(level, pos, targetLevel);
        level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 15, 0.4, 0.4, 0.4, 0.0);
        player.sendOverlayMessage(Component.translatable("civmod.upgrade.done", jobBlock.getBlockState().getBlock().getName(), targetLevel));
        return true;
    }

    /**
     * @param level        the dimension
     * @param pos          the JobBlock position
     * @param currentLevel the JobBlock's level
     * @param targetLevel  the level the item upgrades to
     * @return why the item does not upgrade the block ("Already Lv 5", "Needs Upgrade Lv 3", "Needs a worker at Lv 40"),
     *         empty if it does
     */
    public static Optional<Component> refusal(ServerLevel level, BlockPos pos, int currentLevel, int targetLevel) {
        if (currentLevel >= SettlementRules.MAX_LEVEL) {
            return Optional.of(Component.translatable("civmod.upgrade.max", SettlementRules.MAX_LEVEL));
        }
        if (targetLevel != currentLevel + 1) {
            return Optional.of(Component.translatable("civmod.upgrade.needs_item", currentLevel + 1));
        }
        int cap = SettlementRules.skillCap(currentLevel);
        if (!hasWorkerAtCap(level, pos, cap)) {
            return Optional.of(Component.translatable("civmod.upgrade.needs_worker", cap));
        }
        return Optional.empty();
    }

    /** Whether a loaded settler assigned to the block has reached {@code cap} in a Job it holds at this block. */
    private static boolean hasWorkerAtCap(ServerLevel level, BlockPos pos, int cap) {
        for (SettlementBlock.Assignee assignee : JobBlockSite.blockAt(level, pos).map(SettlementBlock::assignees).orElse(List.of())) {
            if (level.getEntity(assignee.id()) instanceof Settler settler && settler.getJobs().stream()
                    .filter(job -> job.jobBlock().equals(pos)).map(SettlerJob::jobType)
                    .anyMatch(job -> settler.getSkills().get(job).level() >= cap)) {
                return true;
            }
        }
        return false;
    }
}
