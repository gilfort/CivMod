package com.gilfort.civmod.compat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.gilfort.civmod.client.ClientJobDrops;
import com.gilfort.civmod.job.JobDrops;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

/**
 * The layout of the display-only "Settler Gathering" category shared by JEI and REI (vault
 * {@code Systems/Resource Collection Jobs.md}, decisions 2026-10-29): one NPC drop pool entry with the block (or spawn
 * egg) it drops from, an arrow, its result with the chance, the entry's own tool; below them the icons of its Jobs and,
 * for {@code replace}, "Replaces normal drops". The normal loot of the block or animal is not shown. Positions are
 * relative to the category's top left corner, in pixels.
 */
public final class GatheringLayout {

    /** Width of the category. */
    public static final int WIDTH = 116;
    /** Height of the category. */
    public static final int HEIGHT = 52;
    /** One slot. */
    public static final int SLOT = 18;
    /** The row of the from, result and tool slots. */
    public static final int ROW_Y = 1;
    /** The from slot. */
    public static final int FROM_X = 1;
    /** The arrow. */
    public static final int ARROW_X = 22;
    /** The result slot. */
    public static final int RESULT_X = 48;
    /** The chance text beside the result. */
    public static final int CHANCE_X = 68;
    /** The baseline offset of a text in a slot row. */
    public static final int TEXT_Y = 6;
    /** The tool slot. */
    public static final int TOOL_X = 97;
    /** The row of the Job icons. */
    public static final int JOBS_Y = 22;
    /** The "Replaces normal drops" line. */
    public static final int NOTE_Y = 42;
    /** How many Job icons fit into the row. */
    public static final int MAX_JOBS = WIDTH / SLOT;
    /** Text colour of the chance and the note (the recipe viewers' grey). */
    public static final int TEXT_COLOR = 0xFF404040;

    /** Title of the category ("Settler Gathering"). */
    public static final String TITLE = "civmod.recipe_viewer.settler_gathering";
    /** Tooltip line of the from slot. */
    public static final String FROM_LINE = TITLE + ".from";
    /** Tooltip line of the tool slot. */
    public static final String TOOL_LINE = TITLE + ".tool";
    /** The chance text ("5 %"). */
    public static final String CHANCE = TITLE + ".chance";
    /** Tooltip line of a count range ("Count: 1–3"). */
    public static final String COUNT_LINE = TITLE + ".count";
    /** The note of a {@code replace} entry. */
    public static final String REPLACES = TITLE + ".replaces";

    private GatheringLayout() {
    }

    /**
     * @param drop an entry
     * @return whether it drops from entity types (one of its Jobs is the Animal Farmer)
     */
    public static boolean entities(JobDrops.Drop drop) {
        return ClientJobDrops.jobs(drop).stream().anyMatch(job -> JobDrops.entityDrops(job).orElse(false));
    }

    /**
     * @param drop an entry
     * @return the icons of the from slot: the spawn eggs of the entity types, else the items of the blocks
     */
    public static List<ItemStack> fromIcons(JobDrops.Drop drop) {
        boolean entities = entities(drop);
        Set<Item> items = new LinkedHashSet<>();
        for (ExtraCodecs.TagOrElementLocation from : drop.entry().from()) {
            if (entities) {
                (from.tag() ? BuiltInRegistries.ENTITY_TYPE.get(TagKey.create(Registries.ENTITY_TYPE, from.id())).stream().flatMap(set -> set.stream())
                        : BuiltInRegistries.ENTITY_TYPE.get(from.id()).stream())
                        .forEach(type -> SpawnEggItem.byId(type.value()).ifPresent(egg -> items.add(new ItemStack(egg).getItem())));
            } else {
                (from.tag() ? BuiltInRegistries.BLOCK.get(TagKey.create(Registries.BLOCK, from.id())).stream().flatMap(set -> set.stream())
                        : BuiltInRegistries.BLOCK.get(from.id()).stream())
                        .forEach(block -> items.add(block.value().asItem()));
            }
        }
        items.remove(Items.AIR);
        return items.stream().map(ItemStack::new).toList();
    }

    /**
     * @param drop an entry
     * @return the tooltip lines of the from slot ("Gathered by settlers from", the block state if any)
     */
    public static List<Component> fromLines(JobDrops.Drop drop) {
        List<Component> lines = new ArrayList<>(List.of(Component.translatable(FROM_LINE)));
        drop.entry().state().flatMap(InnateRecipeLayout::stateLine).ifPresent(lines::add);
        return lines;
    }

    /**
     * @param drop an entry
     * @return the result stack (with the count of a fixed count, else one)
     */
    public static ItemStack result(JobDrops.Drop drop) {
        JobDrops.Count count = drop.entry().result().count();
        return new ItemStack(drop.entry().result().item(), count.min() == count.max() ? count.min() : 1);
    }

    /**
     * @param drop an entry
     * @return the tooltip lines of the result slot: the count range, if any
     */
    public static List<Component> resultLines(JobDrops.Drop drop) {
        JobDrops.Count count = drop.entry().result().count();
        return count.min() == count.max() ? List.of() : List.of(Component.translatable(COUNT_LINE, count.min(), count.max()));
    }

    /**
     * @param drop an entry
     * @return the chance text ("5 %")
     */
    public static Component chance(JobDrops.Drop drop) {
        return Component.translatable(CHANCE, drop.entry().percent());
    }

    /**
     * @param drop an entry
     * @return the items of the entry's own tool, empty without one
     */
    public static List<ItemStack> toolIcons(JobDrops.Drop drop) {
        return drop.entry().tool().stream().flatMap(tool -> tool.tag()
                ? BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, tool.id())).stream().flatMap(set -> set.stream()).map(ItemStack::new)
                : BuiltInRegistries.ITEM.get(tool.id()).stream().map(ItemStack::new)).toList();
    }

    /**
     * @param drop an entry
     * @return "Replaces normal drops" for a {@code replace} entry, else nothing
     */
    public static List<Component> note(JobDrops.Drop drop) {
        return drop.entry().mode() == JobDrops.Mode.REPLACE ? List.of(Component.translatable(REPLACES)) : List.of();
    }

    /**
     * @param index a Job icon's index
     * @return the x of its slot in the Job row
     */
    public static int jobX(int index) {
        return 1 + index * SLOT;
    }
}
