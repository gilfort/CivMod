package com.gilfort.civmod.client.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.menu.JobBlockTab;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * The client view of a {@link JobBlockTab} in the {@link JobBlockScreen}: what the tab draws and how it takes input.
 * The screen hands every call to the view of the selected tab, except {@link #init}, {@link #onTabChanged},
 * {@link #mouseReleased} and the Info tab contributions, which every view of the window gets. Several tabs may share
 * one view (the Patterns tabs of a JobBlock).
 */
interface JobBlockTabView {

    /** A tab without content of its own (Storage: only slots). */
    JobBlockTabView NONE = new JobBlockTabView() {
    };

    /** Adds the view's widgets (called on every widget rebuild). */
    default void init() {
    }

    /**
     * Draws background content of the tab (absolute coordinates).
     *
     * @param graphics the graphics extractor
     * @param tab      the tab
     */
    default void extractBackground(GuiGraphicsExtractor graphics, JobBlockTab tab) {
    }

    /**
     * Draws the tab (coordinates relative to the panel).
     *
     * @param graphics the graphics extractor
     * @param tab      the tab
     * @param mouseX   the mouse x position
     * @param mouseY   the mouse y position
     */
    default void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab tab, int mouseX, int mouseY) {
    }

    /**
     * @param tab the tab
     * @return the tips and usage info of the tab, for the help icon
     */
    default List<Component> helpLines(JobBlockTab tab) {
        return List.of();
    }

    /** Another tab was selected. */
    default void onTabChanged() {
    }

    /**
     * @param event the click
     * @return whether the click was taken
     */
    default boolean mouseClicked(MouseButtonEvent event) {
        return false;
    }

    /**
     * @param mouseX  the mouse x position
     * @param mouseY  the mouse y position
     * @param scrollY the wheel movement
     * @return whether the wheel was taken
     */
    default boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        return false;
    }

    /**
     * @param event the drag
     * @return whether the drag was taken
     */
    default boolean mouseDragged(MouseButtonEvent event) {
        return false;
    }

    /**
     * @return whether the release ended something the view was doing
     */
    default boolean mouseReleased() {
        return false;
    }

    /**
     * @param event the key
     * @return whether the key was taken
     */
    default boolean keyPressed(KeyEvent event) {
        return false;
    }

    /** The server sent new data of the tab ({@link JobBlockTab#synced()}). */
    default void dataChanged() {
    }

    // ---------------------------------------------------------------- recipe viewers (JEI / REI)

    /**
     * @return target id → absolute area of the screen areas that take a dragged item
     */
    default Map<Integer, Rect2i> ghostTargets() {
        return new LinkedHashMap<>();
    }

    /**
     * @param x the mouse x position
     * @param y the mouse y position
     * @return the ghost target under the mouse, {@code -1} if none
     */
    default int ghostTargetAt(double x, double y) {
        return -1;
    }

    /**
     * Puts a dragged item into a ghost target (not consumed).
     *
     * @param target the target id from {@link #ghostTargets()}
     * @param stack  the item
     * @return whether the target took it
     */
    default boolean acceptGhost(int target, ItemStack stack) {
        return false;
    }

    /**
     * Takes a recipe from a recipe viewer's transfer button, from any tab of the window.
     *
     * @param type       the recipe type of the recipe the viewer shows
     * @param recipe     the recipe the viewer shows
     * @param grid       the items it shows, row by row
     * @param doTransfer whether to transfer (false: only check)
     * @return empty if the view takes no recipes, else the reason it cannot (empty if it can)
     */
    default Optional<Optional<Component>> transfer(RecipeType<?> type, Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid,
            boolean doTransfer) {
        return Optional.empty();
    }

    // ---------------------------------------------------------------- contributions to the Info tab

    /**
     * Adds the view's widgets to the Info tab (called on every widget rebuild).
     *
     * @param info the Info tab's index
     */
    default void initInfo(int info) {
    }

    /**
     * Draws the view's lines of the Info tab below the Job lines.
     *
     * @param graphics the graphics extractor (panel coordinates)
     * @param y        the y of the first line
     * @param width    the line width
     * @param color    the text colour
     * @param mouseX   the mouse x position
     * @param mouseY   the mouse y position
     * @return the y below the lines
     */
    default int extractInfo(GuiGraphicsExtractor graphics, int y, int width, int color, int mouseX, int mouseY) {
        return y;
    }

    /**
     * @return the view's help lines of the Info tab
     */
    default List<Component> infoHelpLines() {
        return List.of();
    }

    /**
     * @return whether the Info tab offers to free the JobBlock's only place
     */
    default boolean offersUnassign() {
        return true;
    }
}
