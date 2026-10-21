package com.gilfort.civmod.menu;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.BiomePosition;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.job.BiomeBonuses;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The texts that show biome bonuses (vault {@code Systems/JobBlock.md}, decisions on biome bonuses): the effects of a
 * resolved bonus ("Speed ×1.1", "Ore chance ×1.1", "Raw Iron drops +50 %"), the line of today's biome for the JobBlock
 * window and the action bar ("Desert · Ore chance ×1.5"), and the "?" overview of every bonus file acting on a Job.
 */
public final class BiomeBonusText {

    /** Separator between the parts of a line. */
    private static final String SEPARATOR = " · ";

    private BiomeBonusText() {
    }

    /**
     * @param job   the Job the bonus was resolved for
     * @param bonus a resolved bonus
     * @return the biome and its effects ("Desert · Ore chance ×1.5"), the biome alone without an effect, empty without
     *         a biome
     */
    public static Optional<Component> line(Job job, BiomeBonus bonus) {
        return bonus.biome().map(biome -> {
            List<Component> parts = new ArrayList<>();
            parts.add(Component.translatable(Util.makeDescriptionId("biome", biome.identifier())));
            parts.addAll(effects(job, bonus));
            return join(parts, SEPARATOR);
        });
    }

    /**
     * @param job   the Job the bonus was resolved for
     * @param bonus a resolved bonus
     * @return its effects: speed, rare chance, then the double drops ({@link #doubleDrops})
     */
    private static List<Component> effects(Job job, BiomeBonus bonus) {
        List<Component> effects = new ArrayList<>();
        if (!bonus.speed().sources().isEmpty()) {
            effects.add(speed(bonus.speed().value()));
        }
        if (!bonus.rareChance().sources().isEmpty()) {
            rareChance(job, bonus.rareChance().value()).ifPresent(effects::add);
        }
        effects.addAll(doubleDrops(bonus));
        return effects;
    }

    /**
     * The double drops of a resolved bonus as the mechanics roll them: one effect per group of produced items matched
     * by the same entries, with the sum S of those entries' chances ({@link BiomeBonus#doubleDropChance}). A group is
     * named like an entry whose items are exactly the group (so entries with the same items read as one), else by its
     * items: {@code [raw_iron, raw_gold] +50 %} and {@code [raw_iron] +25 %} read "Raw Iron drops +75 %", "Raw Gold drops
     * +50 %". Entries that match no item are left out.
     */
    private static List<Component> doubleDrops(BiomeBonus bonus) {
        Map<List<BiomeBonus.DoubleDrop>, List<Item>> groups = new LinkedHashMap<>();
        Set<Item> seen = new HashSet<>();
        for (BiomeBonus.DoubleDrop drop : bonus.doubleDrops()) {
            for (Item item : items(drop.items())) {
                if (seen.add(item)) {
                    groups.computeIfAbsent(bonus.doubleDrops(new ItemStack(item)), matching -> new ArrayList<>()).add(item);
                }
            }
        }
        List<Component> effects = new ArrayList<>();
        groups.forEach((matching, items) -> {
            Set<Item> group = Set.copyOf(items);
            List<ExtraCodecs.TagOrElementLocation> names = matching.stream().map(BiomeBonus.DoubleDrop::items)
                    .filter(written -> items(written).equals(group)).findFirst()
                    .orElseGet(() -> items.stream().map(item -> new ExtraCodecs.TagOrElementLocation(BuiltInRegistries.ITEM.getKey(item), false)).toList());
            effects.add(doubleDrop(names, bonus.doubleDropChance(new ItemStack(items.getFirst()))));
        });
        return effects;
    }

    /** The items that item ids and {@code #item tags} name, in their order (unknown ids and empty tags name none). */
    private static Set<Item> items(List<ExtraCodecs.TagOrElementLocation> entries) {
        Set<Item> items = new LinkedHashSet<>();
        for (ExtraCodecs.TagOrElementLocation entry : entries) {
            if (entry.tag()) {
                BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, entry.id())).forEach(holder -> items.add(holder.value()));
            } else {
                BuiltInRegistries.ITEM.getOptional(entry.id()).ifPresent(items::add);
            }
        }
        return items;
    }

    /**
     * The "?" overview of a Job's biome bonuses: a title, one line per bonus file with an effect for the Job ("Desert:
     * Ore chance ×1.5 · Raw Iron drops +50 %") and the note that all other biomes have no effect.
     *
     * @param job a Job
     * @return the lines, empty for a Job without biome position (Mission Jobs)
     */
    public static List<Component> overview(Job job) {
        if (BiomeBonuses.position(job) == BiomePosition.NONE) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        for (BiomeBonuses.BonusFile file : BiomeBonuses.files().values()) {
            if (!file.jobs().contains(job)) {
                continue;
            }
            List<Component> effects = new ArrayList<>();
            file.speed().ifPresent(value -> effects.add(speed(value)));
            file.rareChance().flatMap(value -> rareChance(job, value)).ifPresent(effects::add);
            file.doubleDrop().forEach(drop -> effects.add(doubleDrop(drop.items(), drop.chance())));
            if (!effects.isEmpty()) {
                Component biomes = join(file.biomes().stream().map(BiomeBonusText::biome).toList(), ", ");
                lines.add(Component.translatable("civmod.biome_bonus.overview_line", biomes, join(effects, SEPARATOR)));
            }
        }
        if (lines.isEmpty()) {
            return List.of(Component.translatable("civmod.biome_bonus.overview_none"));
        }
        lines.addFirst(Component.translatable("civmod.biome_bonus.overview"));
        lines.add(Component.translatable("civmod.biome_bonus.others"));
        return lines;
    }

    /**
     * @param value a speed factor
     * @return "Speed ×1.1"
     */
    private static Component speed(double value) {
        return Component.translatable("civmod.biome_bonus.speed", factor(value));
    }

    /**
     * @param job   a Job
     * @param value a rare chance factor
     * @return "Ore chance ×1.1", empty if the Job has no rare group
     */
    private static Optional<Component> rareChance(Job job, double value) {
        return job.profile(ResourceCollectionProfile.class).flatMap(ResourceCollectionProfile::rareGroup)
                .map(group -> Component.translatable("civmod.biome_bonus.rare_chance", group, factor(value)));
    }

    /**
     * @param items  the item ids and {@code #item tags} of a double drop entry
     * @param chance its chance
     * @return "Raw Iron drops +50 %"
     */
    private static Component doubleDrop(List<ExtraCodecs.TagOrElementLocation> items, double chance) {
        Component names = join(items.stream().map(BiomeBonusText::item).toList(), ", ");
        return Component.translatable("civmod.biome_bonus.double_drop", names, percent(chance));
    }

    /**
     * The tooltip of a Found tab star: one line per effect acting on a drop with its source file ("Ore chance ×1.5
     * (biome bonus civmod:miner_mountains)").
     *
     * @param job   the Job the bonus was resolved for
     * @param bonus a resolved bonus
     * @param rare  whether the drop is in the Job's rare group (the rare chance acts on it)
     * @param drops the bonus's double drop entries matching the drop
     * @return the lines, rare chance first
     */
    public static List<Component> sourceLines(Job job, BiomeBonus bonus, boolean rare, List<BiomeBonus.DoubleDrop> drops) {
        List<Component> lines = new ArrayList<>();
        if (rare) {
            for (Identifier source : bonus.rareChance().sources()) {
                Optional.ofNullable(BiomeBonuses.files().get(source)).flatMap(BiomeBonuses.BonusFile::rareChance).flatMap(value -> rareChance(job, value))
                        .ifPresent(effect -> lines.add(withSource(effect, source)));
            }
        }
        drops.forEach(drop -> lines.add(withSource(doubleDrop(drop.items(), drop.chance()), drop.source())));
        return lines;
    }

    private static Component withSource(Component effect, Identifier source) {
        return Component.translatable("civmod.gui.found.bonus", effect, Component.translatable("civmod.biome_bonus.source", source.toString()));
    }

    private static Component biome(ExtraCodecs.TagOrElementLocation biome) {
        return biome.tag() ? tag("worldgen.biome", biome.id()) : Component.translatable(Util.makeDescriptionId("biome", biome.id()));
    }

    private static Component item(ExtraCodecs.TagOrElementLocation item) {
        if (item.tag()) {
            return tag("item", item.id());
        }
        return BuiltInRegistries.ITEM.getOptional(item.id()).map(found -> new ItemStack(found).getHoverName())
                .orElseGet(() -> Component.literal(item.id().toString()));
    }

    /** A tag by its translation ({@code tag.<registry>.<namespace>.<path>}), else its id. */
    private static Component tag(String registry, Identifier id) {
        return Component.translatableWithFallback("tag." + registry + "." + id.getNamespace() + "." + id.getPath().replace('/', '.'), "#" + id);
    }

    private static Component join(List<Component> parts, String separator) {
        return ComponentUtils.formatList(parts, Component.literal(separator));
    }

    /** A factor without trailing zeros: 1.1, 1.21, 2. */
    private static String factor(double value) {
        return "×" + BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** A chance in percent with its sign: +50 %, −40 %. */
    private static String percent(double chance) {
        long percent = Math.round(chance * 100);
        return (percent < 0 ? "−" + -percent : "+" + percent) + " %";
    }
}
