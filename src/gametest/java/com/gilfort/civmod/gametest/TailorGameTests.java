package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.WoolColors;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModClothing;
import com.gilfort.civmod.registry.ModClothing.ClothSet;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Tailor (#259, vault {@code Jobs/Tailor.md}): a Specialization of the Craftsperson unlocked by white
 * wool, crafting in the textile domain at the Craftsperson's crafting table, sewing the three cloth sets with innate
 * recipes and dyeing each piece with the colour of the wool it used. A loom within 4 blocks of the table replaces its
 * job factor 0.3 by 1.2.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class TailorGameTests {

    /** Work time the request tests catch up: plenty for a few executions at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;
    /** Innate recipes of the Tailor: four pieces of three sets. */
    private static final int INNATE_RECIPES = 12;
    /** Wool a chestplate takes. */
    private static final int CHESTPLATE_WOOL = 8;
    /** The pieces of a set. */
    private static final List<ArmorType> PIECES = List.of(ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS);

    private TailorGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "tailor_unlock", TailorGameTests::unlock);
            TestFunctions.register(helper, "tailor_recipes", TailorGameTests::recipes);
            TestFunctions.register(helper, "tailor_wool_colour_choice", TailorGameTests::colourChoice);
            TestFunctions.register(helper, "tailor_colour_red", TailorGameTests::colourRed);
            TestFunctions.register(helper, "tailor_colour_majority", TailorGameTests::colourMajority);
            TestFunctions.register(helper, "tailor_colour_tie", TailorGameTests::colourTie);
            TestFunctions.register(helper, "tailor_colour_white", TailorGameTests::colourWhite);
            TestFunctions.register(helper, "tailor_medium_needs_trim", TailorGameTests::mediumNeedsTrim);
            TestFunctions.register(helper, "tailor_domain", TailorGameTests::domain);
            TestFunctions.register(helper, "tailor_clerk_request", TailorGameTests::clerkRequest);
            TestFunctions.register(helper, "tailor_loom_faster", TailorGameTests::loomFaster);
            TestFunctions.register(helper, "tailor_without_loom_slower", TailorGameTests::withoutLoomSlower);
        });
    }

    /** The Tailor. */
    static Job tailor() {
        return StationTypeGameTests.job(CivMod.id("tailor"));
    }

    /** A level-2 Craftsperson JobBlock with the Tailor unlocked by white wool in its second Station Types slot. */
    static BlockPos tailorShop(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.WOOL.white()), "White wool unlocks the Tailor");
        return pos;
    }

    private static Item piece(ClothSet set, ArmorType type) {
        return ModClothing.piece(set, type).get();
    }

    /**
     * The Tailor specializes the Craftsperson (job factor 0.3, base EXP 2, hunger 0.5), is unlocked by white wool and
     * works {@code crafting} recipes in the textile domain at a crafting table.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job tailor = tailor();
        helper.assertTrue(tailor != null && tailor.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Tailor specializes the Craftsperson");
        helper.assertTrue(tailor.unlockItem().equals(Optional.of(Items.WOOL.white())), "Unlocked by white wool: " + tailor.unlockItem());
        helper.assertTrue(tailor.jobFactor() == 0.3 && tailor.baseExp() == 2 && tailor.hungerFactor() == 0.5,
                "Job factor 0.3, base EXP 2, hunger 0.5: " + tailor.jobFactor() + " / " + tailor.baseExp() + " / " + tailor.hungerFactor());
        helper.assertTrue(tailor.icon().is(Items.WOOL.white()), "Icon white wool");
        helper.assertTrue(tailor.description().getString().equals("Sews cloth and crafts everything made of wool and leather at a crafting table, fast with a loom near it"),
                "Description: " + tailor.description().getString());
        ProducingProfile profile = tailor.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting recipes");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).equals(CraftspersonGameTests.profile().station(RecipeType.CRAFTING)),
                "At the Craftsperson's crafting table");
        TagKey<Item> domain = profile.domain().orElseThrow();
        helper.assertTrue(domain.location().equals(CivMod.id("domains/textile")), "Textile domain: " + domain);
        for (Item item : List.of(Items.WOOL.white(), Items.WOOL.red(), Items.CARPET.white(), Items.BED.red(), Items.BANNER.white(), Items.LEATHER, Items.STRING)) {
            helper.assertTrue(item.getDefaultInstance().is(domain), item + " is in the textile domain");
        }
        helper.assertFalse(Items.OAK_PLANKS.getDefaultInstance().is(domain), "Planks are not");
        helper.assertFalse(Items.IRON_INGOT.getDefaultInstance().is(domain), "Iron is not");
        BlockPos pos = tailorShop(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(tailor),
                "The job picker offers the Tailor");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CRAFTING_TABLE);
        helper.assertTrue(tailor.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a crafting table");
        helper.succeed();
    }

    /** The twelve cloth recipes are innate recipes of the Tailor and fill the first slots of its library. */
    private static void recipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job tailor = tailor();
        BlockPos pos = tailorShop(helper, new BlockPos(1, 1, 1));
        Patterns.syncInnate(level, pos, tailor);
        var block = StationTypeGameTests.block(level, pos);
        Set<Item> made = new HashSet<>();
        for (int slot = 0; slot < INNATE_RECIPES; slot++) {
            var pattern = Patterns.pattern(block, tailor, slot);
            helper.assertTrue(pattern.isPresent(), "Innate recipe in slot " + slot);
            made.add(pattern.get().output());
        }
        helper.assertTrue(Patterns.pattern(block, tailor, INNATE_RECIPES).isEmpty(), "Exactly twelve innate recipes");
        for (ClothSet set : ClothSet.values()) {
            for (ArmorType type : PIECES) {
                helper.assertTrue(made.contains(piece(set, type)), set + " " + type + " has a recipe");
            }
        }
        helper.succeed();
    }

    /** The pure colour choice: the most common colour wins, the first one listed on a tie, nothing for no wool. */
    private static void colourChoice(GameTestHelper helper) {
        DyeColor red = DyeColor.RED;
        DyeColor blue = DyeColor.BLUE;
        helper.assertTrue(WoolColors.dominant(List.of()).isEmpty(), "No wool, no colour");
        helper.assertTrue(WoolColors.dominant(List.of(red)).equals(Optional.of(red)), "One wool");
        helper.assertTrue(WoolColors.dominant(colours(red, 5, blue, 3)).equals(Optional.of(red)), "5 red, 3 blue: red");
        helper.assertTrue(WoolColors.dominant(colours(blue, 3, red, 5)).equals(Optional.of(red)), "3 blue, 5 red: red, whatever the order");
        helper.assertTrue(WoolColors.dominant(colours(red, 4, blue, 4)).equals(Optional.of(red)), "4 red first, 4 blue: red");
        helper.assertTrue(WoolColors.dominant(colours(blue, 4, red, 4)).equals(Optional.of(blue)), "4 blue first, 4 red: blue");
        helper.assertTrue(WoolColors.dominant(List.of(red, blue, blue, red)).equals(Optional.of(red)), "A tie in mixed order: the first colour listed");
        helper.assertTrue(WoolColors.of(new ItemStack(Items.WOOL.red())).equals(Optional.of(red)), "Red wool is red");
        helper.assertTrue(WoolColors.of(new ItemStack(Items.WOOL.white())).equals(Optional.of(DyeColor.WHITE)), "White wool is white");
        helper.assertTrue(WoolColors.of(new ItemStack(Items.STRING)).isEmpty(), "String has no wool colour");
        helper.assertTrue(WoolColors.of(new ItemStack(Items.CARPET.red())).isEmpty(), "A carpet is no wool");
        helper.succeed();
    }

    private static List<DyeColor> colours(DyeColor first, int firstCount, DyeColor second, int secondCount) {
        List<DyeColor> colours = new ArrayList<>();
        for (int i = 0; i < firstCount; i++) {
            colours.add(first);
        }
        for (int i = 0; i < secondCount; i++) {
            colours.add(second);
        }
        return colours;
    }

    /** A chestplate from 8 red wool and a string is dyed red; the wool and the string are used up. */
    private static void colourRed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item chestplate = piece(ClothSet.SIMPLE, ArmorType.CHESTPLATE);
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.red(), CHESTPLATE_WOOL), new ItemStack(Items.STRING)),
                List.of(new ItemStack(chestplate)));
        BlockPos craft = tailorShop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, tailor());
        checkOnly(helper, craft, chestplate);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        ItemStack made = delivered(level, setup, chestplate);
        helper.assertTrue(!made.isEmpty(), "A chestplate arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(colourOf(made) == rgb(DyeColor.RED), "Dyed red: " + colourOf(made));
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WOOL.red()) == 0, "The wool is used up");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.STRING) == 0, "The string is used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** From 5 red and 3 blue wool the chestplate is red, whichever lies first. */
    private static void colourMajority(GameTestHelper helper) {
        int colour = chestplateColour(helper, List.of(new ItemStack(Items.WOOL.blue(), 3), new ItemStack(Items.WOOL.red(), 5)));
        helper.assertTrue(colour == rgb(DyeColor.RED), "5 red against 3 blue is red: " + colour);
        helper.succeed();
    }

    /** From 4 red and 4 blue wool the chestplate takes the colour of the first slot. */
    private static void colourTie(GameTestHelper helper) {
        int colour = chestplateColour(helper, List.of(new ItemStack(Items.WOOL.blue(), 4), new ItemStack(Items.WOOL.red(), 4)));
        helper.assertTrue(colour == rgb(DyeColor.BLUE), "4 blue in the first slot against 4 red is blue: " + colour);
        helper.succeed();
    }

    /** White wool gives a white piece: undyed cloth is white, so the piece carries no dye. */
    private static void colourWhite(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item boots = piece(ClothSet.SIMPLE, ArmorType.BOOTS);
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.white(), 4), new ItemStack(Items.STRING)), List.of(new ItemStack(boots)));
        BlockPos craft = tailorShop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, tailor());
        checkOnly(helper, craft, boots);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        ItemStack made = delivered(level, setup, boots);
        helper.assertTrue(!made.isEmpty(), "Boots arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertFalse(made.has(DataComponents.DYED_COLOR), "Undyed, so white: " + made.get(DataComponents.DYED_COLOR));
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** The colour of the chestplate the Tailor sews from the wool (in the order of the chest) and a string. */
    private static int chestplateColour(GameTestHelper helper, List<ItemStack> wool) {
        ServerLevel level = helper.getLevel();
        Item chestplate = piece(ClothSet.SIMPLE, ArmorType.CHESTPLATE);
        List<ItemStack> stock = new ArrayList<>(wool);
        stock.add(new ItemStack(Items.STRING));
        Setup setup = FletcherGameTests.setup(helper, stock, List.of(new ItemStack(chestplate)));
        BlockPos craft = tailorShop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, tailor());
        checkOnly(helper, craft, chestplate);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        ItemStack made = delivered(level, setup, chestplate);
        helper.assertTrue(!made.isEmpty(), "A chestplate arrived (status " + settler.getWork().status(craft) + ")");
        int colour = colourOf(made);
        FletcherGameTests.cleanUp(setup, settler);
        return colour;
    }

    /**
     * A Medium Cloth helmet needs lapis lazuli and light blue dye on top of wool and string: without them nothing is
     * made and the request misses both; with both in stock it is made.
     */
    private static void mediumNeedsTrim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item helmet = piece(ClothSet.MEDIUM, ArmorType.HELMET);
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.red(), 5), new ItemStack(Items.STRING)), List.of(new ItemStack(helmet)));
        BlockPos craft = tailorShop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, tailor());
        checkOnly(helper, craft, helmet);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(delivered(level, setup, helmet).isEmpty(), "No helmet without lapis and dye");
        List<Request.Missing> missing = Requests.forRule(setup.settlement(), setup.clerk(), 0).orElseThrow().missing();
        helper.assertTrue(missing.contains(new Request.Missing(Items.LAPIS_LAZULI, 1)), "Missing lapis lazuli: " + missing);
        helper.assertTrue(missing.contains(new Request.Missing(Items.DYE.lightBlue(), 1)), "Missing light blue dye: " + missing);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WOOL.red()) == 5, "The wool stays");
        ClerkStorage.stock(level, setup.clerk(), true).insert(new ItemStack(Items.LAPIS_LAZULI));
        ClerkStorage.stock(level, setup.clerk(), true).insert(new ItemStack(Items.DYE.lightBlue()));
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(!delivered(level, setup, helmet).isEmpty(), "With both in stock the helmet is made (status " + settler.getWork().status(craft) + ")");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * A white carpet pattern lies inside the textile domain and is checked; an iron pickaxe pattern lies outside, is
     * greyed out and cannot be checked.
     */
    private static void domain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job tailor = tailor();
        BlockPos craft = tailorShop(helper, new BlockPos(1, 1, 5));
        Patterns.syncInnate(level, craft, tailor);
        // The six innate recipes the library checked on its own fill the limit: free a checkmark.
        Patterns.toggleCheck(level, craft, tailor, 0);
        helper.assertTrue(Patterns.setOutput(level, craft, tailor, INNATE_RECIPES, Items.CARPET.white()).orElseThrow().checked(), "A carpet pattern is checked");
        helper.assertFalse(Patterns.setOutput(level, craft, tailor, INNATE_RECIPES + 1, Items.IRON_PICKAXE).orElseThrow().checked(), "A pickaxe is outside the domain");
        helper.assertFalse(Patterns.toggleCheck(level, craft, tailor, INNATE_RECIPES + 1), "And cannot be checked");
        PatternsTabData tab = PatternsTabData.of(level, craft, tailor);
        helper.assertTrue(tab.filtered().equals(Set.of(INNATE_RECIPES + 1))
                && tab.filteredLine().equals(Component.translatable("civmod.gui.patterns.outside_domain")), "Greyed out: " + tab.filtered());
        helper.succeed();
    }

    /** A Clerk Min rule on Simple Cloth boots is served: the Tailor sews them from 4 wool and a string. */
    private static void clerkRequest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item boots = piece(ClothSet.SIMPLE, ArmorType.BOOTS);
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.blue(), 4), new ItemStack(Items.STRING)), List.of(new ItemStack(boots)));
        BlockPos craft = tailorShop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, tailor());
        checkOnly(helper, craft, boots);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), boots) == 1, "The boots arrived (status " + settler.getWork().status(craft) + ")");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** With a loom within 4 blocks of the crafting table the job factor of the day is 1.2. */
    private static void loomFaster(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.LOOM);
        double factor = jobFactor(helper);
        helper.assertTrue(factor == 1.2, "With a loom 1.2: " + factor);
        helper.succeed();
    }

    /** Without a loom the job factor of the day is the base 0.3. */
    private static void withoutLoomSlower(GameTestHelper helper) {
        double factor = jobFactor(helper);
        helper.assertTrue(factor == 0.3, "Without a loom 0.3: " + factor);
        helper.succeed();
    }

    private static double jobFactor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item boots = piece(ClothSet.SIMPLE, ArmorType.BOOTS);
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.white(), 4), new ItemStack(Items.STRING)), List.of(new ItemStack(boots)));
        BlockPos craft = tailorShop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, tailor());
        checkOnly(helper, craft, boots);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        double factor = settler.getWork().jobFactor(level, craft);
        FletcherGameTests.cleanUp(setup, settler);
        return factor;
    }

    /**
     * Keeps only the innate recipe that makes the item checked. The Tailor has twelve innate recipes and a settler of
     * level 1 checks six patterns at most, so a test chooses the one it wants worked.
     */
    private static void checkOnly(GameTestHelper helper, BlockPos craft, Item wanted) {
        ServerLevel level = helper.getLevel();
        Job tailor = tailor();
        Patterns.syncInnate(level, craft, tailor);
        var block = StationTypeGameTests.block(level, craft);
        for (int slot = 0; slot < INNATE_RECIPES; slot++) {
            var pattern = Patterns.pattern(block, tailor, slot).orElseThrow();
            if (pattern.checked() != (pattern.output() == wanted)) {
                Patterns.toggleCheck(level, craft, tailor, slot);
            }
        }
    }

    /** The first stack of the item in the Clerk's storage, empty if there is none. */    private static ItemStack delivered(ServerLevel level, Setup setup, Item item) {
        return ClerkStorage.stock(level, setup.clerk(), true).contents().stream().filter(stack -> stack.is(item)).findFirst().orElse(ItemStack.EMPTY);
    }

    private static int colourOf(ItemStack stack) {
        DyedItemColor dye = stack.get(DataComponents.DYED_COLOR);
        return dye == null ? -1 : dye.rgb() & 0xFFFFFF;
    }

    private static int rgb(DyeColor colour) {
        return colour.getTextureDiffuseColor() & 0xFFFFFF;
    }
}
