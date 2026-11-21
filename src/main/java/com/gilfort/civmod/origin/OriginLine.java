package com.gilfort.civmod.origin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/**
 * The three parts of an origin a settler tells in a talk (vault {@code Systems/Settler Origin.md}): where it comes from, how it grew
 * up and what turned its life around. A told line is saved as a short list of text parameters, so the chat line and the diary entry
 * are built from the same words on whichever side reads them; the first parameter names the line.
 */
public final class OriginLine {

    /** The lines a settler can tell. */
    public enum Slot implements StringRepresentable {
        /** Where the settler comes from. */
        PLACE("place"),
        /** The family, the trade surname and the talent hint. */
        UPBRINGING("upbringing"),
        /** What went wrong. */
        TURNING_POINT("turning_point");

        /** Save codec. */
        public static final Codec<Slot> CODEC = StringRepresentable.fromEnum(Slot::values);

        private final String serializedName;

        Slot(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    /** The upbringing has no trade surname to tell about. */
    private static final String PLAIN = "plain";
    /** The trade surname fits a Job the upbringing favours. */
    private static final String SAME = "same";
    /** The trade surname comes from another trade than the Jobs the upbringing favours. */
    private static final String DIFFERENT = "different";

    private static final String JOB_SEPARATOR = ",";
    private static final int PLACE_PARAMS = 5;
    private static final int UPBRINGING_PARAMS = 6;

    private OriginLine() {
    }

    /**
     * @param origin a settler's origin
     * @return the lines it has to tell: the place, and the upbringing and turning point when the origin has them
     */
    public static List<Slot> tellable(SettlerOrigin origin) {
        List<Slot> slots = new ArrayList<>(List.of(Slot.PLACE));
        if (origin.upbringing().isPresent()) {
            slots.add(Slot.UPBRINGING);
        }
        if (origin.turningPoint().isPresent()) {
            slots.add(Slot.TURNING_POINT);
        }
        return slots;
    }

    /**
     * Collects what the words of a line need, once, so the diary entry reads the same later whatever the datapack says by then.
     *
     * @param slot   the line, one of {@link #tellable}
     * @param origin the settler's origin
     * @param race   the settler's race definition, empty if it has none (the upbringing then has no trade or talent part)
     * @return the text parameters, for {@link #text}
     */
    public static List<String> params(Slot slot, SettlerOrigin origin, Optional<RaceDefinition> race) {
        return switch (slot) {
            case PLACE -> placeParams(origin.place());
            case UPBRINGING -> upbringingParams(origin, race);
            case TURNING_POINT -> List.of(slot.getSerializedName(), origin.turningPoint().orElseThrow().toString());
        };
    }

    /**
     * Builds the words of a told line in the first person.
     *
     * @param params the text parameters from {@link #params}
     * @return the text; empty for parameters that no line of this version produced (a save can hold entries of another version)
     */
    public static Component text(List<String> params) {
        if (params.isEmpty()) {
            return Component.empty();
        }
        String slot = params.get(0);
        if (slot.equals(Slot.PLACE.getSerializedName()) && params.size() == PLACE_PARAMS) {
            return Component.translatable("origin.told.place", OriginText.place(placeOf(params)));
        }
        if (slot.equals(Slot.UPBRINGING.getSerializedName()) && params.size() == UPBRINGING_PARAMS) {
            return upbringingText(params);
        }
        if (slot.equals(Slot.TURNING_POINT.getSerializedName()) && params.size() == 2) {
            return OriginText.idKey("turning_point", params.get(1));
        }
        return Component.empty();
    }

    private static List<String> placeParams(Place place) {
        return List.of(Slot.PLACE.getSerializedName(), place.kind().getSerializedName(), place.id().toString(),
                place.direction().map(Compass::getSerializedName).orElse(""), Integer.toString(place.days()));
    }

    private static Place placeOf(List<String> params) {
        Place.Kind kind = Arrays.stream(Place.Kind.values()).filter(value -> value.getSerializedName().equals(params.get(1))).findFirst()
                .orElse(Place.Kind.FAR_AWAY);
        Identifier id = Identifier.tryParse(params.get(2));
        Optional<Compass> direction = Arrays.stream(Compass.values()).filter(value -> value.getSerializedName().equals(params.get(3))).findFirst();
        return new Place(kind, id == null ? Place.FAR_AWAY_ID : id, direction, parseDays(params.get(4)));
    }

    private static int parseDays(String days) {
        try {
            return Integer.parseInt(days);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> upbringingParams(SettlerOrigin origin, Optional<RaceDefinition> race) {
        Identifier id = origin.upbringing().orElseThrow();
        Optional<OriginEntry> upbringing = race.flatMap(definition -> definition.upbringing(id));
        String jobs = upbringing.map(entry -> String.join(JOB_SEPARATOR, entry.jobs().stream().map(Identifier::toString).toList())).orElse("");
        SettlerName.Surname surname = origin.name().map(SettlerName::surname).orElse(SettlerName.Surname.NONE);
        Optional<OriginEntry> trade = surname.type() == SurnameType.TRADE ? race.flatMap(definition -> definition.surnameEntries()
                .getOrDefault(SurnameType.TRADE, List.of()).stream().filter(entry -> entry.id().toString().equals(surname.param())).findFirst())
                : Optional.empty();
        if (trade.isEmpty() || surname.ancestor().isEmpty()) {
            return List.of(Slot.UPBRINGING.getSerializedName(), id.toString(), PLAIN, "", "", jobs);
        }
        String variant = upbringing.filter(trade.get()::sharesJobWith).isPresent() ? SAME : DIFFERENT;
        return List.of(Slot.UPBRINGING.getSerializedName(), id.toString(), variant, surname.param(), surname.ancestor().get().getSerializedName(), jobs);
    }

    private static Component upbringingText(List<String> params) {
        Component family = OriginText.idKey("upbringing", params.get(1));
        List<Component> jobs = Arrays.stream(params.get(5).split(JOB_SEPARATOR)).filter(job -> !job.isEmpty()).map(OriginLine::jobPhrase).toList();
        if (jobs.isEmpty()) {
            return family;
        }
        Component talent = jobs.size() == 1 ? jobs.get(0) : Component.translatable("origin.told.jobs.two", jobs.get(0), jobs.get(1));
        String variant = params.get(2);
        if (variant.equals(PLAIN)) {
            return Component.translatable("origin.told.upbringing.plain", family, talent);
        }
        Ancestor ancestor = Arrays.stream(Ancestor.values()).filter(value -> value.getSerializedName().equals(params.get(4))).findFirst()
                .orElse(Ancestor.GRANDFATHER);
        return Component.translatable("origin.told.upbringing." + (variant.equals(SAME) ? SAME : DIFFERENT), family,
                OriginText.idKey("surname.trade", params.get(3)), Component.translatable(ancestor.langKey()), OriginText.idKey("trade", params.get(3)), talent);
    }

    private static Component jobPhrase(String job) {
        Identifier id = Identifier.tryParse(job);
        return id == null ? Component.literal(job) : Component.translatableWithFallback(OriginText.key("job", id), id.getPath());
    }
}
