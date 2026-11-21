package com.gilfort.civmod.mastery;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.mastery.MasterworkRules.Effect;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;

/**
 * What a masterwork carries (vault {@code Systems/Producing Jobs.md}): the effects of all its hits and the name of the settler
 * who made it. The components that show them (durability, tool speed, attributes, name, rarity, lore) are worked out from this
 * and the item's own defaults, so they can be worked out again over another item after a smithing upgrade. The component is no
 * relevant component: it takes no part in item identity.
 *
 * @param madeFor the item the other components were last worked out for; another item means they are stale
 * @param effects one effect per hit, from every craft that added to the masterwork, at least one
 * @param maker   the name of the settler who made the masterwork first
 */
public record MasterworkData(Holder<Item> madeFor, List<Effect> effects, String maker) {

    /** The most effects a masterwork can carry: far above what upgrades reach, and a bound for what a file or a packet can claim. */
    private static final int MAX_EFFECTS = 256;

    /** Stored with the item. Built on first use: the effects name attributes, which are not registered while the mod loads. */
    public static final Codec<MasterworkData> CODEC = Codec.lazyInitialized(() -> RecordCodecBuilder.create(instance -> instance.group(
            Item.CODEC.fieldOf("made_for").forGetter(MasterworkData::madeFor),
            Effect.CODEC.listOf(1, MAX_EFFECTS).fieldOf("effects").forGetter(MasterworkData::effects),
            Codec.STRING.fieldOf("maker").forGetter(MasterworkData::maker)
    ).apply(instance, MasterworkData::new)));

    /** Sent to the client with the item. */
    public static final StreamCodec<RegistryFriendlyByteBuf, MasterworkData> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

    /**
     * @param hits the effects of new hits
     * @return the same masterwork with the hits added to its effects
     */
    MasterworkData with(List<Effect> hits) {
        List<Effect> all = new ArrayList<>(effects);
        all.addAll(hits);
        return new MasterworkData(madeFor, List.copyOf(all), maker);
    }

    /**
     * @param item the item the components are worked out for
     * @return the same masterwork, marked as worked out for the item
     */
    MasterworkData madeFor(Holder<Item> item) {
        return new MasterworkData(item, effects, maker);
    }
}
