package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for innate recipes ({@code civmod:job_crafting}, #127, vault {@code Systems/Producing Jobs.md}): they sit
 * locked in the first library slots ordered by recipe id, count towards the checkmark limit, move player patterns aside
 * on a datapack change (losing those pushed beyond the last slot), free their slot when removed and keep their check
 * state per recipe id; a recipe with its own station is worked there and skipped with a marker without it. The
 * GameTest datapack gives {@code civmod:gametest_mason} one innate recipe (cobblestone → gravel at an anvil).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class InnateRecipeGameTests {

    private static final long WORK_TICKS = 4000L;
    private static final ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> FLINT = recipe(CivMod.id("fletcher/flint_from_gravel"));
    private static final ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> STRING = recipe(CivMod.id("fletcher/string_from_wool"));

    private InnateRecipeGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "innate_first_and_locked", InnateRecipeGameTests::firstAndLocked);
            register(helper, "innate_checkmark_limit", InnateRecipeGameTests::checkmarkLimit);
            register(helper, "innate_pushed_beyond_last_slot", InnateRecipeGameTests::pushedBeyondLastSlot);
            register(helper, "innate_removed_and_check_state", InnateRecipeGameTests::removedAndCheckState);
            register(helper, "innate_station_per_recipe", InnateRecipeGameTests::stationPerRecipe);
            register(helper, "innate_missing_station", InnateRecipeGameTests::missingStation);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * The Fletcher's innate recipes fill its library's first slots by recipe id (flint, then string), locked and checked
     * automatically; edit, clear, overwrite, cycling and recipe viewer transfers are refused, the checkmark toggles,
     * a player pattern goes into the next slot.
     */
    private static void firstAndLocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job fletcher = FletcherGameTests.fletcher();
        BlockPos pos = FletcherGameTests.fletchery(helper, new BlockPos(1, 1, 1));
        Patterns.syncInnate(level, pos, fletcher);
        PatternsTabData tab = PatternsTabData.of(level, pos, fletcher);
        Pattern flint = tab.pattern(0).orElseThrow();
        Pattern string = tab.pattern(1).orElseThrow();
        helper.assertTrue(flint.innate() && flint.output() == Items.FLINT && flint.recipe().equals(Optional.of(FLINT)) && flint.checked(),
                "Slot 0: gravel → flint, locked and checked: " + flint);
        helper.assertTrue(string.innate() && string.output() == Items.STRING && string.count() == 2 && string.checked(),
                "Slot 1: wool → 2 string, locked and checked: " + string);
        helper.assertTrue(tab.innate(0) && tab.innate(1) && !tab.innate(2) && tab.unusable(0) == false, "Shown locked and usable");

        helper.assertTrue(Patterns.setOutput(level, pos, fletcher, 0, Items.ARROW).isEmpty(), "No overwrite");
        helper.assertTrue(Patterns.setInput(level, pos, fletcher, 1, 0, Items.STICK).isEmpty(), "No edit");
        helper.assertTrue(Patterns.cycle(level, pos, fletcher, 1, 1).isEmpty(), "No other recipe");
        helper.assertTrue(Patterns.transfer(level, pos, fletcher, 0, Optional.of(recipe(Identifier.withDefaultNamespace("arrow"))),
                List.of(Items.FLINT, Items.AIR, Items.AIR, Items.STICK, Items.AIR, Items.AIR, Items.FEATHER)).isEmpty(), "No recipe viewer transfer");
        Patterns.clear(SettlementData.get(level), pos, fletcher, 0);
        helper.assertTrue(PatternsTabData.of(level, pos, fletcher).pattern(0).filter(Pattern::innate).isPresent(), "Not cleared");

        helper.assertFalse(Patterns.toggleCheck(level, pos, fletcher, 1), "Unchecked");
        helper.assertTrue(Patterns.toggleCheck(level, pos, fletcher, 1), "Checked again");
        Pattern arrow = Patterns.setOutput(level, pos, fletcher, 2, Items.ARROW).orElseThrow();
        helper.assertTrue(arrow.checked() && !arrow.innate(), "A player pattern in the next slot: " + arrow);
        helper.succeed();
    }

    /**
     * Innate recipes count towards the checkmark limit (6): with both checked only 4 player patterns get a checkmark.
     * An innate recipe added by a datapack later moves the player pattern on its slot to the next free one and stays
     * unchecked while the limit is full.
     */
    private static void checkmarkLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job fletcher = FletcherGameTests.fletcher();
        BlockPos pos = FletcherGameTests.fletchery(helper, new BlockPos(1, 1, 1));
        List<Item> outputs = List.of(Items.ARROW, Items.BOW, Items.FISHING_ROD, Items.LEAD, Items.CROSSBOW);
        for (int i = 0; i < outputs.size(); i++) {
            Pattern pattern = Patterns.setOutput(level, pos, fletcher, 2 + i, outputs.get(i)).orElseThrow();
            helper.assertTrue(pattern.checked() == (i < 4), outputs.get(i) + (i < 4 ? " checked" : " unchecked at the limit"));
        }
        helper.assertTrue(PatternsTabData.of(level, pos, fletcher).checked() == 6, "6 checkmarks: 2 innate + 4");

        List<RecipeHolder<JobCraftingRecipe>> innate = with(level, fletcher, extra("fletcher/zz_test", Items.TARGET));
        helper.assertTrue(Patterns.syncInnate(level, pos, fletcher, innate), "The library changed");
        PatternsTabData tab = PatternsTabData.of(level, pos, fletcher);
        Pattern added = tab.pattern(2).orElseThrow();
        helper.assertTrue(added.innate() && added.output() == Items.TARGET && !added.checked(), "The new innate recipe in slot 2, unchecked: " + added);
        helper.assertTrue(tab.pattern(7).filter(pattern -> pattern.output() == Items.ARROW && pattern.checked()).isPresent(),
                "The arrow pattern moved to the next free slot, checked: " + tab.pattern(7));
        helper.assertTrue(tab.checked() == 6, "Still 6 checkmarks: " + tab.checked());
        helper.succeed();
    }

    /** A player pattern pushed beyond the last of the 54 slots is lost. */
    private static void pushedBeyondLastSlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job fletcher = FletcherGameTests.fletcher();
        BlockPos pos = FletcherGameTests.fletchery(helper, new BlockPos(1, 1, 1));
        for (int slot = 2; slot < Pattern.SLOTS; slot++) {
            helper.assertTrue(Patterns.setOutput(level, pos, fletcher, slot, slot == 2 ? Items.BOW : Items.ARROW).isPresent(), "Pattern " + slot);
        }
        Patterns.syncInnate(level, pos, fletcher, with(level, fletcher, extra("fletcher/zz_test", Items.TARGET)));
        PatternsTabData tab = PatternsTabData.of(level, pos, fletcher);
        helper.assertTrue(tab.patterns().size() == Pattern.SLOTS, "Every slot is used: " + tab.patterns().size());
        helper.assertTrue(tab.patterns().stream().noneMatch(pattern -> pattern.output() == Items.BOW), "The bow pattern is lost");
        helper.assertTrue(tab.pattern(53).filter(pattern -> pattern.output() == Items.ARROW).isPresent(), "The last slot keeps its pattern");
        helper.succeed();
    }

    /**
     * A removed innate recipe frees its slot; the check state belongs to the recipe id: an unchecked innate recipe
     * stays unchecked when a new recipe sorted before it moves it to another slot.
     */
    private static void removedAndCheckState(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job fletcher = FletcherGameTests.fletcher();
        BlockPos pos = FletcherGameTests.fletchery(helper, new BlockPos(1, 1, 1));
        helper.assertFalse(Patterns.toggleCheck(level, pos, fletcher, 1), "String unchecked");
        helper.assertTrue(Patterns.setOutput(level, pos, fletcher, 3, Items.ARROW).isPresent(), "Arrow pattern in slot 3");

        Patterns.syncInnate(level, pos, fletcher, with(level, fletcher, extra("fletcher/a_test", Items.TARGET)));
        PatternsTabData tab = PatternsTabData.of(level, pos, fletcher);
        helper.assertTrue(tab.pattern(0).filter(pattern -> pattern.output() == Items.TARGET).isPresent(), "The new recipe sorts first");
        helper.assertTrue(tab.pattern(1).filter(pattern -> pattern.recipe().equals(Optional.of(FLINT)) && pattern.checked()).isPresent(),
                "Flint moved to slot 1, still checked");
        helper.assertTrue(tab.pattern(2).filter(pattern -> pattern.recipe().equals(Optional.of(STRING)) && !pattern.checked()).isPresent(),
                "String moved to slot 2, still unchecked: " + tab.pattern(2));

        List<RecipeHolder<JobCraftingRecipe>> onlyFlint = InnateRecipes.of(level, fletcher).stream().filter(holder -> holder.id().equals(FLINT)).toList();
        Patterns.syncInnate(level, pos, fletcher, onlyFlint);
        tab = PatternsTabData.of(level, pos, fletcher);
        helper.assertTrue(tab.pattern(0).filter(pattern -> pattern.recipe().equals(Optional.of(FLINT))).isPresent(), "Flint in slot 0");
        helper.assertTrue(tab.pattern(1).isEmpty() && tab.pattern(2).isEmpty(), "The removed recipes freed their slots: " + tab.patterns());
        helper.assertTrue(tab.pattern(3).filter(pattern -> pattern.output() == Items.ARROW).isPresent(), "The player pattern stays");
        helper.succeed();
    }

    /**
     * An innate recipe with its own station ({@code #minecraft:anvil}) is worked at the nearest anvil, not at the Job's
     * crafting table: the mason turns cobblestone into gravel.
     */
    private static void stationPerRecipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FletcherGameTests.Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.COBBLESTONE)), List.of(new ItemStack(Items.GRAVEL)));
        BlockPos craft = masonry(helper);
        helper.setBlock(new BlockPos(1, 1, 9), Blocks.ANVIL);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, mason());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.GRAVEL) == 1, "Gravel arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(PatternsTabData.of(level, craft, mason()).needs().isEmpty(), "Nothing marked");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * Without an anvil the gravel recipe is skipped and marked "Needs a Anvil nearby"; the mason still crafts sticks
     * from its own pattern.
     */
    private static void missingStation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FletcherGameTests.Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.COBBLESTONE), new ItemStack(Items.OAK_PLANKS, 2)),
                List.of(new ItemStack(Items.GRAVEL), new ItemStack(Items.STICK, 4)));
        BlockPos craft = masonry(helper);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, mason());
        helper.assertTrue(Patterns.setOutput(level, craft, mason(), 1, Items.STICK).orElseThrow().checked(), "Sticks checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.STICK) == 4, "Sticks arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.GRAVEL) == 0, "No gravel without an anvil");
        Optional<String> line = Optional.ofNullable(PatternsTabData.of(level, craft, mason()).needs().get(0)).map(Component::getString);
        helper.assertTrue(line.equals(Optional.of("Needs a Anvil nearby")), "The gravel recipe is marked: " + line);
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** The GameTest mason ({@code civmod:gametest_mason}, crafting at a crafting table). */
    private static Job mason() {
        return StationTypeGameTests.job(CivMod.id("gametest_mason"));
    }

    /** A level-2 Craftsperson JobBlock at (1, 1, 5) with the mason unlocked and a crafting table at (3, 1, 5). */
    private static BlockPos masonry(GameTestHelper helper) {
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        StationTypeGameTests.setLevel(helper, new BlockPos(1, 1, 5), 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, craft, 1, Items.STONECUTTER), "A stonecutter unlocks the mason");
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        return craft;
    }

    /** The Job's innate recipes plus one more, ordered by recipe id. */
    private static List<RecipeHolder<JobCraftingRecipe>> with(ServerLevel level, Job job, RecipeHolder<JobCraftingRecipe> extra) {
        List<RecipeHolder<JobCraftingRecipe>> list = new ArrayList<>(InnateRecipes.of(level, job));
        list.add(extra);
        list.sort(Comparator.comparing(holder -> holder.id().identifier().toString()));
        return list;
    }

    /** An innate recipe of the Fletcher that no datapack holds: a stick makes the item. */
    private static RecipeHolder<JobCraftingRecipe> extra(String path, Item result) {
        return new RecipeHolder<>(recipe(CivMod.id(path)), new JobCraftingRecipe(List.of(CivMod.id("fletcher")), List.of(Ingredient.of(Items.STICK)),
                new ItemStackTemplate(result), Optional.empty(), Optional.empty()));
    }

    private static ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> recipe(Identifier id) {
        return ResourceKey.create(Registries.RECIPE, id);
    }
}
