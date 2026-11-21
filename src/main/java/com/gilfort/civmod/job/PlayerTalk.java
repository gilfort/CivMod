package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.chronicle.Chronicle;
import com.gilfort.civmod.chronicle.Diary;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.origin.OriginData;
import com.gilfort.civmod.origin.OriginLine;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * A player talks to a settler (vault {@code Systems/Settler Origin.md}, {@code Systems/Entertainment.md}): Shift + right-click with an
 * empty hand. The settler turns its head, greets, shows the conversation bubble and says one line in chat to the player who talked.
 * The first talks tell the three parts of its origin in random order, each once, and write each into the diary; after that it makes
 * small talk about the weather or, from its own needs, about the settlement. The first talk of a day gives a little Entertainment.
 * <p>
 * The told lines belong to the settler, not to a player: every player hears the lines that are left. The class lives in this
 * package because the greeting and the needs are package-private; the settler does not stop what it is doing.
 */
public final class PlayerTalk {

    /**
     * What a talk said.
     *
     * @param line the words, without the settler's name
     * @param told the part of the origin the line told, empty for small talk
     */
    public record Result(Component line, Optional<OriginLine.Slot> told) {
    }

    /** Entertainment points of the first talk with a player per settler and day. */
    static final double TALK_POINTS = 0.5;

    private static final int SMALL_TALK_VARIANTS = 3;

    private static @Nullable RandomSource scripted;

    private PlayerTalk() {
    }

    /**
     * Replaces the random source of the talks (tests): the order of the origin lines and the choice of the small talk.
     *
     * @param random the scripted source, or {@code null} for the settler's own
     */
    public static void script(@Nullable RandomSource random) {
        scripted = random;
    }

    /**
     * Lets a player talk to a settler. A settler that is asleep does not talk; the caller then treats the click like a plain
     * right-click.
     *
     * @param settler the settler
     * @param player  the player who talks to it
     * @return what the settler said, empty if it did not talk
     */
    public static Optional<Result> talk(Settler settler, ServerPlayer player) {
        if (!(settler.level() instanceof ServerLevel level) || settler.isSleeping() || settler.getWork().activity() == Schedule.Activity.SLEEP) {
            return Optional.empty();
        }
        Result result = choose(level, settler);
        SettlerWork work = settler.getWork();
        work.social().greet(player, level.getGameTime());
        work.conversationBubble();
        work.setEntertainment(work.entertainment().talkedToPlayer(TALK_POINTS, DayClock.day(level)));
        player.sendSystemMessage(Component.translatable("civmod.talk.line", settler.getName(), result.line()));
        return Optional.of(result);
    }

    /**
     * Decides what the settler says and writes what it tells into the diary. This is the one place that picks the words, so a
     * settler in another state (a visitor that opens with its price and trade) puts its own words in front here.
     */
    private static Result choose(ServerLevel level, Settler settler) {
        RandomSource random = scripted != null ? scripted : settler.getRandom();
        return tellOrigin(settler, random).orElseGet(() -> new Result(smallTalk(level, settler, random), Optional.empty()));
    }

    /** The next part of the origin the settler has not told yet, picked at random; written into the diary. */
    private static Optional<Result> tellOrigin(Settler settler, RandomSource random) {
        Optional<SettlerOrigin> origin = settler.getPast().origin();
        if (origin.isEmpty()) {
            return Optional.empty();
        }
        Chronicle chronicle = settler.getPast().chronicle();
        List<OriginLine.Slot> untold = OriginLine.tellable(origin.get()).stream().filter(slot -> !chronicle.hasTold(slot)).toList();
        if (untold.isEmpty()) {
            return Optional.empty();
        }
        OriginLine.Slot slot = untold.get(random.nextInt(untold.size()));
        List<String> params = OriginLine.params(slot, origin.get(), OriginData.race(settler.getRace()));
        settler.getPast().setChronicle(chronicle.withTold(slot));
        Diary.add(settler, ModDiaryEntryTypes.ORIGIN_LINE.get(), params);
        return Optional.of(new Result(OriginLine.text(params), Optional.of(slot)));
    }

    /** The weather, or the settlement told from the settler's own needs; a homeless settler has no settlement to talk about. */
    private static Component smallTalk(ServerLevel level, Settler settler, RandomSource random) {
        if (settler.isHomeless() || random.nextInt(2) == 0) {
            String mood = level.isRaining() || level.isThundering() ? "bad" : "good";
            return variant("weather." + mood, random);
        }
        List<String> unmet = unmetNeeds(level, settler);
        return unmet.isEmpty() ? variant("settlement.fine", random) : variant("settlement." + unmet.get(random.nextInt(unmet.size())), random);
    }

    /** The basic needs the settler complains about now: its bed, a fire near it and food, as the keys of the small talk. */
    private static List<String> unmetNeeds(ServerLevel level, Settler settler) {
        List<String> unmet = new ArrayList<>();
        for (NeedsReport.Row row : settler.getWork().needsReport(level).rows()) {
            if (row.met()) {
                continue;
            }
            if (row.name().equals(Needs.BED.name())) {
                unmet.add("bed");
            } else if (row.name().equals(Needs.FIRE.name())) {
                unmet.add("fire");
            } else if (row.name().equals(Needs.FOOD.name())) {
                unmet.add("food");
            }
        }
        return unmet;
    }

    private static Component variant(String topic, RandomSource random) {
        return Component.translatable("civmod.talk.small." + topic + "." + random.nextInt(SMALL_TALK_VARIANTS));
    }
}
