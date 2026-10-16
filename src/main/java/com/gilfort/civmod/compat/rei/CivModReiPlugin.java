package com.gilfort.civmod.compat.rei;

import java.util.stream.Stream;

import com.gilfort.civmod.client.gui.JobBlockScreen;

import me.shedaniel.rei.api.client.gui.drag.DraggableStack;
import me.shedaniel.rei.api.client.gui.drag.DraggableStackVisitor;
import me.shedaniel.rei.api.client.gui.drag.DraggedAcceptorResult;
import me.shedaniel.rei.api.client.gui.drag.DraggingContext;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.forge.REIPluginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * REI soft compat: items dragged from REI onto the Clerk's rule slots become the rule's item. REI finds this class by
 * its annotation; without REI it is never loaded.
 */
@REIPluginClient
public final class CivModReiPlugin implements REIClientPlugin {

    @Override
    public void registerScreens(ScreenRegistry registry) {
        registry.registerDraggableStackVisitor(new RuleSlots());
    }

    /** The active rule slots of the Rules tab as drop targets. */
    private static final class RuleSlots implements DraggableStackVisitor<JobBlockScreen> {

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
            int slot = screen.ruleSlotAt(x, y);
            return slot >= 0 && screen.setRuleItem(slot, item) ? DraggedAcceptorResult.CONSUMED : DraggedAcceptorResult.PASS;
        }

        @Override
        public Stream<BoundsProvider> getDraggableAcceptingBounds(DraggingContext<JobBlockScreen> context, DraggableStack stack) {
            return context.getScreen().ruleTargets().values().stream().map(area -> BoundsProvider.ofShape(
                    Shapes.box(area.getX(), area.getY(), 0, area.getX() + area.getWidth(), area.getY() + area.getHeight(), 1)));
        }
    }
}
