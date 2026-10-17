package com.gilfort.civmod.compat.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.client.gui.JobBlockScreen;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.registry.ModMenuTypes;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
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
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI soft compat: items dragged from JEI onto the Clerk's rule slots become the rule's item, onto the Craftsperson's
 * pattern slots a pattern's output or input; the recipe transfer button of a crafting recipe fills a Craftsperson
 * pattern. JEI finds this class by its annotation; without JEI it is never loaded.
 */
@JeiPlugin
public final class CivModJeiPlugin implements IModPlugin {

    @Override
    public Identifier getPluginUid() {
        return CivMod.id("jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(JobBlockScreen.class, new GhostSlots());
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(new PatternTransfer(registration.getTransferHelper()), RecipeTypes.CRAFTING);
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

    /** The transfer button of a crafting recipe fills a pattern of the open Craftsperson menu. */
    private static final class PatternTransfer implements IRecipeTransferHandler<JobBlockMenu, RecipeHolder<CraftingRecipe>> {

        private final IRecipeTransferHandlerHelper helper;

        PatternTransfer(IRecipeTransferHandlerHelper helper) {
            this.helper = helper;
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
        public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
            return RecipeTypes.CRAFTING;
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(JobBlockMenu container, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots,
                Player player, boolean maxTransfer, boolean doTransfer) {
            if (!JobBlockMenu.isCraftsperson(container.getInfo()) || !(Minecraft.getInstance().gui.screen() instanceof JobBlockScreen screen)) {
                // Other JobBlocks have no patterns: no button.
                return helper.createInternalError();
            }
            List<Item> grid = new ArrayList<>();
            for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
                grid.add(slot.getDisplayedItemStack().map(ItemStack::getItem).orElse(Items.AIR));
            }
            Optional<Component> error = screen.transferPattern(Optional.of(recipe.id()), grid.subList(0, Math.min(Pattern.GRID, grid.size())),
                    doTransfer);
            return error.map(helper::createUserErrorWithTooltip).orElse(null);
        }
    }
}
