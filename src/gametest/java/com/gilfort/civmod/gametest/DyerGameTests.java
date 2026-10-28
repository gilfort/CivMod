package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
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
 * GameTests for the Dyer (#128, vault {@code Jobs/Dyer.md}): a Specialization of the Craftsperson unlocked by a loom,
 * crafting in the dye domain at its loom; its innate recipe bleaches dyed wool back to white wool at a water cauldron;
 * a water cauldron near the loom replaces its job factor 0.3 by 1.2.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class DyerGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for a few executions at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;
    /** The work time the speed tests catch up: enough for 4 executions at job factor 1.2, not at 0.3. */
    private static final long SPEED_TICKS = 1200L;

    private DyerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "dyer_unlock", DyerGameTests::unlock);
            register(helper, "dyer_dye_and_bleach", DyerGameTests::dyeAndBleach);
            register(helper, "dyer_without_cauldron", DyerGameTests::withoutCauldron);
            register(helper, "dyer_cauldron_faster", DyerGameTests::cauldronFaster);
            register(helper, "dyer_without_cauldron_slower", DyerGameTests::withoutCauldronSlower);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Dyer. */
    static Job dyer() {
        return StationTypeGameTests.job(CivMod.id("dyer"));
    }

    /** A level-2 Craftsperson JobBlock with the Dyer unlocked by a loom in its second Station Types slot. */
    static BlockPos dyehouse(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.LOOM), "A loom unlocks the Dyer");
        return pos;
    }

    /**
     * The Dyer specializes the Craftsperson (job factor 0.3, base EXP 2, hunger 0.5), is unlocked by a loom, works
     * {@code crafting} recipes in the dye domain at a loom and looks like red dye. Its innate bleaching recipe sits locked
     * in slot 0.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job dyer = dyer();
        helper.assertTrue(dyer != null && dyer.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Dyer specializes the Craftsperson");
        helper.assertTrue(dyer.unlockItem().equals(Optional.of(Items.LOOM)), "Unlocked by a loom: " + dyer.unlockItem());
        helper.assertTrue(dyer.jobFactor() == 0.3 && dyer.baseExp() == 2 && dyer.hungerFactor() == 0.5,
                "Job factor 0.3, base EXP 2, hunger 0.5: " + dyer.jobFactor() + " / " + dyer.baseExp() + " / " + dyer.hungerFactor());
        helper.assertTrue(dyer.icon().is(Items.LOOM) && dyer.bubbleIcon().is(Items.DYE.red()) && dyer.displayItem().equals(Optional.of(Items.DYE.red())),
                "Icon loom, bubble and display item red dye");
        helper.assertTrue(dyer.description().getString().equals("Makes dyes and dyes blocks at a loom, fast with a water cauldron near it"),
                "Description: " + dyer.description().getString());
        ProducingProfile profile = dyer.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting recipes");
        TagKey<Item> domain = profile.domain().orElseThrow();
        helper.assertTrue(domain.location().equals(CivMod.id("domains/dye")), "Dye domain: " + domain);
        for (Item item : List.of(Items.DYE.red(), Items.DYE.white(), Items.DANDELION, Items.SUNFLOWER, Items.INK_SAC, Items.GLOW_INK_SAC,
                Items.COCOA_BEANS, Items.BEETROOT, Items.LAPIS_LAZULI, Items.BONE_MEAL)) {
            helper.assertTrue(item.getDefaultInstance().is(domain), item + " is in the dye domain");
        }
        helper.assertFalse(Items.OAK_PLANKS.getDefaultInstance().is(domain), "Planks are not");
        BlockPos pos = dyehouse(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(dyer),
                "The job picker offers the Dyer");
        Patterns.syncInnate(level, pos, dyer);
        Optional<Pattern> bleach = Patterns.pattern(StationTypeGameTests.block(level, pos), dyer, 0);
        helper.assertTrue(bleach.filter(pattern -> pattern.innate() && pattern.output() == Items.WOOL.white()).isPresent(),
                "Slot 0 holds the innate bleaching recipe: " + bleach);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.LOOM);
        helper.assertTrue(dyer.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a loom");
        helper.succeed();
    }

    /**
     * Clerk Min rules on yellow dye and white wool: with a water cauldron nearby the Dyer makes the dye from a dandelion
     * at its loom and bleaches red wool to white at the cauldron.
     */
    private static void dyeAndBleach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.DANDELION), new ItemStack(Items.WOOL.red())),
                List.of(new ItemStack(Items.DYE.yellow()), new ItemStack(Items.WOOL.white())));
        BlockPos craft = dyehouse(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.LOOM);
        helper.setBlock(new BlockPos(9, 1, 5), Blocks.WATER_CAULDRON);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, dyer());
        helper.assertTrue(Patterns.setOutput(level, craft, dyer(), 1, Items.DYE.yellow()).orElseThrow().checked(), "Yellow dye checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.DYE.yellow()) == 1,
                "Yellow dye arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WOOL.white()) == 1,
                "White wool arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WOOL.red()) == 0, "The red wool is used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Without a water cauldron the bleaching recipe is skipped and marked "Needs a station nearby: Water Cauldron"; the dye is made
     * all the same.
     */
    private static void withoutCauldron(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.DANDELION), new ItemStack(Items.WOOL.red())),
                List.of(new ItemStack(Items.DYE.yellow()), new ItemStack(Items.WOOL.white())));
        BlockPos craft = dyehouse(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.LOOM);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, dyer());
        helper.assertTrue(Patterns.setOutput(level, craft, dyer(), 1, Items.DYE.yellow()).orElseThrow().checked(), "Yellow dye checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.DYE.yellow()) == 1,
                "Yellow dye arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WOOL.white()) == 0, "No white wool without a water cauldron");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WOOL.red()) == 1, "The red wool stays");
        PatternsTabData tab = PatternsTabData.of(level, craft, dyer());
        Optional<String> line = Optional.ofNullable(tab.needs().get(0)).map(Component::getString);
        helper.assertTrue(line.equals(Optional.of("Needs a station nearby: Water Cauldron")), "The bleaching recipe is marked: " + tab.needs());
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** With a water cauldron within 4 blocks of the loom the Dyer works at job factor 1.2: 4 yellow dyes in time. */
    private static void cauldronFaster(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.WATER_CAULDRON);
        int delivered = dyes(helper, 1.2);
        helper.assertTrue(delivered == 4, "With a water cauldron the Dyer delivered 4 yellow dyes: " + delivered);
        helper.succeed();
    }

    /** The same request in the same work time without a water cauldron: job factor 0.3, not done yet. */
    private static void withoutCauldronSlower(GameTestHelper helper) {
        int delivered = dyes(helper, 0.3);
        helper.assertTrue(delivered < 4, "Without a water cauldron the Dyer is slower: " + delivered);
        helper.succeed();
    }

    /** Requests 4 yellow dyes (4 executions), lets a Dyer work {@link #SPEED_TICKS} and returns how many arrived. */
    private static int dyes(GameTestHelper helper, double factor) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.DANDELION, 4)), List.of(new ItemStack(Items.DYE.yellow(), 4)));
        BlockPos craft = dyehouse(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.LOOM);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, dyer());
        helper.assertTrue(Patterns.setOutput(level, craft, dyer(), 1, Items.DYE.yellow()).orElseThrow().checked(), "Yellow dye checked");
        CraftspersonGameTests.catchUp(level, settler, SPEED_TICKS);
        helper.assertTrue(settler.getWork().jobFactor(level, craft) == factor, "Job factor of the day " + factor + ": "
                + settler.getWork().jobFactor(level, craft));
        int delivered = FletcherGameTests.count(level, setup.clerk(), Items.DYE.yellow());
        FletcherGameTests.cleanUp(setup, settler);
        return delivered;
    }
}
