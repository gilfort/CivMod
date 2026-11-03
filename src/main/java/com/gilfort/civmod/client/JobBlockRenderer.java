package com.gilfort.civmod.client;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a floating, rotating red X above unconnected JobBlocks and the beam of a glowing Center Block.
 */
public class JobBlockRenderer implements BlockEntityRenderer<JobBlockEntity, JobBlockRenderer.State> {

    private static final int VIEW_DISTANCE = 32;
    private static final float DEGREES_PER_TICK = 3.0F;

    private final ItemModelResolver itemModelResolver;

    /**
     * @param context the renderer context
     */
    public JobBlockRenderer(BlockEntityRendererProvider.Context context) {
        this.itemModelResolver = context.itemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(JobBlockEntity blockEntity, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.beam = blockEntity.getGlow() == JobBlockEntity.GLOW_BEAM;
        if (state.beam && blockEntity.getLevel() != null) {
            state.beamTime = blockEntity.getLevel().getGameTime() + partialTicks;
        }
        state.unconnected = blockEntity.isUnconnected();
        if (state.unconnected && blockEntity.getLevel() != null) {
            state.rotation = (blockEntity.getLevel().getGameTime() + partialTicks) * DEGREES_PER_TICK;
            itemModelResolver.updateForTopItem(state.marker, ModItems.UNCONNECTED_MARKER.get().getDefaultInstance(),
                    ItemDisplayContext.FIXED, blockEntity.getLevel(), null, 0);
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.beam) {
            // Like a beacon's beam, white, from the block up to the build limit.
            BeaconRenderer.submitBeaconBeam(poseStack, collector, BeaconRenderer.BEAM_LOCATION, state.beamTime, 1.0F, 0,
                    BeaconRenderer.MAX_RENDER_Y, 0xFFFFFFFF, BeaconRenderer.SOLID_BEAM_RADIUS, BeaconRenderer.BEAM_GLOW_RADIUS);
        }
        if (!state.unconnected || state.marker.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5F, 1.5F, 0.5F);
        poseStack.rotateDegrees(Axis.YP, state.rotation);
        poseStack.scale(0.25F, 0.25F, 0.25F);
        state.marker.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    /**
     * Render state of a JobBlock.
     */
    public static class State extends BlockEntityRenderState {
        boolean unconnected;
        boolean beam;
        float beamTime;
        float rotation;
        final ItemStackRenderState marker = new ItemStackRenderState();
    }
}
