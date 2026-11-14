package com.gilfort.civmod.compat;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.gilfort.civmod.api.DropOutcome;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.client.ClientDropPools;
import com.gilfort.civmod.job.DropDraw;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.mojang.serialization.JsonOps;

import net.minecraft.advancements.predicates.StatePropertiesPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The layout of the display-only "Job Recipes" category shared by JEI and REI (vault {@code Systems/Producing Jobs.md}):
 * the inputs in a 3×3 grid, an arrow, the result; below them the tool and the station, below the result what a
 * Resource Collection recipe requires; at the bottom the icons of the Jobs that know the recipe. Positions are relative
 * to the category's top left corner, in pixels.
 */
public final class InnateRecipeLayout {

    /** Width of the category. */
    public static final int WIDTH = 116;
    /** Height of the category. */
    public static final int HEIGHT = 76;
    /** One slot. */
    public static final int SLOT = 18;
    /** The arrow between inputs and result. */
    public static final int ARROW_X = 61;
    /** The result slot. */
    public static final int OUTPUT_X = 95;
    /** The row of the arrow and the result: the middle input row. */
    public static final int MIDDLE_Y = 19;
    /** The tool slot. */
    public static final int TOOL_X = 59;
    /** The station slot. */
    public static final int STATION_X = 77;
    /** The row of the tool and the station. */
    public static final int EXTRAS_Y = 38;
    /** The row of the Job icons. */
    public static final int JOBS_Y = 58;
    /** How many Job icons fit into the row. */
    public static final int MAX_JOBS = WIDTH / SLOT;

    /** Title of the category ("Job Recipes"). */
    public static final String TITLE = "civmod.recipe_viewer.innate_recipes";
    /** Tooltip line of the tool slot. */
    public static final String TOOL_LINE = TITLE + ".tool";
    /** Tooltip line of the station slot. */
    public static final String STATION_LINE = TITLE + ".station";
    /** Tooltip line of the station slot of a recipe with {@code station_after}: what the station turns into. */
    public static final String STATION_AFTER_LINE = TITLE + ".station_after";
    /** Prefix of the names of the filled cauldrons, followed by the block's id. */
    private static final String CAULDRON_NAME = TITLE + ".block.";
    /** The slot of what a Resource Collection recipe requires: below the result. */
    public static final int REQUIRES_X = OUTPUT_X;
    /** Tooltip line of the requires slot. */
    public static final String REQUIRES_LINE = TITLE + ".requires";
    /** Tooltip line of a required block state. */
    public static final String STATE_LINE = TITLE + ".state";
    /** Tooltip line of an item a {@code drops} recipe can give: its chance and the skill level it needs. */
    public static final String DROP_LINE = TITLE + ".drop";
    /** Tooltip line of an item that only a {@code tag_result} can give: the chance of the outcome, its level and the tag. */
    public static final String DROP_TAG_LINE = TITLE + ".drop_tag";

    private static final DecimalFormat PERCENT = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private InnateRecipeLayout() {
    }

    /**
     * @param recipe an innate recipe
     * @return the tooltip lines of the station slot: that it is worked there, the name of each station ("Lava Cauldron",
     *         which the slot's icon cannot tell from a cauldron) and, with {@code station_after}, what the station turns
     *         into ("Afterwards the station turns into: Water Cauldron")
     */
    public static List<Component> stationLines(JobCraftingRecipe recipe) {
        List<Component> lines = new ArrayList<>(List.of(Component.translatable(STATION_LINE)));
        InnateRecipes.stations(recipe).forEach(station -> lines.add(PatternRecipes.tagName(station, Component.literal(station.location().getPath()))));
        recipe.stationAfter().ifPresent(after -> lines.add(Component.translatable(STATION_AFTER_LINE, blockName(after.getBlock()))));
        return lines;
    }

    /**
     * @param block a block
     * @return its name; a filled cauldron by what fills it ("Lava Cauldron"), which vanilla names "Cauldron" like the
     *         empty one
     */
    static Component blockName(Block block) {
        if (!(block instanceof AbstractCauldronBlock) || block == Blocks.CAULDRON) {
            return block.getName();
        }
        return Component.translatableWithFallback(CAULDRON_NAME + BuiltInRegistries.BLOCK.getKey(block).getPath(), block.getName().getString());
    }

    /**
     * @param requires what a Resource Collection recipe requires
     * @return the icons of the requires slot: the spawn eggs of the entity types, else the items of the blocks
     */
    public static List<ItemStack> requiresIcons(JobCraftingRecipe.Requirement requires) {
        List<ItemStack> icons = new ArrayList<>();
        requires.entity().ifPresent(types -> types.forEach(type -> SpawnEggItem.byId(type.value()).ifPresent(egg -> icons.add(new ItemStack(egg)))));
        requires.block().ifPresent(blocks -> blocks.forEach(block -> {
            ItemStack item = new ItemStack(block.value().asItem());
            if (!item.isEmpty()) {
                icons.add(item);
            }
        }));
        return icons;
    }

    /**
     * @param requires what a Resource Collection recipe requires
     * @return the tooltip lines of the requires slot ("Requires nearby", the block state if any)
     */
    public static List<Component> requiresLines(JobCraftingRecipe.Requirement requires) {
        List<Component> lines = new ArrayList<>(List.of(Component.translatable(REQUIRES_LINE)));
        requires.state().flatMap(InnateRecipeLayout::stateLine).ifPresent(lines::add);
        return lines;
    }

    /**
     * @param state a block-state predicate
     * @return its tooltip line ("State: age: 3"), empty if it cannot be shown
     */
    public static Optional<Component> stateLine(StatePropertiesPredicate state) {
        return StatePropertiesPredicate.CODEC.encodeStart(JsonOps.INSTANCE, state).result()
                .map(json -> Component.translatable(STATE_LINE, json.toString().replaceAll("[{}\"]", "").replace(":", ": ").replace(",", ", ")));
    }

    /**
     * @param input an ingredient of an innate recipe
     * @return what its input slot cycles through: every item it accepts, each as a stack of the ingredient's count
     */
    public static List<ItemStack> inputStacks(JobCraftingRecipe.Input input) {
        return input.ingredient().items().map(holder -> new ItemStack(holder, input.count())).toList();
    }

    /**
     * @param recipe an innate recipe
     * @return what the result slot shows: its result, then every kind of item its {@code drops} can give (each once),
     *         the members of a {@code tag_result} tag included (the items the recipe uses up are not among them)
     */
    public static List<ItemStack> outputs(JobCraftingRecipe recipe) {
        List<ItemStack> outputs = new ArrayList<>();
        recipe.result().ifPresent(result -> outputs.add(result.create()));
        recipe.drops().ifPresent(drops -> ClientDropPools.outcomes(drops).stream().flatMap(outcome -> outcome.results().stream())
                .map(ItemStackTemplate::create)
                .filter(stack -> outputs.stream().noneMatch(shown -> ItemStack.isSameItemSameComponents(shown, stack))).forEach(outputs::add));
        recipe.drops().ifPresent(drops -> ClientDropPools.outcomes(drops).stream().flatMap(outcome -> outcome.tagResult().stream())
                .flatMap(tagResult -> tagResult.candidates(usedUp(recipe)).stream()).map(ItemStack::new)
                .filter(stack -> outputs.stream().noneMatch(shown -> ItemStack.isSameItemSameComponents(shown, stack))).forEach(outputs::add));
        return outputs;
    }

    private static List<Item> usedUp(JobCraftingRecipe recipe) {
        return recipe.inputs().stream().flatMap(input -> input.ingredient().items()).map(Holder::value).toList();
    }

    /**
     * @param recipe an innate recipe
     * @param shown  an item of the result slot
     * @return the tooltip lines of that item for the outcomes of the recipe's {@code drops} that give it: its chance and
     *         the level it needs, one line per outcome (none for the result and for items the draw cannot give)
     */
    public static List<Component> dropLines(JobCraftingRecipe recipe, ItemStack shown) {
        if (recipe.drops().isEmpty()) {
            return List.of();
        }
        List<DropOutcome> outcomes = ClientDropPools.outcomes(recipe.drops().get());
        List<Component> lines = new ArrayList<>(outcomes.stream()
                .filter(outcome -> outcome.results().stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(stack.create(), shown)))
                .map(outcome -> (Component) Component.translatable(DROP_LINE, PERCENT.format(DropDraw.percentAtUnlock(outcomes, outcome)), outcome.minLevel()))
                .toList());
        for (DropOutcome outcome : outcomes) {
            outcome.tagResult().filter(tagResult -> tagResult.candidates(usedUp(recipe)).contains(shown.getItem())).ifPresent(tagResult -> lines.add(
                    Component.translatable(DROP_TAG_LINE, PERCENT.format(DropDraw.percentAtUnlock(outcomes, outcome)), outcome.minLevel(),
                            "#" + tagResult.tag().location())));
        }
        return lines;
    }

    /**
     * @param index an input's index
     * @return the x of its slot in the 3×3 grid
     */
    public static int inputX(int index) {
        return 1 + (index % 3) * SLOT;
    }

    /**
     * @param index an input's index
     * @return the y of its slot in the 3×3 grid
     */
    public static int inputY(int index) {
        return 1 + (index / 3) * SLOT;
    }

    /**
     * @param index a Job icon's index
     * @return the x of its slot in the Job row
     */
    public static int jobX(int index) {
        return 1 + index * SLOT;
    }
}
