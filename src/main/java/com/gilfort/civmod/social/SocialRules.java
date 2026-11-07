package com.gilfort.civmod.social;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * The rules of the meeting check (vault {@code Systems/Entertainment.md}, Conversations and greetings) as plain functions
 * over plain values, with their numbers from {@code data/<namespace>/civmod_social/rules.json}; files of all packs are
 * merged, later values override, missing values keep the {@link #FALLBACK}. Nothing here touches the world.
 *
 * @param conversationChance the share of a roll that is a conversation (0.20)
 * @param greetingChance     the share of a roll that is a greeting (0.30), counted after the conversation share
 * @param radius             blocks around a settler in which it notices others
 * @param greetingCooldown   ticks before the same pair greets again (1200: 60 seconds)
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record SocialRules(double conversationChance, double greetingChance, int radius, int greetingCooldown) {

    /** What one roll of the meeting check decides. */
    public enum Outcome {
        /** The settler stops and talks. */
        CONVERSATION,
        /** The settler turns its head and swings an arm. */
        GREETING,
        /** Nothing happens. */
        NONE
    }

    /**
     * What a settler is doing, as far as it decides whether the meeting check may run for it. A seated Tavern guest, a
     * settler at work, walking or in a work break has none of these; a new state that must not be interrupted becomes one
     * more component and one more line in {@link #interruptible(Situation)}.
     *
     * @param sleeping  asleep or lying in bed
     * @param away      invisible: away on a Mission, or foraging
     * @param onMission has left for a Mission (walking out counts)
     * @param leading   leads Villagers to the Mission Board
     * @param serving   a Tavern Host or Clerk with a customer at the counter
     * @param eating    eats at the food point
     */
    public record Situation(boolean sleeping, boolean away, boolean onMission, boolean leading, boolean serving, boolean eating) {

        /** A settler with none of the blocking states. */
        public static final Situation FREE = new Situation(false, false, false, false, false, false);
    }

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("social_rules");
    /** Values used when no file is loaded. */
    public static final SocialRules FALLBACK = new SocialRules(0.2, 0.3, 6, 1200);

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_social");
    private static SocialRules current = FALLBACK;

    private record Part(Optional<Double> conversationChance, Optional<Double> greetingChance, Optional<Integer> radius,
            Optional<Integer> greetingCooldown) {

        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("conversation_chance").forGetter(Part::conversationChance),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("greeting_chance").forGetter(Part::greetingChance),
                Codec.intRange(1, 64).optionalFieldOf("radius").forGetter(Part::radius),
                Codec.intRange(0, 1_000_000).optionalFieldOf("greeting_cooldown_ticks").forGetter(Part::greetingCooldown)
        ).apply(instance, Part::new));
    }

    /**
     * @return the loaded rules
     */
    public static SocialRules get() {
        return current;
    }

    /**
     * The roll of one meeting: the conversation share comes first, the greeting share after it, the rest is nothing. If
     * the two shares add up to more than 1, the greeting share is cut off at 1.
     *
     * @param roll a random number from 0 (included) to 1 (excluded)
     * @return what the meeting turns into
     */
    public Outcome outcome(double roll) {
        if (roll < conversationChance) {
            return Outcome.CONVERSATION;
        }
        return roll < conversationChance + greetingChance ? Outcome.GREETING : Outcome.NONE;
    }

    /**
     * @param elapsed ticks since the pair last greeted
     * @return whether the pair may greet again
     */
    public boolean greetingReady(long elapsed) {
        return elapsed >= greetingCooldown;
    }

    /**
     * Whether the meeting check may run for a settler: it is awake and visible and neither away on a Mission, nor leading
     * Villagers, nor serving a customer, nor eating. (The vault also names a Bard who is playing; that state comes with the
     * Service loop.)
     *
     * @param situation what the settler is doing
     * @return whether it can be interrupted
     */
    public static boolean interruptible(Situation situation) {
        return !(situation.sleeping() || situation.away() || situation.onMission() || situation.leading() || situation.serving()
                || situation.eating());
    }

    /**
     * @param distanceSqr squared distance between the two
     * @return whether the other is within the radius (the edge counts)
     */
    public boolean inRange(double distanceSqr) {
        return distanceSqr <= (double) radius * radius;
    }

    /**
     * Merges JSON files in order (later values override).
     *
     * @param files parsed JSON files
     * @return the merged rules
     */
    public static SocialRules merge(List<JsonElement> files) {
        SocialRules merged = FALLBACK;
        for (JsonElement file : files) {
            Part part = Part.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            merged = new SocialRules(part.conversationChance().orElse(merged.conversationChance()),
                    part.greetingChance().orElse(merged.greetingChance()), part.radius().orElse(merged.radius()),
                    part.greetingCooldown().orElse(merged.greetingCooldown()));
        }
        return merged;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<SocialRules> {

        @Override
        protected SocialRules prepare(ResourceManager manager, ProfilerFiller profiler) {
            List<JsonElement> files = new ArrayList<>();
            FILES.listMatchingResourceStacks(manager).entrySet().stream()
                    .filter(entry -> FILES.fileToId(entry.getKey()).getPath().equals("rules"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        for (Resource resource : entry.getValue()) {
                            try (Reader reader = resource.openAsReader()) {
                                files.add(JsonParser.parseReader(reader));
                            } catch (Exception e) {
                                CivMod.LOGGER.error("Failed to read social rules {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                            }
                        }
                    });
            try {
                return merge(files);
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid social rules, using fallback values", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(SocialRules prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
        }
    }
}
