package com.gilfort.civmod.menu;

import java.util.Optional;

import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Clerk's "Stock" tab (vault {@code Jobs/Clerk.md}): its stock rules, with the player inventory to pick items
 * from; the client edits a rule with {@link ClerkTabData.Edit}. Shows the Clerk's data ({@link ClerkTabData}), shared
 * with the Requests and Deliveries tabs.
 */
public final class StockTab extends JobBlockTab {

    private final TabData<ClerkTabData> data;

    /**
     * @param data the Clerk's data, shared with its Requests and Deliveries tabs
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
        Optional<JobBlockMenu.Site> site = menu.site().filter(found -> menu.stillValid(menu.player()));
        if (site.isEmpty()) {
            return;
        }
        SettlementData settlements = SettlementData.get(site.get().level());
        int level = site.get().jobBlock().getJobLevel();
        boolean valid = rule.map(found -> found.slot() == slot && found.item() != Items.AIR && found.activeAt(level)).orElse(true);
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
}
