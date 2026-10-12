package com.gilfort.civmod.client;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Renders the vanilla straw bed under settlers that sleep without a bed. The bed is never placed as a block.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public final class StrawBedRenderer {

    private static final double RANGE = 48.0;
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();

    private static @Nullable BlockModelResolver resolver;
    private static List<Part> frame = List.of();

    private record Part(BlockPos pos, BlockModelRenderState model, int light) {
    }

    private StrawBedRenderer() {
    }

    @SubscribeEvent
    static void onExtract(ExtractLevelRenderStateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            frame = List.of();
            return;
        }
        if (resolver == null) {
            resolver = new BlockModelResolver(minecraft.getModelManager());
        }
        Vec3 camera = minecraft.player.position();
        List<Part> parts = new ArrayList<>();
        for (Entity entity : event.getLevel().entitiesForRendering()) {
            if (!(entity instanceof Settler settler) || !settler.isSleeping() || settler.distanceToSqr(camera) > RANGE * RANGE) {
                continue;
            }
            settler.strawBedFacing().ifPresent(facing -> settler.getSleepingPos().ifPresent(head -> {
                parts.add(part(event, head, facing, BedPart.HEAD));
                parts.add(part(event, head.relative(facing.getOpposite()), facing, BedPart.FOOT));
            }));
        }
        frame = parts;
    }

    private static Part part(ExtractLevelRenderStateEvent event, BlockPos pos, Direction facing, BedPart bedPart) {
        BlockState state = Blocks.STRAW_BED.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, bedPart);
        BlockModelRenderState model = new BlockModelRenderState();
        resolver.update(model, state, DISPLAY_CONTEXT);
        return new Part(pos, model, LightCoordsUtil.getLightCoords(event.getLevel(), pos));
    }

    @SubscribeEvent
    static void onSubmit(SubmitCustomGeometryEvent event) {
        if (frame.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        for (Part part : frame) {
            poseStack.pushPose();
            poseStack.translate(part.pos().getX() - camera.x, part.pos().getY() - camera.y, part.pos().getZ() - camera.z);
            part.model().submit(poseStack, event.getSubmitNodeCollector(), part.light(), OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
    }
}
