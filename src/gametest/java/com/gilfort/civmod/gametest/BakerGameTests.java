package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

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
 * GameTests for the Baker (#254, vault {@code Jobs/Baker.md}): a Specialization of the Craftsperson unlocked by a hay
 * bale, crafting in the food domain at the Craftsperson's crafting table; a furnace near the table replaces its job
 * factor 0.3 by 1.2.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BakerGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for a few executions at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;
    /** The work time the speed tests catch up: enough for 4 executions at job factor 1.2, not at 0.3. */
    private static final long SPEED_TICKS = 1200L;

    private BakerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "baker_unlock", BakerGameTests::unlock);
            register(helper, "baker_bakes_bread", BakerGameTests::bakesBread);
            register(helper, "baker_modded_food", BakerGameTests::moddedFood);
            register(helper, "baker_skips_intermediate", BakerGameTests::skipsIntermediate);
            register(helper, "baker_furnace_faster", BakerGameTests::furnaceFaster);
            register(helper, "baker_without_furnace_slower", BakerGameTests::withoutFurnaceSlower);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Baker. */
    static Job baker() {
        return StationTypeGameTests.job(CivMod.id("baker"));
    }

    /** A level-2 Craftsperson JobBlock with the Baker unlocked by a hay bale in its second Station Types slot. */
    static BlockPos bakery(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.HAY_BLOCK), "A hay bale unlocks the Baker");
        return pos;
    }

    /**
     * The Baker specializes the Craftsperson (job factor 0.3, base EXP 2, hunger 0.5), is unlocked by a hay bale, works
     * {@code crafting} recipes in the food domain at a crafting table and looks like bread. Its library starts empty.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job baker = baker();
        helper.assertTrue(baker != null && baker.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Baker specializes the Craftsperson");
        helper.assertTrue(baker.unlockItem().equals(Optional.of(Items.HAY_BLOCK)), "Unlocked by a hay bale: " + baker.unlockItem());
        helper.assertTrue(baker.jobFactor() == 0.3 && baker.baseExp() == 2 && baker.hungerFactor() == 0.5,
                "Job factor 0.3, base EXP 2, hunger 0.5: " + baker.jobFactor() + " / " + baker.baseExp() + " / " + baker.hungerFactor());
        helper.assertTrue(baker.icon().is(Items.HAY_BLOCK) && baker.bubbleIcon().is(Items.BREAD) && baker.displayItem().equals(Optional.of(Items.BREAD)),
                "Icon hay bale, bubble and display item bread");
        helper.assertTrue(baker.description().getString().equals("Crafts everything that is food at a crafting table, fast with a furnace near it"),
                "Description: " + baker.description().getString());
        ProducingProfile profile = baker.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting recipes");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).equals(CraftspersonGameTests.profile().station(RecipeType.CRAFTING)),
                "At the Craftsperson's crafting table");
        TagKey<Item> domain = profile.domain().orElseThrow();
        helper.assertTrue(domain.location().equals(CivMod.id("domains/food")), "Food domain: " + domain);
        for (Item item : List.of(Items.BREAD, Items.COOKED_BEEF, Items.WHEAT, Items.CARROT, Items.EGG, Items.MILK_BUCKET, Items.SUGAR)) {
            helper.assertTrue(item.getDefaultInstance().is(domain), item + " is in the food domain");
        }
        helper.assertFalse(Items.OAK_PLANKS.getDefaultInstance().is(domain), "Planks are not");
        helper.assertFalse(Items.IRON_INGOT.getDefaultInstance().is(domain), "Iron is not");
        BlockPos pos = bakery(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(baker),
                "The job picker offers the Baker");
        Patterns.syncInnate(level, pos, baker);
        helper.assertTrue(Patterns.pattern(StationTypeGameTests.block(level, pos), baker, 0).isEmpty(), "No innate recipe");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CRAFTING_TABLE);
        helper.assertTrue(baker.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a crafting table");
        helper.succeed();
    }

    /**
     * A Clerk Min rule on bread: the Baker crafts it from wheat at the crafting table and delivers it. A pickaxe pattern
     * lies outside the food domain: filtered, not checkable.
     */
    private static void bakesBread(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WHEAT, 3)), List.of(new ItemStack(Items.BREAD)));
        BlockPos craft = bakery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, baker());
        helper.assertTrue(Patterns.setOutput(level, craft, baker(), 0, Items.BREAD).orElseThrow().checked(), "Bread checked");
        helper.assertFalse(Patterns.setOutput(level, craft, baker(), 1, Items.IRON_PICKAXE).orElseThrow().checked(), "A pickaxe is outside the domain");
        helper.assertFalse(Patterns.toggleCheck(level, craft, baker(), 1), "And cannot be checked");
        PatternsTabData tab = PatternsTabData.of(level, craft, baker());
        helper.assertTrue(tab.filtered().equals(Set.of(1))
                && tab.filteredLine().equals(Component.translatable("civmod.gui.patterns.outside_domain")), "Greyed out: " + tab.filtered());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BREAD) == 1,
                "Bread arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WHEAT) == 0, "The wheat is used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * The GameTest datapack tags flint {@code #c:foods} as a stand-in for modded food: an arrow, which the flint is
     * an input of, lies inside the Baker's domain.
     */
    private static void moddedFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos craft = bakery(helper, new BlockPos(1, 1, 5));
        helper.assertTrue(Items.FLINT.getDefaultInstance().is(baker().profile(ProducingProfile.class).orElseThrow().domain().orElseThrow()),
                "Flint counts as food through #c:foods");
        helper.assertTrue(Patterns.setOutput(level, craft, baker(), 0, Items.ARROW).orElseThrow().checked(), "An arrow pattern with a tagged input is accepted");
        helper.succeed();
    }

    /**
     * Beetroot soup needs a bowl, which is made from planks, outside the food domain: the Baker does not craft it,
     * the bowl pattern cannot be checked and nothing is delivered.
     */
    private static void skipsIntermediate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.BEETROOT, 6), new ItemStack(Items.OAK_PLANKS, 2)),
                List.of(new ItemStack(Items.BEETROOT_SOUP)));
        BlockPos craft = bakery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, baker());
        helper.assertTrue(Patterns.setOutput(level, craft, baker(), 0, Items.BEETROOT_SOUP).orElseThrow().checked(), "Soup checked");
        helper.assertFalse(Patterns.setOutput(level, craft, baker(), 1, Items.BOWL).orElseThrow().checked(), "A bowl is outside the domain");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BEETROOT_SOUP) == 0, "No soup without a bowl");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.OAK_PLANKS) == 2, "The planks stay");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** With a furnace within 4 blocks of the crafting table the Baker works at job factor 1.2: 4 breads in time. */
    private static void furnaceFaster(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.FURNACE);
        int delivered = breads(helper, 1.2);
        helper.assertTrue(delivered == 4, "With a furnace the Baker delivered 4 breads: " + delivered);
        helper.succeed();
    }

    /** The same request in the same work time without a furnace: job factor 0.3, not done yet. */
    private static void withoutFurnaceSlower(GameTestHelper helper) {
        int delivered = breads(helper, 0.3);
        helper.assertTrue(delivered < 4, "Without a furnace the Baker is slower: " + delivered);
        helper.succeed();
    }

    /** Requests 4 breads (4 executions), lets a Baker work {@link #SPEED_TICKS} and returns how many arrived. */
    private static int breads(GameTestHelper helper, double factor) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WHEAT, 12)), List.of(new ItemStack(Items.BREAD, 4)));
        BlockPos craft = bakery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, baker());
        helper.assertTrue(Patterns.setOutput(level, craft, baker(), 0, Items.BREAD).orElseThrow().checked(), "Bread checked");
        CraftspersonGameTests.catchUp(level, settler, SPEED_TICKS);
        helper.assertTrue(settler.getWork().jobFactor(level, craft) == factor, "Job factor of the day " + factor + ": "
                + settler.getWork().jobFactor(level, craft));
        int delivered = FletcherGameTests.count(level, setup.clerk(), Items.BREAD);
        FletcherGameTests.cleanUp(setup, settler);
        return delivered;
    }
}
