package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.PatternLayout;
import com.gilfort.civmod.logistics.PatternRecipes;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestPatterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the pattern layouts per recipe type (#145, vault {@code Systems/Producing Jobs.md}): the editor shows
 * the layout of the pattern's recipe type; short layouts use the first 1 or 3 of the saved 9 cells, and patterns saved
 * before keep working.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class PatternLayoutGameTests {

    /** Work time of the previous workday: plenty for one stonecutting execution at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;
    private static final Identifier EXAMPLE_STONECUTTER = Identifier.fromNamespaceAndPath("civmod_example", "stonecutter");

    private PatternLayoutGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "pattern_layout_stonecutting", PatternLayoutGameTests::stonecutting);
            register(helper, "pattern_layout_smithing", PatternLayoutGameTests::smithing);
            register(helper, "pattern_layout_old_pattern", PatternLayoutGameTests::oldPattern);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * The Stonemason (stonecutting, crafting) gets one layout button per recipe type: the stonecutter's single input,
     * the crafting table's 3 × 3 grid. An exact stonecutting pattern matches from its first cell only; switching the
     * layout clears the inputs. Innate recipes show a row of inputs.
     */
    private static void stonecutting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job stonemason = StonemasonGameTests.stonemason();
        BlockPos pos = StonemasonGameTests.masonry(helper, new BlockPos(1, 1, 1));
        PatternsTabData tab = PatternsTabData.of(level, pos, stonemason);
        helper.assertTrue(tab.layouts().equals(List.of(new PatternLayout(RecipeType.STONECUTTING, PatternLayout.Kind.SINGLE, Items.STONECUTTER),
                new PatternLayout(RecipeType.CRAFTING, PatternLayout.Kind.GRID, Items.CRAFTING_TABLE))), "Stonecutter and crafting table: " + tab.layouts());
        Pattern cut = Patterns.setInput(level, pos, stonemason, 0, 0, Items.STONE, Optional.of(RecipeType.STONECUTTING)).orElseThrow();
        helper.assertTrue(cut.exact() && cut.valid() && cut.layout().equals(Optional.of(RecipeType.STONECUTTING))
                && cut.recipe().flatMap(id -> PatternRecipes.holder(level, id)).filter(holder -> holder.value().getType() == RecipeType.STONECUTTING)
                        .isPresent(), "One stone in the first cell matches a stonecutting recipe: " + cut);
        helper.assertTrue(Patterns.setInput(level, pos, stonemason, 0, 1, Items.STONE, Optional.of(RecipeType.STONECUTTING)).isEmpty(),
                "The stonecutter layout has one cell");
        helper.assertTrue(PatternsTabData.of(level, pos, stonemason).layout(0).equals(tab.layouts().getFirst()), "The slot shows the stonecutter layout");
        Pattern crafted = Patterns.setInput(level, pos, stonemason, 0, 4, Items.COBBLESTONE, Optional.of(RecipeType.CRAFTING)).orElseThrow();
        helper.assertTrue(crafted.grid().get(0) == Items.AIR && crafted.grid().get(4) == Items.COBBLESTONE
                && crafted.layout().equals(Optional.of(RecipeType.CRAFTING)), "Switching the layout clears the inputs: " + crafted.grid());
        Pattern bricks = Patterns.setOutput(level, pos, stonemason, 1, Items.STONE_BRICKS).orElseThrow();
        helper.assertTrue(bricks.layout().equals(Optional.of(RecipeType.STONECUTTING)), "Output first: the recipe sets the layout " + bricks);

        Job stonecutter = StationTypeGameTests.job(EXAMPLE_STONECUTTER);
        Patterns.syncInnate(level, pos, stonecutter);
        PatternsTabData innate = PatternsTabData.of(level, pos, stonecutter);
        helper.assertTrue(innate.pattern(0).filter(Pattern::innate).isPresent() && innate.layout(0).kind() == PatternLayout.Kind.ROW,
                "An innate recipe shows a row of inputs: " + innate.layout(0));
        helper.succeed();
    }

    /** The Smith's smithing layout: template, base and addition in the first 3 cells make the netherite upgrade. */
    private static void smithing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job smith = SmithGameTests.smith();
        BlockPos pos = SmithGameTests.smithy(helper, new BlockPos(1, 1, 1));
        PatternsTabData tab = PatternsTabData.of(level, pos, smith);
        helper.assertTrue(tab.layouts().get(1).equals(new PatternLayout(RecipeType.SMITHING, PatternLayout.Kind.SMITHING, Items.SMITHING_TABLE)),
                "Smithing table layout: " + tab.layouts());
        Optional<RecipeType<?>> smithing = Optional.of(RecipeType.SMITHING);
        Patterns.setInput(level, pos, smith, 0, 0, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, smithing);
        Patterns.setInput(level, pos, smith, 0, 1, Items.DIAMOND_SWORD, smithing);
        Pattern upgrade = Patterns.setInput(level, pos, smith, 0, 2, Items.NETHERITE_INGOT, smithing).orElseThrow();
        helper.assertTrue(upgrade.exact() && upgrade.output() == Items.NETHERITE_SWORD, "Template, base, addition: netherite sword " + upgrade);
        helper.assertTrue(Patterns.setInput(level, pos, smith, 0, 3, Items.STICK, smithing).isEmpty(), "The smithing layout has three cells");
        helper.assertTrue(Patterns.setOutput(level, pos, smith, 1, Items.NETHERITE_CHESTPLATE).orElseThrow().layout().equals(smithing),
                "Output first: a smithing recipe sets the smithing layout");
        helper.assertTrue(PatternsTabData.of(level, pos, smith).layout(1).kind() == PatternLayout.Kind.SMITHING, "The slot shows it");
        helper.succeed();
    }

    /**
     * A pattern saved before the layouts (no layout, its stone in the middle of the 9-cell grid) loads unchanged, shows
     * the layout of its recipe and is still crafted.
     */
    private static void oldPattern(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FletcherGameTests.Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.STONE)), List.of(new ItemStack(Items.STONE_BRICKS)));
        Job stonemason = StonemasonGameTests.stonemason();
        BlockPos craft = StonemasonGameTests.masonry(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.STONECUTTER);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, stonemason);
        PatternRecipes.Shape shape = PatternRecipes.producing(level, List.of(RecipeType.STONECUTTING), Items.STONE_BRICKS).getFirst();
        List<Item> grid = new ArrayList<>(Collections.nCopies(Pattern.GRID, Items.AIR));
        grid.set(4, Items.STONE);
        Pattern saved = new Pattern(0, Items.STONE_BRICKS, 1, grid, Optional.of(shape.id()), true, true);
        Tag encoded = Pattern.CODEC.encodeStart(NbtOps.INSTANCE, saved).getOrThrow();
        helper.assertFalse(encoded.toString().contains("layout"), "Saved without a layout: " + encoded);
        Pattern loaded = Pattern.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        helper.assertTrue(loaded.equals(saved) && loaded.layout().isEmpty(), "Loads unchanged: " + loaded);
        TestPatterns.put(SettlementData.get(level), craft, stonemason, loaded);
        helper.assertTrue(PatternsTabData.of(level, craft, stonemason).layout(0).type() == RecipeType.STONECUTTING, "Shows the layout of its recipe");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.STONE_BRICKS) == 1,
                "The old pattern still crafts (status " + settler.getWork().status(craft) + ")");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }
}
