package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Fuels;
import com.gilfort.civmod.settlement.StationTypes;

/**
 * The tabs of a JobBlock window, chosen by the Job Category of the JobBlock's Job (vault
 * {@code Systems/Job Categories.md}), so an addon Job gets the same window as a built-in one. Every window has the
 * Workers and Storage tabs; only the Clerk keeps tabs keyed to its Job. This is the one place that knows which category
 * shows which tabs; their order comes from each tab's {@link JobBlockTab.Group} (vault {@code Systems/GUI.md}, tab
 * order): Workers · Storage · the Job's tabs · Station Types · Fuel on top, every Patterns tab below the panel.
 */
public final class JobBlockTabs {

    private JobBlockTabs() {
    }

    /**
     * @param jobType the Job of a JobBlock
     * @return new tabs for its window, in tab order (Workers first, the tabs below the panel last)
     */
    public static List<JobBlockTab> of(Job jobType) {
        List<JobBlockTab> tabs = new ArrayList<>(List.of(new WorkersTab(), new StorageTab()));
        if (jobType == ModJobs.CLERK.get()) {
            TabData<ClerkTabData> clerk = StockTab.newData();
            tabs.addAll(List.of(new StockTab(clerk), new RequestsTab(clerk), new DeliveriesTab(clerk)));
        } else {
            switch (jobType.category()) {
                case RESOURCE_COLLECTION -> {
                    tabs.add(new FoundTab());
                    // Only a Base Job with Specializations (the Gatherer): Lumberjack, Miner and Fisherman keep three tabs.
                    if (StationTypes.hasStationTypes(jobType)) {
                        tabs.add(new StationTypesTab(true));
                    }
                }
                case PRODUCING -> tabs.addAll(producing(jobType));
                case MISSION -> tabs.addAll(List.of(new MissionsTab(), new StationTypesTab(StationTypes.hasStationTypes(jobType))));
                case GUARD, LOGISTICS, SERVICE -> {
                    // Workers and Storage only.
                }
            }
        }
        // A stable sort: tabs of one group keep the order they were added in (Stock before the Clerk's task tabs, the
        // Base Job's Patterns tab before its Specializations').
        tabs.sort(Comparator.comparing(JobBlockTab::group));
        return tabs;
    }

    /**
     * The Station Types tab, the Fuel tab (for a Job burning fuel) and the Patterns tabs: the Base Job's first, then one
     * per Producing Specialization in registry order.
     */
    private static List<JobBlockTab> producing(Job jobType) {
        List<Job> specializations = StationTypes.hasStationTypes(jobType)
                ? StationTypes.specializations(jobType).stream().filter(job -> job.category() == JobCategory.PRODUCING).toList()
                : List.of();
        TabData<PatternsTabData> library = PatternsTab.newData();
        StationTypesTab stations = new StationTypesTab(!specializations.isEmpty());
        List<JobBlockTab> tabs = new ArrayList<>(List.of(stations, new PatternsTab(jobType, true, library, stations.data())));
        if (Fuels.burnsFuel(jobType)) {
            tabs.add(new FuelTab());
        }
        specializations.forEach(job -> tabs.add(new PatternsTab(job, false, library, stations.data())));
        return tabs;
    }
}
