package com.gilfort.civmod.api;

import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;

/**
 * Why a Job cannot work, reported by its profile (vault {@code Systems/Job Categories.md}, status "Blocked"): the text
 * replaces the status in the settler window ("No sand in reach"), the icon is shown in the settler's problem bubble
 * (without one the generic "blocked" marker).
 *
 * @param text the reason as shown in the settler window
 * @param icon the item in the problem bubble, if any
 */
@ApiStatus.Experimental
public record BlockingReason(Component text, Optional<Item> icon) {

    /**
     * @param text the reason, e.g. {@code Component.translatable("examplemod.blocked.no_sand")}
     * @return a reason shown with the generic "blocked" marker
     */
    public static BlockingReason of(Component text) {
        return new BlockingReason(text, Optional.empty());
    }

    /**
     * @param langKey the lang key of the reason
     * @return a reason shown with the generic "blocked" marker
     */
    public static BlockingReason of(String langKey) {
        return of(Component.translatable(langKey));
    }

    /**
     * @param icon the item shown in the problem bubble
     * @return this reason with that icon
     */
    public BlockingReason withIcon(ItemLike icon) {
        return new BlockingReason(text, Optional.of(icon.asItem()));
    }
}
