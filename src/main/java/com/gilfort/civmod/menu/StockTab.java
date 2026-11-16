package com.gilfort.civmod.menu;

import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.OneTimeOrders;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Clerk's "Stock" tab (vault {@code Jobs/Clerk.md}): its stock rules, with the player inventory to pick items
 * from; the client edits a rule with {@link ClerkTabData.Edit} and places or withdraws a one-time order with
 * {@link ClerkTabData.Order} / {@link ClerkTabData.Withdraw}. Shows the Clerk's data ({@link ClerkTabData}), shared
 * with the Tasks tab.
 */
public final class StockTab extends JobBlockTab {

    private final TabData<ClerkTabData> data;

    /**
     * @param data the Clerk's data, shared with its Tasks tab
     */
    StockTab(TabData<ClerkTabData> data) {
        this.data = data;
    }

    /**
     * @return a new instance of the Clerk's data, for the Clerk's tabs of one window
     */
    static TabData<ClerkTabData> newData() {
        return new TabData<>(ClerkTabData.EMPTY, (menu, level, pos) -> ClerkTabData.of(level, pos), ClerkTabData.Sync::new);
    }

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.clerk.tab.stock");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.WRITABLE_BOOK);
    }

    @Override
    boolean tall() {
        return true;
    }

    @Override
    boolean showsInventory() {
        return true;
    }

    @Override
    public Optional<TabData<?>> synced() {
        return Optional.of(data);
    }

    /**
     * @return the Clerk's rules and requests as last synced
     */
    public TabData<ClerkTabData> data() {
        return data;
    }

    /**
     * Server: sets or deletes the stock rule in a slot (only active slots take a new rule) and checks the rules at once.
     *
     * @param slot the rule slot
     * @param rule the new rule, empty to delete it
     */
    public void editRule(int slot, Optional<StockRule> rule) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.validSite();
        if (site.isEmpty()) {
            return;
        }
        SettlementData settlements = SettlementData.get(site.get().level());
        // StockRules.setRule refuses inactive slots (the settlement entry's level and Ledgers).
        boolean valid = rule.map(found -> found.slot() == slot && !found.item().isEmpty()).orElse(true);
        if (!valid) {
            return;
        }
        boolean changed = rule.isPresent() ? StockRules.setRule(settlements, site.get().pos(), rule.get())
                : StockRules.removeRule(settlements, site.get().pos(), slot);
        if (changed) {
            StockRules.check(site.get().level(), site.get().pos(), DayClock.dayTime(site.get().level()));
            menu.syncSoon();
        }
    }

    /**
     * Server: places a one-time order at this Clerk (no rule slot, no timeout).
     *
     * @param item   the item
     * @param amount the amount
     */
    public void order(ItemKey item, int amount) {
        menu().validSite().ifPresent(site -> {
            if (OneTimeOrders.place(SettlementData.get(site.level()), site.pos(), item, amount).isPresent()) {
                menu().syncSoon();
            }
        });
    }

    /**
     * Server: withdraws an open one-time order of this Clerk.
     *
     * @param request the request id
     */
    public void withdrawOrder(UUID request) {
        menu().validSite().ifPresent(site -> {
            if (OneTimeOrders.withdraw(SettlementData.get(site.level()), site.pos(), request)) {
                menu().syncSoon();
            }
        });
    }
}
