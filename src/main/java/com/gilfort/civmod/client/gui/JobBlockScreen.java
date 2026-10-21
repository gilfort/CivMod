package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.DeliveriesTab;
import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.menu.InfoTab;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.menu.MissionsTab;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.menu.PatternsTab;
import com.gilfort.civmod.menu.RequestsTab;
import com.gilfort.civmod.menu.StationTypesTabData;
import com.gilfort.civmod.menu.StationTypesTab;
import com.gilfort.civmod.menu.StockTab;
import com.gilfort.civmod.menu.StorageTab;
import com.gilfort.civmod.menu.TabData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Screen of a JobBlock: the tab bar of the menu's tabs ({@link JobBlockMenu#tabs()}), each drawn by its view
 * ({@link JobBlockTabView}). The screen only iterates over the tabs: draw and input go to the view of the selected tab.
 */
public class JobBlockScreen extends CivContainerScreen<JobBlockMenu> {

    /** Height of a list row (missions, requests, settlers). */
    static final int ROW_HEIGHT = 23;
    /** Width of the scroll arrow column of a list. */
    static final int SCROLL_COLUMN = 8;

    /** The view of each tab, by tab index (the Patterns tabs share one). */
    private final List<JobBlockTabView> views;
    /** Every view once, in tab order. */
    private final List<JobBlockTabView> distinct;

    /**
     * @param menu      the menu
     * @param inventory the player inventory
     * @param title     the title
     */
    public JobBlockScreen(JobBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, menu.isTall() ? JobBlockMenu.CLERK_HEIGHT : 166, barTabs(menu, false));
        this.views = views(menu.tabs());
        this.distinct = views.stream().distinct().toList();
    }

    private static List<TabBar.Tab> barTabs(JobBlockMenu menu, boolean below) {
        return menu.tabs().stream().filter(tab -> tab.below() == below).map(tab -> new TabBar.Tab(tab.label(), tab.icon())).toList();
    }

    /** One view per tab; the Patterns tabs share one (they share the synced library and the open editor). */
    private List<JobBlockTabView> views(List<JobBlockTab> tabs) {
        List<JobBlockTabView> result = new ArrayList<>();
        @Nullable PatternsTabView patterns = null;
        for (JobBlockTab tab : tabs) {
            if (tab instanceof PatternsTab && patterns == null) {
                patterns = new PatternsTabView(this);
            }
            PatternsTabView library = patterns;
            result.add(switch (tab) {
                case StorageTab storage -> JobBlockTabView.NONE;
                case InfoTab info -> new InfoTabView(this, info);
                case FoundTab found -> new FoundTabView(this, found);
                case MissionsTab missions -> new MissionsTabView(this, missions);
                case StockTab stock -> new StockTabView(this, stock);
                case RequestsTab requests -> new RequestsTabView(this, requests);
                case DeliveriesTab deliveries -> new DeliveriesTabView(this, deliveries);
                case StationTypesTab stations -> new StationTypesTabView(this, stations);
                case PatternsTab job -> library;
            });
        }
        return List.copyOf(result);
    }

    /** The tabs below the panel (a Producing JobBlock's Specializations). */
    @Override
    protected List<TabBar.Tab> bottomTabs() {
        return barTabs(menu, true);
    }

    @Override
    protected boolean isTabShown(int tab) {
        return menu.tabs().get(tab).shown();
    }

    @Override
    protected void initContent() {
        distinct.forEach(JobBlockTabView::init);
    }

    @Override
    protected void extractTabBackground(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        views.get(tab).extractBackground(graphics, menu.tabs().get(tab));
    }

    @Override
    protected void onTabChanged(int tab) {
        distinct.forEach(JobBlockTabView::onTabChanged);
    }

    @Override
    protected void extractTabLabels(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        views.get(tab).extractLabels(graphics, menu.tabs().get(tab), mouseX, mouseY);
    }

    @Override
    protected List<Component> helpLines(int tab) {
        return views.get(tab).helpLines(menu.tabs().get(tab));
    }

    // ---------------------------------------------------------------- input

    private JobBlockTabView activeView() {
        return views.get(menu.getActiveTab());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return activeView().mouseClicked(event) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return activeView().mouseScrolled(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return activeView().mouseDragged(event) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean taken = false;
        for (JobBlockTabView view : distinct) {
            taken |= view.mouseReleased();
        }
        return taken || super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return activeView().keyPressed(event) || super.keyPressed(event);
    }

    // ---------------------------------------------------------------- shared with the tab views

    /**
     * @return every view of the window once, in tab order
     */
    List<JobBlockTabView> views() {
        return distinct;
    }

    /**
     * @return the left edge of the panel
     */
    int left() {
        return leftPos;
    }

    /**
     * @return the top edge of the panel
     */
    int top() {
        return topPos;
    }

    /**
     * @return the panel width
     */
    int panelWidth() {
        return imageWidth;
    }

    /**
     * @return the panel height
     */
    int panelHeight() {
        return imageHeight;
    }

    /**
     * Adds a widget shown only on one tab.
     *
     * @param tab    the tab index
     * @param widget the widget
     * @param <W>    the widget type
     * @return the widget
     */
    <W extends AbstractWidget> W addWidget(int tab, W widget) {
        return addTabWidget(tab, widget);
    }

    /** Rebuilds the widgets (a view changed which widgets it shows). */
    void rebuild() {
        rebuildWidgets();
    }

    /**
     * @param tab the tab to select on client and server
     */
    void select(int tab) {
        selectTab(tab);
    }

    /** Plays the vanilla button click. */
    void clickSound() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /**
     * Presses a menu button on client and server.
     *
     * @param button the button id
     */
    void clickMenuButton(int button) {
        menu.clickMenuButton(minecraft.player, button);
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    // ---------------------------------------------------------------- sync

    /**
     * Puts data the server sent into the open menu's tab and tells the views of the tabs showing it.
     *
     * @param containerId the container id of the payload
     * @param data        the tab data of the open menu, if it has the tab
     * @param value       the data
     * @param <T>         the data type
     */
    private static <T> void receive(int containerId, Function<JobBlockMenu, Optional<TabData<T>>> data, T value) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof JobBlockMenu open) || open.containerId != containerId) {
            return;
        }
        data.apply(open).ifPresent(found -> {
            found.set(value);
            if (minecraft.gui.screen() instanceof JobBlockScreen screen) {
                open.tabs().stream().filter(tab -> tab.synced().filter(found::equals).isPresent()).map(tab -> screen.views.get(tab.index()))
                        .distinct().forEach(JobBlockTabView::dataChanged);
            }
        });
    }

    /**
     * Client handler of {@link ClerkTabData.Sync}: updates the open Clerk menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handleClerkTab(ClerkTabData.Sync payload, IPayloadContext context) {
        receive(payload.containerId(), open -> open.tab(StockTab.class).map(StockTab::data), payload.tab());
    }

    /**
     * Client handler of {@link FoundTabData.Sync}: updates the open Resource Collection JobBlock menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handleFoundTab(FoundTabData.Sync payload, IPayloadContext context) {
        receive(payload.containerId(), open -> open.tab(FoundTab.class).map(FoundTab::data), payload.tab());
    }

    /**
     * Client handler of {@link PatternsTabData.Sync}: updates the open Producing JobBlock menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handlePatternTab(PatternsTabData.Sync payload, IPayloadContext context) {
        receive(payload.containerId(), open -> open.tab(PatternsTab.class).map(PatternsTab::data), payload.tab());
    }

    /**
     * Client handler of {@link StationTypesTabData.Sync}: updates the open JobBlock menu and which Patterns tabs it shows.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handleStationTypeTab(StationTypesTabData.Sync payload, IPayloadContext context) {
        receive(payload.containerId(), open -> open.tab(StationTypesTab.class).map(StationTypesTab::data), payload.tab());
    }

    /**
     * Client handler of {@link JobBlockInfo.Sync}: updates the info of the open JobBlock menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handleInfo(JobBlockInfo.Sync payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof JobBlockMenu open && open.containerId == payload.containerId()) {
            open.setInfo(payload.info());
            if (minecraft.gui.screen() instanceof JobBlockScreen screen) {
                screen.rebuildWidgets();
            }
        }
    }

    // ---------------------------------------------------------------- recipe viewers

    /**
     * The screen areas of the selected tab that take a dragged item (for JEI / REI ghost drag): the Clerk's active stock
     * slots on the Stock tab, or the pattern slots on a Patterns tab.
     *
     * @return target id → absolute area
     */
    public Map<Integer, Rect2i> ghostTargets() {
        return activeView().ghostTargets();
    }

    /**
     * @param x the mouse x position
     * @param y the mouse y position
     * @return the ghost target under the mouse, {@code -1} if none
     */
    public int ghostTargetAt(double x, double y) {
        return activeView().ghostTargetAt(x, y);
    }

    /**
     * Puts a dragged item into a ghost target (not consumed).
     *
     * @param target the target id from {@link #ghostTargets()}
     * @param stack  the item
     * @return whether the target took it
     */
    public boolean acceptGhost(int target, ItemStack stack) {
        return activeView().acceptGhost(target, stack);
    }

    /**
     * Fills a pattern from a recipe viewer's transfer button: the open pattern, else the first empty library slot. Only
     * recipes of a type the working Job follows can be transferred ("Not a recipe this Job follows").
     *
     * @param type       the recipe type of the recipe the viewer shows
     * @param recipe     the recipe the viewer shows
     * @param grid       the items it shows, row by row
     * @param doTransfer whether to transfer (false: only check)
     * @return the reason it cannot, empty if it can
     */
    public Optional<Component> transferPattern(RecipeType<?> type, Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid, boolean doTransfer) {
        for (JobBlockTabView view : distinct) {
            Optional<Optional<Component>> result = view.transfer(type, recipe, grid, doTransfer);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return Optional.of(Component.translatable("civmod.gui.patterns.not_here"));
    }
}
