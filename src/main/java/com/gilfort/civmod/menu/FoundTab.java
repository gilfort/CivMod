package com.gilfort.civmod.menu;

import java.util.Optional;

import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The "Found" tab of a Resource Collection JobBlock (vault {@code Systems/Resource Collection Jobs.md}): the resources
 * of the latest scan ({@link FoundTabData}, synced while the window is open); the client excludes or includes one with
 * {@link FoundTabData.Toggle}.
 */
public final class FoundTab extends JobBlockTab {

    private final TabData<FoundTabData> data = new TabData<>(FoundTabData.EMPTY, (menu, level, pos) -> FoundTabData.of(level, pos),
            FoundTabData.Sync::new);

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.job_block.tab.found");
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.SPYGLASS);
    }

    @Override
    public Optional<TabData<?>> synced() {
        return Optional.of(data);
    }

    /**
     * @return the found resources as last synced
     */
    public TabData<FoundTabData> data() {
        return data;
    }

    /**
     * Server: excludes or includes a found resource and sends the tab at once. Only a type the tab lists is taken.
     *
     * @param type the resource type
     * @return whether it was toggled
     */
    public boolean toggleExcluded(Identifier type) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.site().filter(found -> menu.stillValid(menu.player()));
        if (site.isEmpty() || FoundTabData.of(site.get().level(), site.get().pos()).entry(type).isEmpty()) {
            return false;
        }
        SettlementWorld.toggleExcluded(site.get().level(), site.get().pos(), type);
        menu.syncSoon();
        return true;
    }
}
