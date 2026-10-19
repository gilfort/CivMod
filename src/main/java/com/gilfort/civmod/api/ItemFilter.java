package com.gilfort.civmod.api;

import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Which items a Producing Job works with (vault {@code Systems/Producing Jobs.md}), as item tags: an optional tag the
 * items must be in and an optional tag they must not be in. Used by {@link ProducingProfile#inputFilter()} and
 * {@link ProducingProfile#outputFilter()}.
 *
 * @param allowed the tag an item must be in (empty: every item)
 * @param denied  the tag an item must not be in (empty: none)
 */
@ApiStatus.Experimental
public record ItemFilter(Optional<TagKey<Item>> allowed, Optional<TagKey<Item>> denied) {

    /** Every item. */
    public static final ItemFilter ANY = new ItemFilter(Optional.empty(), Optional.empty());

    /**
     * @param tag the tag an item must be in
     * @return a filter that lets only the tag's items through
     */
    public static ItemFilter allow(TagKey<Item> tag) {
        return new ItemFilter(Optional.of(tag), Optional.empty());
    }

    /**
     * @param tag the tag an item must not be in
     * @return a filter that lets every item through except the tag's
     */
    public static ItemFilter deny(TagKey<Item> tag) {
        return new ItemFilter(Optional.empty(), Optional.of(tag));
    }

    /**
     * @param item an item
     * @return whether the filter lets it through
     */
    public boolean test(Holder<Item> item) {
        return allowed.map(item::is).orElse(true) && denied.map(tag -> !item.is(tag)).orElse(true);
    }

    /**
     * @param item an item
     * @return whether the filter lets it through
     */
    public boolean test(Item item) {
        return test(BuiltInRegistries.ITEM.wrapAsHolder(item));
    }
}
