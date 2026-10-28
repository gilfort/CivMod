package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * GameTests for the Stonemason (#128, vault {@code Jobs/Stonemason.md}): a Specialization of the Craftsperson unlocked
 * by a stonecutter, cutting stone at its stonecutter ({@code stonecutting}, main type) and crafting stone recipes at a
 * crafting table nearby ({@code crafting}), both in the stone domain; a grindstone near the stonecutter replaces its job
 * factor 0.3 by 1.2. It has no innate recipe.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class StonemasonGameTests {

    /** Work time of the previous workday the request test catches up: plenty for a few executions at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;
    /** The work time the speed tests catch up: enough for 4 executions at job factor 1.2, not at 0.3. */
    private static final long SPEED_TICKS = 1200L;
    /** The example addon's Stonecutter, unlocked by the same item. */
    private static final Identifier EXAMPLE_STONECUTTER = Identifier.fromNamespaceAndPath("civmod_example", "stonecutter");

    private StonemasonGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "stonemason_unlock", StonemasonGameTests::unlock);
            register(helper, "stonemason_fills_requests", StonemasonGameTests::fillsRequests);
            register(helper, "stonemason_grindstone_faster", StonemasonGameTests::grindstoneFaster);
            register(helper, "stonemason_without_grindstone_slower", StonemasonGameTests::withoutGrindstoneSlower);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Stonemason. */
    static Job stonemason() {
        return StationTypeGameTests.job(CivMod.id("stonemason"));
    }

    /** A level-2 Craftsperson JobBlock with the Stonemason unlocked by a stonecutter in its second Station Types slot. */
    static BlockPos masonry(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.STONECUTTER), "A stonecutter unlocks the Stonemason");
        return pos;
    }

    /**
     * The Stonemason specializes the Craftsperson (job factor 0.3, base EXP 2, hunger 0.5), is unlocked by a stonecutter
     * together with the example addon's Stonecutter, works {@code stonecutting} (main) and {@code crafting} recipes in
     * the stone domain at a stonecutter and looks like stone bricks. Its library starts empty: no innate recipe.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job stonemason = stonemason();
        helper.assertTrue(stonemason != null && stonemason.parent().orElseThrow() == CraftspersonGameTests.craftJob(),
                "The Stonemason specializes the Craftsperson");
        helper.assertTrue(stonemason.unlockItem().equals(Optional.of(Items.STONECUTTER)), "Unlocked by a stonecutter: " + stonemason.unlockItem());
        helper.assertTrue(stonemason.jobFactor() == 0.3 && stonemason.baseExp() == 2 && stonemason.hungerFactor() == 0.5,
                "Job factor 0.3, base EXP 2, hunger 0.5: " + stonemason.jobFactor() + " / " + stonemason.baseExp() + " / " + stonemason.hungerFactor());
        helper.assertTrue(stonemason.icon().is(Items.STONECUTTER) && stonemason.bubbleIcon().is(Items.STONE_BRICKS)
                && stonemason.displayItem().equals(Optional.of(Items.STONE_BRICKS)), "Icon stonecutter, bubble and display item stone bricks");
        helper.assertTrue(stonemason.description().getString().equals("Crafts stone recipes and cuts stone at a stonecutter, fast with a grindstone near it"),
                "Description: " + stonemason.description().getString());
        ProducingProfile profile = stonemason.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.STONECUTTING, RecipeType.CRAFTING)), "Stonecutting (main) and crafting");
        TagKey<Item> domain = profile.domain().orElseThrow();
        helper.assertTrue(domain.location().equals(CivMod.id("domains/stone")), "Stone domain: " + domain);
        for (Item item : List.of(Items.STONE, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE, Items.STONE_BRICKS, Items.POLISHED_ANDESITE,
                Items.DEEPSLATE_BRICKS, Items.TUFF, Items.BASALT, Items.SANDSTONE, Items.RED_SANDSTONE, Items.BRICKS, Items.NETHER_BRICKS,
                Items.MUD_BRICKS, Items.PRISMARINE, Items.QUARTZ_BLOCK, Items.PURPUR_BLOCK, Items.END_STONE)) {
            helper.assertTrue(item.getDefaultInstance().is(domain), item + " is in the stone domain");
        }
        for (Item item : List.of(Items.TERRACOTTA, Items.CONCRETE.white(), Items.CLAY_BALL, Items.BRICK, Items.OAK_PLANKS)) {
            helper.assertFalse(item.getDefaultInstance().is(domain), item + " is not");
        }
        BlockPos pos = masonry(helper, new BlockPos(1, 1, 1));
        List<Job> offered = JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList();
        helper.assertTrue(offered.contains(stonemason) && offered.contains(StationTypeGameTests.job(EXAMPLE_STONECUTTER)),
                "One stonecutter brings the Stonemason and the example Stonecutter: " + offered);
        Patterns.syncInnate(level, pos, stonemason);
        helper.assertTrue(PatternsTabData.of(level, pos, stonemason).patterns().stream().noneMatch(pattern -> pattern.valid()),
                "No innate recipe");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONECUTTER);
        helper.assertTrue(stonemason.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a stonecutter");
        helper.succeed();
    }

    /**
     * Clerk Min rules on stone bricks and a furnace: the Stonemason cuts stone into bricks at its stonecutter and crafts
     * the furnace from cobblestone at a crafting table nearby. A stick pattern lies outside the stone domain: filtered,
     * not checkable.
     */
    private static void fillsRequests(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.STONE), new ItemStack(Items.COBBLESTONE, 8)),
                List.of(new ItemStack(Items.STONE_BRICKS), new ItemStack(Items.FURNACE)));
        BlockPos craft = masonry(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.STONECUTTER);
        CraftspersonGameTests.placeTable(helper, new BlockPos(6, 1, 5));
        Settler settler = FletcherGameTests.worker(helper, setup, craft, stonemason());
        helper.assertTrue(Patterns.setOutput(level, craft, stonemason(), 0, Items.STONE_BRICKS).filter(pattern -> pattern.checked()
                && pattern.recipe().flatMap(id -> PatternRecipes.holder(level, id))
                        .filter(holder -> holder.value().getType() == RecipeType.STONECUTTING).isPresent()).isPresent(),
                "Stone bricks checked, cut at the stonecutter");
        helper.assertTrue(Patterns.setOutput(level, craft, stonemason(), 1, Items.FURNACE).orElseThrow().checked(), "Furnace checked");
        helper.assertFalse(Patterns.setOutput(level, craft, stonemason(), 2, Items.STICK).orElseThrow().checked(), "Sticks are outside the domain");
        helper.assertFalse(Patterns.toggleCheck(level, craft, stonemason(), 2), "And cannot be checked");
        PatternsTabData tab = PatternsTabData.of(level, craft, stonemason());
        helper.assertTrue(tab.filtered().equals(Set.of(2))
                && tab.filteredLine().equals(Component.translatable("civmod.gui.patterns.outside_domain")), "Greyed out: " + tab.filtered());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.STONE_BRICKS) == 1,
                "Stone bricks arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.FURNACE) == 1,
                "The furnace arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.STONE) == 0
                && FletcherGameTests.count(level, setup.clerk(), Items.COBBLESTONE) == 0, "The stone is used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** With a grindstone within 4 blocks of the stonecutter the Stonemason works at job factor 1.2: 4 stone bricks in time. */
    private static void grindstoneFaster(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.GRINDSTONE);
        int delivered = bricks(helper, 1.2);
        helper.assertTrue(delivered == 4, "With a grindstone the Stonemason delivered 4 stone bricks: " + delivered);
        helper.succeed();
    }

    /** The same request in the same work time without a grindstone: job factor 0.3, not done yet. */
    private static void withoutGrindstoneSlower(GameTestHelper helper) {
        int delivered = bricks(helper, 0.3);
        helper.assertTrue(delivered < 4, "Without a grindstone the Stonemason is slower: " + delivered);
        helper.succeed();
    }

    /** Requests 4 stone bricks (4 stonecutting executions), lets a Stonemason work {@link #SPEED_TICKS} and returns how many arrived. */
    private static int bricks(GameTestHelper helper, double factor) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.STONE, 4)), List.of(new ItemStack(Items.STONE_BRICKS, 4)));
        BlockPos craft = masonry(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.STONECUTTER);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, stonemason());
        helper.assertTrue(Patterns.setOutput(level, craft, stonemason(), 0, Items.STONE_BRICKS).orElseThrow().checked(), "Stone bricks checked");
        CraftspersonGameTests.catchUp(level, settler, SPEED_TICKS);
        helper.assertTrue(settler.getWork().jobFactor(level, craft) == factor, "Job factor of the day " + factor + ": "
                + settler.getWork().jobFactor(level, craft));
        int delivered = FletcherGameTests.count(level, setup.clerk(), Items.STONE_BRICKS);
        FletcherGameTests.cleanUp(setup, settler);
        return delivered;
    }
}
