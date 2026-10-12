package com.gilfort.civmod.client;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.settlement.SettlementOutline;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Draws the combined join-radius outline of nearby settlements as a particle line at foot height, in the
 * settlement colour.
 * <p>
 * Only arcs of a JobBlock's circle that are not covered by another circle of the same settlement are drawn, so each
 * settlement shows one outer outline. Points farther than {@value #DRAW_DISTANCE} blocks from the player are skipped.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public final class SettlementOutlineRenderer {

    private static final int INTERVAL = 10;
    private static final double DRAW_DISTANCE = 64.0;
    private static List<SettlementOutline.OutlineSettlement> settlements = List.of();
    private static boolean alwaysShow;

    private SettlementOutlineRenderer() {
    }

    /**
     * Handles the outline payload from the server.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handle(SettlementOutline.Sync payload, IPayloadContext context) {
        settlements = payload.settlements();
        alwaysShow = payload.alwaysShow();
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.isPaused() || player.tickCount % INTERVAL != 0) {
            return;
        }
        boolean holding = SettlementOutline.isJobBlockItem(player.getItemInHand(InteractionHand.MAIN_HAND))
                || SettlementOutline.isJobBlockItem(player.getItemInHand(InteractionHand.OFF_HAND));
        if (!holding && !alwaysShow) {
            return;
        }
        double y = player.getY() + 0.1;
        for (SettlementOutline.OutlineSettlement settlement : settlements) {
            DustParticleOptions particle = new DustParticleOptions(settlement.color(), 1.0F);
            for (SettlementOutline.OutlineBlock block : settlement.blocks()) {
                drawArcs(level, particle, block, settlement.blocks(), player.getX(), y, player.getZ());
            }
        }
    }

    private static void drawArcs(ClientLevel level, DustParticleOptions particle, SettlementOutline.OutlineBlock block,
            List<SettlementOutline.OutlineBlock> others,
            double playerX, double y, double playerZ) {
        double centerX = block.pos().getX() + 0.5;
        double centerZ = block.pos().getZ() + 0.5;
        int radius = SettlementRules.joinRadius(block.level());
        int points = (int) Math.ceil(2 * Math.PI * radius);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double x = centerX + radius * Math.cos(angle);
            double z = centerZ + radius * Math.sin(angle);
            double dx = x - playerX;
            double dz = z - playerZ;
            if (dx * dx + dz * dz > DRAW_DISTANCE * DRAW_DISTANCE || isCovered(block, others, x, z)) {
                continue;
            }
            level.addParticle(particle, x, y, z, 0.0, 0.0, 0.0);
        }
    }

    private static boolean isCovered(SettlementOutline.OutlineBlock self, List<SettlementOutline.OutlineBlock> others, double x, double z) {
        for (SettlementOutline.OutlineBlock other : others) {
            if (other == self) {
                continue;
            }
            double dx = x - (other.pos().getX() + 0.5);
            double dz = z - (other.pos().getZ() + 0.5);
            double radius = SettlementRules.joinRadius(other.level());
            if (dx * dx + dz * dz < radius * radius) {
                return true;
            }
        }
        return false;
    }
}
