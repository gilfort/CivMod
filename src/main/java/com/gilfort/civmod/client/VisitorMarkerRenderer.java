package com.gilfort.civmod.client;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Draws the visitor marker (vault {@code Systems/NPC Population.md}, section 6): a small compass above the name tag of every
 * visitor of an inn, so the player tells visitors from settlers. The marker is not a thought bubble: it shows within
 * {@value #DISTANCE} blocks also while the visitor sleeps, and the client option "Show thought bubbles" does not hide it. The server
 * tells the client who is a visitor through the synched flag of the settler ({@link Settler#hasVisitorMarker()}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public final class VisitorMarkerRenderer {

    /** Markers further away are not drawn. */
    private static final double DISTANCE = 16.0;
    /** Height of the compass centre above the top of the settler: just above the name tag. */
    private static final double ABOVE_HEAD = 0.85;
    private static final float COMPASS_SCALE = 0.35F;

    private static List<Frame> frame = List.of();

    /** A marker drawn this frame. */
    private record Frame(Vec3 pos, ItemStackRenderState compass) {
    }

    private VisitorMarkerRenderer() {
    }

    @SubscribeEvent
    static void onExtract(ExtractLevelRenderStateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = event.getLevel();
        if (minecraft.player == null) {
            frame = List.of();
            return;
        }
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 viewer = minecraft.player.position();
        List<Frame> frames = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Settler settler && settler.hasVisitorMarker() && !settler.isInvisible()
                    && settler.position().distanceToSqr(viewer) <= DISTANCE * DISTANCE) {
                Vec3 pos = settler.getPosition(partialTick).add(0.0, settler.getBbHeight() + ABOVE_HEAD, 0.0);
                frames.add(new Frame(pos, compass(minecraft)));
            }
        }
        frame = frames;
    }

    /** The compass is made new for every frame: its needle moves with the world. */
    private static ItemStackRenderState compass(Minecraft minecraft) {
        ItemStackRenderState state = new ItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(state, new ItemStack(Items.COMPASS), ItemDisplayContext.FIXED, minecraft.level,
                null, 0);
        return state;
    }

    @SubscribeEvent
    static void onSubmit(SubmitCustomGeometryEvent event) {
        if (frame.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        for (Frame marker : frame) {
            poseStack.pushPose();
            poseStack.translate(marker.pos().x - camera.x, marker.pos().y - camera.y, marker.pos().z - camera.z);
            // Faces the camera like a name tag.
            poseStack.rotate(event.getLevelRenderState().cameraRenderState.orientation);
            poseStack.scale(COMPASS_SCALE, COMPASS_SCALE, COMPASS_SCALE);
            marker.compass().submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
    }
}
