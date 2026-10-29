package com.gilfort.civmod.compat.rei;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.compat.GatheringLayout;
import com.gilfort.civmod.job.JobDrops;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import net.minecraft.resources.Identifier;

/**
 * REI's display of one NPC drop pool entry in the display-only "Settler Gathering" category.
 */
final class GatheringDisplay extends BasicDisplay {

    /** The category. */
    static final CategoryIdentifier<GatheringDisplay> CATEGORY = CategoryIdentifier.of(CivMod.id("settler_gathering"));

    private static final MapCodec<GatheringDisplay> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("id").forGetter(display -> display.drop.id()),
            JobDrops.Entry.CODEC.fieldOf("entry").forGetter(display -> display.drop.entry())
    ).apply(instance, (id, entry) -> new GatheringDisplay(new JobDrops.Drop(id, entry))));
    /** Not saved: the displays come from the entries the server sent. */
    static final DisplaySerializer<GatheringDisplay> SERIALIZER = DisplaySerializer.of(CODEC,
            JobDrops.Drop.STREAM_CODEC.map(GatheringDisplay::new, display -> display.drop), false);

    private final JobDrops.Drop drop;

    GatheringDisplay(JobDrops.Drop drop) {
        super(List.of(EntryIngredients.ofItemStacks(GatheringLayout.fromIcons(drop))), List.of(EntryIngredients.of(GatheringLayout.result(drop))),
                Optional.of(drop.id()));
        this.drop = drop;
    }

    /**
     * @return the entry
     */
    JobDrops.Drop drop() {
        return drop;
    }

    @Override
    public CategoryIdentifier<?> getCategoryIdentifier() {
        return CATEGORY;
    }

    @Override
    public DisplaySerializer<? extends GatheringDisplay> getSerializer() {
        return SERIALIZER;
    }
}
