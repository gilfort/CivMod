package com.gilfort.civmod.npc;

import com.gilfort.civmod.CivMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.Item;

/**
 * A thought bubble above a settler (vault {@code Systems/Thought Bubbles.md}): an activity it starts (shown once) or a
 * problem (red "!", repeated client-side while it lasts).
 */
public enum ThoughtBubble {

    /** Switched to a Job or was automatically assigned to one: the Job's bubble icon. */
    JOB(false),
    /** Walking to the drop-off: the drop-off marker. */
    DROP_OFF(false),
    /** Fetching a tool, inputs or starting a Deliveryman tour: the collect marker. */
    COLLECT(false),
    /** Crafting: the crafting table. */
    CRAFTING(false),
    /** Going to eat or joining a Clerk queue: bread. */
    EATING(false),
    /** Going to bed: a bed. */
    BED(false),
    /** Leaving on a Mission: a map. */
    MISSION(false),
    /** Problem: no usable tool (the needed tool). */
    NO_TOOL(true),
    /** Problem: the drop-off or the Deliveryman's store is full (the drop-off marker). */
    DROP_OFF_FULL(true),
    /** Problem: no work place (the JobBlock). */
    NO_WORK_PLACE(true),
    /** Problem: inputs missing for every request tried (the crafting table). */
    MISSING_INPUTS(true),
    /** Problem: hungry without food (bread). */
    HUNGRY_NO_FOOD(true),
    /** Problem: the Job's profile reports a blocking reason (its icon, else the generic "blocked" marker, a barrier). */
    BLOCKED(true),
    /** Problem: a need of its tier is unmet, on a grace day (the need's icon). */
    NEED_UNMET(true);

    /** Network codec. */
    public static final StreamCodec<ByteBuf, ThoughtBubble> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], ThoughtBubble::ordinal);

    private final boolean problem;

    ThoughtBubble(boolean problem) {
        this.problem = problem;
    }

    /**
     * @return whether it marks a problem (red "!", repeated while it lasts)
     */
    public boolean problem() {
        return problem;
    }

    /**
     * Server → client, to the players tracking the settler: show a bubble, or turn a problem on / off.
     *
     * @param settlerId the settler's entity id
     * @param bubble    the bubble
     * @param icon      the item shown in the bubble
     * @param on        false only to end a problem
     */
    public record Show(int settlerId, ThoughtBubble bubble, Item icon, boolean on) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Show> TYPE = new Type<>(CivMod.id("thought_bubble"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Show> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Show::settlerId,
                ThoughtBubble.STREAM_CODEC, Show::bubble,
                ByteBufCodecs.registry(Registries.ITEM), Show::icon,
                ByteBufCodecs.BOOL, Show::on,
                Show::new);

        @Override
        public Type<Show> type() {
            return TYPE;
        }
    }
}
