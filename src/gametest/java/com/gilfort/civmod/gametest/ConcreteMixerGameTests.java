package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Concrete Mixer (#251, vault {@code Jobs/Concrete Mixer.md}): a Specialization of the Craftsperson
 * unlocked by a water bucket that works only innate recipes at a water cauldron: one concrete powder gives one
 * concrete, in every colour, and the cauldron keeps its water.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ConcreteMixerGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for a few executions at job factor 1.0. */
    private static final long WORK_TICKS = 4000L;
    /** The number of dye colours, so the number of concrete recipes. */
    private static final int COLOURS = 16;
    /** How many recipes a new Mixer checks: the checkmark limit of a settler without skill. */
    private static final int FIRST_CHECKED = 6;

    private ConcreteMixerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "concrete_mixer_unlock", ConcreteMixerGameTests::unlock);
            register(helper, "concrete_mixer_sixteen_recipes", ConcreteMixerGameTests::sixteenRecipes);
            register(helper, "concrete_mixer_white", ConcreteMixerGameTests::white);
            register(helper, "concrete_mixer_without_cauldron", ConcreteMixerGameTests::withoutCauldron);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Concrete Mixer. */
    static Job mixer() {
        return StationTypeGameTests.job(CivMod.id("concrete_mixer"));
    }

    /** A level-2 Craftsperson JobBlock with the Concrete Mixer unlocked by a water bucket in its second Station Types slot. */
    static BlockPos mixery(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.WATER_BUCKET), "A water bucket unlocks the Concrete Mixer");
        return pos;
    }

    private static Item powder(DyeColor colour) {
        return CrusherGameTests.vanilla(colour.getSerializedName() + "_concrete_powder");
    }

    private static Item concrete(DyeColor colour) {
        return CrusherGameTests.vanilla(colour.getSerializedName() + "_concrete");
    }

    /**
     * The Concrete Mixer specializes the Craftsperson (job factor 1.0, base EXP 2, hunger 0.5), is unlocked by a water
     * bucket, works innate recipes only at the water cauldron station the Dyer bleaches at and looks like white concrete.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job mixer = mixer();
        helper.assertTrue(mixer != null && mixer.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Concrete Mixer specializes the Craftsperson");
        helper.assertTrue(mixer.unlockItem().equals(Optional.of(Items.WATER_BUCKET)), "Unlocked by a water bucket: " + mixer.unlockItem());
        helper.assertTrue(mixer.jobFactor() == 1.0 && mixer.baseExp() == 2 && mixer.hungerFactor() == 0.5,
                "Job factor 1.0, base EXP 2, hunger 0.5: " + mixer.jobFactor() + " / " + mixer.baseExp() + " / " + mixer.hungerFactor());
        helper.assertTrue(mixer.icon().is(Items.CAULDRON) && mixer.bubbleIcon().is(Items.CONCRETE.white())
                && mixer.displayItem().equals(Optional.of(Items.CONCRETE.white())), "Icon cauldron, bubble and display item white concrete");
        helper.assertTrue(mixer.description().getString().equals("Turns concrete powder into concrete at a water cauldron"),
                "Description: " + mixer.description().getString());
        ProducingProfile profile = mixer.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting is the only recipe type");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).location().equals(CivMod.id("stations/water_cauldron")),
                "The station tag is the water cauldron's: " + profile.station(RecipeType.CRAFTING));
        BlockPos pos = mixery(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(mixer),
                "The job picker offers the Concrete Mixer");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.WATER_CAULDRON);
        helper.assertTrue(mixer.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a water cauldron");
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CAULDRON);
        helper.assertFalse(mixer.isValidWorkPlace(level, helper.absolutePos(new BlockPos(5, 1, 1))), "Not at an empty cauldron");
        helper.succeed();
    }

    /**
     * One recipe per colour, each one concrete powder to one concrete of the same colour, none needing a tool; they sit
     * locked in the first 16 slots and the first six are checked (limit 6).
     */
    private static void sixteenRecipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job mixer = mixer();
        List<JobCraftingRecipe> recipes = InnateRecipes.of(level, mixer).stream().map(RecipeHolder::value).toList();
        helper.assertTrue(recipes.size() == COLOURS, "16 recipes: " + recipes.size());
        for (DyeColor colour : DyeColor.values()) {
            Optional<ItemStack> result = CrusherGameTests.resultFor(level, mixer, powder(colour));
            helper.assertTrue(result.filter(stack -> stack.is(concrete(colour)) && stack.getCount() == 1).isPresent(),
                    colour + " powder gives 1 concrete: " + result);
        }
        helper.assertTrue(recipes.stream().map(JobCraftingRecipe::tool).allMatch(Optional::isEmpty), "No recipe needs a tool");
        BlockPos pos = mixery(helper, new BlockPos(1, 1, 1));
        Patterns.syncInnate(level, pos, mixer);
        for (int slot = 0; slot < COLOURS; slot++) {
            Optional<Pattern> pattern = Patterns.pattern(StationTypeGameTests.block(level, pos), mixer, slot);
            boolean firstSix = slot < FIRST_CHECKED;
            helper.assertTrue(pattern.filter(Pattern::innate).filter(found -> found.checked() == firstSix).isPresent(),
                    "Slot " + slot + " holds an innate recipe, checked for the first six only: " + pattern);
        }
        helper.assertTrue(Patterns.pattern(StationTypeGameTests.block(level, pos), mixer, COLOURS).isEmpty(), "No 17th recipe");
        helper.succeed();
    }

    /**
     * A Clerk Min rule on white concrete, white concrete powder in stock: the Mixer turns it into concrete at a water
     * cauldron, which still holds its water afterwards.
     */
    private static void white(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(powder(DyeColor.WHITE))), List.of(new ItemStack(concrete(DyeColor.WHITE))));
        BlockPos craft = mixery(helper, new BlockPos(1, 1, 5));
        BlockPos cauldron = new BlockPos(3, 1, 5);
        helper.setBlock(cauldron, Blocks.WATER_CAULDRON);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, mixer());
        checkOnly(helper, craft, concrete(DyeColor.WHITE));
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), concrete(DyeColor.WHITE)) == 1,
                "The white concrete arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), powder(DyeColor.WHITE)) == 0, "The powder is used up");
        helper.assertTrue(helper.getBlockState(cauldron).is(Blocks.WATER_CAULDRON), "The cauldron still holds water: " + helper.getBlockState(cauldron));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** Without a water cauldron nothing is mixed and the Mixer reports that it has no work place. */
    private static void withoutCauldron(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(powder(DyeColor.WHITE))), List.of(new ItemStack(concrete(DyeColor.WHITE))));
        BlockPos craft = mixery(helper, new BlockPos(1, 1, 5));
        Settler settler = FletcherGameTests.worker(helper, setup, craft, mixer());
        checkOnly(helper, craft, concrete(DyeColor.WHITE));
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), concrete(DyeColor.WHITE)) == 0, "No concrete without a water cauldron");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), powder(DyeColor.WHITE)) == 1, "The powder stays");
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.NO_WORK_PLACE, "Status: " + settler.getWork().status(craft));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** Checks the one innate recipe that makes the item and unchecks every other: a new Mixer has only its first six checked. */
    private static void checkOnly(GameTestHelper helper, BlockPos craft, Item output) {
        ServerLevel level = helper.getLevel();
        Job mixer = mixer();
        Patterns.syncInnate(level, craft, mixer);
        for (int slot = 0; slot < COLOURS; slot++) {
            Pattern pattern = Patterns.pattern(StationTypeGameTests.block(level, craft), mixer, slot).orElseThrow();
            boolean wanted = pattern.output().equals(output);
            if (pattern.checked() != wanted) {
                helper.assertTrue(Patterns.toggleCheck(level, craft, mixer, slot) == wanted, "Toggled slot " + slot);
            }
        }
    }
}
