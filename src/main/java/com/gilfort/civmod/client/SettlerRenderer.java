package com.gilfort.civmod.client;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSkins;
import com.gilfort.civmod.npc.SleepingPose;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/**
 * Renders settlers with the player model and their vanilla default skin (wide or slim arms).
 * <p>
 * The name tag is shown within {@value #NAME_DISTANCE} blocks and only with line of sight. Sleeping settlers lie on their
 * left side, right side or back ({@link SleepingPose}).
 * <p>
 * The render state is an {@link AvatarRenderState}, so vanilla submits it with its own player renderer (picked by the
 * skin's arm width): only extracting the state runs here. The side pose is therefore applied around that renderer, in
 * {@link #onRenderPre} / {@link #onRenderPost}, and taken back out for the name tag.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public class SettlerRenderer extends HumanoidMobRenderer<Settler, AvatarRenderState, PlayerModel> {

    private static final double NAME_DISTANCE = 16.0;
    /** Half the body depth (4 px): how far the back reaches below the body axis when lying face up. */
    private static final float HALF_DEPTH = 2.0F / 16.0F;
    /** How far the lower arm reaches out from the body axis (body 4 px + arm 4 px, slim arm 3 px). */
    private static final float ARM_REACH_WIDE = 8.0F / 16.0F;
    private static final float ARM_REACH_SLIM = 7.0F / 16.0F;
    /** How deep the lower arm sinks into the mattress on a side: resting on top of it looks stiff. */
    private static final float ARM_SINK = 5.0F / 16.0F;
    private static final PlayerSkin[] SKINS = new PlayerSkin[SettlerSkins.COUNT];

    static {
        for (int i = 0; i < SKINS.length; i++) {
            SKINS[i] = new PlayerSkin(new ClientAsset.ResourceTexture(SettlerSkins.asset(i)), null, null,
                    SettlerSkins.isSlim(i) ? PlayerModelType.SLIM : PlayerModelType.WIDE, true);
        }
    }

    /**
     * @param context the renderer context
     */
    public SettlerRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
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
        SettlerRenderState settlerState = (SettlerRenderState) state;
        settlerState.roll = null;
        Direction bed = state.bedOrientation;
        if (!settler.hasPose(Pose.SLEEPING) || bed == null) {
            return;
        }
        SleepingPose pose = SleepingPose.of(settler.getUUID(), settler.level().getOverworldClockTime());
        if (pose == SleepingPose.BACK) {
            return;
        }
        // Roll around the body axis, which runs along the bed. On its side the lower arm rests on the bed instead of
        // the back: lift the body axis by the difference, minus the part of the arm that sinks into the mattress.
        float angle = (float) Math.toRadians(pose == SleepingPose.LEFT_SIDE ? 90.0 : -90.0);
        settlerState.roll = new Quaternionf().rotationAxis(angle, bed.getStepX(), 0.0F, bed.getStepZ());
        settlerState.lift = (state.skin.model() == PlayerModelType.SLIM ? ARM_REACH_SLIM : ARM_REACH_WIDE) - HALF_DEPTH - ARM_SINK;
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

    /** Last listener, so the pose is only pushed when the render goes ahead (and {@link #onRenderPost} pops it). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRenderPre(RenderLivingEvent.Pre<?, ?, ?> event) {
        if (event.getRenderState() instanceof SettlerRenderState state && state.roll != null) {
            event.getPoseStack().pushPose();
            event.getPoseStack().translate(0.0F, state.lift, 0.0F);
            event.getPoseStack().rotate(state.roll);
        }
    }

    @SubscribeEvent
    static void onRenderPost(RenderLivingEvent.Post<?, ?, ?> event) {
        if (event.getRenderState() instanceof SettlerRenderState state && state.roll != null) {
            event.getPoseStack().popPose();
        }
    }

    /** The name tag is drawn inside the rolled pose: take the roll back out so it stays upright above the settler. */
    @SubscribeEvent
    static void onNameTag(RenderNameTagEvent.DoRender event) {
        if (event.getEntityRenderState() instanceof SettlerRenderState state && state.roll != null) {
            event.getPoseStack().rotate(new Quaternionf(state.roll).conjugate());
            event.getPoseStack().translate(0.0F, -state.lift, 0.0F);
        }
    }

    /** The player render state plus tonight's side pose (none when awake or lying on the back). */
    private static final class SettlerRenderState extends AvatarRenderState {
        private @Nullable Quaternionf roll;
        private float lift;
    }
}
