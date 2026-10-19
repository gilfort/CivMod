package com.gilfort.civmod.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModClientConfig;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Draws thought bubbles above settlers (vault {@code Systems/Thought Bubbles.md}): a pixel bubble above the name tag
 * with an item icon, a red "!" in the corner for problems. Each bubble shows for {@value #SHOW_TICKS} ticks and shrinks
 * away at the end; bubbles that arrive meanwhile wait their turn. Problems repeat every {@value #REPEAT_TICKS} ticks
 * while they are on. Only within {@value #DISTANCE} blocks, never while the settler sleeps or is away (Mission), and as
 * the client option "Show thought bubbles" allows.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID, value = Dist.CLIENT)
public final class ThoughtBubbleRenderer {

    /** How long a bubble shows (about 3 s). */
    private static final int SHOW_TICKS = 60;
    /** The last ticks of a bubble, in which it shrinks away. */
    private static final int FADE_TICKS = 10;
    /** How often a problem shows again while it is on (30 s). */
    private static final int REPEAT_TICKS = 600;
    /** Bubbles further away are not drawn. */
    private static final double DISTANCE = 16.0;
    /** Bubbles waiting per settler at most (older ones are dropped). */
    private static final int MAX_QUEUED = 3;
    /** Height of the bubble centre above the top of the settler (above the name tag). */
    private static final double ABOVE_HEAD = 1.0;
    private static final float BUBBLE_SCALE = 0.6F;
    private static final float ICON_SCALE = 0.3F;
    private static final float MARKER_SCALE = 0.25F;

    private static final Map<Integer, Thoughts> THOUGHTS = new HashMap<>();
    private static @Nullable ClientLevel lastLevel;
    private static List<Frame> frame = List.of();

    /** One bubble to show; its render states are made the first time it is drawn and kept while it shows. */
    private static final class Bubble {
        private final ThoughtBubble type;
        private final Item icon;
        private @Nullable Drawn drawn;

        Bubble(ThoughtBubble type, Item icon) {
            this.type = type;
            this.icon = icon;
        }

        Drawn drawn(Minecraft minecraft) {
            if (drawn == null) {
                drawn = new Drawn(state(minecraft, new ItemStack(ModItems.THOUGHT_BUBBLE.get())), state(minecraft, new ItemStack(icon)),
                        type.problem() ? state(minecraft, new ItemStack(ModItems.PROBLEM_MARKER.get())) : null);
            }
            return drawn;
        }
    }

    /** The render states of a bubble: the bubble itself, its icon and the problem marker. */
    private record Drawn(ItemStackRenderState bubble, ItemStackRenderState icon, @Nullable ItemStackRenderState marker) {
    }

    /** A problem that is on: its icon and when it shows again. */
    private static final class Problem {
        private final Item icon;
        /** Shown at once, then repeated from the tick loop. */
        private long nextRepeat = Long.MIN_VALUE;

        Problem(Item icon) {
            this.icon = icon;
        }
    }

    /** The bubbles of one settler. */
    private static final class Thoughts {
        private final Deque<Bubble> queue = new ArrayDeque<>();
        private @Nullable Bubble current;
        private long shownAt;
        private final Map<ThoughtBubble, Problem> problems = new EnumMap<>(ThoughtBubble.class);

        void add(Bubble bubble) {
            if (queue.size() == MAX_QUEUED) {
                queue.removeFirst();
            }
            queue.addLast(bubble);
        }

        boolean idle() {
            return current == null && queue.isEmpty() && problems.isEmpty();
        }
    }

    /** A bubble drawn this frame. */
    private record Frame(Vec3 pos, float scale, Drawn drawn) {
    }

    private ThoughtBubbleRenderer() {
    }

    /**
     * Handles a thought bubble payload from the server.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handle(ThoughtBubble.Show payload, IPayloadContext context) {
        Thoughts thoughts = THOUGHTS.computeIfAbsent(payload.settlerId(), id -> new Thoughts());
        if (!payload.bubble().problem()) {
            thoughts.add(new Bubble(payload.bubble(), payload.icon()));
            return;
        }
        if (!payload.on()) {
            thoughts.problems.remove(payload.bubble());
            thoughts.queue.removeIf(waiting -> waiting.type == payload.bubble());
            return;
        }
        thoughts.problems.put(payload.bubble(), new Problem(payload.icon()));
    }

    private static boolean allowed(ThoughtBubble type) {
        return switch (CivModClientConfig.SHOW_THOUGHT_BUBBLES.get()) {
            case ALL -> true;
            case PROBLEMS_ONLY -> type.problem();
            case OFF -> false;
        };
    }

    @SubscribeEvent
    static void onExtract(ExtractLevelRenderStateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = event.getLevel();
        if (level != lastLevel) {
            // Entity ids belong to one level.
            THOUGHTS.clear();
            lastLevel = level;
        }
        if (THOUGHTS.isEmpty() || minecraft.player == null) {
            frame = List.of();
            return;
        }
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long now = level.getGameTime();
        Vec3 viewer = minecraft.player.position();
        List<Frame> frames = new ArrayList<>();
        Iterator<Map.Entry<Integer, Thoughts>> entries = THOUGHTS.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, Thoughts> entry = entries.next();
            Thoughts thoughts = entry.getValue();
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof Settler settler) || settler.isRemoved()) {
                entries.remove();
                continue;
            }
            advance(thoughts, now);
            if (thoughts.idle()) {
                entries.remove();
                continue;
            }
            Bubble current = thoughts.current;
            // Out of range: no position, no render state.
            if (current == null || settler.isSleeping() || settler.isInvisible()
                    || settler.position().distanceToSqr(viewer) > DISTANCE * DISTANCE) {
                continue;
            }
            Vec3 pos = settler.getPosition(partialTick).add(0.0, settler.getBbHeight() + ABOVE_HEAD, 0.0);
            float age = now - thoughts.shownAt + partialTick;
            float scale = Math.clamp((SHOW_TICKS - age) / FADE_TICKS, 0.0F, 1.0F);
            frames.add(new Frame(pos, scale, current.drawn(minecraft)));
        }
        frame = frames;
    }

    /** Ends a bubble whose time is up, queues due problem repeats and shows the next allowed bubble. */
    private static void advance(Thoughts thoughts, long now) {
        if (thoughts.current != null && now - thoughts.shownAt >= SHOW_TICKS) {
            thoughts.current = null;
        }
        thoughts.problems.forEach((type, problem) -> {
            if (problem.nextRepeat <= now) {
                problem.nextRepeat = now + REPEAT_TICKS;
                thoughts.add(new Bubble(type, problem.icon));
            }
        });
        while (thoughts.current == null && !thoughts.queue.isEmpty()) {
            Bubble next = thoughts.queue.removeFirst();
            if (allowed(next.type)) {
                thoughts.current = next;
                thoughts.shownAt = now;
            }
        }
    }

    private static ItemStackRenderState state(Minecraft minecraft, ItemStack stack) {
        ItemStackRenderState state = new ItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.FIXED, minecraft.level, null, 0);
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
        for (Frame bubble : frame) {
            if (bubble.scale() <= 0.0F) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(bubble.pos().x - camera.x, bubble.pos().y - camera.y, bubble.pos().z - camera.z);
            // Faces the camera like a name tag.
            poseStack.rotate(event.getLevelRenderState().cameraRenderState.orientation);
            poseStack.scale(bubble.scale(), bubble.scale(), bubble.scale());
            Drawn drawn = bubble.drawn();
            submit(poseStack, collector, drawn.bubble(), 0.0F, 0.0F, 0.0F, BUBBLE_SCALE);
            // The icon sits in the round part of the bubble, slightly in front of it.
            submit(poseStack, collector, drawn.icon(), 0.0F, 0.04F, 0.02F, ICON_SCALE);
            if (drawn.marker() != null) {
                submit(poseStack, collector, drawn.marker(), 0.2F, 0.2F, 0.04F, MARKER_SCALE);
            }
            poseStack.popPose();
        }
    }

    private static void submit(PoseStack poseStack, SubmitNodeCollector collector, ItemStackRenderState state, float x, float y, float z,
            float scale) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.scale(scale, scale, scale);
        state.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}
