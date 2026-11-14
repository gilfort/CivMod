package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.DropPools;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.SettlementData;

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
 * GameTests for the Sifter (#252, vault {@code Jobs/Sifter.md}): a Specialization of the Craftsperson unlocked by a
 * hopper; its innate recipes sift sand, red sand and gravel with a shovel and draw one find per block from the shared
 * pool {@code civmod:sifter}. A recipe with {@code drops} runs only as an "Always produce" pattern. The draws are
 * scripted ({@link DropPools#script}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SifterGameTests {

    /** Work time the tests catch up: plenty for a few blocks at job factor 1.0. */
    private static final long WORK_TICKS = 6000L;
    /** The library slot of the sand recipe: gravel, red sand, sand in recipe id order. */
    private static final int SAND_SLOT = 2;
    /** A JobBlock level whose skill cap (20 per level) lets a skill level of 100 count. */
    private static final int TOP_BLOCK_LEVEL = 5;

    private SifterGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "sifter_unlock", SifterGameTests::unlock);
            register(helper, "sifter_level_one_draws", SifterGameTests::levelOneDraws);
            register(helper, "sifter_level_hundred_draws", SifterGameTests::levelHundredDraws);
            register(helper, "sifter_shovel_wear", SifterGameTests::shovelWear);
            register(helper, "sifter_without_shovel", SifterGameTests::withoutShovel);
            register(helper, "sifter_always_produce", SifterGameTests::alwaysProduce);
            register(helper, "sifter_serves_no_request", SifterGameTests::servesNoRequest);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Sifter. */
    static Job sifter() {
        return StationTypeGameTests.job(CivMod.id("sifter"));
    }

    /** A Craftsperson JobBlock of the given level with the Sifter unlocked by a hopper in its second Station Types slot. */
    private static BlockPos sifting(GameTestHelper helper, BlockPos relative, int blockLevel) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, blockLevel);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.HOPPER), "A hopper unlocks the Sifter");
        return pos;
    }

    /** The settler's skill level in the Sifter. */
    private static void setSkill(Settler settler, int level) {
        settler.getSkills().set(sifter(), new SettlerSkills.Skill(level, 0));
    }

    /** Turns the sand recipe into an "Always produce" pattern that sifts the given number of blocks per round. */
    private static void alwaysSift(GameTestHelper helper, BlockPos craft, int blocks) {
        ServerLevel level = helper.getLevel();
        Patterns.syncInnate(level, craft, sifter());
        helper.assertTrue(Patterns.toggleAlways(level, craft, sifter(), SAND_SLOT), "Always produce on the sand recipe");
        Patterns.setAtOnce(SettlementData.get(level), craft, sifter(), SAND_SLOT, blocks);
    }

    private static int total(ServerLevel level, Setup setup, Set<Item> items) {
        return items.stream().mapToInt(item -> FletcherGameTests.count(level, setup.clerk(), item)).sum();
    }

    /**
     * The Sifter specializes the Craftsperson (job factor 1.0, base EXP 2, hunger 0.5), is unlocked by a hopper, works at
     * a hopper and knows three innate recipes that all draw: gravel, red sand and sand, in that order.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job sifter = sifter();
        helper.assertTrue(sifter != null && sifter.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Sifter specializes the Craftsperson");
        helper.assertTrue(sifter.unlockItem().equals(Optional.of(Items.HOPPER)), "Unlocked by a hopper: " + sifter.unlockItem());
        helper.assertTrue(sifter.jobFactor() == 1.0 && sifter.baseExp() == 2 && sifter.hungerFactor() == 0.5,
                "Job factor 1.0, base EXP 2, hunger 0.5: " + sifter.jobFactor() + " / " + sifter.baseExp() + " / " + sifter.hungerFactor());
        ProducingProfile profile = sifter.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "A crafting profile that no pattern passes");
        TagKey<Item> domain = profile.domain().orElseThrow();
        helper.assertFalse(Items.SAND.getDefaultInstance().is(domain) || Items.STICK.getDefaultInstance().is(domain), "The domain tag is empty");
        BlockPos pos = sifting(helper, new BlockPos(1, 1, 1), 2);
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(sifter),
                "The job picker offers the Sifter");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.HOPPER);
        helper.assertTrue(sifter.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a hopper");
        Patterns.syncInnate(level, pos, sifter);
        List<Item> inputs = List.of(Items.GRAVEL, Items.RED_SAND, Items.SAND);
        for (int slot = 0; slot < inputs.size(); slot++) {
            Pattern pattern = PatternsTabData.of(level, pos, sifter).pattern(slot).orElseThrow();
            helper.assertTrue(pattern.innate() && pattern.checked(), "Slot " + slot + " is a checked innate recipe: " + pattern);
            JobCraftingRecipe recipe = InnateRecipes.recipe(level, pattern).orElseThrow();
            helper.assertTrue(recipe.drops().isPresent() && recipe.result().isEmpty(), "Slot " + slot + " draws: " + recipe);
            helper.assertTrue(recipe.ingredients().getFirst().test(new ItemStack(inputs.get(slot))), "Slot " + slot + " takes " + inputs.get(slot));
            helper.assertTrue(recipe.tool().isPresent(), "Slot " + slot + " needs a shovel");
            helper.assertTrue(pattern.output() != Items.AIR && pattern.count() == 1, "A drops pattern shows an item, one per execution: " + pattern);
        }
        helper.succeed();
    }

    /**
     * A level 1 Sifter sifts 8 blocks of sand with the highest and the lowest rolls and some in between: every find is
     * of the first step (bone meal, dyes, flint).
     */
    private static void levelOneDraws(GameTestHelper helper) {
        Set<Item> firstStep = Set.of(Items.BONE_MEAL, Items.DYE.white(), Items.DYE.gray(), Items.DYE.lightGray(), Items.DYE.blue(), Items.FLINT);
        sift(helper, 1, 8, new int[] { 999, 0, 500, 123, 999, 998, 1, 700 }, setup -> {
            ServerLevel level = helper.getLevel();
            helper.assertTrue(total(level, setup, firstStep) == 8, "8 finds, all of the first step");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.FLINT) >= 1, "The top roll is flint");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.SAND) == 0, "The sand is used up");
        });
    }

    /**
     * A level 100 Sifter (JobBlock level 5) with scripted rolls finds a diamond and netherite scrap at the top of the
     * pool and still junk on the other rolls.
     */
    private static void levelHundredDraws(GameTestHelper helper) {
        sift(helper, 100, 4, new int[] { 1430, 0, 1436, 500 }, setup -> {
            ServerLevel level = helper.getLevel();
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.DIAMOND) == 1, "A diamond on roll 1430");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.NETHERITE_SCRAP) == 1, "Netherite scrap on roll 1436");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 1, "Junk stays in the pool: bone meal on roll 0");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.DYE.lightGray()) == 1, "Light gray dye on roll 500");
        });
    }

    /** Sifts sand in an "Always produce" round of the given size with scripted rolls, then lets the check look at the Clerk. */
    private static void sift(GameTestHelper helper, int skill, int blocks, int[] rolls, Consumer<Setup> check) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.SAND, blocks)), List.of());
        BlockPos craft = sifting(helper, new BlockPos(1, 1, 5), TOP_BLOCK_LEVEL);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.HOPPER);
        alwaysSift(helper, craft, blocks);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, sifter());
        setSkill(settler, skill);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.NETHERITE_SHOVEL));
        DropPools.script(DropsGameTests.scripted(rolls));
        try {
            CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
            check.accept(setup);
        } finally {
            DropPools.script(null);
            FletcherGameTests.cleanUp(setup, settler);
        }
        helper.succeed();
    }

    /** The shovel loses 1 durability per sifted block. */
    private static void shovelWear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.SAND, 3)), List.of());
        BlockPos craft = sifting(helper, new BlockPos(1, 1, 5), 2);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.HOPPER);
        alwaysSift(helper, craft, 3);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, sifter());
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.IRON_SHOVEL));
        DropPools.script(DropsGameTests.scripted(0));
        try {
            CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
            ItemStack worn = settler.getInventory().getItem(Settler.BELT_START);
            helper.assertTrue(worn.is(Items.IRON_SHOVEL) && worn.getDamageValue() == 3, "3 blocks, 3 durability: " + worn + " damage " + worn.getDamageValue());
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 3, "3 finds arrived");
        } finally {
            DropPools.script(null);
            FletcherGameTests.cleanUp(setup, settler);
        }
        helper.succeed();
    }

    /** Without a shovel nothing is sifted, and the Patterns tab says "Needs Shovel" for the Sifter's recipes. */
    private static void withoutShovel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.SAND, 3)), List.of());
        BlockPos craft = sifting(helper, new BlockPos(1, 1, 5), 2);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.HOPPER);
        alwaysSift(helper, craft, 3);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, sifter());
        DropPools.script(DropsGameTests.scripted(0));
        try {
            CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.SAND) == 3, "The sand stays");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 0, "Nothing was sifted");
            PatternsTabData tab = PatternsTabData.of(level, craft, sifter());
            Optional<String> line = Optional.ofNullable(tab.needs().get(SAND_SLOT)).map(Component::getString);
            helper.assertTrue(line.equals(Optional.of("Needs Shovel")), "The sand recipe is marked: " + tab.needs());
        } finally {
            DropPools.script(null);
            FletcherGameTests.cleanUp(setup, settler);
        }
        helper.succeed();
    }

    /**
     * An "Always produce" round with an amount sifts exactly that many blocks and delivers the finds to the Home Clerk,
     * of several kinds in one round, without a Max rule on any of them.
     */
    private static void alwaysProduce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.SAND, 6)), List.of());
        BlockPos craft = sifting(helper, new BlockPos(1, 1, 5), 2);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.HOPPER);
        alwaysSift(helper, craft, 6);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, sifter());
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.IRON_SHOVEL));
        // Rolls 0 and 200 are bone meal and white dye of the first step.
        DropPools.script(DropsGameTests.scripted(0, 200, 0, 200, 0, 200));
        try {
            CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 3, "3 bone meal at the Home Clerk");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.DYE.white()) == 3, "3 white dye at the Home Clerk");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.SAND) == 0, "All 6 blocks sifted");
            helper.assertTrue(settler.getWork().buffer(craft).count() == 0, "Nothing is left in the settler's buffer");
        } finally {
            DropPools.script(null);
            FletcherGameTests.cleanUp(setup, settler);
        }
        helper.succeed();
    }

    /**
     * A recipe with drops cannot serve a request: a Min rule for bone meal, a checked gravel recipe (it shows bone meal) and gravel in stock
     * sift nothing, because the result of a draw is not known beforehand.
     */
    private static void servesNoRequest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.GRAVEL, 4)), List.of(new ItemStack(Items.BONE_MEAL, 4)));
        BlockPos craft = sifting(helper, new BlockPos(1, 1, 5), 2);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.HOPPER);
        Patterns.syncInnate(level, craft, sifter());
        Settler settler = FletcherGameTests.worker(helper, setup, craft, sifter());
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.IRON_SHOVEL));
        DropPools.script(DropsGameTests.scripted(0));
        try {
            CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.BONE_MEAL) == 0, "No bone meal: the request is not served");
            helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.GRAVEL) == 4, "The gravel stays");
        } finally {
            DropPools.script(null);
            FletcherGameTests.cleanUp(setup, settler);
        }
        helper.succeed();
    }
}
