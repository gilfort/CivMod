package com.gilfort.civmod.compat.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.client.gui.JobBlockScreen;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * JEI soft compat: items dragged from JEI onto the Clerk's rule slots become the rule's item. JEI finds this class by
 * its annotation; without JEI it is never loaded.
 */
@JeiPlugin
public final class CivModJeiPlugin implements IModPlugin {

    @Override
    public Identifier getPluginUid() {
        return CivMod.id("jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(JobBlockScreen.class, new RuleSlots());
    }

    /** The active rule slots of the Rules tab as ghost targets. */
    private static final class RuleSlots implements IGhostIngredientHandler<JobBlockScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(JobBlockScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
            Optional<ItemStack> stack = ingredient.getItemStack();
            List<Target<I>> targets = new ArrayList<>();
            if (stack.isEmpty()) {
                return targets;
            }
            for (Map.Entry<Integer, Rect2i> entry : screen.ruleTargets().entrySet()) {
                int slot = entry.getKey();
                Rect2i area = entry.getValue();
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return area;
                    }

                    @Override
                    public void accept(I ignored) {
                        screen.setRuleItem(slot, stack.get());
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {
        }
    }
}
