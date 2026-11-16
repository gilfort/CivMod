package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** The texts of a Clerk's stock rule slot: its tooltip in the grid and the reason a slot is locked. */
final class StockTooltips {

    private StockTooltips() {
    }

    /**
     * The tooltip of a rule slot: the rule's item and every field that is set, a note when its surplus has nowhere to go,
     * and why the slot is locked when it is.
     *
     * @param clerk the Clerk's data
     * @param slot  the rule slot
     * @return the tooltip lines
     */
    static List<Component> rule(ClerkTabData clerk, int slot) {
        List<Component> lines = new ArrayList<>();
        Optional<StockRule> rule = clerk.rule(slot);
        if (rule.isPresent()) {
            lines.add(rule.get().item().toStack().getHoverName());
            StockRule found = rule.get();
            // One line per set field.
            if (found.hasMin()) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.min", found.min()).withStyle(ChatFormatting.GRAY));
            }
            if (found.hasMax()) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.max", found.max()).withStyle(ChatFormatting.GRAY));
            }
            if (found.order() != StockRule.NOT_SET) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.order", found.order()).withStyle(ChatFormatting.GRAY));
            }
            if (found.hasMin()) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.priority_value", found.priority()).withStyle(ChatFormatting.GRAY));
            }
            if (clerk.nowhere().contains(slot)) {
                lines.add(Component.translatable("civmod.gui.clerk.stock.surplus_nowhere").withStyle(ChatFormatting.RED));
            }
        } else {
            lines.add(Component.translatable("civmod.gui.clerk.stock.empty_slot"));
        }
        if (slot >= clerk.slots()) {
            lines.add(locked(clerk).copy().withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    /**
     * Why a row is locked, naming only the ways still open: an upgrade and a Ledger, only a Ledger at Lv 5, only an
     * upgrade with every Ledger used.
     */
    static Component locked(ClerkTabData tab) {
        boolean upgrade = tab.level() < SettlementRules.MAX_LEVEL;
        boolean ledger = tab.ledgers() < SettlementRules.MAX_LEDGERS;
        String key = upgrade && ledger ? "civmod.gui.clerk.stock.locked.both"
                : ledger ? "civmod.gui.clerk.stock.locked.ledger" : "civmod.gui.clerk.stock.locked.upgrade";
        return Component.translatable(key);
    }
}
