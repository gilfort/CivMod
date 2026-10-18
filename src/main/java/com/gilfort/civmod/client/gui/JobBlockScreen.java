package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.job.JobProfile;
import com.gilfort.civmod.menu.ClerkTab;
import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.PatternTab;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementRules;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Screen of a JobBlock: storage tab and info tab (job, level, skill cap, settlement status, upgrade button). Job tabs
 * are drawn by their views: the Mission Board's missions tab ({@link MissionTabView}), the Clerk's Stock and Requests
 * tabs ({@link ClerkTabView}), the Craftsperson's Patterns tab ({@link PatternTabView}) and the Found tab of Resource
 * Collection JobBlocks ({@link FoundTabView}).
 * <p>
 * Unconnected JobBlocks show the info greyed out with a red hint; storage keeps working.
 */
public class JobBlockScreen extends CivContainerScreen<JobBlockMenu> {

    /** Height of a list row (missions, requests, settlers). */
    static final int ROW_HEIGHT = 23;
    /** Width of the scroll arrow column of a list. */
    static final int SCROLL_COLUMN = 8;

    private static final int COST_SLOTS = 7;
    private static final int COST_X = CivGui.PADDING + 9;
    private static final int MISSING_TINT = 0x80FF2020;
    private static final int LINE_HEIGHT = 11;
    /** Top of the first Job line in the info tab (below job, level and settlement). */
    private static final int JOB_LINES_Y = 22 + 3 * LINE_HEIGHT;
    /** Size of the automatic assignment checkbox at the end of a Job line. */
    private static final int AUTO_BOX = 9;

    private final MissionTabView missions;
    private final ClerkTabView clerk;
    private final PatternTabView patterns;
    private final FoundTabView found;
    private int costOffset;
    private int costRowY = -1;

    /**
     * @param menu      the menu
     * @param inventory the player inventory
     * @param title     the title
     */
    public JobBlockScreen(JobBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, menu.isTall() ? JobBlockMenu.CLERK_HEIGHT : 166, tabs(menu));
        missions = new MissionTabView(this);
        clerk = new ClerkTabView(this);
        patterns = new PatternTabView(this);
        found = new FoundTabView(this);
    }

    private static List<TabBar.Tab> tabs(JobBlockMenu menu) {
        List<TabBar.Tab> tabs = new ArrayList<>(List.of(
                new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.storage"), new ItemStack(Items.CHEST)),
                new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.info"), new ItemStack(Items.BOOK))));
        if (JobBlockMenu.isMissionBoard(menu.getInfo())) {
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.missions"), new ItemStack(Items.MAP)));
        }
        if (JobBlockMenu.isClerk(menu.getInfo())) {
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.clerk.tab.stock"), new ItemStack(Items.WRITABLE_BOOK)));
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.clerk.tab.requests"), new ItemStack(Items.PAPER)));
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.clerk.tab.deliveries"), new ItemStack(Items.BUNDLE)));
        }
        if (JobBlockMenu.isCraftsperson(menu.getInfo())) {
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.patterns.tab"), new ItemStack(Items.CRAFTING_TABLE)));
        }
        if (JobBlockMenu.hasFoundTab(menu.getInfo())) {
            tabs.add(new TabBar.Tab(Component.translatable("civmod.gui.job_block.tab.found"), new ItemStack(Items.SPYGLASS)));
        }
        return tabs;
    }

    @Override
    protected void initContent() {
        JobBlockInfo current = menu.getInfo();
        if (JobBlockMenu.isClerk(current)) {
            clerk.init();
        }
        if (JobBlockMenu.isCraftsperson(current)) {
            patterns.init();
        }
        boolean max = current.level() >= SettlementRules.MAX_LEVEL;
        Component upgradeLabel = max ? Component.translatable("civmod.gui.job_block.upgrade.max")
                : Component.translatable("civmod.gui.job_block.upgrade.to", current.level() + 1);
        Button upgrade = addTabWidget(JobBlockMenu.TAB_INFO, Button.builder(upgradeLabel, button -> {
            clickMenuButton(JobBlockMenu.BUTTON_UPGRADE);
            rebuildWidgets();
        }).bounds(leftPos + CivGui.PADDING, topPos + imageHeight - 28, imageWidth - 2 * CivGui.PADDING, 20).build());
        upgrade.active = !max && (minecraft.player.isCreative() || current.canAffordNext(minecraft.player.getInventory()));
        if (menu.getInfo().jobType().profile().map(JobProfile::hasLeavesOption).orElse(false) && menu.getInfo().inSettlement()) {
            Component label = Component.translatable(menu.getInfo().collectLeaves() ? "civmod.gui.job_block.leaves.on" : "civmod.gui.job_block.leaves.off");
            addTabWidget(JobBlockMenu.TAB_INFO, Button.builder(label, button -> {
                clickMenuButton(JobBlockMenu.BUTTON_TOGGLE_LEAVES);
                rebuildWidgets();
            }).bounds(leftPos + CivGui.PADDING, topPos + imageHeight - 72, imageWidth - 2 * CivGui.PADDING, 20).build());
        }
        if (JobBlockMenu.isCraftsperson(current) && current.inSettlement()) {
            Component label = Component.translatable(current.byDeliveryman() ? "civmod.gui.job_block.delivery.deliveryman"
                    : "civmod.gui.job_block.delivery.self");
            addTabWidget(JobBlockMenu.TAB_INFO, Button.builder(label, button -> {
                clickMenuButton(JobBlockMenu.BUTTON_TOGGLE_DELIVERY);
                rebuildWidgets();
            }).bounds(leftPos + CivGui.PADDING, topPos + imageHeight - 72, imageWidth - 2 * CivGui.PADDING, 20).build());
        }
        if (JobBlockMenu.isMissionBoard(current)) {
            missions.init();
        }
        // Frees the block's only place; blocks with several places are left with ✕ in the settler window.
        if (!JobBlockMenu.isMissionBoard(current) && current.places().stream().mapToInt(JobPlaces.Line::places).sum() == 1
                && !current.assignees().isEmpty()) {
            addTabWidget(JobBlockMenu.TAB_INFO, Button.builder(Component.translatable("civmod.gui.job_block.unassign"), button -> {
                clickMenuButton(JobBlockMenu.BUTTON_UNASSIGN);
                rebuildWidgets();
            }).bounds(leftPos + CivGui.PADDING, topPos + imageHeight - 50, imageWidth - 2 * CivGui.PADDING, 20).build());
        }
    }

    @Override
    protected void extractTabBackground(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (JobBlockMenu.isClerk(menu.getInfo())) {
            clerk.extractBackground(graphics, tab);
            return;
        }
        if (tab == JobBlockMenu.TAB_MISSIONS && JobBlockMenu.isMissionBoard(menu.getInfo())) {
            missions.extractBackground(graphics);
        }
    }

    @Override
    protected void onTabChanged(int tab) {
        if (JobBlockMenu.isMissionBoard(menu.getInfo())) {
            missions.onTabChanged();
        }
    }

    @Override
    protected void extractTabLabels(GuiGraphicsExtractor graphics, int tab, int mouseX, int mouseY) {
        if (JobBlockMenu.isClerk(menu.getInfo()) && clerk.extractLabels(graphics, tab, mouseX, mouseY)) {
            return;
        }
        if (JobBlockMenu.isCraftsperson(menu.getInfo()) && tab == JobBlockMenu.TAB_PATTERNS) {
            patterns.extractLabels(graphics, mouseX, mouseY);
            return;
        }
        if (tab == JobBlockMenu.TAB_MISSIONS && JobBlockMenu.isMissionBoard(menu.getInfo())) {
            missions.extractLabels(graphics, mouseX, mouseY);
            return;
        }
        if (tab == JobBlockMenu.TAB_FOUND && JobBlockMenu.hasFoundTab(menu.getInfo())) {
            found.extractLabels(graphics, mouseX, mouseY);
            return;
        }
        if (tab == JobBlockMenu.TAB_INFO) {
            extractInfo(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected List<Component> helpLines(int tab) {
        JobBlockInfo info = menu.getInfo();
        if (JobBlockMenu.isClerk(info) && (tab == JobBlockMenu.TAB_STOCK || tab == JobBlockMenu.TAB_REQUESTS || tab == JobBlockMenu.TAB_DELIVERIES)) {
            return clerk.helpLines(tab);
        }
        if (JobBlockMenu.isCraftsperson(info) && tab == JobBlockMenu.TAB_PATTERNS) {
            return patterns.helpLines();
        }
        if (JobBlockMenu.isMissionBoard(info) && tab == JobBlockMenu.TAB_MISSIONS) {
            return missions.helpLines();
        }
        if (JobBlockMenu.hasFoundTab(info) && tab == JobBlockMenu.TAB_FOUND) {
            return found.helpLines();
        }
        List<Component> lines = new ArrayList<>();
        if (tab == JobBlockMenu.TAB_INFO && info.inSettlement() && !info.connected()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.connect"));
        }
        if (tab == JobBlockMenu.TAB_INFO && info.jobType().profile().map(JobProfile::hasLeavesOption).orElse(false) && info.inSettlement()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.leaves"));
        }
        if (tab == JobBlockMenu.TAB_INFO && JobBlockMenu.isCraftsperson(info) && info.inSettlement()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.delivery"));
        }
        if (tab == JobBlockMenu.TAB_INFO && info.inSettlement()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.auto_assign"));
        }
        return lines;
    }

    // ---------------------------------------------------------------- info tab

    private void extractInfo(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        JobBlockInfo info = menu.getInfo();
        boolean active = info.inSettlement() && info.connected();
        int color = active ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR;
        int y = 22;
        graphics.text(font, Component.translatable("civmod.gui.job_block.job", info.jobType().displayName()), CivGui.PADDING, y, color, false);
        y += LINE_HEIGHT;
        graphics.text(font, Component.translatable("civmod.gui.job_block.level_cap", info.level(), SettlementRules.MAX_LEVEL,
                SettlementRules.skillCap(info.level())), CivGui.PADDING, y, color, false);
        y += LINE_HEIGHT;
        // The settlement line also carries the connection state (one line: the buttons below leave no room for more).
        int width = imageWidth - 2 * CivGui.PADDING;
        if (!info.inSettlement()) {
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.job_block.not_registered"), width), CivGui.PADDING, y,
                    CivGui.ERROR_COLOR, false);
        } else if (!info.connected()) {
            Component settlement = Component.translatable("civmod.gui.job_block.settlement", info.settlementName());
            Component unconnected = Component.translatable("civmod.gui.job_block.unconnected", info.required(), info.distance());
            graphics.text(font, CivGui.fit(font, unconnected, width), CivGui.PADDING, y, CivGui.ERROR_COLOR, false);
            if (mouseX >= leftPos + CivGui.PADDING && mouseX < leftPos + CivGui.PADDING + width && mouseY >= topPos + y
                    && mouseY < topPos + y + font.lineHeight) {
                graphics.setComponentTooltipForNextFrame(font, List.of(settlement, unconnected), mouseX, mouseY);
            }
        } else {
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.job_block.settlement", info.settlementName()), width),
                    CivGui.PADDING, y, color, false);
        }
        y += LINE_HEIGHT;
        // One line per offered Job with taken / places; a single place names its holder, several list them on hover.
        // In a settlement each line ends with its automatic assignment checkbox.
        int textWidth = info.inSettlement() ? width - AUTO_BOX - 2 : width;
        int lineIndex = 0;
        for (JobPlaces.Line line : info.places()) {
            List<SettlementBlock.Assignee> holders = info.assignees().stream().filter(assignee -> assignee.job() == line.job()).toList();
            Component places = JobPickerScreen.label(line);
            Component text = line.places() == 1 && !holders.isEmpty()
                    ? Component.translatable("civmod.gui.job_block.job_holder", places, holders.get(0).name()) : places;
            graphics.text(font, CivGui.fit(font, text, textWidth), CivGui.PADDING, y, color, false);
            if (info.inSettlement()) {
                boolean hovered = overAutoBox(lineIndex, mouseX, mouseY);
                CivGui.checkbox(graphics, line.auto(), hovered, autoBoxX(), y - 1, AUTO_BOX);
                if (hovered) {
                    graphics.setComponentTooltipForNextFrame(font, List.of(Component.translatable(line.auto()
                            ? "civmod.gui.job_block.auto_assign.on" : "civmod.gui.job_block.auto_assign.off")),
                            mouseX, mouseY);
                }
            }
            lineIndex++;
            if (!holders.isEmpty() && mouseX >= leftPos + CivGui.PADDING && mouseX < leftPos + CivGui.PADDING + Math.min(textWidth, font.width(text))
                    && mouseY >= topPos + y && mouseY < topPos + y + font.lineHeight) {
                graphics.setComponentTooltipForNextFrame(font, settlerLines(info, holders), mouseX, mouseY);
            }
            y += LINE_HEIGHT;
        }
        if (JobBlockMenu.isClerk(info)) {
            y = clerk.extractWarehouse(graphics, y, width, color, mouseX, mouseY);
        }
        extractCosts(graphics, info, y, mouseX, mouseY);
    }

    /** Left edge of the automatic assignment checkboxes (panel coordinates). */
    private int autoBoxX() {
        return imageWidth - CivGui.PADDING - AUTO_BOX;
    }

    /** Whether the mouse is over the automatic assignment checkbox of a Job line. */
    private boolean overAutoBox(int line, double mouseX, double mouseY) {
        int x = leftPos + autoBoxX();
        int y = topPos + JOB_LINES_Y + line * LINE_HEIGHT - 1;
        return mouseX >= x && mouseX < x + AUTO_BOX && mouseY >= y && mouseY < y + AUTO_BOX;
    }

    /** A left click on an automatic assignment checkbox of the info tab sets the wanted state on client and server. */
    private boolean infoClicked(MouseButtonEvent event) {
        JobBlockInfo info = menu.getInfo();
        if (menu.getActiveTab() != JobBlockMenu.TAB_INFO || !info.inSettlement() || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        for (int line = 0; line < info.places().size(); line++) {
            if (overAutoBox(line, event.x(), event.y())) {
                clickMenuButton(JobBlockMenu.autoButtonId(line, !info.places().get(line).auto()));
                clickSound();
                return true;
            }
        }
        return false;
    }

    /**
     * @return one tooltip line per settler: its name and, if any, the mission it took
     */
    private static List<Component> settlerLines(JobBlockInfo info, List<SettlementBlock.Assignee> holders) {
        List<Component> lines = new ArrayList<>();
        for (SettlementBlock.Assignee holder : holders) {
            String name = holder.name();
            Optional<JobBlockInfo.TakenMission> taken = info.taken().stream().filter(entry -> entry.settler().equals(name)).findFirst();
            lines.add(taken.<Component>map(entry -> Component.translatable("civmod.gui.job_block.settler_mission", name,
                    Component.translatable(entry.departed() ? "civmod.settler.status.on_mission" : "civmod.settler.status.mission_reserved",
                            MissionTabView.title(entry.mission(), MissionTabView.mission(entry.mission()))))).orElseGet(() -> Component.literal(name)));
        }
        return lines;
    }

    /**
     * Draws the costs of the next level as a row of slots (icon, needed count, red tint when missing); more than
     * {@value #COST_SLOTS} entries scroll with the mouse wheel.
     */
    private int extractCosts(GuiGraphicsExtractor graphics, JobBlockInfo info, int y, int mouseX, int mouseY) {
        List<JobBlockInfo.CostEntry> costs = info.nextCosts();
        if (costs.isEmpty() || info.level() >= SettlementRules.MAX_LEVEL) {
            return y;
        }
        graphics.text(font, Component.translatable("civmod.gui.job_block.upgrade.costs"), CivGui.PADDING, y, CivGui.LABEL_COLOR, false);
        int rowY = y + 10;
        costRowY = rowY;
        costOffset = Math.clamp(costOffset, 0, Math.max(0, costs.size() - COST_SLOTS));
        boolean creative = minecraft.player.isCreative();
        if (costOffset > 0) {
            graphics.text(font, Component.literal("«"), CivGui.PADDING, rowY + 4, CivGui.LABEL_COLOR, false);
        }
        if (costOffset + COST_SLOTS < costs.size()) {
            graphics.text(font, Component.literal("»"), COST_X + COST_SLOTS * 18 + 2, rowY + 4, CivGui.LABEL_COLOR, false);
        }
        for (int i = 0; i < Math.min(COST_SLOTS, costs.size() - costOffset); i++) {
            JobBlockInfo.CostEntry cost = costs.get(costOffset + i);
            int x = COST_X + i * 18;
            CivGui.slot(graphics, x, rowY);
            graphics.item(cost.icon(), x, rowY);
            graphics.itemDecorations(font, cost.icon(), x, rowY, Integer.toString(cost.count()));
            int have = cost.count(minecraft.player.getInventory());
            if (!creative && have < cost.count()) {
                graphics.fill(x, rowY, x + 16, rowY + 16, MISSING_TINT);
            }
            if (mouseX >= leftPos + x && mouseX < leftPos + x + 16 && mouseY >= topPos + rowY && mouseY < topPos + rowY + 16) {
                graphics.setComponentTooltipForNextFrame(font, List.of(cost.icon().getHoverName(),
                        Component.translatable("civmod.gui.job_block.upgrade.have", have, cost.count())
                                .withStyle(creative || have >= cost.count() ? ChatFormatting.GREEN : ChatFormatting.RED)),
                        mouseX, mouseY);
            }
        }
        return rowY + 20;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (JobBlockMenu.isClerk(menu.getInfo()) && clerk.mouseClicked(event)) {
            return true;
        }
        if (JobBlockMenu.isCraftsperson(menu.getInfo()) && patterns.mouseClicked(event)) {
            return true;
        }
        if (JobBlockMenu.isMissionBoard(menu.getInfo()) && missions.mouseClicked(event)) {
            return true;
        }
        if (JobBlockMenu.hasFoundTab(menu.getInfo()) && found.mouseClicked(event)) {
            return true;
        }
        if (infoClicked(event)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (JobBlockMenu.isClerk(menu.getInfo()) && clerk.mouseScrolled(scrollY)) {
            return true;
        }
        if (JobBlockMenu.isMissionBoard(menu.getInfo()) && missions.mouseScrolled(scrollY)) {
            return true;
        }
        int size = menu.getInfo().nextCosts().size();
        if (menu.getActiveTab() == JobBlockMenu.TAB_INFO && size > COST_SLOTS && costRowY >= 0
                && mouseY >= topPos + costRowY - 2 && mouseY < topPos + costRowY + 18) {
            costOffset = Math.clamp(costOffset - (int) Math.signum(scrollY), 0, size - COST_SLOTS);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (JobBlockMenu.isClerk(menu.getInfo()) && clerk.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- shared with the tab views

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

    // ---------------------------------------------------------------- Clerk and Craftsperson: sync and recipe viewers

    /**
     * Client handler of {@link ClerkTab.Sync}: updates the open Clerk menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handleClerkTab(ClerkTab.Sync payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof JobBlockMenu open && open.containerId == payload.containerId()) {
            open.setClerkTab(payload.tab());
            if (minecraft.gui.screen() instanceof JobBlockScreen screen) {
                screen.clerk.update();
            }
        }
    }

    /**
     * Client handler of {@link FoundTab.Sync}: updates the open Resource Collection JobBlock menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handleFoundTab(FoundTab.Sync payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof JobBlockMenu open && open.containerId == payload.containerId()) {
            open.setFoundTab(payload.tab());
        }
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

    /**
     * Client handler of {@link PatternTab.Sync}: updates the open Craftsperson menu.
     *
     * @param payload the payload
     * @param context the payload context
     */
    public static void handlePatternTab(PatternTab.Sync payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof JobBlockMenu open && open.containerId == payload.containerId()) {
            open.setPatternTab(payload.tab());
            if (minecraft.gui.screen() instanceof JobBlockScreen screen) {
                screen.patterns.update();
            }
        }
    }

    /**
     * The screen areas that take a dragged item (for JEI / REI ghost drag): the Clerk's active stock slots on the Stock
     * tab, or the Craftsperson's pattern slots on the Patterns tab.
     *
     * @return target id → absolute area
     */
    public Map<Integer, Rect2i> ghostTargets() {
        if (JobBlockMenu.isClerk(menu.getInfo())) {
            return clerk.stockTargets();
        }
        return JobBlockMenu.isCraftsperson(menu.getInfo()) ? patterns.targets() : new LinkedHashMap<>();
    }

    /**
     * @param x the mouse x position
     * @param y the mouse y position
     * @return the ghost target under the mouse, {@code -1} if none
     */
    public int ghostTargetAt(double x, double y) {
        if (JobBlockMenu.isClerk(menu.getInfo())) {
            return clerk.stockSlotAt(x, y);
        }
        return JobBlockMenu.isCraftsperson(menu.getInfo()) ? patterns.targetAt(x, y) : -1;
    }

    /**
     * Puts a dragged item into a ghost target (not consumed).
     *
     * @param target the target id from {@link #ghostTargets()}
     * @param stack  the item
     * @return whether the target took it
     */
    public boolean acceptGhost(int target, ItemStack stack) {
        if (JobBlockMenu.isClerk(menu.getInfo())) {
            return clerk.setStockItem(target, stack);
        }
        return JobBlockMenu.isCraftsperson(menu.getInfo()) && patterns.setItem(target, stack);
    }

    /**
     * Fills a pattern from a recipe viewer's transfer button: the open pattern, else the first empty library slot.
     *
     * @param recipe     the recipe the viewer shows
     * @param grid       the items it shows, row by row
     * @param doTransfer whether to transfer (false: only check)
     * @return the reason it cannot, empty if it can
     */
    public Optional<Component> transferPattern(Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid, boolean doTransfer) {
        if (!JobBlockMenu.isCraftsperson(menu.getInfo())) {
            return Optional.of(Component.translatable("civmod.gui.patterns.not_here"));
        }
        return patterns.transfer(recipe, grid, doTransfer);
    }
}
