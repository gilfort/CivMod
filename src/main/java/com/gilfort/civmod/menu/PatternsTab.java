package com.gilfort.civmod.menu;

import java.util.Optional;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A "Patterns" tab of a Producing JobBlock (vault {@code Systems/Producing Jobs.md}): the pattern library of one Job,
 * with the player inventory to pick items from; the client edits a pattern with {@link PatternsTabData.Edit}. The Base
 * Job's tab sits on top and also handles the Info tab's delivery toggle (Self / Deliveryman); each Specialization's tab
 * sits below the panel, shown while the Job is unlocked or its library holds a pattern. All Patterns tabs of a window
 * share one synced library ({@link PatternsTabData}): the one of the tab shown, else the one looked at last.
 */
public final class PatternsTab extends JobBlockTab {

    /** Button of the Base Job's tab: toggle who carries the results, Self / Deliveryman. */
    public static final int TOGGLE_DELIVERY = 0;

    private final Job job;
    private final boolean base;
    private final TabData<PatternsTabData> data;
    private final TabData<StationTypesTabData> stations;

    /**
     * @param job      the Producing Job of the library
     * @param base     whether it is the JobBlock's Base Job (else a Specialization)
     * @param data     the synced library, shared by the window's Patterns tabs
     * @param stations the window's Station Types, which decide whether a Specialization's tab is shown
     */
    PatternsTab(Job job, boolean base, TabData<PatternsTabData> data, TabData<StationTypesTabData> stations) {
        this.job = job;
        this.base = base;
        this.data = data;
        this.stations = stations;
    }

    /**
     * @return a new synced library, for the Patterns tabs of one window
     */
    static TabData<PatternsTabData> newData() {
        return new TabData<>(PatternsTabData.EMPTY, (menu, level, pos) -> {
            // The library of the Patterns tab shown, else the one looked at last (the Base Job's before any).
            Job job = menu.activeTab() instanceof PatternsTab shown ? shown.job
                    : menu.tab(PatternsTab.class).flatMap(first -> first.data.get().job()).orElse(menu.getInfo().jobType());
            // The innate recipes first; a pattern its Job filters out loses its checkmark as soon as the tab shows it,
            // foreign ones keep theirs.
            Patterns.syncInnate(level, pos, job);
            Patterns.uncheckFiltered(level, pos, job);
            return PatternsTabData.of(level, pos, job);
        }, PatternsTabData.Sync::new);
    }

    /**
     * @param menu a JobBlock window
     * @param job  a Producing Job
     * @return the window's Patterns tab of that Job, if it has one
     */
    public static Optional<PatternsTab> of(JobBlockMenu menu, Job job) {
        return menu.tabs().stream().filter(PatternsTab.class::isInstance).map(PatternsTab.class::cast).filter(tab -> tab.job == job).findFirst();
    }

    /**
     * @return the Job of the library
     */
    public Job job() {
        return job;
    }

    @Override
    public Component label() {
        return Component.translatable("civmod.gui.patterns.tab_job", job.displayName());
    }

    @Override
    public ItemStack icon() {
        return base ? new ItemStack(Items.CRAFTING_TABLE) : job.icon();
    }

    @Override
    public boolean below() {
        return !base;
    }

    @Override
    public boolean shown() {
        return base || stations.get().unlocked().contains(job) || stations.get().libraries().contains(job);
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
     * @return the library of the Patterns tab last looked at, as last synced
     */
    public TabData<PatternsTabData> data() {
        return data;
    }

    @Override
    int buttons() {
        return base ? 1 : 0;
    }

    @Override
    boolean clickButton(Player player, int button) {
        JobBlockMenu menu = menu();
        menu.setInfo(menu.getInfo().withDeliveryToggled());
        menu.site().ifPresent(site -> JobAssignments.toggleDelivery(SettlementData.get(site.level()), site.pos()));
        return true;
    }

    /**
     * Server: changes a pattern of this tab's library and sends the tab at once.
     *
     * @param edit the edit (of this tab's Job)
     */
    public void edit(PatternsTabData.Edit edit) {
        JobBlockMenu menu = menu();
        Optional<JobBlockMenu.Site> site = menu.site().filter(found -> menu.stillValid(menu.player()));
        if (site.isEmpty() || edit.job() != job) {
            // Only the tab of the edit's Job takes it (PatternsTab.of).
            return;
        }
        ServerLevel level = site.get().level();
        BlockPos pos = site.get().pos();
        Optional<?> ignored = switch (edit.op()) {
            case SET_OUTPUT -> Patterns.setOutput(level, pos, job, edit.slot(), edit.item());
            case SET_INPUT -> Patterns.setInput(level, pos, job, edit.slot(), edit.index(), edit.item(), edit.layout());
            case CYCLE -> Patterns.cycle(level, pos, job, edit.slot(), edit.index());
            case TOGGLE_CHECK -> Optional.of(Patterns.toggleCheck(level, pos, job, edit.slot()));
            case CLEAR -> {
                Patterns.clear(SettlementData.get(level), pos, job, edit.slot());
                yield Optional.empty();
            }
            case TRANSFER -> Patterns.transfer(level, pos, job, edit.slot(), edit.recipe(), edit.grid(), edit.layout());
        };
        menu.syncSoon();
    }
}
