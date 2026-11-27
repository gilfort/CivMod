package com.gilfort.civmod.npc;

import com.gilfort.civmod.CivMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * A bubble above a settler (vault {@code Systems/Thought Bubbles.md}): an activity it starts (shown once) or a problem
 * (red "!", repeated client-side while it lasts). Most are thought bubbles with dots; a speech bubble has a tail and is
 * drawn for what a settler says.
 */
public enum ThoughtBubble {

    /** Switched to a Job or was automatically assigned to one: the Job's bubble icon. */
    JOB(Kind.ACTIVITY),
    /** Walking to the drop-off: the drop-off marker. */
    DROP_OFF(Kind.ACTIVITY),
    /** Fetching a tool, inputs or starting a Deliveryman tour: the collect marker. */
    COLLECT(Kind.ACTIVITY),
    /** Crafting: the crafting table. */
    CRAFTING(Kind.ACTIVITY),
    /** Going to eat or joining a Clerk queue: bread. */
    EATING(Kind.ACTIVITY),
    /** Going to bed: a bed. */
    BED(Kind.ACTIVITY),
    /** Leaving on a Mission: a map. */
    MISSION(Kind.ACTIVITY),
    /** A guest in the Tavern misses music: a music disc, a hint that a jukebox with a disc improves the Tavern. */
    RECORD(Kind.ACTIVITY),
    /**
     * Two settlers or visitors talk, or a settler answers a player: a speech bubble with three dots in the sprite and no item
     * icon, above the talkers. An activity bubble without the red mark, hidden by "Problems only".
     */
    CONVERSATION(Kind.SPEECH),
    /** A Bard starts a stop or a stage act: a note block. */
    PLAYING(Kind.ACTIVITY),
    /** The first level-up in a Job its upbringing favours confirms the settler's talent: the Job's bubble icon. Hidden by "Problems only". */
    TALENT(Kind.ACTIVITY),
    /** The settler eats its favourite meal for the first time: the item it ate. Hidden by "Problems only". */
    FAVOURITE_MEAL(Kind.ACTIVITY),
    /**
     * The settler has a story aspect to tell today: a speech bubble with a book and quill, repeated like a problem while it lasts but
     * without the red mark. Hidden by "Problems only".
     */
    STORY(Kind.SPEECH_REPEATING),
    /** Problem: no usable tool (the needed tool). */
    NO_TOOL(Kind.PROBLEM),
    /** Problem: the Animal Farmer has no food for its animals (wheat). */
    NO_FOOD(Kind.PROBLEM),
    /** Problem: the drop-off or the Deliveryman's store is full (the drop-off marker). */
    DROP_OFF_FULL(Kind.PROBLEM),
    /** Problem: no work place (the JobBlock). */
    NO_WORK_PLACE(Kind.PROBLEM),
    /** Problem: inputs missing for every request tried (the crafting table). */
    MISSING_INPUTS(Kind.PROBLEM),
    /** Problem: hungry without food (bread). */
    HUNGRY_NO_FOOD(Kind.PROBLEM),
    /** Problem: the Job's profile reports a blocking reason (its icon, else the generic "blocked" marker, a barrier). */
    BLOCKED(Kind.PROBLEM),
    /** Problem: a need of its tier is unmet, on a grace day (the need's icon). */
    NEED_UNMET(Kind.PROBLEM);

    /** Network codec. */
    public static final StreamCodec<ByteBuf, ThoughtBubble> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], ThoughtBubble::ordinal);

    /**
     * Chance in percent that the need bubble shows when it comes up. It keeps all settlers from blinking at once before the
     * first night.
     */
    private static final int NEED_UNMET_SHOW_PERCENT = 40;

    /** The icon of a bubble that shows none (air): the dots of a speech bubble are drawn in its sprite. */
    public static final Item NO_ICON = Items.AIR;

    /** What a bubble looks like and how it behaves: whether it marks a problem, has a tail, and repeats while it lasts. */
    private enum Kind {
        /** A thought bubble for an activity, shown once. */
        ACTIVITY(false, false, false),
        /** A thought bubble with the red mark, repeated while the problem lasts. */
        PROBLEM(true, false, true),
        /** A speech bubble for an activity, shown once. */
        SPEECH(false, true, false),
        /** A speech bubble repeated while it lasts, without the red mark. */
        SPEECH_REPEATING(false, true, true);

        private final boolean problem;
        private final boolean speech;
        private final boolean repeats;

        Kind(boolean problem, boolean speech, boolean repeats) {
            this.problem = problem;
            this.speech = speech;
            this.repeats = repeats;
        }
    }

    private final boolean problem;
    private final boolean speech;
    private final boolean repeats;

    ThoughtBubble(Kind kind) {
        this.problem = kind.problem;
        this.speech = kind.speech;
        this.repeats = kind.repeats;
    }

    /**
     * The client rolls this each time a bubble would show: on its first appearance and on every repeat of a problem.
     *
     * @param random the client's random source
     * @return whether the bubble shows this time; only the need bubble is ever skipped
     */
    public boolean showsThisTime(RandomSource random) {
        return this != NEED_UNMET || random.nextInt(100) < NEED_UNMET_SHOW_PERCENT;
    }

    /**
     * @return whether it marks a problem (red "!", repeated while it lasts, kept by the option "Problems only")
     */
    public boolean problem() {
        return problem;
    }

    /**
     * @return whether the server turns it on and off and the client shows it again every 30 seconds while it is on (every problem, and
     *         the story bubble)
     */
    public boolean repeats() {
        return repeats;
    }

    /**
     * @return whether it is drawn as a speech bubble (with a tail) instead of a thought bubble
     */
    public boolean speech() {
        return speech;
    }

    /**
     * Server → client, to the players tracking the settler: show a bubble, or turn a problem on / off.
     *
     * @param settlerId the settler's entity id
     * @param bubble    the bubble
     * @param icon      the item shown in the bubble, {@link #NO_ICON} for none
     * @param on        false only to end a bubble that repeats ({@link #repeats()})
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
