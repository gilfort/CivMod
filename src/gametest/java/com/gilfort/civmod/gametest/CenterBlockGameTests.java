package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementManager.Outcome;
import com.gilfort.civmod.settlement.SettlementManager.Placement;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestSettlements;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Center Block (vault {@code Systems/Settlement.md}, decisions of 2026-10-31 and 2026-11-01): founding,
 * radii, the limit per race, JobBlocks without a settlement, the Administrator and the timer of a settlement without a
 * Center Block.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CenterBlockGameTests {

    private static final Identifier OTHER_RACE = CivMod.id("gametest_race");

    private CenterBlockGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "center_block_founds_settlement", CenterBlockGameTests::founds);
            register(helper, "center_block_recipe_returns_scroll", CenterBlockGameTests::recipe);
            register(helper, "center_block_fixed_radii", CenterBlockGameTests::fixedRadii);
            register(helper, "center_block_one_per_race", CenterBlockGameTests::onePerRace);
            register(helper, "center_block_second_removed_in_world", CenterBlockGameTests::secondRemovedInWorld);
            register(helper, "center_block_offers_administrator", CenterBlockGameTests::offersAdministrator);
            register(helper, "administrator_moves_on_to_next_job", CenterBlockGameTests::administratorMovesOn);
            register(helper, "job_block_never_founds", CenterBlockGameTests::jobBlockNeverFounds);
            register(helper, "job_block_unsettled_in_world", CenterBlockGameTests::unsettledInWorld);
            register(helper, "unsettled_block_joins_on_place_and_upgrade", CenterBlockGameTests::unsettledJoins);
            register(helper, "unsettled_block_survives_saving", CenterBlockGameTests::unsettledPersists);
            register(helper, "centerless_settlement_dissolves_on_fourth_morning", CenterBlockGameTests::dissolvesOnFourthMorning);
            register(helper, "centerless_settlement_revived", CenterBlockGameTests::revived);
            register(helper, "centerless_settlement_revived_at_empty_site", CenterBlockGameTests::revivedAtEmptySite);
            register(helper, "centerless_timer_survives_saving", CenterBlockGameTests::timerPersists);
            register(helper, "centerless_timer_runs_out_over_missed_mornings", CenterBlockGameTests::missedMornings);
            register(helper, "last_job_block_does_not_dissolve", CenterBlockGameTests::lastJobBlock);
            register(helper, "dissolved_settlement_leaves_settlers_homeless", CenterBlockGameTests::dissolvedWorld);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static BlockPos at(int x) {
        return new BlockPos(x, 64, 0);
    }

    private static Placement center(SettlementData data, int x) {
        return center(data, x, Races.HUMAN);
    }

    private static Placement center(SettlementData data, int x, Identifier race) {
        return SettlementManager.placeCenter(data, at(x), ModJobs.ADMINISTRATOR.get(), 1, race, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
    }

    private static Placement jobBlock(SettlementData data, int x, int level) {
        return SettlementManager.place(data, at(x), ModJobs.LUMBERJACK.get(), level, SettlementManager.Listener.NONE);
    }

    private static SettlementData reload(GameTestHelper helper, SettlementData data) {
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
    }

    /** Outside every exclusion zone a Center Block founds a settlement and the starting settlers are scheduled. */
    private static void founds(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        helper.assertTrue(SettlementManager.wouldFound(data, at(0)), "Nothing stands here yet");
        Placement first = SettlementManager.placeCenter(data, at(0), ModJobs.ADMINISTRATOR.get(), 1, Races.HUMAN, 1000L, RandomSource.create(0L),
                SettlementManager.Listener.NONE);
        helper.assertTrue(first.outcome() == Outcome.FOUNDED, "Founded: " + first);
        helper.assertTrue(first.settlement().settlersDue() == 1000L + SettlementManager.SETTLER_DELAY_TICKS, "Starting settlers on their way");
        helper.assertTrue(first.settlement().hasCenter(Races.HUMAN), "The block is the settlement's Center Block");
        helper.assertTrue(center(data, 151).outcome() == Outcome.FOUNDED && data.settlements().size() == 2, "Outside both zones: a second settlement");
        helper.succeed();
    }

    /** The Human recipe: bread, scroll, planks and a barrel; the scroll comes back, with its binding, from every recipe. */
    private static void recipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack none = ItemStack.EMPTY;
        ItemStack planks = new ItemStack(Items.OAK_PLANKS);
        ItemStack bread = new ItemStack(Items.BREAD);
        ItemStack barrel = new ItemStack(Items.BARREL);
        ItemStack scroll = new ItemStack(ModItems.SCROLL_OF_ASSIGNMENT.get());
        scroll.set(ModDataComponents.SCROLL_BINDING.get(), new com.gilfort.civmod.item.ScrollBinding(java.util.Optional.of(BlockPos.ZERO),
                java.util.Optional.empty(), "Miner"));
        CraftingInput input = CraftingInput.of(3, 3, List.of(none, bread, none, planks, scroll, planks, planks, barrel, planks));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(recipe.isPresent() && recipe.get().value().assemble(input).is(ModItems.HUMAN_CENTER_BLOCK.get()), "Crafts the Human Center Block");
        ItemStack left = recipe.orElseThrow().value().getRemainingItems(input).get(4);
        helper.assertTrue(left.is(ModItems.SCROLL_OF_ASSIGNMENT.get()) && left.getCount() == 1
                && left.get(ModDataComponents.SCROLL_BINDING.get()) != null, "The scroll is returned: " + left);
        helper.assertTrue(planks.is(ItemTags.PLANKS), "Any planks");
        helper.succeed();
    }

    /** Join radius 25 and exclusion radius 75 at every level; an upgrade changes neither. */
    private static void fixedRadii(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        center(data, 0);
        helper.assertTrue(jobBlock(data, 25, 1).outcome() == Outcome.JOINED, "25 joins");
        SettlementManager.remove(data, at(25), 0L, SettlementManager.Listener.NONE);
        helper.assertTrue(jobBlock(data, -26, 1).outcome() == Outcome.UNCONNECTED, "26 is out of reach");
        helper.assertTrue(jobBlock(data, -75, 1).outcome() == Outcome.UNCONNECTED, "75 is inside the exclusion zone");
        helper.assertTrue(jobBlock(data, 76, 1).outcome() == Outcome.UNSETTLED, "76 is outside it");

        SettlementManager.setLevel(data, at(0), SettlementRules.MAX_LEVEL, SettlementManager.Listener.NONE);
        Settlement settlement = data.settlementAt(at(0)).orElseThrow();
        helper.assertTrue(settlement.block(at(0)).orElseThrow().level() == SettlementRules.MAX_LEVEL, "The Center Block was upgraded");
        helper.assertTrue(settlement.block(at(-26)).orElseThrow().connected() == false, "A level 5 center does not reach 26");
        helper.assertTrue(jobBlock(data, 77, 1).outcome() == Outcome.UNSETTLED, "A level 5 center does not exclude 77");
        helper.succeed();
    }

    /** A second Center Block of a race is refused anywhere in the settlement's zones; another race may join. */
    private static void onePerRace(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = center(data, 0).settlement();
        Placement inJoin = center(data, 10);
        helper.assertTrue(inJoin.outcome() == Outcome.REFUSED && inJoin.settlement() == settlement, "Refused inside the join radius");
        helper.assertTrue(center(data, 60).outcome() == Outcome.REFUSED, "Refused inside the exclusion zone");
        helper.assertTrue(SettlementManager.wouldRefuse(data, at(60), Races.HUMAN), "Known before placing");
        helper.assertTrue(data.settlementAt(at(10)).isEmpty() && data.settlementAt(at(60)).isEmpty() && settlement.blocks().size() == 1,
                "A refused Center Block is not registered");
        helper.assertTrue(center(data, 0).outcome() == Outcome.JOINED && settlement.blocks().size() == 1, "The registered one stays as it is");

        Placement other = center(data, 10, OTHER_RACE);
        helper.assertTrue(other.outcome() == Outcome.JOINED && other.settlement() == settlement, "Another race joins the settlement");
        helper.assertTrue(data.settlements().size() == 1, "No second settlement");
        helper.succeed();
    }

    /** A Center Block placed without a player where it is refused does not stay. */
    private static void secondRemovedInWorld(GameTestHelper helper) {
        BlockPos first = new BlockPos(1, 1, 1);
        BlockPos second = new BlockPos(3, 1, 1);
        BlockPos center = TestSettlements.center(helper.getLevel(), helper.absolutePos(first)).orElseThrow();
        SettlementData data = SettlementData.get(helper.getLevel());
        helper.setBlock(second, ModBlocks.HUMAN_CENTER_BLOCK.get());
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(ModBlocks.HUMAN_CENTER_BLOCK.get(), second);
            helper.assertTrue(data.settlementAt(helper.absolutePos(second)).isEmpty(), "Not registered");
            helper.assertTrue(data.settlementAt(center).isPresent(), "The first one stays");
        });
    }

    /** The Center Block offers the Administrator, a Service job, and is upgraded like every JobBlock. */
    private static void offersAdministrator(GameTestHelper helper) {
        helper.assertTrue(ModBlocks.HUMAN_CENTER_BLOCK.get().getJobType() == ModJobs.ADMINISTRATOR.get(), "The block's job");
        helper.assertTrue(ModJobs.ADMINISTRATOR.getId().equals(CivMod.id("administrator")), "Job id");
        helper.assertTrue(ModJobs.ADMINISTRATOR.get().category() == JobCategory.SERVICE, "Service job");
        helper.assertTrue(JobPlaces.offered(ModJobs.ADMINISTRATOR.get()).equals(List.of(ModJobs.ADMINISTRATOR.get())), "Offered Jobs");
        helper.succeed();
    }

    /** With the Administrator on top of its list a settler goes on to work its next job right away. */
    private static void administratorMovesOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlement(settler.getSettlementId()).orElseThrow();
        BlockPos center = settlement.blocks().stream().filter(block -> block.centerRace().isPresent()).map(SettlementBlock::pos).findFirst().orElseThrow();
        helper.assertTrue(SettlementWorld.assign(level, center, settler, ModJobs.ADMINISTRATOR.get()) == AssignResult.OK, "Assigned at the Center Block");
        SettlerJob lumberjack = settler.getJobs().stream().filter(job -> job.jobType() == ModJobs.LUMBERJACK.get()).findFirst().orElseThrow();
        SettlementWorld.setJobs(level, settler, List.of(new SettlerJob(ModJobs.ADMINISTRATOR.get(), center), lumberjack));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                settler.getWork().activeJob().filter(job -> job.jobType() == ModJobs.LUMBERJACK.get()).isPresent(),
                "The settler works its next job: " + settler.getWork().activeJob())).thenExecute(() -> {
                    settler.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                }).thenSucceed();
    }

    /** A normal JobBlock founds nothing: it stays placed, without a settlement, inside no index but its own. */
    private static void jobBlockNeverFounds(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Placement placement = jobBlock(data, 0, 1);
        helper.assertTrue(placement.outcome() == Outcome.UNSETTLED && placement.settlement() == null, "No settlement: " + placement);
        helper.assertTrue(data.settlements().isEmpty(), "Nothing founded");
        helper.assertTrue(data.unsettledAt(at(0)).isPresent(), "The block is known");
        SettlementManager.Status status = SettlementManager.status(data, at(0));
        helper.assertTrue(status.settlement().isEmpty() && !status.connected(), "Shows as unconnected: " + status);

        SettlementManager.remove(data, at(0), 0L, SettlementManager.Listener.NONE);
        helper.assertTrue(data.unsettled().isEmpty(), "A removed block is forgotten");
        helper.succeed();
    }

    /** In the world such a block shows as unconnected until a settlement reaches it. */
    private static void unsettledInWorld(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, ModBlocks.MINER_BLOCK.get());
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                helper.getBlockEntity(relative, JobBlockEntity.class).isUnconnected(), "Shows as unconnected")).thenExecute(() -> {
                    SettlementData data = SettlementData.get(helper.getLevel());
                    helper.assertTrue(data.settlementAt(pos).isEmpty() && data.unsettledAt(pos).isPresent(), "Belongs to no settlement");
                    TestSettlements.center(helper.getLevel(), pos.east(30));
                    // The next place in reach of both connects it.
                    TestSettlements.setBlock(helper, new BlockPos(11, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
                }).thenWaitUntil(() -> helper.assertFalse(helper.getBlockEntity(relative, JobBlockEntity.class).isUnconnected(), "Connected now"))
                .thenExecute(() -> helper.assertTrue(SettlementData.get(helper.getLevel()).settlementAt(pos).isPresent(), "Joined"))
                .thenSucceed();
    }

    /** A block without a settlement joins when a settlement reaches it through a founding, a placement or an upgrade. */
    private static void unsettledJoins(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        List<BlockPos> connected = new ArrayList<>();
        SettlementManager.Listener listener = new SettlementManager.Listener() {
            @Override
            public void blockChanged(BlockPos pos, int settlementId, boolean isConnected) {
            }

            @Override
            public void autoConnected(BlockPos pos) {
                connected.add(pos);
            }
        };
        SettlementManager.place(data, at(20), ModJobs.LUMBERJACK.get(), 1, listener);
        Settlement settlement = SettlementManager.placeCenter(data, at(0), ModJobs.ADMINISTRATOR.get(), 1, Races.HUMAN, 0L, RandomSource.create(0L), listener).settlement();
        helper.assertTrue(data.settlementAt(at(20)).filter(found -> found == settlement).isPresent() && data.unsettled().isEmpty(),
                "The founding reaches it");

        SettlementManager.place(data, at(200), ModJobs.LUMBERJACK.get(), 1, listener);
        SettlementManager.place(data, at(30), ModJobs.LUMBERJACK.get(), 1, listener);
        SettlementManager.place(data, at(40), ModJobs.LUMBERJACK.get(), 1, listener);
        SettlementManager.place(data, at(100), ModJobs.LUMBERJACK.get(), 1, listener);
        helper.assertTrue(data.unsettledAt(at(100)).isPresent(), "Too far away for now: " + data.unsettled());
        SettlementManager.setLevel(data, at(100), 5, listener);
        helper.assertTrue(data.unsettledAt(at(100)).isPresent(), "Its own upgrade does not reach anything");
        helper.assertTrue(data.unsettledAt(at(100)).orElseThrow().level() == 5, "But the level is kept");
        SettlementManager.place(data, at(50), ModJobs.LUMBERJACK.get(), 3, listener);
        helper.assertTrue(data.settlementAt(at(100)).isEmpty(), "A level 3 block at 50 reaches 80");
        SettlementManager.setLevel(data, at(50), 5, listener);
        helper.assertTrue(data.settlementAt(at(100)).filter(found -> found == settlement).isPresent(), "The upgrade reaches it");
        helper.assertTrue(settlement.block(at(100)).orElseThrow().level() == 5 && settlement.block(at(100)).orElseThrow().connected(), "Joined with its level");
        helper.assertTrue(data.unsettledAt(at(200)).isPresent(), "One out of reach stays");
        helper.assertTrue(connected.containsAll(List.of(at(20), at(100))), "Players hear of it: " + connected);
        helper.succeed();
    }

    /** The state of a block without a settlement survives saving. */
    private static void unsettledPersists(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        jobBlock(data, 0, 3);
        SettlementData loaded = reload(helper, data);
        helper.assertTrue(loaded.unsettledAt(at(0)).filter(block -> block.level() == 3 && !block.connected()).isPresent(), "Kept: " + loaded.unsettled());
        helper.assertTrue(loaded.blocksInChunk(new net.minecraft.world.level.ChunkPos(0, 0)).contains(at(0)), "Chunk index rebuilt");
        helper.succeed();
    }

    /** Removed on day 10: the settlement stays for days 11 to 13 and dissolves on the morning of day 14. */
    private static void dissolvesOnFourthMorning(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = center(data, 0).settlement();
        SettlementManager.remove(data, at(0), 10L, SettlementManager.Listener.NONE);
        helper.assertTrue(data.settlements().contains(settlement), "Removing the Center Block dissolves nothing");
        helper.assertTrue(settlement.centerGrace().orElseThrow().dissolveDay() == 14L, "Timer: " + settlement.centerGrace());
        for (long day = 11L; day <= 13L; day++) {
            SettlementManager.morning(data, day, SettlementManager.Listener.NONE);
            helper.assertTrue(data.settlements().contains(settlement), "Still there on the morning of day " + day);
        }
        SettlementManager.morning(data, 14L, SettlementManager.Listener.NONE);
        helper.assertTrue(data.settlements().isEmpty(), "Dissolved on the fourth morning");
        helper.succeed();
    }

    /** A new Center Block in that time revives the settlement and stops the timer. */
    private static void revived(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = center(data, 0).settlement();
        jobBlock(data, 20, 1);
        SettlementManager.remove(data, at(0), 0L, SettlementManager.Listener.NONE);
        helper.assertFalse(SettlementManager.wouldFound(data, at(10)), "No second settlement over the first");
        Placement again = center(data, 10);
        helper.assertTrue(again.outcome() == Outcome.REVIVED && again.settlement() == settlement, "Revived: " + again);
        helper.assertTrue(settlement.centerGrace().isEmpty(), "The timer stopped");
        SettlementManager.morning(data, 100L, SettlementManager.Listener.NONE);
        helper.assertTrue(data.settlements().contains(settlement), "It no longer dissolves");
        helper.succeed();
    }

    /** With the last JobBlock gone the settlement still holds the place of its last Center Block. */
    private static void revivedAtEmptySite(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = center(data, 0).settlement();
        SettlementManager.remove(data, at(0), 0L, SettlementManager.Listener.NONE);
        helper.assertTrue(settlement.isEmpty(), "No block left");
        Placement again = center(data, 0);
        helper.assertTrue(again.outcome() == Outcome.REVIVED && again.settlement() == settlement && data.settlements().size() == 1, "Revived: " + again);
        helper.succeed();
    }

    /** The timer survives saving and the place stays held. */
    private static void timerPersists(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        center(data, 0);
        SettlementManager.remove(data, at(0), 2L, SettlementManager.Listener.NONE);
        SettlementData loaded = reload(helper, data);
        Settlement settlement = loaded.settlements().iterator().next();
        helper.assertTrue(settlement.centerGrace().filter(grace -> grace.dissolveDay() == 6L && grace.lastCenter().equals(at(0))).isPresent(),
                "Kept: " + settlement.centerGrace());
        SettlementManager.morning(loaded, 5L, SettlementManager.Listener.NONE);
        helper.assertTrue(loaded.settlements().size() == 1, "Not yet");
        SettlementManager.morning(loaded, 6L, SettlementManager.Listener.NONE);
        helper.assertTrue(loaded.settlements().isEmpty(), "Runs out after loading");
        helper.succeed();
    }

    /** Catch-up: mornings nobody saw count, the first check after them dissolves the settlement. */
    private static void missedMornings(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        center(data, 0);
        jobBlock(data, 20, 2);
        SettlementManager.remove(data, at(0), 0L, SettlementManager.Listener.NONE);
        SettlementManager.morning(data, 30L, SettlementManager.Listener.NONE);
        helper.assertTrue(data.settlements().isEmpty(), "Ran out while nobody watched");
        helper.assertTrue(data.unsettledAt(at(20)).filter(block -> block.level() == 2).isPresent(), "Its JobBlocks stay, without a settlement");
        helper.assertTrue(data.settlementAt(at(20)).isEmpty(), "Position index cleared");
        helper.succeed();
    }

    /** Removing the last JobBlock of a settlement dissolves nothing at once, also when it was no Center Block. */
    private static void lastJobBlock(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = center(data, 0).settlement();
        jobBlock(data, 10, 1);
        SettlementManager.remove(data, at(10), 0L, SettlementManager.Listener.NONE);
        helper.assertTrue(settlement.centerGrace().isEmpty(), "The Center Block stands: no timer");
        SettlementManager.remove(data, at(0), 0L, SettlementManager.Listener.NONE);
        helper.assertTrue(data.settlements().contains(settlement) && settlement.isEmpty(), "Only the timer dissolves it");
        helper.succeed();
    }

    /** Dissolving makes the settlers homeless and leaves the JobBlocks in the world without a settlement. */
    private static void dissolvedWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(1, 1, 1);
        TestSettlements.setBlock(helper, relative, ModBlocks.CLERK_BLOCK.get());
        BlockPos pos = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        BlockPos center = settlement.blocks().stream().filter(block -> block.centerRace().isPresent()).map(SettlementBlock::pos).findFirst().orElseThrow();
        long today = DayClock.day(level);
        level.destroyBlock(center, false);
        helper.assertTrue(settlement.centerGrace().filter(grace -> grace.dissolveDay() == today + 4).isPresent(), "The world counts settler days");
        SettlementWorld.morning(level, today + 3);
        helper.assertFalse(settler.isHomeless(), "Not yet");
        SettlementWorld.morning(level, today + 4);
        helper.assertTrue(settler.isHomeless(), "Homeless");
        helper.assertTrue(data.unsettledAt(pos).isPresent(), "The JobBlock stays");
        helper.assertTrue(helper.getBlockEntity(relative, JobBlockEntity.class).isUnconnected(), "And shows as unconnected");
        settler.discard();
        level.destroyBlock(pos, false);
        helper.succeed();
    }
}
