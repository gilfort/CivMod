package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.MissionProfile;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the example addon ({@code example-addon/}, mod id {@code civmod_example}), which the dev runs load next
 * to CivMod: its Jobs run through their category like built-in ones.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ExampleAddonGameTests {

    private static final Identifier SCOUT = Identifier.fromNamespaceAndPath("civmod_example", "scout");
    private static final Identifier SCOUTING_TRIP = Identifier.fromNamespaceAndPath("civmod_example", "scouting_trip");
    private static final Identifier STONECUTTER = Identifier.fromNamespaceAndPath("civmod_example", "stonecutter");

    private ExampleAddonGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "example_scout_mission", ExampleAddonGameTests::scoutMission);
            register(helper, "example_stonecutter_request", ExampleAddonGameTests::stonecutterRequest);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * The Scout is a Mission Specialization of the Adventurer with its own profile; it takes its datapack mission from a
     * level-2 board, leaves, returns with loot and delivers it to the board.
     */
    private static void scoutMission(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job scout = ModJobs.REGISTRY.getValue(SCOUT);
        helper.assertTrue(scout != null && scout.category() == JobCategory.MISSION && scout.parent().orElseThrow() == ModJobs.ADVENTURER.get(),
                "The Scout is a Mission Specialization of the Adventurer: " + scout);
        helper.assertTrue(scout.profile(MissionProfile.class).filter(profile -> profile != MissionProfile.NEUTRAL).isPresent(), "Own profile");
        helper.assertTrue(ModJobs.ADVENTURER.get().offeredAt(2).contains(scout) && !ModJobs.ADVENTURER.get().offeredAt(1).contains(scout),
                "Offered by the Mission Board from level 2");
        helper.assertTrue(scout.displayName().getString().equals("Scout"), "Lang key: " + scout.displayName().getString());
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = MissionRunGameTests.missionSettler(helper, scout, "Scout");
        settler.getWork().forceMissionOutcome(MissionRules.Outcome.SUCCESS);
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        boolean[] returned = new boolean[1];
        helper.onEachTick(() -> {
            if (returned[0] || !settler.getWork().isAway()) {
                return;
            }
            MissionTrip trip = settler.getWork().missionTrip().orElseThrow();
            helper.assertTrue(trip.mission().equals(SCOUTING_TRIP), "Away on the addon's mission: " + trip);
            helper.assertTrue(settler.getWork().returnFromMission(level, trip.returnTime()), "Back at the return time");
            returned[0] = true;
            helper.assertTrue(settler.getWork().buffer(board).count() > 0, "Returned with loot");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(returned[0], "The Scout should leave on its mission");
            JobBuffer buffer = settler.getWork().buffer(board);
            Container container = (Container) level.getBlockEntity(board);
            helper.assertTrue(buffer.isEmpty() && !container.isEmpty(), "The loot is delivered to the board inventory");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * The Stonecutter is a Producing Specialization of the Craftsperson (offered from JobBlock level 2) with its own
     * profile: at a level-2 Craftsperson JobBlock its library follows {@code stonecutting} recipes, and it fills a Clerk
     * request for stone bricks from stone at a stonecutter.
     */
    private static void stonecutterRequest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job stonecutter = ModJobs.REGISTRY.getValue(STONECUTTER);
        helper.assertTrue(stonecutter != null && stonecutter.category() == JobCategory.PRODUCING
                && stonecutter.parent().orElseThrow() == ModJobs.CRAFTSPERSON.get(), "A Producing Specialization of the Craftsperson: " + stonecutter);
        helper.assertTrue(ModJobs.CRAFTSPERSON.get().offeredAt(2).contains(stonecutter) && !ModJobs.CRAFTSPERSON.get().offeredAt(1).contains(stonecutter),
                "Offered by the Craftsperson JobBlock from level 2");
        helper.assertTrue(stonecutter.profile(ProducingProfile.class).orElseThrow().recipeTypes().equals(List.of(RecipeType.STONECUTTING)),
                "Stonecutting recipes");

        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.STONE, 4));
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_BRICKS, 4, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);

        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        helper.getBlockEntity(new BlockPos(1, 1, 5), JobBlockEntity.class).setJobLevel(2);
        SettlementManager.setLevel(data, craft, 2, SettlementManager.Listener.NONE);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.STONECUTTER);
        helper.assertTrue(stonecutter.isValidWorkPlace(level, helper.absolutePos(new BlockPos(3, 1, 5))), "A stonecutter is its station");
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), data, settlement);
        settler.setCustomName(Component.literal("Stella"));
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        helper.assertTrue(JobAssignments.assign(data, craft, settler.getUUID(), "Stella", settlement.id(), List.of(), stonecutter)
                == JobAssignments.AssignResult.OK, "Stonecutter assigned");
        settler.addJob(new SettlerJob(stonecutter, craft));

        Pattern bricks = Patterns.setOutput(level, craft, 0, Items.STONE_BRICKS).orElseThrow();
        helper.assertTrue(bricks.checked() && bricks.grid().getFirst() == Items.STONE && bricks.count() == 1,
                "A stonecutting pattern, checked automatically: " + bricks);
        CraftspersonGameTests.catchUp(level, settler, 4000L);

        helper.assertTrue(ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.STONE_BRICKS)) == 4, "Stone bricks delivered");
        helper.assertTrue(ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.STONE)) == 0, "Stone used up");
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Request done: " + Requests.byPriority(settlement));
        JobAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }
}
