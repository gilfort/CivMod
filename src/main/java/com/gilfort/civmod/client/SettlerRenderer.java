package com.gilfort.civmod.client;

import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSkins;
import com.mojang.blaze3d.vertex.PoseStack;

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
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Renders settlers with the player model and their vanilla default skin (wide or slim arms).
 * <p>
 * The name tag is shown within {@value #NAME_DISTANCE} blocks and only with line of sight.
 */
public class SettlerRenderer extends HumanoidMobRenderer<Settler, AvatarRenderState, PlayerModel> {

    private static final double NAME_DISTANCE = 16.0;
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
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(Settler settler, AvatarRenderState state, float partialTicks) {
        super.extractRenderState(settler, state, partialTicks);
        state.skin = SKINS[Math.floorMod(settler.getSkin(), SKINS.length)];
        state.showCape = false;
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
        return settler.hasCustomName() && distanceToCameraSq <= NAME_DISTANCE * NAME_DISTANCE
                && minecraft.player != null && minecraft.player.hasLineOfSight(settler);
    }
}
