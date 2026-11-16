package com.gilfort.civmod.gametest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
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
 * GameTests for the Carpenter (#264, vault {@code Jobs/Carpenter.md}): a Specialization of the Craftsperson unlocked by a
 * stripped oak log, crafting in the wood domain at the Craftsperson's crafting table, job factor 1.2 with no station extra.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CarpenterGameTests {

    /** Work time of the previous workday the request tests catch up: plenty for one execution at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;
    /** The work time the speed tests catch up: enough for 4 executions at job factor 1.2, not at 0.3. */
    private static final long SPEED_TICKS = 1200L;
    /** Chests of the speed tests: one round of 4 executions, delivered when the last one is done. */
    private static final int CHESTS = 4;

    private CarpenterGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "carpenter_unlock", CarpenterGameTests::unlock);
            TestFunctions.register(helper, "carpenter_crafts_door", CarpenterGameTests::craftsDoor);
            TestFunctions.register(helper, "carpenter_modded_chest", CarpenterGameTests::moddedChest);
            TestFunctions.register(helper, "carpenter_domain_share", CarpenterGameTests::domainShare);
            TestFunctions.register(helper, "carpenter_domain_tags", CarpenterGameTests::domainTags);
            TestFunctions.register(helper, "carpenter_domain_lists", CarpenterGameTests::domainLists);
            TestFunctions.register(helper, "carpenter_faster", CarpenterGameTests::faster);
            TestFunctions.register(helper, "carpenter_craftsperson_slower", CarpenterGameTests::craftspersonSlower);
        });
    }

    /** The Carpenter. */
    static Job carpenter() {
        return StationTypeGameTests.job(CivMod.id("carpenter"));
    }

    /** A level-2 Craftsperson JobBlock with the Carpenter unlocked by a stripped oak log in its second Station Types slot. */
    static BlockPos workshop(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.STRIPPED_OAK_LOG), "A stripped oak log unlocks the Carpenter");
        return pos;
    }

    /**
     * The Carpenter specializes the Craftsperson (job factor 1.2, base EXP 2, hunger 0.5), is unlocked by a stripped oak
     * log, works {@code crafting} recipes in the wood domain at a crafting table and looks like an oak door.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job carpenter = carpenter();
        helper.assertTrue(carpenter != null && carpenter.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Carpenter specializes the Craftsperson");
        helper.assertTrue(carpenter.unlockItem().equals(Optional.of(Items.STRIPPED_OAK_LOG)), "Unlocked by a stripped oak log: " + carpenter.unlockItem());
        helper.assertTrue(carpenter.jobFactor() == 1.2 && carpenter.baseExp() == 2 && carpenter.hungerFactor() == 0.5,
                "Job factor 1.2, base EXP 2, hunger 0.5: " + carpenter.jobFactor() + " / " + carpenter.baseExp() + " / " + carpenter.hungerFactor());
        helper.assertTrue(carpenter.icon().is(Items.STRIPPED_OAK_LOG) && carpenter.bubbleIcon().is(Items.OAK_DOOR)
                && carpenter.displayItem().equals(Optional.of(Items.OAK_DOOR)), "Icon stripped oak log, bubble and display item oak door");
        helper.assertTrue(carpenter.description().getString().equals("Crafts everything made of wood at a crafting table"),
                "Description: " + carpenter.description().getString());
        ProducingProfile profile = carpenter.profile(ProducingProfile.class).orElseThrow();
        helper.assertTrue(profile.recipeTypes().equals(List.of(RecipeType.CRAFTING)), "Crafting recipes");
        helper.assertTrue(profile.station(RecipeType.CRAFTING).equals(CraftspersonGameTests.profile().station(RecipeType.CRAFTING)),
                "At the Craftsperson's crafting table");
        TagKey<Item> domain = profile.domain().orElseThrow();
        helper.assertTrue(domain.location().equals(CivMod.id("domains/wood")), "Wood domain: " + domain);
        for (Item item : List.of(Items.OAK_LOG, Items.OAK_PLANKS, Items.OAK_SLAB, Items.OAK_STAIRS, Items.OAK_DOOR, Items.OAK_FENCE,
                Items.OAK_TRAPDOOR, Items.OAK_BUTTON, Items.OAK_PRESSURE_PLATE, Items.OAK_SIGN, Items.OAK_HANGING_SIGN, Items.OAK_BOAT,
                Items.OAK_FENCE_GATE, Items.CHEST, Items.BARREL, Items.STICK, Items.STRIPPED_OAK_LOG)) {
            helper.assertTrue(item.getDefaultInstance().is(domain), item + " is in the wood domain");
        }
        helper.assertFalse(Items.IRON_INGOT.getDefaultInstance().is(domain), "Iron is not");
        helper.assertFalse(Items.COBBLESTONE.getDefaultInstance().is(domain), "Cobblestone is not");
        BlockPos pos = workshop(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(JobPlaces.lines(StationTypeGameTests.block(level, pos)).stream().map(JobPlaces.Line::job).toList().contains(carpenter),
                "The job picker offers the Carpenter");
        Patterns.syncInnate(level, pos, carpenter);
        helper.assertTrue(Patterns.pattern(StationTypeGameTests.block(level, pos), carpenter, 0).isEmpty(), "No innate recipe");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CRAFTING_TABLE);
        helper.assertTrue(carpenter.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 1))), "Works at a crafting table");
        helper.succeed();
    }

    /**
     * A Clerk Min rule on an oak door: the Carpenter crafts it from planks at the crafting table and delivers it. A chestplate
     * pattern lies outside the wood domain: filtered, not checkable.
     */
    private static void craftsDoor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.OAK_PLANKS, 6)), List.of(new ItemStack(Items.OAK_DOOR)));
        BlockPos craft = workshop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, carpenter());
        helper.assertTrue(Patterns.setOutput(level, craft, carpenter(), 0, ItemKey.of(Items.OAK_DOOR)).orElseThrow().checked(), "Door checked");
        CraftingRounds.assertOutsideDomain(helper, craft, carpenter(), 1, Items.IRON_CHESTPLATE);
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.OAK_DOOR) >= 1,
                "A door arrived (status " + settler.getWork().status(craft) + ")");
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.OAK_PLANKS) == 0, "The planks are used up");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * The GameTest datapack tags an amethyst shard {@code #c:chests/wooden} as a stand-in for the chest of a mod: a
     * spyglass, which the shard is an input of, lies inside the Carpenter's domain.
     */
    private static void moddedChest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos craft = workshop(helper, new BlockPos(1, 1, 5));
        helper.assertTrue(Items.AMETHYST_SHARD.getDefaultInstance().is(carpenter().profile(ProducingProfile.class).orElseThrow().domain().orElseThrow()),
                "The shard counts as a wooden chest through #c:chests/wooden");
        helper.assertTrue(Patterns.setOutput(level, craft, carpenter(), 0, ItemKey.of(Items.AMETHYST_BLOCK)).orElseThrow().checked(),
                "A pattern with a tagged input is accepted");
        helper.succeed();
    }

    /**
     * A pattern is in the Carpenter's domain when its output or half of its ingredient slots are wooden: a ladder (all
     * sticks), a chest (all planks) and a torch (a stick and coal, exactly 50 %) pass. A wooden pickaxe and a stone shovel
     * (2 of 3 slots wooden) fall to the {@code #c:tools} deny list, an iron pickaxe (2 of 5 slots) to the share.
     */
    private static void domainShare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos craft = workshop(helper, new BlockPos(1, 1, 1));
        int slot = 0;
        for (Item item : List.of(Items.LADDER, Items.CHEST, Items.TORCH)) {
            helper.assertTrue(Patterns.setOutput(level, craft, carpenter(), slot++, ItemKey.of(item)).orElseThrow().checked(), item + " is in the domain");
        }
        for (Item item : List.of(Items.WOODEN_PICKAXE, Items.STONE_SHOVEL, Items.IRON_PICKAXE)) {
            helper.assertFalse(Patterns.setOutput(level, craft, carpenter(), slot, ItemKey.of(item)).orElseThrow().checked(), item + " is outside the domain");
            helper.assertFalse(Patterns.toggleCheck(level, craft, carpenter(), slot++), item + " cannot be checked");
        }
        helper.succeed();
    }

    /**
     * A tag ingredient counts only when everything it accepts is wooden. The datapack recipes of this test hold a stick and an
     * iron ingot next to a plank-or-cobblestone tag (1 of 3 slots wooden, outside), a plank-only tag (2 of 3, inside), three
     * iron ingots with two sticks (40 %, outside) and one ingot with one stick (exactly 50 %, inside).
     */
    private static void domainTags(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos craft = workshop(helper, new BlockPos(1, 1, 1));
        helper.assertFalse(Patterns.setOutput(level, craft, carpenter(), 0, ItemKey.of(Items.BEDROCK)).orElseThrow().checked(), "A mixed tag does not count: 1 of 3");
        helper.assertTrue(Patterns.setOutput(level, craft, carpenter(), 1, ItemKey.of(Items.REINFORCED_DEEPSLATE)).orElseThrow().checked(),
                "A wooden-only tag counts: 2 of 3");
        helper.assertFalse(Patterns.setOutput(level, craft, carpenter(), 2, ItemKey.of(Items.DRAGON_EGG)).orElseThrow().checked(), "40 % is too little");
        helper.assertTrue(Patterns.setOutput(level, craft, carpenter(), 3, ItemKey.of(Items.SPAWNER)).orElseThrow().checked(), "Exactly 50 % passes");
        helper.succeed();
    }

    /**
     * A datapack changes the rule without code: {@code recipe_allow} lets an iron pickaxe through although only 40 % of its
     * slots are wooden, deny wins when the item is on both lists, a lower {@code domain_input_share} lets it through too and
     * a higher one rejects the torch.
     */
    private static void domainLists(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos craft = workshop(helper, new BlockPos(1, 1, 1));
        Map<Job, JobBalancing> before = new HashMap<>();
        ModJobs.REGISTRY.stream().forEach(job -> before.put(job, JobBalancing.of(job)));
        try {
            install(before, "{\"recipe_allow\": [\"minecraft:iron_pickaxe\", \"#c:tools\"]}");
            helper.assertTrue(Patterns.setOutput(level, craft, carpenter(), 0, ItemKey.of(Items.IRON_PICKAXE)).orElseThrow().checked(),
                    "An allowed output passes although the share is too low");
            install(before, "{\"recipe_allow\": \"minecraft:iron_pickaxe\", \"recipe_deny\": \"#c:tools\"}");
            helper.assertFalse(Patterns.setOutput(level, craft, carpenter(), 1, ItemKey.of(Items.IRON_PICKAXE)).orElseThrow().checked(),
                    "The same output on both lists is rejected");
            install(before, "{\"domain_input_share\": 0.4}");
            helper.assertTrue(Patterns.setOutput(level, craft, carpenter(), 2, ItemKey.of(Items.IRON_PICKAXE)).orElseThrow().checked(),
                    "A share of 40 % lets the iron pickaxe through");
            install(before, "{\"domain_input_share\": 0.6}");
            helper.assertFalse(Patterns.setOutput(level, craft, carpenter(), 3, ItemKey.of(Items.TORCH)).orElseThrow().checked(),
                    "A share of 60 % rejects the torch");
            helper.assertTrue(Patterns.setOutput(level, craft, carpenter(), 4, ItemKey.of(Items.OAK_DOOR)).orElseThrow().checked(),
                    "A door is a wooden output whatever the share");
        } finally {
            JobBalancing.install(before);
        }
        helper.succeed();
    }

    private static void install(Map<Job, JobBalancing> before, String file) {
        Map<Job, JobBalancing> loaded = new HashMap<>(before);
        loaded.put(carpenter(), JobBalancing.merge(List.<JsonElement>of(JsonParser.parseString(file))));
        JobBalancing.install(loaded);
    }

    /** The Carpenter works at job factor 1.2 straight away: 4 chests in time, with no station extra. */
    private static void faster(GameTestHelper helper) {
        int delivered = chests(helper, carpenter(), 1.2, SPEED_TICKS);
        helper.assertTrue(delivered == CHESTS, "The Carpenter delivered " + CHESTS + " chests: " + delivered);
        helper.succeed();
    }

    /** The same request in the same work time: the Craftsperson (job factor 0.3) is not done yet. */
    private static void craftspersonSlower(GameTestHelper helper) {
        int delivered = chests(helper, CraftspersonGameTests.craftJob(), 0.3, SPEED_TICKS);
        helper.assertTrue(delivered < CHESTS, "The Craftsperson is slower: " + delivered);
        helper.succeed();
    }

    /** Requests 4 chests (4 executions), lets a settler of the Job work the work time and returns how many arrived. */
    private static int chests(GameTestHelper helper, Job job, double factor, long workTicks) {
        BlockPos relative = new BlockPos(1, 1, 5);
        return CraftingRounds.deliver(helper, job, () -> job == carpenter() ? workshop(helper, relative) : CraftspersonGameTests.craftsperson(helper, relative),
                Blocks.CRAFTING_TABLE, List.of(new ItemStack(Items.OAK_PLANKS, 32)), new ItemStack(Items.CHEST, CHESTS), 0, workTicks, factor);
    }
}
