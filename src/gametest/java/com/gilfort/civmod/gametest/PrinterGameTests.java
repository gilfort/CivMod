package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Printer (#265, vault {@code Jobs/Printer.md}): a Specialization of the Craftsperson unlocked by a
 * lectern, crafting in the print domain at the Craftsperson's crafting table at job factor 1.2 with no station extra.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class PrinterGameTests {

    /** The work time the speed tests catch up: enough for 4 executions at job factor 1.2, not at 0.3. */
    private static final long SPEED_TICKS = 1200L;
    /** Work time for a single book. */
    private static final long WORK_TICKS = 4000L;
    /** Books of the speed tests: one round of 4 executions, delivered when the last one is done. */
    private static final int BOOKS = 4;
    /** Paper per book. */
    private static final int PAPER_PER_BOOK = 3;

    private PrinterGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "printer_unlock", PrinterGameTests::unlock);
            TestFunctions.register(helper, "printer_crafts_book", PrinterGameTests::craftsBook);
            TestFunctions.register(helper, "printer_domain", PrinterGameTests::domain);
            TestFunctions.register(helper, "printer_four_times_faster", PrinterGameTests::fourTimesFaster);
            TestFunctions.register(helper, "printer_craftsperson_slower", PrinterGameTests::craftspersonSlower);
        });
    }

    /** The Printer. */
    static Job printer() {
        return StationTypeGameTests.job(CivMod.id("printer"));
    }

    /** A level-2 Craftsperson JobBlock with the Printer unlocked by a lectern in its second Station Types slot. */
    static BlockPos printingHouse(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.LECTERN), "A lectern unlocks the Printer");
        return pos;
    }

    /**
     * The Printer specializes the Craftsperson (job factor 1.2, base EXP 2, hunger 0.5), is unlocked by a lectern, works
     * {@code crafting} recipes in the print domain at a crafting table and looks like a book. Its library starts empty.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job printer = printer();
        helper.assertTrue(printer != null && printer.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Printer specializes the Craftsperson");
        helper.assertTrue(printer.unlockItem().equals(Optional.of(Items.LECTERN)), "Unlocked by a lectern: " + printer.unlockItem());
        helper.assertTrue(printer.jobFactor() == 1.2 && printer.baseExp() == 2 && printer.hungerFactor() == 0.5,
                "Job factor 1.2, base EXP 2, hunger 0.5: " + printer.jobFactor() + " / " + printer.baseExp() + " / " + printer.hungerFactor());
        helper.assertTrue(printer.icon().is(Items.LECTERN) && printer.bubbleIcon().is(Items.BOOK) && printer.displayItem().equals(Optional.of(Items.BOOK)),
                "Icon lectern, bubble and display item book");
        helper.assertTrue(printer.description().getString().equals("Crafts paper, books and bookshelves at a crafting table"),
                "Description: " + printer.description().getString());
        ProducingProfile profile = printer.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting recipes");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).equals(CraftspersonGameTests.profile().station(RecipeType.CRAFTING)),
                "At the Craftsperson's crafting table");
        TagKey<Item> domain = profile.domain().orElseThrow();
        helper.assertTrue(domain.location().equals(CivMod.id("domains/print")), "Print domain: " + domain);
        for (Item item : List.of(Items.PAPER, Items.BOOK, Items.LECTERN, Items.INK_SAC, Items.FEATHER, Items.WRITABLE_BOOK, Items.BOOKSHELF)) {
            helper.assertTrue(item.getDefaultInstance().is(domain), item + " is in the print domain");
        }
        helper.assertFalse(Items.OAK_PLANKS.getDefaultInstance().is(domain), "Planks are not");
        helper.assertFalse(Items.IRON_INGOT.getDefaultInstance().is(domain), "Iron is not");
        BlockPos pos = printingHouse(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(printer),
                "The job picker offers the Printer");
        Patterns.syncInnate(level, pos, printer);
        helper.assertTrue(Patterns.pattern(StationTypeGameTests.block(level, pos), printer, 0).isEmpty(), "No innate recipe");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CRAFTING_TABLE);
        helper.assertTrue(printer.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a crafting table");
        helper.succeed();
    }

    /** A Clerk Min rule on a book: the Printer crafts it from paper and leather at the crafting table and delivers it. */
    private static void craftsBook(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.PAPER, PAPER_PER_BOOK), new ItemStack(Items.LEATHER)),
                List.of(new ItemStack(Items.BOOK)));
        BlockPos craft = printingHouse(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, printer());
        helper.assertTrue(Patterns.setOutput(level, craft, printer(), 0, Items.BOOK).orElseThrow().checked(), "Book checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BOOK) == 1,
                "Book arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.PAPER) == 0, "The paper is used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** A bookshelf pattern is inside the domain (it takes books); an iron pickaxe pattern is outside: greyed out, not checkable. */
    private static void domain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos craft = printingHouse(helper, new BlockPos(1, 1, 5));
        helper.assertTrue(Patterns.setOutput(level, craft, printer(), 0, Items.BOOKSHELF).orElseThrow().checked(), "Bookshelf checked");
        helper.assertFalse(Patterns.setOutput(level, craft, printer(), 1, Items.IRON_PICKAXE).orElseThrow().checked(), "A pickaxe is outside the domain");
        helper.assertFalse(Patterns.toggleCheck(level, craft, printer(), 1), "And cannot be checked");
        PatternsTabData tab = PatternsTabData.of(level, craft, printer());
        helper.assertTrue(tab.filtered().equals(Set.of(1))
                && tab.filteredLine().equals(Component.translatable("civmod.gui.patterns.outside_domain")), "Greyed out: " + tab.filtered());
        helper.succeed();
    }

    /** The Printer works at job factor 1.2: 4 books in the time the Craftsperson (0.3) cannot finish them. */
    private static void fourTimesFaster(GameTestHelper helper) {
        helper.assertTrue(printer().jobFactor() == 4 * CraftspersonGameTests.craftJob().jobFactor(), "Four times the Craftsperson's job factor");
        int delivered = books(helper, printer(), SPEED_TICKS);
        helper.assertTrue(delivered == BOOKS, "The Printer delivered " + BOOKS + " books: " + delivered);
        helper.succeed();
    }

    /** The same request in the same work time: the Craftsperson is not done yet. */
    private static void craftspersonSlower(GameTestHelper helper) {
        int delivered = books(helper, CraftspersonGameTests.craftJob(), SPEED_TICKS);
        helper.assertTrue(delivered < BOOKS, "The Craftsperson is slower: " + delivered);
        helper.succeed();
    }

    /** Requests 4 books (4 executions), lets a settler of the Job work the work time and returns how many arrived. */
    private static int books(GameTestHelper helper, Job job, long workTicks) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.PAPER, PAPER_PER_BOOK * BOOKS), new ItemStack(Items.LEATHER, BOOKS)),
                List.of(new ItemStack(Items.BOOK, BOOKS)));
        BlockPos relative = new BlockPos(1, 1, 5);
        BlockPos craft = job == printer() ? printingHouse(helper, relative) : CraftspersonGameTests.craftsperson(helper, relative);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, job);
        helper.assertTrue(Patterns.setOutput(level, craft, job, 0, Items.BOOK).orElseThrow().checked(), "Book checked");
        CraftspersonGameTests.catchUp(level, settler, workTicks);
        int delivered = FletcherGameTests.count(level, setup.clerk(), Items.BOOK);
        FletcherGameTests.cleanUp(setup, settler);
        return delivered;
    }
}
