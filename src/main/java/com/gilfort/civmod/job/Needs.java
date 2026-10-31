package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.gilfort.civmod.config.CivModConfig;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The needs that exist so far: add a new {@link Need} to {@link #ALL}. */
final class Needs {

    /** The food need: met while the settler is not in the hunger state. */
    private static final Need FOOD = new Need() {
        @Override
        public int tier() {
            return 1;
        }

        @Override
        public Component name() {
            return Component.translatable("civmod.need.food");
        }

        @Override
        public Item icon() {
            return Items.BREAD;
        }

        @Override
        public Check check(Context context) {
            return context.meals().hungerState(context.foodPoint()).map(state -> new Check(false, state))
                    .orElseGet(() -> new Check(true, Component.translatable("civmod.need.food.met")));
        }
    };

    /** The fire need: a hearth within reach of the place the settler sleeps at. */
    private static final Need FIRE = new Need() {
        @Override
        public int tier() {
            return 1;
        }

        @Override
        public Component name() {
            return Component.translatable("civmod.need.fire");
        }

        @Override
        public Item icon() {
            return Items.CAMPFIRE;
        }

        @Override
        public Check check(Context context) {
            OptionalInt distance = context.rest().hearthDistance(context.level());
            if (distance.isEmpty()) {
                return new Check(false, Component.translatable("civmod.need.fire.none"));
            }
            int range = CivModConfig.HEARTH_RANGE.get();
            return distance.getAsInt() <= range
                    ? new Check(true, Component.translatable("civmod.need.fire.near", distance.getAsInt()))
                    : new Check(false, Component.translatable("civmod.need.fire.far", distance.getAsInt(), range));
        }
    };

    /** Every need, in the order of its rows in the Needs tab. */
    static final List<Need> ALL = List.of(FOOD, FIRE);

    private Needs() {
    }

    /**
     * @param context what the checks can read
     * @return the unmet need of the lowest tier, if any
     */
    static Optional<Need> firstUnmet(Need.Context context) {
        return ALL.stream().sorted(Comparator.comparingInt(Need::tier)).filter(need -> !need.check(context).met()).findFirst();
    }

    /**
     * @param unmet the unmet need of the lowest tier, if any
     * @return the highest tier whose needs and those of every lower tier are met (0: not even tier 1)
     */
    static int highestMet(Optional<Need> unmet) {
        return unmet.map(need -> need.tier() - 1).orElse(NeedState.MAX_TIER);
    }
}
