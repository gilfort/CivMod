package com.gilfort.civmod.compat.rei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.gilfort.civmod.client.ClientInnateRecipes;
import com.gilfort.civmod.client.ClientJobDrops;
import com.gilfort.civmod.client.gui.JobBlockScreen;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.PatternsTab;
import com.gilfort.civmod.settlement.Patterns;

import me.shedaniel.rei.api.client.gui.drag.DraggableStack;
import me.shedaniel.rei.api.client.gui.drag.DraggableStackVisitor;
import me.shedaniel.rei.api.client.gui.drag.DraggedAcceptorResult;
import me.shedaniel.rei.api.client.gui.drag.DraggingContext;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.forge.REIPluginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * REI soft compat: items dragged from REI onto the Clerk's rule slots become the rule's item, onto the Producing
 * JobBlocks' pattern slots a pattern's output or input; the transfer button fills a pattern from a recipe of every
 * recipe type a registered Producing Job follows ({@link Patterns#allRecipeTypes()}). REI finds this class by its
 * annotation; without REI it is never loaded.
 * <p>
 * REI displays name their category and recipe id, not the recipe type: a category counts as a recipe type's if its id
 * is the type's id, {@code <namespace>:plugins/<path>} (REI's vanilla convention) or one of REI's vanilla names that
 * differ ({@code plugins/stone_cutting}, {@code plugins/campfire}).
 * <p>
 * The innate recipes ({@code civmod:job_crafting}) get the display-only "Job Recipes" category
 * ({@link InnateRecipeCategory}) with the Jobs that know them as workstations; the NPC drop pool entries the
 * display-only "Settler Gathering" category ({@link GatheringCategory}) with their Jobs as workstations.
 */
@REIPluginClient
public final class CivModReiPlugin implements REIClientPlugin {

    /** REI's vanilla categories whose path differs from the recipe type's. */
    private static final Map<RecipeType<?>, String> VANILLA_PATHS = Map.of(
            RecipeType.STONECUTTING, "plugins/stone_cutting",
            RecipeType.CAMPFIRE_COOKING, "plugins/campfire");

    @Override
    public void registerCategories(CategoryRegistry registry) {
        registry.add(new InnateRecipeCategory());
        ClientInnateRecipes.allJobs().forEach(job -> registry.addWorkstations(InnateRecipeDisplay.CATEGORY, EntryStacks.of(job.icon())));
        registry.add(new GatheringCategory());
        ClientJobDrops.allJobs().forEach(job -> registry.addWorkstations(GatheringDisplay.CATEGORY, EntryStacks.of(job.icon())));
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        ClientInnateRecipes.recipes().forEach(holder -> registry.add(new InnateRecipeDisplay(holder)));
        ClientJobDrops.drops().forEach(drop -> registry.add(new GatheringDisplay(drop)));
    }

    @Override
    public void registerScreens(ScreenRegistry registry) {
        registry.registerDraggableStackVisitor(new GhostSlots());
    }

    @Override
    public void registerTransferHandlers(TransferHandlerRegistry registry) {
        Map<CategoryIdentifier<?>, RecipeType<?>> categories = new HashMap<>();
        for (RecipeType<?> type : Patterns.allRecipeTypes()) {
            Identifier id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            if (id == null) {
                continue;
            }
            categories.putIfAbsent(CategoryIdentifier.of(id), type);
            categories.putIfAbsent(CategoryIdentifier.of(id.getNamespace(), "plugins/" + id.getPath()), type);
            Optional.ofNullable(VANILLA_PATHS.get(type)).ifPresent(path -> categories.putIfAbsent(CategoryIdentifier.of(id.getNamespace(), path), type));
        }
        registry.register(new PatternTransfer(Map.copyOf(categories)));
    }

    /** The stock slots of the Stock tab and the pattern slots of the Patterns tab as drop targets. */
    private static final class GhostSlots implements DraggableStackVisitor<JobBlockScreen> {

        @Override
        public <R extends Screen> boolean isHandingScreen(R screen) {
            return screen instanceof JobBlockScreen;
        }

        @Override
        public DraggedAcceptorResult acceptDraggedStack(DraggingContext<JobBlockScreen> context, DraggableStack stack) {
            if (!(stack.getStack().getValue() instanceof ItemStack item)) {
                return DraggedAcceptorResult.PASS;
            }
            JobBlockScreen screen = context.getScreen();
            Minecraft minecraft = Minecraft.getInstance();
            double x = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
            double y = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
            int target = screen.ghostTargetAt(x, y);
            return target >= 0 && screen.acceptGhost(target, item) ? DraggedAcceptorResult.CONSUMED : DraggedAcceptorResult.PASS;
        }

        @Override
        public Stream<BoundsProvider> getDraggableAcceptingBounds(DraggingContext<JobBlockScreen> context, DraggableStack stack) {
            return context.getScreen().ghostTargets().values().stream().map(area -> BoundsProvider.ofShape(
                    Shapes.box(area.getX(), area.getY(), 0, area.getX() + area.getWidth(), area.getY() + area.getHeight(), 1)));
        }
    }

    /**
     * The transfer button of a recipe of a Producing recipe type fills a pattern of the open Producing JobBlock menu;
     * failed with "Not a recipe this Job follows" when its working Job does not follow the type.
     */
    private static final class PatternTransfer implements TransferHandler {

        /** The REI categories of the Producing recipe types. */
        private final Map<CategoryIdentifier<?>, RecipeType<?>> categories;

        PatternTransfer(Map<CategoryIdentifier<?>, RecipeType<?>> categories) {
            this.categories = categories;
        }

        @Override
        public Result handle(Context context) {
            RecipeType<?> type = categories.get(context.getDisplay().getCategoryIdentifier());
            if (type == null || !(context.getMenu() instanceof JobBlockMenu menu) || menu.tab(PatternsTab.class).isEmpty()
                    || !(context.getContainerScreen() instanceof JobBlockScreen screen)) {
                return Result.createNotApplicable();
            }
            List<Item> grid = new ArrayList<>();
            for (EntryIngredient input : context.getDisplay().getInputEntries()) {
                grid.add(input.stream().map(entry -> entry.getValue()).filter(ItemStack.class::isInstance).map(ItemStack.class::cast)
                        .findFirst().map(ItemStack::getItem).orElse(Items.AIR));
            }
            Optional<Component> error = screen.transferPattern(type, context.getDisplay().getDisplayLocation()
                    .map(id -> ResourceKey.create(Registries.RECIPE, id)), grid.subList(0, Math.min(Pattern.GRID, grid.size())),
                    context.isActuallyCrafting());
            return error.map(Result::createFailed).orElseGet(Result::createSuccessful);
        }
    }
}
