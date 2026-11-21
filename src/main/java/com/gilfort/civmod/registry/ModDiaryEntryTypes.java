package com.gilfort.civmod.registry;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.DiaryEntryType;
import com.gilfort.civmod.chronicle.Diary;
import com.gilfort.civmod.origin.OriginLine;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The {@code civmod:diary_entry_type} registry (vault {@code Systems/Chronicle.md}): static, filled at mod load and synced to
 * clients, which build the words of an entry from its type. CivMod registers its own types through the addon API like an addon does.
 */
public final class ModDiaryEntryTypes {

    /** Registry key of {@code civmod:diary_entry_type}. */
    public static final ResourceKey<Registry<DiaryEntryType>> KEY = CivModApi.DIARY_ENTRY_TYPE_REGISTRY;

    /** Deferred register for diary entry types in the {@code civmod} namespace. */
    public static final DeferredRegister<DiaryEntryType> TYPES = CivModApi.diaryEntryTypes(CivMod.MOD_ID);

    /** The diary entry type registry. */
    public static final Registry<DiaryEntryType> REGISTRY = TYPES.makeRegistry(builder -> builder.sync(true));

    /** The first entry of every settler that arrives: the reason and who took it in. It never drops out of a full diary. */
    public static final DeferredHolder<DiaryEntryType, DiaryEntryType> ARRIVAL = TYPES.register("arrival",
            () -> new DiaryEntryType(Diary::arrivalText, false, true));

    /** A part of the past that the settler told in a talk: where it comes from, its family or its turning point. */
    public static final DeferredHolder<DiaryEntryType, DiaryEntryType> ORIGIN_LINE = TYPES.register("origin_line",
            () -> new DiaryEntryType(OriginLine::text, true, false));

    /** The settler has lost its settlement. */
    public static final DeferredHolder<DiaryEntryType, DiaryEntryType> HOMELESS = TYPES.register("homeless",
            () -> DiaryEntryType.of("diary.civmod.homeless"));

    private ModDiaryEntryTypes() {
    }

    /**
     * @param modEventBus the mod event bus
     */
    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }
}
