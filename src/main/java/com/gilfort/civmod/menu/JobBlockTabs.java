package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.List;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Fuels;
import com.gilfort.civmod.settlement.StationTypes;

/**
 * The tabs of a JobBlock window, chosen by the Job Category of the JobBlock's Job (vault
 * {@code Systems/Job Categories.md}), so an addon Job gets the same window as a built-in one. Every window starts with
 * the Storage and Workers tabs; only the Clerk keeps tabs keyed to its Job. This is the one place that knows which
 * category shows which tabs.
 */
public final class JobBlockTabs {

    private JobBlockTabs() {
    }

    /**
     * @param jobType the Job of a JobBlock
     * @return new tabs for its window, in tab order (the tabs below the panel last)
     */
    public static List<JobBlockTab> of(Job jobType) {
        List<JobBlockTab> tabs = new ArrayList<>(List.of(new StorageTab(), new WorkersTab()));
        if (jobType == ModJobs.CLERK.get()) {
            TabData<ClerkTabData> clerk = StockTab.newData();
            tabs.addAll(List.of(new StockTab(clerk), new RequestsTab(clerk), new DeliveriesTab(clerk)));
            return tabs;
        }
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
                // Storage and Workers only.
            }
        }
        return tabs;
    }

    /**
     * The Base Job's Patterns tab, the Station Types tab and (for a Job burning fuel) the Fuel tab on top, one Patterns
     * tab per Producing Specialization (in registry order) below the panel.
     */
    private static List<JobBlockTab> producing(Job jobType) {
        List<Job> specializations = StationTypes.hasStationTypes(jobType)
                ? StationTypes.specializations(jobType).stream().filter(job -> job.category() == JobCategory.PRODUCING).toList()
                : List.of();
        TabData<PatternsTabData> library = PatternsTab.newData();
        StationTypesTab stations = new StationTypesTab(!specializations.isEmpty());
        List<JobBlockTab> tabs = new ArrayList<>(List.of(new PatternsTab(jobType, true, library, stations.data()), stations));
        if (Fuels.burnsFuel(jobType)) {
            // On top, after Station Types.
            tabs.add(2, new FuelTab());
        }
        specializations.forEach(job -> tabs.add(new PatternsTab(job, false, library, stations.data())));
        return tabs;
    }
}
