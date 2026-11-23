package com.gilfort.civmod.chronicle;

import java.util.Arrays;
import java.util.List;

import com.gilfort.civmod.api.DiaryEntryType;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.origin.OriginText;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;

/**
 * Writes into the diary of a settler (vault {@code Systems/Chronicle.md}). Every entry is written on an event, never on a tick: the
 * arrival, joining a settlement from an inn, the loss of a home and what the settler tells in a talk. The public way in for addons is
 * {@link com.gilfort.civmod.api.CivModApi#addDiaryEntry}.
 */
public final class Diary {

    /** How a favourite meal came out. */
    public enum Reveal implements StringRepresentable {
        /** The settler told it in a talk. */
        TOLD("told"),
        /** The settler ate it for the first time. */
        EATEN("eaten");

        private final String serializedName;

        Reveal(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    private Diary() {
    }

    /**
     * Writes an entry dated with the settler day of now.
     *
     * @param settler a settler
     * @param type    the type of the entry, registered in the {@code civmod:diary_entry_type} registry
     * @param params  the text parameters of the type
     * @return whether the entry was written: not on the client, and not when the diary is full of permanent entries
     */
    public static boolean add(Settler settler, DiaryEntryType type, List<String> params) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Identifier id = ModDiaryEntryTypes.REGISTRY.getKey(type);
        if (id == null) {
            throw new IllegalArgumentException("A diary entry type must be registered before it is used: " + type);
        }
        Chronicle before = settler.getPast().chronicle();
        Chronicle after = before.with(new DiaryEntry(id, params, DayClock.day(level)), Diary::isPermanent);
        settler.getPast().setChronicle(after);
        return after != before;
    }

    /**
     * The first entry of a settler that arrives: why it came and who took it in.
     *
     * @param settler a settler that has just got its origin
     * @param origin  the origin
     */
    public static void arrival(Settler settler, SettlerOrigin origin) {
        add(settler, ModDiaryEntryTypes.ARRIVAL.get(),
                List.of(origin.reason().map(Identifier::toString).orElse(""), origin.rescuer().map(rescuer -> rescuer.name()).orElse("")));
    }

    /**
     * The entry of a visitor that joins a settlement: the reason it came to the inn.
     *
     * @param settler the visitor, now a settler
     */
    public static void joined(Settler settler) {
        List<String> reason = settler.getPast().origin().flatMap(SettlerOrigin::reason).map(id -> List.of(id.toString())).orElse(List.of());
        add(settler, ModDiaryEntryTypes.JOINED.get(), reason);
    }

    /**
     * The entry of a settler that has lost its settlement. A settler that is homeless already gets none.
     *
     * @param settler the settler, before it leaves its settlement
     */
    public static void homeless(Settler settler) {
        if (settler.isHomeless()) {
            return;
        }
        add(settler, ModDiaryEntryTypes.HOMELESS.get(), List.of());
    }

    /**
     * Builds the arrival text: "<reason> <player> took me in."; without a reason "I came to this place.", without a rescuer only the
     * reason.
     *
     * @param params the reason id (empty for none) and the rescuer's name (empty for none)
     * @return the text
     */
    public static Component arrivalText(List<String> params) {
        Component why = params.stream().findFirst().map(Identifier::tryParse).<Component>map(reason -> Component.translatable(OriginText.key("reason", reason)))
                .orElseGet(() -> Component.translatable("diary.civmod.arrival.no_reason"));
        if (params.size() < 2 || params.get(1).isEmpty()) {
            return why;
        }
        return Component.translatable("diary.civmod.arrival.rescued", why, params.get(1));
    }

    /**
     * Builds the text of the entry of a visitor that joined: "I joined the settlement. <reason>"; without a reason only the first part.
     *
     * @param params the reason id (empty for none)
     * @return the text
     */
    public static Component joinedText(List<String> params) {
        return params.stream().findFirst().map(Identifier::tryParse)
                .<Component>map(reason -> Component.translatable("diary.civmod.joined.reason", Component.translatable(OriginText.key("reason", reason))))
                .orElseGet(() -> Component.translatable("diary.civmod.joined"));
    }

    /**
     * Writes the entry of a settler that showed its favourite meal.
     *
     * @param settler the settler
     * @param item    the favourite meal's item id
     * @param reveal  how the favourite came out
     * @return the text parameters of the entry, which {@link #favouriteMealText} turns into words
     */
    public static List<String> favouriteMeal(Settler settler, Identifier item, Reveal reveal) {
        List<String> params = List.of(item.toString(), reveal.getSerializedName());
        add(settler, ModDiaryEntryTypes.FAVOURITE_MEAL.get(), params);
        return params;
    }

    /**
     * Builds the text of the favourite meal entry: "My favourite meal is Bread." when the settler told it, "I finally got to eat Bread, my
     * favourite meal." when it ate it first.
     *
     * @param params the item id and the way it came out, as {@link #favouriteMeal} wrote them
     * @return the text; empty for parameters that no version of the entry wrote
     */
    public static Component favouriteMealText(List<String> params) {
        Identifier id = params.size() == 2 ? Identifier.tryParse(params.get(0)) : null;
        if (id == null) {
            return Component.empty();
        }
        Component item = BuiltInRegistries.ITEM.getOptional(id).<Component>map(found -> new ItemStack(found).getHoverName())
                .orElseGet(() -> Component.literal(id.getPath()));
        return Arrays.stream(Reveal.values()).filter(reveal -> reveal.getSerializedName().equals(params.get(1))).findFirst()
                .<Component>map(reveal -> Component.translatable("diary.civmod.favourite_meal." + reveal.getSerializedName(), item))
                .orElse(Component.empty());
    }

    private static boolean isPermanent(Identifier type) {
        return ModDiaryEntryTypes.REGISTRY.getOptional(type).map(DiaryEntryType::permanent).orElse(false);
    }
}
