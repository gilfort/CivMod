package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.MissionProfile;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.api.SimpleCollectionProfile;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.block.UpgradeCosts;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.MissionTrip;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.menu.JobBlockTabs;
import com.gilfort.civmod.menu.PatternTab;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.StockRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
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
    private static final Identifier SAND_DIGGER = Identifier.fromNamespaceAndPath("civmod_example", "sand_digger");
    private static final Identifier SAND_DIGGER_BLOCK = Identifier.fromNamespaceAndPath("civmod_example", "sand_digger_block");
    private static final Identifier SAND = Identifier.withDefaultNamespace("sand");
    private static final Identifier RED_SAND = Identifier.withDefaultNamespace("red_sand");
    private static final long DAY = 24000L;
    private static final Identifier STONECUTTER = Identifier.fromNamespaceAndPath("civmod_example", "stonecutter");

    private ExampleAddonGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "example_scout_mission", ExampleAddonGameTests::scoutMission);
            register(helper, "example_sand_digger_produces", ExampleAddonGameTests::sandDiggerProduces);
            register(helper, "example_sand_digger_exclusion", ExampleAddonGameTests::sandDiggerExclusion);
            register(helper, "example_sand_digger_blocked", ExampleAddonGameTests::sandDiggerBlocked);
            register(helper, "example_stonecutter_request", ExampleAddonGameTests::stonecutterRequest);
            register(helper, "example_job_switch_keeps_checkmarks", ExampleAddonGameTests::jobSwitchKeepsCheckmarks);
            register(helper, "example_recipe_viewer_transfer", ExampleAddonGameTests::recipeViewerTransfer);
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
     * Sets up a Sand Digger JobBlock at (1, 1, 1) on a stone floor and a settler with a stone shovel and plenty of food
     * working it. Placed as a block (not from an item), the JobBlock starts without its starter tool.
     *
     * @param sand the blocks laid in turn on the floor at x 4–7, z 4–7 (empty for none)
     * @return the settler (its job is the first one)
     */
    private static Settler sandDigger(GameTestHelper helper, List<Block> sand) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, BuiltInRegistries.BLOCK.getValue(SAND_DIGGER_BLOCK));
        int index = 0;
        for (int x = 4; x < 8; x++) {
            for (int z = 4; z < 8; z++) {
                if (!sand.isEmpty()) {
                    helper.setBlock(new BlockPos(x, 1, z), sand.get(index++ % sand.size()));
                }
            }
        }
        BlockPos jobBlock = helper.absolutePos(relative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal("Digger"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_SHOVEL));
        // Plenty of food: hunger must not limit the test.
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        helper.assertTrue(SettlementWorld.assign(level, jobBlock, settler, job(SAND_DIGGER)) == JobAssignments.AssignResult.OK,
                "Sand Digger assigned");
        return settler;
    }

    private static Job job(Identifier id) {
        return ModJobs.REGISTRY.getValue(id);
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                total += container.getItem(i).getCount();
            }
        }
        return total;
    }

    private static void cleanUp(ServerLevel level, Settler settler, List<long[]> forced) {
        settler.discard();
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
    }

    /**
     * The Sand Digger is a Resource Collection Job built with the {@link SimpleCollectionProfile} builder, with its own
     * JobBlock (Found tab), lang key and upgrade costs. It scans the sand around its JobBlock and digs it live without
     * touching the world; a caught-up day delivers sand into its drop-off (the JobBlock inventory).
     */
    private static void sandDiggerProduces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job digger = job(SAND_DIGGER);
        helper.assertTrue(digger != null && digger.category() == JobCategory.RESOURCE_COLLECTION
                && digger.profile(SimpleCollectionProfile.class).isPresent(), "The Sand Digger is a builder Resource Collection Job: " + digger);
        helper.assertTrue(digger.displayName().getString().equals("Sand Digger"), "Lang key: " + digger.displayName().getString());
        helper.assertTrue(JobBlockTabs.of(digger) == JobBlockTabs.FOUND, "Storage, Info, Found");
        List<UpgradeCosts.Cost> costs = UpgradeCosts.get(digger, 2);
        helper.assertTrue(!costs.isEmpty() && costs.get(0).item().filter(Items.SAND::equals).isPresent(), "Own upgrade costs: " + costs);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = sandDigger(helper, List.of(Blocks.SAND));
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        BlockPos sand = helper.absolutePos(new BlockPos(5, 1, 5));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().buffer(jobBlock).stacks().stream().anyMatch(stack -> stack.is(Items.SAND)),
                        "No sand dug yet (status " + settler.getWork().status(jobBlock) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getBlockState(sand).is(Blocks.SAND), "The sand is never dug away");
                    helper.assertTrue(FoundTab.of(level, jobBlock).entry(SAND).isPresent(), "Found tab lists sand: " + FoundTab.of(level, jobBlock));
                    Container container = (Container) level.getBlockEntity(jobBlock);
                    long clock = level.getOverworldClockTime();
                    settler.getWork().setCatchUp(clock - DAY, clock);
                    CatchUp.runNow(level, List.of(settler));
                    helper.assertTrue(count(container, Items.SAND) >= 20, "Sand expected in the drop-off after a caught-up day: "
                            + count(container, Items.SAND));
                    cleanUp(level, settler, forced);
                })
                .thenSucceed();
    }

    /** Exclusions work for builder Jobs: with red sand excluded the Found tab marks it and a caught-up day yields only sand. */
    private static void sandDiggerExclusion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = sandDigger(helper, List.of(Blocks.SAND, Blocks.RED_SAND));
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        SettlementWorld.toggleExcluded(level, jobBlock, RED_SAND);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(FoundTab.of(level, jobBlock).entry(RED_SAND).filter(FoundTab.Entry::excluded).isPresent(),
                        "Red sand not listed as excluded: " + FoundTab.of(level, jobBlock)))
                .thenExecute(() -> {
                    Container container = (Container) level.getBlockEntity(jobBlock);
                    long clock = level.getOverworldClockTime();
                    settler.getWork().setCatchUp(clock - DAY, clock);
                    CatchUp.runNow(level, List.of(settler));
                    int red = count(container, Items.RED_SAND) + settler.getWork().buffer(jobBlock).stacks().stream()
                            .filter(stack -> stack.is(Items.RED_SAND)).mapToInt(ItemStack::getCount).sum();
                    helper.assertTrue(red == 0, "Excluded red sand produced: " + red);
                    helper.assertTrue(count(container, Items.SAND) >= 20, "Sand expected: " + count(container, Items.SAND));
                    cleanUp(level, settler, forced);
                })
                .thenSucceed();
    }

    /**
     * Without sand in reach the Sand Digger pauses with the status "Blocked" and its profile's reason: the reason is the
     * status text, and the problem bubble shows the reason's icon.
     */
    private static void sandDiggerBlocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = sandDigger(helper, List.of());
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), DAY));
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.BLOCKED, "Blocked: " + settler.getWork().status(jobBlock));
        helper.assertTrue(JobStatus.BLOCKED.isBlocked(), "The settler works its next priority meanwhile");
        helper.assertTrue(settler.getWork().statusText(jobBlock).getString().equals("No sand in reach"),
                "The profile's reason: " + settler.getWork().statusText(jobBlock).getString());
        helper.assertTrue(settler.getWork().recentThoughts().contains(new ThoughtBubble.Show(settler.getId(), ThoughtBubble.BLOCKED, Items.SAND, true)),
                "Problem bubble with the reason's icon: " + settler.getWork().recentThoughts());
        cleanUp(level, settler, forced);
        helper.succeed();
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

    /**
     * Switching the settler of one JobBlock between the Craftsperson and the Stonecutter keeps both libraries: patterns
     * of the other Job's recipe type are unusable but keep their checkmark, the limit counts only the working Job's
     * usable patterns (none trimmed), and switching back works them again.
     */
    private static void jobSwitchKeepsCheckmarks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job stonecutter = ModJobs.REGISTRY.getValue(STONECUTTER);
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        helper.getBlockEntity(new BlockPos(1, 1, 5), JobBlockEntity.class).setJobLevel(2);
        SettlementManager.setLevel(data, craft, 2, SettlementManager.Listener.NONE);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.STONECUTTER);
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.CRAFTING_TABLE);

        Item[] crafting = {Items.STICK, Items.OAK_PLANKS, Items.TORCH, Items.CHEST, Items.STONE_PICKAXE, Items.STONE_AXE};
        for (int slot = 0; slot < crafting.length; slot++) {
            helper.assertTrue(Patterns.setOutput(level, craft, slot, crafting[slot]).orElseThrow().checked(), "Craftsperson: " + crafting[slot] + " checked");
        }

        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), data, settlement);
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        helper.assertTrue(JobAssignments.assign(data, craft, settler.getUUID(), "Stella", settlement.id(), List.of(), stonecutter)
                == JobAssignments.AssignResult.OK, "Stonecutter assigned");
        settler.addJob(new SettlerJob(stonecutter, craft));
        helper.assertTrue(Patterns.setOutput(level, craft, 6, Items.STONE_BRICKS).orElseThrow().checked(), "Stonecutter: bricks checked");
        helper.assertTrue(Patterns.setOutput(level, craft, 7, Items.STONE_SLAB).orElseThrow().checked(), "Stonecutter: slabs checked");
        CraftspersonGameTests.catchUp(level, settler, 2000L);
        assertChecked(helper, level, craft, 8, "Stonecutter works");
        PatternTab tab = PatternTab.of(level, craft);
        helper.assertTrue(tab.foreign().equals(Set.of(0, 1, 2, 3, 4, 5)) && tab.unusable(0) && !tab.unusable(6), "Crafting patterns unusable: " + tab.foreign());
        helper.assertTrue(tab.checked() == 2 && tab.notice().isEmpty(), "The limit counts the Stonecutter's 2 patterns, nothing trimmed: " + tab.checked());

        JobAssignments.unassign(data, craft, settler.getUUID());
        settler.removeJob(craft);
        helper.assertTrue(JobAssignments.assign(data, craft, settler.getUUID(), "Stella", settlement.id(), List.of(), ModJobs.CRAFTSPERSON.get())
                == JobAssignments.AssignResult.OK, "Craftsperson assigned");
        settler.addJob(new SettlerJob(ModJobs.CRAFTSPERSON.get(), craft));
        CraftspersonGameTests.catchUp(level, settler, 2000L);
        assertChecked(helper, level, craft, 8, "Back to the Craftsperson");
        tab = PatternTab.of(level, craft);
        helper.assertTrue(tab.foreign().equals(Set.of(6, 7)) && tab.checked() == 6 && tab.notice().isEmpty(),
                "Stonecutting patterns unusable, the 6 crafting patterns worked again: " + tab.foreign() + " " + tab.checked());
        helper.assertTrue(Patterns.usable(level, data.settlementAt(craft).flatMap(found -> found.block(craft)).orElseThrow()).size() == 6,
                "The Craftsperson uses its 6 patterns");
        JobAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    /**
     * A recipe viewer's transfer button fills a pattern from every recipe type the working Job follows: at a Craftsperson
     * JobBlock worked by the Stonecutter a stonecutting recipe becomes a recipe pattern (checked automatically), while a
     * recipe of a type the working Job does not follow is refused and leaves the slot empty. The Patterns tab tells the
     * client which recipe types the working Job follows.
     */
    private static void recipeViewerTransfer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job stonecutter = ModJobs.REGISTRY.getValue(STONECUTTER);
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        helper.getBlockEntity(new BlockPos(1, 1, 5), JobBlockEntity.class).setJobLevel(2);
        SettlementManager.setLevel(data, craft, 2, SettlementManager.Listener.NONE);
        ResourceKey<Recipe<?>> bricks = ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace("stone_bricks_from_stone_stonecutting"));
        ResourceKey<Recipe<?>> sticks = ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace("stick"));
        List<Item> stickGrid = List.of(Items.AIR, Items.OAK_PLANKS, Items.AIR, Items.AIR, Items.OAK_PLANKS, Items.AIR, Items.AIR, Items.AIR, Items.AIR);

        PatternTab tab = PatternTab.of(level, craft);
        helper.assertTrue(tab.follows(RecipeType.CRAFTING) && !tab.follows(RecipeType.STONECUTTING), "Nobody assigned: the Craftsperson follows crafting");
        helper.assertTrue(Patterns.transfer(level, craft, 0, Optional.of(bricks), List.of(Items.STONE)).isEmpty(),
                "The Craftsperson refuses a stonecutting recipe");
        helper.assertTrue(PatternTab.of(level, craft).pattern(0).isEmpty(), "Refused: the slot stays empty");

        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), data, settlement);
        helper.assertTrue(JobAssignments.assign(data, craft, settler.getUUID(), "Stella", settlement.id(), List.of(), stonecutter)
                == JobAssignments.AssignResult.OK, "Stonecutter assigned");
        settler.addJob(new SettlerJob(stonecutter, craft));
        tab = PatternTab.of(level, craft);
        helper.assertTrue(tab.follows(RecipeType.STONECUTTING) && !tab.follows(RecipeType.CRAFTING), "The Stonecutter follows stonecutting");

        Pattern pattern = Patterns.transfer(level, craft, 0, Optional.of(bricks), List.of(Items.STONE)).orElseThrow();
        helper.assertTrue(pattern.output() == Items.STONE_BRICKS && pattern.recipe().equals(Optional.of(bricks)) && !pattern.exact()
                && pattern.grid().getFirst() == Items.STONE && pattern.checked(), "A stonecutting recipe pattern, checked automatically: " + pattern);
        helper.assertTrue(Patterns.transfer(level, craft, 1, Optional.of(sticks), stickGrid).isEmpty(), "The Stonecutter refuses a crafting recipe");
        helper.assertTrue(PatternTab.of(level, craft).pattern(1).isEmpty(), "Refused: the slot stays empty");
        JobAssignments.unassign(data, craft, settler.getUUID());
        JobAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    private static void assertChecked(GameTestHelper helper, ServerLevel level, BlockPos pos, int count, String when) {
        List<Pattern> checked = Patterns.checked(SettlementData.get(level).settlementAt(pos).flatMap(found -> found.block(pos)).orElseThrow());
        helper.assertTrue(checked.size() == count, when + ": every checkmark kept, " + checked.size() + " of " + count);
    }
}
