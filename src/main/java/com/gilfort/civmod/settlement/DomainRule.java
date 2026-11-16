package com.gilfort.civmod.settlement;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.job.BiomeBonuses;
import com.gilfort.civmod.job.JobBalancing;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Decides whether a pattern lies outside a Job's domain (vault {@code Systems/Producing Jobs.md}). A pattern is in the
 * domain when its output is, or when at least {@code inputShare} of its ingredient slots are. A slot counts when its
 * item is in the domain; a tag ingredient counts only when every alternative it accepts is. The Job's
 * {@code recipe_deny} and {@code recipe_allow} lists are matched against the output item only: deny beats allow, allow
 * beats the domain test.
 *
 * @param domain     the domain item tag (empty: the Job has no domain, so only the deny list can block)
 * @param inputShare the share of the ingredient slots that puts a pattern in the domain (0 to 1)
 * @param allow      outputs that are always in the domain
 * @param deny       outputs that are never in the domain
 */
public record DomainRule(Optional<TagKey<Item>> domain, double inputShare, List<ExtraCodecs.TagOrElementLocation> allow,
        List<ExtraCodecs.TagOrElementLocation> deny) {

    /** The share of ingredient slots in the domain when the Job's datapack file names none. */
    public static final double DEFAULT_INPUT_SHARE = 0.5;

    /**
     * @param profile   the Job's Producing profile (its domain)
     * @param balancing the Job's datapack values (share and lists)
     * @return the rule of that Job
     */
    public static DomainRule of(ProducingProfile profile, JobBalancing balancing) {
        return new DomainRule(profile.domain(), balancing.domainInputShare().orElse(DEFAULT_INPUT_SHARE), balancing.recipeAllow().orElse(List.of()),
                balancing.recipeDeny().orElse(List.of()));
    }

    /**
     * @param output the item the pattern makes
     * @param inputs one ingredient per used slot of the pattern
     * @return whether the pattern is blocked: denied by the list, or not allowed and outside the domain
     */
    public boolean outside(Item output, List<Ingredient> inputs) {
        if (listed(deny, output)) {
            return true;
        }
        if (listed(allow, output) || domain.isEmpty()) {
            return false;
        }
        TagKey<Item> tag = domain.get();
        return !output.getDefaultInstance().is(tag) && !reachesShare(tag, inputs);
    }

    private boolean reachesShare(TagKey<Item> tag, List<Ingredient> inputs) {
        long inDomain = inputs.stream().filter(input -> inDomain(tag, input)).count();
        return !inputs.isEmpty() && inDomain >= inputShare * inputs.size();
    }

    private static boolean inDomain(TagKey<Item> tag, Ingredient input) {
        List<Holder<Item>> alternatives = input.items().toList();
        return !alternatives.isEmpty() && alternatives.stream().allMatch(item -> item.is(tag));
    }

    private static boolean listed(List<ExtraCodecs.TagOrElementLocation> entries, Item output) {
        return BiomeBonuses.matches(entries, BuiltInRegistries.ITEM.wrapAsHolder(output), Registries.ITEM);
    }
}
