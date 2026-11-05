package com.gilfort.civmod.job;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The texts of the tool staircase for the settler window (vault {@code Systems/Needs.md}, decisions of 2026-11-04): the
 * Jobs tab line per tool band and the Needs tab row per tool Job. Pure data, so tests read them without a screen.
 */
public final class ToolTexts {

    private ToolTexts() {
    }

    /**
     * @param band a tool band (1-{@value NeedState#MAX_TIER}, the need tier of the Job skill)
     * @return the Jobs tab line for it, e.g. "Tool: Stone – Iron"
     */
    public static Component line(int band) {
        return Component.translatable("civmod.gui.settler.tool.band." + band);
    }

    /**
     * @return the tooltip of the Jobs tab line: what the tiers mean
     */
    public static Component lineTooltip() {
        return Component.translatable("civmod.gui.settler.tool.tooltip");
    }

    /**
     * @param tier    the tier the row sits in
     * @param jobName the Job's name (the row's tooltip)
     * @param icon    the Job's tool
     * @param view    how the tool need shows
     * @return the Needs tab row of a tool Job; it names no material
     */
    static NeedsReport.Row row(int tier, Component jobName, ItemStack icon, ToolNeeds.View view) {
        Component detail = switch (view) {
            case HAPPY -> Component.translatable("civmod.need.tool.met");
            case WEAK -> Component.translatable("civmod.need.tool.unmet");
            case NONE -> Component.translatable("civmod.need.tool.none");
        };
        return new NeedsReport.Row(tier, Component.translatable("civmod.need.tool"), view == ToolNeeds.View.HAPPY, detail, List.of(icon), jobName);
    }
}
