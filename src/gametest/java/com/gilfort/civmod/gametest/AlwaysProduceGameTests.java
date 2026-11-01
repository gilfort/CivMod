package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for "Always produce" (#146, vault {@code Systems/Producing Jobs.md}): a Producing Job works chosen patterns
 * in its idle time, without a request and without reserving inputs. They use the Craftsperson, as the loop is the same
 * for every Producing Job: sticks (4 per craft) from planks, torches (4 per craft) from coal and sticks, planks (4 per
 * craft) from logs.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AlwaysProduceGameTests {

    private AlwaysProduceGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "always_produce_pattern", AlwaysProduceGameTests::pattern);
            register(helper, "always_produce_round_robin", AlwaysProduceGameTests::roundRobin);
            register(helper, "always_produce_request_first", AlwaysProduceGameTests::requestFirst);
            register(helper, "always_produce_request_lacks_inputs", AlwaysProduceGameTests::requestLacksInputs);
            register(helper, "always_produce_max_and_missing", AlwaysProduceGameTests::maxAndMissing);
            register(helper, "always_produce_no_reservation", AlwaysProduceGameTests::noReservation);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * The box also checks the pattern, the checkmark limit stays, the amount needs the box and an edit keeps both; old
     * patterns load with the box off and both survive the save codec.
     */
    private static void pattern(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ClerkGameTests.clerkWithStorage(helper);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        SettlementData data = SettlementData.get(level);
        CraftspersonGameTests.define(helper, craft, 0, Items.STICK);
        Patterns.setAtOnce(data, craft, CraftspersonGameTests.craftJob(), 0, 5);
        helper.assertTrue(library(level, craft, 0).atOnce() == Pattern.DEFAULT_AT_ONCE, "The amount is inactive without the box");
        Patterns.toggleCheck(level, craft, CraftspersonGameTests.craftJob(), 0);
        helper.assertTrue(always(level, craft, 0), "Setting the box");
        helper.assertTrue(library(level, craft, 0).checked(), "The box also checks the pattern");
        Patterns.setAtOnce(data, craft, CraftspersonGameTests.craftJob(), 0, 5);
        Patterns.setAtOnce(data, craft, CraftspersonGameTests.craftJob(), 0, 0);
        helper.assertTrue(library(level, craft, 0).atOnce() == 1, "At least one craft");
        Patterns.toggleCheck(level, craft, CraftspersonGameTests.craftJob(), 0);
        helper.assertFalse(library(level, craft, 0).always(), "Without its checkmark it is no longer always produced");
        helper.assertTrue(always(level, craft, 0), "Setting the box again");
        Patterns.setAtOnce(data, craft, CraftspersonGameTests.craftJob(), 0, 16);
        Patterns.cycle(level, craft, CraftspersonGameTests.craftJob(), 0, 1);
        helper.assertTrue(library(level, craft, 0).always() && library(level, craft, 0).atOnce() == 16, "Replacing the pattern keeps the settings");

        for (int slot = 1; slot < Patterns.BASE_CHECKMARKS; slot++) {
            CraftspersonGameTests.define(helper, craft, slot, slot % 2 == 0 ? Items.TORCH : Items.OAK_PLANKS);
        }
        Patterns.setOutput(level, craft, CraftspersonGameTests.craftJob(), 6, Items.LADDER);
        helper.assertFalse(library(level, craft, 6).checked() || always(level, craft, 6), "No free checkmark: the box stays off");

        Pattern saved = library(level, craft, 0);
        var json = Pattern.CODEC.encodeStart(JsonOps.INSTANCE, saved).getOrThrow();
        helper.assertTrue(Pattern.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().equals(saved), "Saved with the pattern");
        json.getAsJsonObject().remove("always");
        helper.assertFalse(Pattern.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().always(), "Old patterns load with the box off");
        helper.succeed();
    }

    /** An idle Craftsperson works its patterns in turn, each round with its own amount, and delivers to the Home Clerk. */
    private static void roundRobin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 6));
        chest.setItem(1, new ItemStack(Items.COAL, 3));
        chest.setItem(2, new ItemStack(Items.STICK, 3));
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        always(helper, craft, 0, Items.STICK, 1);
        always(helper, craft, 1, Items.TORCH, 2);
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");

        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.FETCHING_INPUTS, "Idle: a round starts " + settler.getWork().status(craft));
        // Leaving the JobBlock ends the live round; catch-up starts again at the first pattern.
        settler.removeJob(craft);
        settler.addJob(new SettlerJob(CraftspersonGameTests.craftJob(), craft));
        CraftspersonGameTests.catchUp(level, settler, 12000L);

        helper.assertTrue(count(level, clerk, Items.STICK) >= 12, "3 rounds of 1 craft (4 each): " + count(level, clerk, Items.STICK));
        helper.assertTrue(count(level, clerk, Items.TORCH) == 12, "Torches, 2 crafts a round: " + count(level, clerk, Items.TORCH));
        helper.assertTrue(count(level, clerk, Items.OAK_PLANKS) == 0 && count(level, clerk, Items.COAL) == 0, "Inputs used up");
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.NO_REQUEST, "Nothing left, no error status: " + settler.getWork().status(craft));
        TestAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /** A request goes first: it gets the only planks (sticks for its torches), the always-produce pattern what is left. */
    private static void requestFirst(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        chest.setItem(1, new ItemStack(Items.COAL, 1));
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.TORCH, 4, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        always(helper, craft, 0, Items.STICK, 1);
        CraftspersonGameTests.define(helper, craft, 1, Items.TORCH);
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        CraftspersonGameTests.catchUp(level, settler, 4000L);
        helper.assertTrue(count(level, clerk, Items.TORCH) == 4, "Request filled: " + count(level, clerk, Items.TORCH));
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Request done");
        TestAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /** A request lacking inputs still counts as a request: the always-produce pattern waits. */
    private static void requestLacksInputs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.TORCH, 4, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        always(helper, craft, 0, Items.STICK, 1);
        CraftspersonGameTests.define(helper, craft, 1, Items.TORCH);
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        CraftspersonGameTests.catchUp(level, settler, 4000L);
        helper.assertTrue(count(level, clerk, Items.OAK_PLANKS) == 2, "The request lacks coal: the planks stay");
        helper.assertTrue(Requests.claimable(settlement).size() == 1, "Request still open");
        TestAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /** A pattern at its Max and one without inputs pass their turn, without an error status. */
    private static void maxAndMissing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
        helper.assertTrue(StockRules.setRule(data, clerk, new StockRule(0, Items.STICK, StockRule.NOT_SET, 12, StockRule.NOT_SET, 0)), "Max 12 sticks");
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        always(helper, craft, 0, Items.TORCH, 1);
        always(helper, craft, 1, Items.STICK, 1);
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        CraftspersonGameTests.catchUp(level, settler, 12000L);
        helper.assertTrue(count(level, clerk, Items.STICK) == 12, "Stops at Max: " + count(level, clerk, Items.STICK));
        helper.assertTrue(count(level, clerk, Items.TORCH) == 0, "No coal: torches skipped");
        helper.assertTrue(count(level, clerk, Items.OAK_PLANKS) == 10, "The rest stays: " + count(level, clerk, Items.OAK_PLANKS));
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.NO_REQUEST, "No error status: " + settler.getWork().status(craft));
        TestAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /**
     * A round reserves nothing: its inputs stay in stock for everyone; taken meanwhile, the round ends quietly. The inputs
     * lie in the collect inventory, so the settler fetches them without waiting at a Clerk's counter.
     */
    private static void noReservation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Own floor: test places are spread over the world and the terrain below them varies.
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        // Entities only tick in forced chunks on the GameTest server.
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        Container own = (Container) level.getBlockEntity(craft);
        own.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        always(helper, craft, 0, Items.STICK, 1);
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        settler.setNoAi(false);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(craft) == JobStatus.FETCHING_INPUTS, "Round started: " + settler.getWork().status(craft));
        helper.assertTrue(own.countItem(Items.OAK_PLANKS) == 2, "Still there for everyone");
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "No request");
        own.setItem(0, ItemStack.EMPTY);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().status(craft) == JobStatus.NO_REQUEST, "No error status: " + settler.getWork().status(craft));
            helper.assertTrue(settler.getWork().buffer(craft).count() == 0, "Nothing fetched");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A checked pattern set to "Always produce" with an amount. */
    private static void always(GameTestHelper helper, BlockPos craft, int slot, Item output, int atOnce) {
        ServerLevel level = helper.getLevel();
        CraftspersonGameTests.define(helper, craft, slot, output);
        helper.assertTrue(always(level, craft, slot), "Always produce " + output);
        Patterns.setAtOnce(SettlementData.get(level), craft, CraftspersonGameTests.craftJob(), slot, atOnce);
    }

    private static boolean always(ServerLevel level, BlockPos craft, int slot) {
        return Patterns.toggleAlways(level, craft, CraftspersonGameTests.craftJob(), slot);
    }

    private static Pattern library(ServerLevel level, BlockPos craft, int slot) {
        return Patterns.pattern(SettlementData.get(level).settlementAt(craft).flatMap(settlement -> settlement.block(craft)).orElseThrow(),
                CraftspersonGameTests.craftJob(), slot).orElseThrow();
    }

    private static int count(ServerLevel level, BlockPos clerk, Item item) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(item));
    }
}
