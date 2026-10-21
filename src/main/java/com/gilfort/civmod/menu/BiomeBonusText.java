package com.gilfort.civmod.menu;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.api.BiomeBonus;
import com.gilfort.civmod.api.BiomePosition;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.job.BiomeBonuses;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Util;
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
     * @return its effects: speed, rare chance, then one double drop per item (chances of the same items added)
     */
    public static List<Component> effects(Job job, BiomeBonus bonus) {
        List<Component> effects = new ArrayList<>();
        if (!bonus.speed().sources().isEmpty()) {
            effects.add(speed(bonus.speed().value()));
        }
        if (!bonus.rareChance().sources().isEmpty()) {
            rareChance(job, bonus.rareChance().value()).ifPresent(effects::add);
        }
        Map<List<ExtraCodecs.TagOrElementLocation>, Double> drops = new LinkedHashMap<>();
        bonus.doubleDrops().forEach(drop -> drops.merge(drop.items(), drop.chance(), Double::sum));
        drops.forEach((items, chance) -> effects.add(doubleDrop(items, chance)));
        return effects;
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
        for (BiomeBonuses.File file : BiomeBonuses.files().values()) {
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
    public static Component speed(double value) {
        return Component.translatable("civmod.biome_bonus.speed", factor(value));
    }

    /**
     * @param job   a Job
     * @param value a rare chance factor
     * @return "Ore chance ×1.1", empty if the Job has no rare group
     */
    public static Optional<Component> rareChance(Job job, double value) {
        return job.profile(ResourceCollectionProfile.class).flatMap(ResourceCollectionProfile::rareGroup)
                .map(group -> Component.translatable("civmod.biome_bonus.rare_chance", group, factor(value)));
    }

    /**
     * @param items  the item ids and {@code #item tags} of a double drop entry
     * @param chance its chance
     * @return "Raw Iron drops +50 %"
     */
    public static Component doubleDrop(List<ExtraCodecs.TagOrElementLocation> items, double chance) {
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
                Optional.ofNullable(BiomeBonuses.files().get(source)).flatMap(BiomeBonuses.File::rareChance).flatMap(value -> rareChance(job, value))
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
