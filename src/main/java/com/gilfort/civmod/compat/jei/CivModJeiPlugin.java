package com.gilfort.civmod.compat.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.client.ClientInnateRecipes;
import com.gilfort.civmod.client.gui.JobBlockScreen;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.PatternsTab;
import com.gilfort.civmod.registry.ModMenuTypes;
import com.gilfort.civmod.settlement.Patterns;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * JEI soft compat: items dragged from JEI onto the Clerk's rule slots become the rule's item, onto the Producing
 * JobBlocks' pattern slots a pattern's output or input; the recipe transfer button fills a pattern from a recipe of
 * every recipe type a registered Producing Job follows ({@link Patterns#allRecipeTypes()}: crafting, stonecutting, an
 * addon's modded type, …). The innate recipes ({@code civmod:job_crafting}) get the display-only "Job Recipes"
 * category ({@link InnateRecipeCategory}) with the Jobs that know them as catalysts. JEI finds this class by its
 * annotation; without JEI it is never loaded.
 */
@JeiPlugin
public final class CivModJeiPlugin implements IModPlugin {

    @Override
    public Identifier getPluginUid() {
        return CivMod.id("jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new InnateRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(InnateRecipeCategory.TYPE, ClientInnateRecipes.recipes());
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        ClientInnateRecipes.allJobs().forEach(job -> registration.addCraftingStation(InnateRecipeCategory.TYPE, job.icon()));
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(JobBlockScreen.class, new GhostSlots());
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        // One handler per recipe type, for the JEI category of that type (its uid is the recipe type's id, as for JEI's
        // own vanilla categories); whether the open JobBlock's working Job follows it is checked per click.
        for (RecipeType<?> type : Patterns.allRecipeTypes()) {
            register(registration, type);
        }
    }

    private static <R extends Recipe<?>> void register(IRecipeTransferRegistration registration, RecipeType<R> type) {
        IRecipeHolderType<R> jeiType = IRecipeType.create(type);
        registration.addRecipeTransferHandler(new PatternTransfer<>(registration.getTransferHelper(), jeiType), jeiType);
    }

    /** The stock slots of the Stock tab and the pattern slots of the Patterns tab as ghost targets. */
    private static final class GhostSlots implements IGhostIngredientHandler<JobBlockScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(JobBlockScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
            Optional<ItemStack> stack = ingredient.getItemStack();
            List<Target<I>> targets = new ArrayList<>();
            if (stack.isEmpty()) {
                return targets;
            }
            for (Map.Entry<Integer, Rect2i> entry : screen.ghostTargets().entrySet()) {
                int target = entry.getKey();
                Rect2i area = entry.getValue();
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return area;
                    }

                    @Override
                    public void accept(I ignored) {
                        screen.acceptGhost(target, stack.get());
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {
        }
    }

    /**
     * The transfer button of a recipe of one recipe type fills a pattern of the open Producing JobBlock menu; disabled
     * with "Not a recipe this Job follows" when its working Job does not follow the type.
     */
    private static final class PatternTransfer<R extends Recipe<?>> implements IRecipeTransferHandler<JobBlockMenu, RecipeHolder<R>> {

        private final IRecipeTransferHandlerHelper helper;
        private final IRecipeHolderType<R> type;

        PatternTransfer(IRecipeTransferHandlerHelper helper, IRecipeHolderType<R> type) {
            this.helper = helper;
            this.type = type;
        }

        @Override
        public Class<? extends JobBlockMenu> getContainerClass() {
            return JobBlockMenu.class;
        }

        @Override
        public Optional<MenuType<JobBlockMenu>> getMenuType() {
            return Optional.of(ModMenuTypes.JOB_BLOCK.get());
        }

        @Override
        public IRecipeType<RecipeHolder<R>> getRecipeType() {
            return type;
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(JobBlockMenu container, RecipeHolder<R> recipe, IRecipeSlotsView slots,
                Player player, boolean maxTransfer, boolean doTransfer) {
            if (container.tab(PatternsTab.class).isEmpty() || !(Minecraft.getInstance().gui.screen() instanceof JobBlockScreen screen)) {
                // Other JobBlocks have no patterns: no button.
                return helper.createInternalError();
            }
            List<Item> grid = new ArrayList<>();
            for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
                grid.add(slot.getDisplayedItemStack().map(ItemStack::getItem).orElse(Items.AIR));
            }
            Optional<Component> error = screen.transferPattern(recipe.value().getType(), Optional.of(recipe.id()),
                    grid.subList(0, Math.min(Pattern.GRID, grid.size())), doTransfer);
            return error.map(helper::createUserErrorWithTooltip).orElse(null);
        }
    }
}
