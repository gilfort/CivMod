package com.gilfort.civmod.job;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.Request;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * The usable checked patterns of a Producing Job, split by what the settler can work now (vault
 * {@code Systems/Producing Jobs.md}): innate recipes whose tool or station is missing are left out with their need
 * ("Needs Shears"), the tool before the station. Decides which missing tools are worth ordering and what blocks the Job
 * when no request fits a pattern it can work.
 *
 * @param workable    the patterns the settler can work now
 * @param unworkable  the patterns left out, with their need
 * @param toolsAtHand the tools of innate recipes the settler has on its belt or in its collect inventory
 */
record PatternNeeds(List<Pattern> workable, List<Unworkable> unworkable, Set<Ingredient> toolsAtHand) {

    /**
     * A pattern the settler cannot work now.
     *
     * @param pattern        the pattern
     * @param need           what it lacks, as marked in the Patterns tab
     * @param missingTool    the tool it lacks, empty when the tool is at hand and only the station is missing
     * @param servesRequests whether the pattern can serve a request (not an innate recipe with {@code drops})
     */
    record Unworkable(Pattern pattern, Component need, Optional<Ingredient> missingTool, boolean servesRequests) {
    }

    /**
     * The missing tools somebody wants: of a pattern an open request asks for, or one set to
     * "Always produce". A checked recipe nobody needs orders nothing.
     *
     * @param open the claimable requests of the settlement
     * @return the tools to order, each once
     */
    Set<Ingredient> wantedTools(List<Request> open) {
        Set<Ingredient> tools = new LinkedHashSet<>();
        for (Unworkable entry : unworkable) {
            if (entry.pattern().always() || asked(entry, open)) {
                entry.missingTool().ifPresent(tools::add);
            }
        }
        return tools;
    }

    /**
     * What blocks the Job when no open request fits a pattern it can work: the need of the highest-priority request a
     * left-out pattern would serve, a missing tool before a missing station.
     *
     * @param open the claimable requests of the settlement, highest priority first
     * @return the need to show as status "Blocked", empty when no open request fits a left-out pattern ("No request")
     */
    Optional<Component> blockedBy(List<Request> open) {
        for (Request request : open) {
            Optional<Unworkable> found = unworkable.stream()
                    .filter(entry -> serves(entry, request))
                    .min(Comparator.comparing(entry -> entry.missingTool().isEmpty()));
            if (found.isPresent()) {
                return Optional.of(found.get().need());
            }
        }
        return Optional.empty();
    }

    private static boolean asked(Unworkable entry, List<Request> open) {
        return open.stream().anyMatch(request -> serves(entry, request));
    }

    private static boolean serves(Unworkable entry, Request request) {
        return entry.servesRequests() && entry.pattern().output().equals(request.item());
    }
}
