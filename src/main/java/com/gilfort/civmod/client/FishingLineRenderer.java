package com.gilfort.civmod.client;

import java.util.Optional;

import com.gilfort.civmod.npc.FishingCast;
import com.gilfort.civmod.npc.Settler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the Fisherman's cast (vault {@code Jobs/Fisherman.md}): vanilla's bobber and fishing line, from the settler's
 * hand to a bobber on the water. Vanilla's hook renderer expects a player as owner, so the line is drawn here from the
 * settler's render state, the same way ({@code FishingHookRenderer}). The bobber follows the {@link FishingCast} timeline.
 */
final class FishingLineRenderer {

    private static final RenderType BOBBER_TYPE = RenderTypes.entityCutoutCull(Identifier.withDefaultNamespace("textures/entity/fishing/fishing_hook.png"));
    /** Height of a water source block's surface above its bottom (8/9, vanilla's source height). */
    private static final double WATER_SURFACE = 8.0 / 9.0;
    /** The line leaves the hand this far to the side and in front of the eyes, and below them (vanilla's third person values). */
    private static final double HAND_SIDE = 0.35;
    private static final double HAND_FORWARD = 0.8;
    private static final double HAND_BELOW_EYES = 0.45;
    /** The line sags between hand and bobber; it is drawn in this many segments. */
    private static final int LINE_SEGMENTS = 16;
    private static final float BOBBER_SIZE = 0.5F;
    /** The line starts this far above the bobber's centre, at its top. */
    private static final float BOBBER_TOP = 0.25F;
    /** Half the bobber quad: its vertices are centred on the origin. */
    private static final float BOBBER_HALF = 0.5F;
    /** The line's colour, opaque black (ARGB). */
    private static final int LINE_COLOR = 0xFF000000;
    /** The bobber texture is drawn untinted (opaque white, ARGB). */
    private static final int BOBBER_TINT = 0xFFFFFFFF;
    /** The sag of the line follows (t squared + t) / 2, vanilla's curve. */
    private static final float SAG_HALF = 0.5F;

    /**
     * What one frame draws, relative to the settler's feet.
     *
     * @param hand   where the line leaves the settler
     * @param bobber where the bobber is
     */
    record Line(Vec3 hand, Vec3 bobber) {
    }

    private FishingLineRenderer() {
    }

    /**
     * @param settler     a settler
     * @param partialTick the partial tick of the frame
     * @return the line to draw, empty while the settler has none out or the bobber is hidden
     */
    static Optional<Line> extract(Settler settler, float partialTick) {
        Optional<BlockPos> target = settler.castTarget();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        Vec3 feet = settler.getPosition(partialTick);
        Vec3 hand = hand(settler, partialTick);
        Vec3 water = Vec3.atBottomCenterOf(target.get()).add(0.0, WATER_SURFACE, 0.0).subtract(feet);
        FishingCast.Pose pose = settler.fishingCast().pose(settler.level().getGameTime() + partialTick);
        return FishingCast.bobber(pose, hand, water).map(bobber -> new Line(hand, bobber));
    }

    /** The hand as vanilla places it for a player in third person, relative to the settler's feet. */
    private static Vec3 hand(Settler settler, float partialTick) {
        float bodyYaw = Mth.lerp(partialTick, settler.yBodyRotO, settler.yBodyRot) * Mth.DEG_TO_RAD;
        double sin = Mth.sin(bodyYaw);
        double cos = Mth.cos(bodyYaw);
        float scale = settler.getScale();
        double side = (settler.getMainArm() == HumanoidArm.RIGHT ? 1 : -1) * HAND_SIDE * scale;
        double forward = HAND_FORWARD * scale;
        return new Vec3(-cos * side - sin * forward, settler.getEyeHeight() - HAND_BELOW_EYES * scale, -sin * side + cos * forward);
    }

    /**
     * @param line      the line to draw
     * @param poseStack the pose stack at the settler's feet
     * @param collector the collector to submit the geometry to
     * @param light     the light of the settler
     */
    static void submit(Line line, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        Vec3 bobber = line.bobber();
        poseStack.pushPose();
        poseStack.translate(bobber.x, bobber.y, bobber.z);
        poseStack.pushPose();
        poseStack.scale(BOBBER_SIZE, BOBBER_SIZE, BOBBER_SIZE);
        Camera camera = Minecraft.getInstance().getEntityRenderDispatcher().camera;
        if (camera != null) {
            poseStack.rotate(camera.rotation());
        }
        collector.submitCustomGeometry(poseStack, BOBBER_TYPE, (pose, buffer) -> {
            bobberVertex(buffer, pose, light, 0.0F, 0, 0, 1);
            bobberVertex(buffer, pose, light, 1.0F, 0, 1, 1);
            bobberVertex(buffer, pose, light, 1.0F, 1, 1, 0);
            bobberVertex(buffer, pose, light, 0.0F, 1, 0, 0);
        });
        poseStack.popPose();
        Vec3 toHand = line.hand().subtract(bobber).subtract(0.0, BOBBER_TOP, 0.0);
        float width = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
        collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
            for (int i = 0; i < LINE_SEGMENTS; i++) {
                float from = (float) i / LINE_SEGMENTS;
                float to = (float) (i + 1) / LINE_SEGMENTS;
                lineVertex(toHand, buffer, pose, from, to, width);
                lineVertex(toHand, buffer, pose, to, from, width);
            }
        });
        poseStack.popPose();
    }

    private static void bobberVertex(VertexConsumer buffer, PoseStack.Pose pose, int light, float x, int y, int u, int v) {
        buffer.addVertex(pose, x - BOBBER_HALF, y - BOBBER_HALF, 0.0F)
                .setColor(BOBBER_TINT)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /** One end of a line segment on the sagging curve from the bobber (at 0) to the hand (at 1), as vanilla draws it. */
    private static void lineVertex(Vec3 toHand, VertexConsumer buffer, PoseStack.Pose pose, float at, float next, float width) {
        float x = (float) toHand.x * at;
        float y = (float) toHand.y * (at * at + at) * SAG_HALF + BOBBER_TOP;
        float z = (float) toHand.z * at;
        float dx = (float) toHand.x * next - x;
        float dy = (float) toHand.y * (next * next + next) * SAG_HALF + BOBBER_TOP - y;
        float dz = (float) toHand.z * next - z;
        float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        buffer.addVertex(pose, x, y, z).setColor(LINE_COLOR).setNormal(pose, dx / length, dy / length, dz / length).setLineWidth(width);
    }
}
