package com.gilfort.civmod.job;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.gilfort.civmod.npc.Settler;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One need of a need tier (vault {@code Systems/Needs.md}): a check per settler. A new need implements this and is
 * listed in {@link Needs#ALL}; tiers, grace day, downgrade, climbing back, complaints and the Needs tab follow from
 * there. Needs whose system does not exist yet are simply not listed: they count as met and are not shown.
 * <p>
 * {@link #check} runs at wake-up, in the 5 s decision step of a settler that can climb and when a settler window opens:
 * it must stay cheap, without area scans.
 */
interface Need {

    /**
     * What a check can read.
     *
     * @param level     the level
     * @param day       the day of the check (simulated in catch-up)
     * @param settler   the settler
     * @param meals     the settler's meals
     * @param rest      the settler's rest: where it sleeps
     * @param foodPoint whether a food point has food for the settler now; a container lookup, ask it last
     */
    record Context(ServerLevel level, long day, Settler settler, SettlerMeals meals, SettlerRest rest, BooleanSupplier foodPoint) {
    }

    /**
     * The result of a check.
     *
     * @param met    whether the need is met
     * @param detail the detail text of its row in the Needs tab
     * @param icons  items shown with the detail, may be empty
     */
    record Check(boolean met, Component detail, List<ItemStack> icons) {

        /**
         * @param met    whether the need is met
         * @param detail the detail text of its row in the Needs tab
         */
        Check(boolean met, Component detail) {
            this(met, detail, List.of());
        }
    }

    /**
     * @return the tier the need belongs to (1–{@value NeedState#MAX_TIER})
     */
    int tier();

    /**
     * @return its name
     */
    Component name();

    /**
     * @return the item shown in the thought bubble while the need is missing
     */
    Item icon();

    /**
     * @param context what the check can read
     * @return whether the need is met now
     */
    Check check(Context context);
}
