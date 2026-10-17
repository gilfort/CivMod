package com.gilfort.civmod.menu;

import net.minecraft.ChatFormatting;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Sprites CivMod windows draw and also show inside texts (help lines, tooltips).
 * <p>
 * Lives in common code, not in the client-only {@code CivGui}, so the dedicated GameTest server can check it.
 */
public final class GuiIcons {

    /** The checkmark of a checked pattern (vanilla beacon confirm sprite of the GUI atlas). */
    public static final Identifier CHECKMARK = Identifier.withDefaultNamespace("container/beacon/confirm");

    private GuiIcons() {
    }

    /**
     * @return the checkmark sprite as a text component; a green "✔" where the sprite cannot be shown
     */
    public static Component inlineCheckmark() {
        return Component.object(new AtlasSprite(AtlasIds.GUI, CHECKMARK), Component.literal("✔").withStyle(ChatFormatting.GREEN));
    }
}
