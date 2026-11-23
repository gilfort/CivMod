package com.gilfort.civmod.food;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
     * their ids, so a scripted random source gives the same food on every start.
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
}
