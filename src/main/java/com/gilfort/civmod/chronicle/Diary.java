package com.gilfort.civmod.chronicle;

import java.util.List;

import com.gilfort.civmod.api.DiaryEntryType;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.origin.OriginText;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * Writes into the diary of a settler (vault {@code Systems/Chronicle.md}). Every entry is written on an event, never on a tick: the
 * arrival, the loss of a home and what the settler tells in a talk. The public way in for addons is
 * {@link com.gilfort.civmod.api.CivModApi#addDiaryEntry}.
 */
public final class Diary {

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
        Identifier reason = params.isEmpty() ? null : Identifier.tryParse(params.get(0));
        Component why = reason == null ? Component.translatable("diary.civmod.arrival.no_reason")
                : Component.translatable(OriginText.key("reason", reason));
        if (params.size() < 2 || params.get(1).isEmpty()) {
            return why;
        }
        return Component.translatable("diary.civmod.arrival.rescued", why, params.get(1));
    }

    private static boolean isPermanent(Identifier type) {
        return ModDiaryEntryTypes.REGISTRY.getOptional(type).map(DiaryEntryType::permanent).orElse(false);
    }
}
