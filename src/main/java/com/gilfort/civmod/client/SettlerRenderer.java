package com.gilfort.civmod.client;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSkins;
import com.gilfort.civmod.npc.SleepingPose;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Renders settlers with the player model and their vanilla default skin (wide or slim arms).
 * <p>
 * The name tag is shown within {@value #NAME_DISTANCE} blocks and only with line of sight. Sleeping settlers lie on their
 * left side, right side or back ({@link SleepingPose}).
 */
public class SettlerRenderer extends HumanoidMobRenderer<Settler, AvatarRenderState, PlayerModel> {

    private static final double NAME_DISTANCE = 16.0;
    /** Half the body depth (4 px): how far the back reaches below the body axis when lying face up. */
    private static final float HALF_DEPTH = 2.0F / 16.0F;
    /** How far the lower arm reaches out from the body axis (body 4 px + arm 4 px, slim arm 3 px). */
    private static final float ARM_REACH_WIDE = 8.0F / 16.0F;
    private static final float ARM_REACH_SLIM = 7.0F / 16.0F;
    private static final PlayerSkin[] SKINS = new PlayerSkin[SettlerSkins.COUNT];

    static {
        for (int i = 0; i < SKINS.length; i++) {
            SKINS[i] = new PlayerSkin(new ClientAsset.ResourceTexture(SettlerSkins.asset(i)), null, null,
                    SettlerSkins.isSlim(i) ? PlayerModelType.SLIM : PlayerModelType.WIDE, true);
        }
    }

    private final PlayerModel wideModel;
    private final PlayerModel slimModel;

    /**
     * @param context the renderer context
     */
    public SettlerRenderer(EntityRendererProvider.Context context) {
        this(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
                new PlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true));
    }

    private SettlerRenderer(EntityRendererProvider.Context context, PlayerModel wideModel, PlayerModel slimModel) {
        super(context, wideModel, 0.5F);
        this.wideModel = wideModel;
        this.slimModel = slimModel;
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new SettlerRenderState();
    }

    @Override
    public void extractRenderState(Settler settler, AvatarRenderState state, float partialTicks) {
        super.extractRenderState(settler, state, partialTicks);
        state.skin = SKINS[Math.floorMod(settler.getSkin(), SKINS.length)];
        state.showCape = false;
        ((SettlerRenderState) state).sleepingPose = settler.hasPose(Pose.SLEEPING)
                ? SleepingPose.of(settler.getUUID(), settler.level().getOverworldClockTime())
                : SleepingPose.BACK;
    }

    @Override
    protected void setupRotations(AvatarRenderState state, PoseStack poseStack, float bodyRot, float entityScale) {
        SleepingPose pose = ((SettlerRenderState) state).sleepingPose;
        if (!state.hasPose(Pose.SLEEPING) || pose == SleepingPose.BACK) {
            super.setupRotations(state, poseStack, bodyRot, entityScale);
            return;
        }
        // On its side the lower arm rests on the bed instead of the back: lift the body axis by the difference so the
        // body neither sinks into the (straw) bed nor floats above it.
        float armReach = state.skin.model() == PlayerModelType.SLIM ? ARM_REACH_SLIM : ARM_REACH_WIDE;
        poseStack.translate(0.0F, armReach - HALF_DEPTH, 0.0F);
        super.setupRotations(state, poseStack, bodyRot, entityScale);
        // After the vanilla lying rotation the model's Y axis runs along the body (feet to head): roll around it.
        poseStack.rotateDegrees(Axis.YP, pose == SleepingPose.LEFT_SIDE ? 90.0F : -90.0F);
    }

    @Override
    public void submit(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        // One renderer serves both arm widths; pick the model matching the skin.
        model = state.skin.model() == PlayerModelType.SLIM ? slimModel : wideModel;
        super.submit(state, poseStack, collector, camera);
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }

    @Override
    protected HumanoidModel.ArmPose getArmPose(Settler settler, HumanoidArm arm) {
        return settler.getItemHeldByArm(arm).isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
    }

    @Override
    protected boolean shouldShowName(Settler settler, double distanceToCameraSq) {
        Minecraft minecraft = Minecraft.getInstance();
        return settler.hasCustomName() && !settler.isInvisible() && distanceToCameraSq <= NAME_DISTANCE * NAME_DISTANCE
                && minecraft.player != null && minecraft.player.hasLineOfSight(settler);
    }

    /** The player render state plus the sleeping pose of tonight. */
    private static final class SettlerRenderState extends AvatarRenderState {
        private SleepingPose sleepingPose = SleepingPose.BACK;
    }
}
