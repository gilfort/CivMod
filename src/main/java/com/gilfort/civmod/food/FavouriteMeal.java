package com.gilfort.civmod.food;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Where the favourite meal of a settler comes from (vault {@code Systems/Needs.md}): an item tag named by its race, rolled once.
 * The tag of the race {@code namespace:path} is {@code namespace:favourite_meal/path} (Human: {@code civmod:favourite_meal/human}),
 * so a datapack or an addon extends the tag of a race, and a race of an addon brings a tag of its own. A race without a tag has no
 * favourite meal and its settlers need none.
 */
public final class FavouriteMeal {

    private static final String TAG_PATH = "favourite_meal/";

    private FavouriteMeal() {
    }

    /**
     * @param race a race id
     * @return the item tag its favourite meals come from
     */
    public static TagKey<Item> tag(Identifier race) {
        return TagKey.create(Registries.ITEM, race.withPath(TAG_PATH + race.getPath()));
    }

    /**
     * Rolls a favourite meal: one of the foods in the tag of the race that settlers eat. An item of the tag that a settler would never
     * eat (a cake has no food component) is skipped, or the settler could never get what it needs. The foods are drawn in the order of
     * their ids, so the same state of the random source gives the same food whatever order the tag lists them in. The callers pass the
     * settler's own source, not the scripted source of the origin roll, so that the scripted sequences of those tests stay as they are.
     *
     * @param race   the race id
     * @param random the random source
     * @return the item id, empty if the race has no tag or nothing in it can be eaten
     */
    public static Optional<Identifier> roll(Identifier race, RandomSource random) {
        List<Identifier> foods = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag(race))) {
            if (SettlerFood.isEdible(new ItemStack(holder.value()))) {
                foods.add(BuiltInRegistries.ITEM.getKey(holder.value()));
            }
        }
        foods.sort(Comparator.naturalOrder());
        return foods.isEmpty() ? Optional.empty() : Optional.of(foods.get(random.nextInt(foods.size())));
    }

    /**
     * @param item a food item id
     * @return the name of the item, or the path of its id when the item is gone (a removed mod)
     */
    public static Component name(Identifier item) {
        return BuiltInRegistries.ITEM.getOptional(item).<Component>map(found -> new ItemStack(found).getHoverName())
                .orElseGet(() -> Component.literal(item.getPath()));
    }
}
