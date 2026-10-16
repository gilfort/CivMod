package com.gilfort.civmod.compat.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.gilfort.civmod.client.gui.JobBlockScreen;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.JobBlockMenu;

import me.shedaniel.rei.api.client.gui.drag.DraggableStack;
import me.shedaniel.rei.api.client.gui.drag.DraggableStackVisitor;
import me.shedaniel.rei.api.client.gui.drag.DraggedAcceptorResult;
import me.shedaniel.rei.api.client.gui.drag.DraggingContext;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.forge.REIPluginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * REI soft compat: items dragged from REI onto the Clerk's rule slots become the rule's item, onto the Craftsperson's
 * pattern slots a pattern's output or input; the transfer button of a crafting recipe fills a Craftsperson pattern. REI
 * finds this class by its annotation; without REI it is never loaded.
 */
@REIPluginClient
public final class CivModReiPlugin implements REIClientPlugin {

    /** REI's category of crafting table recipes. */
    private static final CategoryIdentifier<Display> CRAFTING = CategoryIdentifier.of("minecraft", "plugins/crafting");

    @Override
    public void registerScreens(ScreenRegistry registry) {
        registry.registerDraggableStackVisitor(new GhostSlots());
    }

    @Override
    public void registerTransferHandlers(TransferHandlerRegistry registry) {
        registry.register(new PatternTransfer());
    }

    /** The rule slots of the Rules tab and the pattern slots of the Patterns tab as drop targets. */
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

    /** The transfer button of a crafting recipe fills a pattern of the open Craftsperson menu. */
    private static final class PatternTransfer implements TransferHandler {

        @Override
        public Result handle(Context context) {
            if (!(context.getMenu() instanceof JobBlockMenu menu) || !JobBlockMenu.isCraftsperson(menu.getInfo())
                    || !(context.getContainerScreen() instanceof JobBlockScreen screen)
                    || !CRAFTING.equals(context.getDisplay().getCategoryIdentifier())) {
                return Result.createNotApplicable();
            }
            List<Item> grid = new ArrayList<>();
            for (EntryIngredient input : context.getDisplay().getInputEntries()) {
                grid.add(input.stream().map(entry -> entry.getValue()).filter(ItemStack.class::isInstance).map(ItemStack.class::cast)
                        .findFirst().map(ItemStack::getItem).orElse(Items.AIR));
            }
            Optional<Component> error = screen.transferPattern(context.getDisplay().getDisplayLocation()
                    .map(id -> ResourceKey.create(Registries.RECIPE, id)), grid.subList(0, Math.min(Pattern.GRID, grid.size())),
                    context.isActuallyCrafting());
            return error.map(Result::createFailed).orElseGet(Result::createSuccessful);
        }
    }
}
