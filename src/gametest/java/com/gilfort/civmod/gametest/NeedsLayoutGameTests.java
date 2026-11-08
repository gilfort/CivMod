package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.menu.NeedsLayout;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the scroll range of the settler window's Needs tab (#222): a list that fits the window does not scroll,
 * a longer one scrolls by exactly the part that does not fit.
 * <p>
 * Expected heights are worked out by hand: a section header is 10 px, a need row 20 px (name and detail line) plus 16 px
 * when it shows item icons, and two sections are 4 px apart.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class NeedsLayoutGameTests {

    /** Height of the Needs tab's list in the settler window (184 px panel, 22 px above and 8 px below the list). */
    private static final int VIEW = 154;

    private NeedsLayoutGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "needs_layout_fits", NeedsLayoutGameTests::fits);
            register(helper, "needs_layout_overflows", NeedsLayoutGameTests::overflows);
            register(helper, "needs_layout_edge", NeedsLayoutGameTests::edge);
            register(helper, "needs_layout_section_top", NeedsLayoutGameTests::sectionTop);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static NeedsReport.Row plain(int tier) {
        return new NeedsReport.Row(tier, Component.literal("Need"), true, Component.literal("Detail"), List.of());
    }

    private static NeedsReport.Row icons(int tier) {
        return new NeedsReport.Row(tier, Component.literal("Need"), false, Component.literal("Detail"), List.of(new ItemStack(Items.BREAD)));
    }

    private static NeedsReport report(NeedsReport.Row... rows) {
        return new NeedsReport(1, new ArrayList<>(List.of(rows)));
    }

    /** Two plain rows of one tier: 10 + 2 * 20 = 50 px, well inside the window. */
    private static void fits(GameTestHelper helper) {
        NeedsReport needs = report(plain(1), plain(1));
        helper.assertTrue(NeedsLayout.contentHeight(needs) == 50, "Content height of a short list: " + NeedsLayout.contentHeight(needs));
        helper.assertTrue(NeedsLayout.maxOffset(NeedsLayout.contentHeight(needs), VIEW) == 0, "A list that fits does not scroll");
        helper.assertTrue(NeedsLayout.maxOffset(NeedsLayout.contentHeight(NeedsReport.NONE), VIEW) == 0, "No needs: no scroll");
        helper.succeed();
    }

    /**
     * Tier 1: two plain rows = 50 px. Tier 2: one row with icons = 10 + 36 = 46 px. Tier 3: three rows with icons =
     * 10 + 108 = 118 px. Two 4 px gaps: 50 + 4 + 46 + 4 + 118 = 222 px, 68 px more than the window shows.
     */
    private static void overflows(GameTestHelper helper) {
        NeedsReport needs = report(plain(1), plain(1), icons(2), icons(3), icons(3), icons(3));
        helper.assertTrue(NeedsLayout.contentHeight(needs) == 222, "Content height of a long list: " + NeedsLayout.contentHeight(needs));
        helper.assertTrue(NeedsLayout.maxOffset(NeedsLayout.contentHeight(needs), VIEW) == 68,
                "Scrolls by the part that does not fit: " + NeedsLayout.maxOffset(NeedsLayout.contentHeight(needs), VIEW));
        helper.succeed();
    }

    /** A list exactly as high as the window does not scroll; one pixel more scrolls by one pixel. */
    private static void edge(GameTestHelper helper) {
        helper.assertTrue(NeedsLayout.maxOffset(VIEW, VIEW) == 0, "Exactly fitting: no scroll");
        helper.assertTrue(NeedsLayout.maxOffset(VIEW + 1, VIEW) == 1, "One pixel too high: scrolls one pixel");
        helper.assertTrue(NeedsLayout.maxOffset(VIEW - 1, VIEW) == 0, "Lower than the window: no scroll");
        helper.succeed();
    }

    /** A tier's section starts below the sections of the lower tiers; a tier without needs takes no room. */
    private static void sectionTop(GameTestHelper helper) {
        NeedsReport needs = report(plain(1), plain(1), icons(3));
        helper.assertTrue(NeedsLayout.sectionTop(needs, 1) == 0, "Tier 1 starts at the top: " + NeedsLayout.sectionTop(needs, 1));
        helper.assertTrue(NeedsLayout.sectionTop(needs, 3) == 54, "Tier 3 after tier 1 (50) and a gap (4): " + NeedsLayout.sectionTop(needs, 3));
        helper.assertTrue(NeedsLayout.sectionHeight(needs.rows(3)) == 46, "Section of tier 3: " + NeedsLayout.sectionHeight(needs.rows(3)));
        helper.succeed();
    }
}
