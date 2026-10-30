package com.gilfort.civmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.job.LumberjackProfile;
import com.gilfort.civmod.menu.InfoTab;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.JobBlockTab;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementRules;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The "Info" tab of the {@link JobBlockScreen}; the client side of {@link InfoTab}: Job, level and skill cap, settlement
 * status, one line per offered Job with its automatic assignment checkbox, today's biome on the Job line (its bonus
 * effects on hover; the "?" lists every biome with an effect for the Job) and the leaf collection and Free buttons. Other tabs' views add their own lines and
 * buttons ({@link JobBlockTabView#extractInfo}, {@link JobBlockTabView#initInfo}).
 * <p>
 * Unconnected JobBlocks show the info greyed out with a red hint; storage keeps working.
 */
final class InfoTabView implements JobBlockTabView {

    private static final int LINE_HEIGHT = 11;
    /** Top of the first Job line (below job, level and settlement). */
    private static final int JOB_LINES_Y = 22 + 3 * LINE_HEIGHT;
    /** Size of the automatic assignment checkbox at the end of a Job line. */
    private static final int AUTO_BOX = 9;
    /** Distance from the panel's bottom edge of the toggle button row (leaf collection, delivery). */
    static final int TOGGLE_ROW = 72;

    private final JobBlockScreen screen;
    private final JobBlockMenu menu;
    private final InfoTab tab;

    /**
     * @param screen the screen showing the tab
     * @param tab    the tab
     */
    InfoTabView(JobBlockScreen screen, InfoTab tab) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.tab = tab;
    }

    /**
     * Adds a button of the Info tab: across the panel, {@code fromBottom} pixels above its bottom edge; it presses a
     * menu button on client and server and rebuilds the widgets.
     *
     * @param screen     the screen
     * @param info       the Info tab's index
     * @param label      the label
     * @param fromBottom the distance of its top edge from the panel's bottom edge
     * @param buttonId   the menu button id
     * @param active     whether it can be pressed
     */
    static void button(JobBlockScreen screen, int info, Component label, int fromBottom, int buttonId, boolean active) {
        Button button = screen.addWidget(info, Button.builder(label, pressed -> {
            screen.clickMenuButton(buttonId);
            screen.rebuild();
        }).bounds(screen.left() + CivGui.PADDING, screen.top() + screen.panelHeight() - fromBottom, screen.panelWidth() - 2 * CivGui.PADDING, 20)
                .build());
        button.active = active;
    }

    private void button(Component label, int fromBottom, int buttonId, boolean active) {
        button(screen, tab.index(), label, fromBottom, buttonId, active);
    }

    @Override
    public void init() {
        JobBlockInfo info = menu.getInfo();
        if (info.jobType().profile(LumberjackProfile.class).isPresent() && info.inSettlement()) {
            button(Component.translatable(info.collectLeaves() ? "civmod.gui.job_block.leaves.on" : "civmod.gui.job_block.leaves.off"), TOGGLE_ROW,
                    tab.buttonId(InfoTab.TOGGLE_LEAVES), true);
        }
        screen.views().forEach(view -> view.initInfo(tab.index()));
        // Frees the block's only place; blocks with several places are left with ✕ in the settler window.
        if (screen.views().stream().allMatch(JobBlockTabView::offersUnassign) && info.places().stream().mapToInt(JobPlaces.Line::places).sum() == 1
                && !info.assignees().isEmpty()) {
            button(Component.translatable("civmod.gui.job_block.unassign"), 50, tab.buttonId(InfoTab.UNASSIGN), true);
        }
    }

    @Override
    public List<Component> helpLines(JobBlockTab shown) {
        JobBlockInfo info = menu.getInfo();
        List<Component> lines = new ArrayList<>();
        if (info.inSettlement() && !info.connected()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.connect"));
        }
        if (info.jobType().profile(LumberjackProfile.class).isPresent() && info.inSettlement()) {
            lines.add(Component.translatable("civmod.gui.job_block.help.leaves"));
        }
        // The biomes that change something for the Job, and that all others do not.
        lines.addAll(info.biome().overview());
        return lines;
    }

    @Override
    public void extractLabels(GuiGraphicsExtractor graphics, JobBlockTab shown, int mouseX, int mouseY) {
        Font font = screen.getFont();
        JobBlockInfo info = menu.getInfo();
        boolean active = info.inSettlement() && info.connected();
        int color = active ? CivGui.LABEL_COLOR : CivGui.HINT_COLOR;
        int left = screen.left();
        int top = screen.top();
        int y = 22;
        int width = screen.panelWidth() - 2 * CivGui.PADDING;
        // The Job line also names today's biome (one line: the buttons below leave no room for more); a star marks
        // effects, which show on hover (vault Systems/JobBlock.md, biome bonuses).
        JobBlockInfo.BiomeInfo biome = info.biome();
        MutableComponent jobLine = Component.translatable("civmod.gui.job_block.job", info.jobType().displayName());
        biome.biome().ifPresent(name -> {
            jobLine.append(" · ").append(name);
            if (!biome.effects().isEmpty()) {
                jobLine.append(" ").append(CivGui.STAR.copy().withColor(CivGui.STAR_COLOR & 0xFFFFFF));
            }
        });
        graphics.text(font, CivGui.fit(font, jobLine, width), CivGui.PADDING, y, color, false);
        if (biome.biome().isPresent() && overLine(y, width, mouseX, mouseY)) {
            List<Component> lines = new ArrayList<>();
            lines.add(biome.biome().get());
            lines.addAll(biome.effects());
            graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
        y += LINE_HEIGHT;
        graphics.text(font, Component.translatable("civmod.gui.job_block.level_cap", info.level(), SettlementRules.MAX_LEVEL,
                SettlementRules.skillCap(info.level())), CivGui.PADDING, y, color, false);
        y += LINE_HEIGHT;
        // The settlement line also carries the connection state (one line: the buttons below leave no room for more).
        if (!info.inSettlement()) {
            graphics.text(font, CivGui.fit(font, Component.translatable("civmod.gui.job_block.not_registered"), width), CivGui.PADDING, y,
                    CivGui.ERROR_COLOR, false);
        } else if (!info.connected()) {
            Component settlement = Component.translatable("civmod.gui.job_block.settlement", info.settlementName());
            Component unconnected = Component.translatable("civmod.gui.job_block.unconnected", info.required(), info.distance());
            graphics.text(font, CivGui.fit(font, unconnected, width), CivGui.PADDING, y, CivGui.ERROR_COLOR, false);
            if (overLine(y, width, mouseX, mouseY)) {
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
            if (!holders.isEmpty() && mouseX >= left + CivGui.PADDING && mouseX < left + CivGui.PADDING + Math.min(textWidth, font.width(text))
                    && mouseY >= top + y && mouseY < top + y + font.lineHeight) {
                graphics.setComponentTooltipForNextFrame(font, settlerLines(info, holders), mouseX, mouseY);
            }
            y += LINE_HEIGHT;
        }
        for (JobBlockTabView view : screen.views()) {
            y = view.extractInfo(graphics, y, width, color, mouseX, mouseY);
        }
    }

    /** Left edge of the automatic assignment checkboxes (panel coordinates). */
    private int autoBoxX() {
        return screen.panelWidth() - CivGui.PADDING - AUTO_BOX;
    }

    /** Whether the mouse is over the automatic assignment checkbox of a Job line. */
    private boolean overAutoBox(int line, double mouseX, double mouseY) {
        int x = screen.left() + autoBoxX();
        int y = screen.top() + JOB_LINES_Y + line * LINE_HEIGHT - 1;
        return mouseX >= x && mouseX < x + AUTO_BOX && mouseY >= y && mouseY < y + AUTO_BOX;
    }

    /** Whether the mouse is over a text line at panel height {@code y} that is {@code width} wide. */
    private boolean overLine(int y, int width, int mouseX, int mouseY) {
        int x = screen.left() + CivGui.PADDING;
        int top = screen.top() + y;
        return mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + screen.getFont().lineHeight;
    }

    /** A left click on an automatic assignment checkbox sets the wanted state on client and server. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event) {
        JobBlockInfo info = menu.getInfo();
        if (!info.inSettlement() || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        for (int line = 0; line < info.places().size(); line++) {
            if (overAutoBox(line, event.x(), event.y())) {
                screen.clickMenuButton(tab.autoButtonId(line, !info.places().get(line).auto()));
                screen.clickSound();
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
                            MissionsTabView.title(entry.mission(), MissionsTabView.mission(entry.mission()))))).orElseGet(() -> Component.literal(name)));
        }
        return lines;
    }
}
