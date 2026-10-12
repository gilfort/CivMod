package com.gilfort.civmod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.settlement.JobPointsDisplay;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Draws the work points of JobBlocks while a Scroll of Assignment is held: a coloured outline plus a small floating
 * icon above each point (like the unconnected X), and lines from the bound JobBlock to its points.
 * <p>
 * Colours: work = blue (primary marked "1"), collect = turquoise, drop-off = orange; points of other JobBlocks are
 * dimmed while the scroll is bound.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public final class JobPointsRenderer {

    private static final int WORK_COLOR = 0x3B7DDD;
    private static final int COLLECT_COLOR = 0x2EC4B6;
    private static final int DROP_OFF_COLOR = 0xFF8C1A;
    private static final float DEGREES_PER_TICK = 3.0F;

    private static final int BED_COLOR = 0xE8E8E8;

    private static List<JobPointsDisplay.JobPoints> points = List.of();
    private static Optional<BlockPos> bound = Optional.empty();
    private static List<BlockPos> beds = List.of();
    private static Optional<BlockPos> ownBed = Optional.empty();
    private static Optional<java.util.UUID> boundSettler = Optional.empty();
    private static Optional<Vec3> settlerPos = Optional.empty();
    private static List<Marker> frame = List.of();
    private static float rotation;

    private record Marker(BlockPos pos, BlockPos jobBlock, int color, boolean dimmed, ItemStackRenderState icon) {
    }

    private JobPointsRenderer() {
    }

    /**
     * Handles the points payload from the server.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handle(JobPointsDisplay.Sync payload, IPayloadContext context) {
        points = payload.points();
        bound = payload.bound();
        beds = payload.beds();
        ownBed = payload.ownBed();
        boundSettler = payload.settler();
    }

    @SubscribeEvent
    static void onExtract(ExtractLevelRenderStateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !(JobPointsDisplay.isScroll(player.getItemInHand(InteractionHand.MAIN_HAND))
                || JobPointsDisplay.isScroll(player.getItemInHand(InteractionHand.OFF_HAND)))) {
            frame = List.of();
            return;
        }
        rotation = (event.getLevel().getGameTime() + event.getDeltaTracker().getGameTimeDeltaPartialTick(false)) * DEGREES_PER_TICK;
        List<Marker> markers = new ArrayList<>();
        for (JobPointsDisplay.JobPoints jobPoints : points) {
            boolean dimmed = bound.isPresent() && !bound.get().equals(jobPoints.jobBlock());
            for (int i = 0; i < jobPoints.work().size(); i++) {
                markers.add(marker(minecraft, jobPoints.work().get(i), jobPoints.jobBlock(), WORK_COLOR, dimmed,
                        i == 0 ? ModItems.PRIMARY_WORK_MARKER.get().getDefaultInstance() : ModItems.WORK_MARKER.get().getDefaultInstance()));
            }
            jobPoints.collect().ifPresent(pos -> markers.add(marker(minecraft, pos, jobPoints.jobBlock(), COLLECT_COLOR, dimmed,
                    ModItems.COLLECT_MARKER.get().getDefaultInstance())));
            jobPoints.dropOff().ifPresent(pos -> markers.add(marker(minecraft, pos, jobPoints.jobBlock(), DROP_OFF_COLOR, dimmed,
                    ModItems.DROP_OFF_MARKER.get().getDefaultInstance())));
        }
        for (BlockPos bed : beds) {
            boolean own = ownBed.filter(bed::equals).isPresent();
            markers.add(marker(minecraft, bed, bed, BED_COLOR, !own, ModItems.BED_MARKER.get().getDefaultInstance()));
        }
        settlerPos = boundSettler.flatMap(uuid -> java.util.stream.StreamSupport.stream(event.getLevel().entitiesForRendering().spliterator(), false)
                .filter(entity -> entity.getUUID().equals(uuid)).findFirst()
                .map(entity -> entity.getPosition(event.getDeltaTracker().getGameTimeDeltaPartialTick(false)).add(0, 1.0, 0)));
        frame = markers;
    }

    private static Marker marker(Minecraft minecraft, BlockPos pos, BlockPos jobBlock, int color, boolean dimmed, ItemStack icon) {
        ItemStackRenderState state = new ItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(state, icon, ItemDisplayContext.FIXED, minecraft.level, null, 0);
        return new Marker(pos, jobBlock, color, dimmed, state);
    }

    @SubscribeEvent
    static void onSubmit(SubmitCustomGeometryEvent event) {
        if (frame.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        for (Marker marker : frame) {
            int alpha = marker.dimmed() ? 0x60 : 0xFF;
            int argb = alpha << 24 | marker.color();
            poseStack.pushPose();
            poseStack.translate(marker.pos().getX() - camera.x, marker.pos().getY() - camera.y, marker.pos().getZ() - camera.z);
            collector.submitShapeOutline(poseStack, Shapes.block(), marker.dimmed() ? RenderTypes.linesTranslucent() : RenderTypes.lines(),
                    argb, 2.0F, false);
            if (!marker.dimmed()) {
                poseStack.pushPose();
                poseStack.translate(0.5F, 1.5F, 0.5F);
                poseStack.rotateDegrees(Axis.YP, rotation);
                poseStack.scale(0.25F, 0.25F, 0.25F);
                marker.icon().submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                poseStack.popPose();
            }
            poseStack.popPose();
            if (bound.filter(marker.jobBlock()::equals).isPresent()) {
                submitLine(poseStack, collector, camera, Vec3.atCenterOf(marker.jobBlock()), Vec3.atCenterOf(marker.pos()), argb);
            }
            if (ownBed.filter(marker.pos()::equals).isPresent() && settlerPos.isPresent()) {
                submitLine(poseStack, collector, camera, settlerPos.get(), Vec3.atCenterOf(marker.pos()), argb);
            }
        }
    }

    private static void submitLine(PoseStack poseStack, SubmitNodeCollector collector, Vec3 camera, Vec3 from, Vec3 to, int argb) {
        float x1 = (float) (from.x - camera.x);
        float y1 = (float) (from.y - camera.y);
        float z1 = (float) (from.z - camera.z);
        float x2 = (float) (to.x - camera.x);
        float y2 = (float) (to.y - camera.y);
        float z2 = (float) (to.z - camera.z);
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-4F) {
            return;
        }
        float nx = dx / length;
        float ny = dy / length;
        float nz = dz / length;
        collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
            buffer.addVertex(pose, x1, y1, z1).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(1.5F);
            buffer.addVertex(pose, x2, y2, z2).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(1.5F);
        });
    }
}
