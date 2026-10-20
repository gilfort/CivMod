package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.item.ScrollOfAssignmentItem;
import com.gilfort.civmod.job.CraftspersonProfile;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.PatternTab;
import com.gilfort.civmod.menu.StationTypeTab;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobPlaces;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.StationTypes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Station Types (#92, vault {@code Systems/JobBlock.md}): unlock items in the Station Types tab of a
 * Craftsperson JobBlock unlock its Specializations there, each with its own pattern library.
 * <p>
 * On the GameTest server it registers two more Producing Specializations of the Craftsperson (crafting recipes):
 * {@code civmod:gametest_mason}, unlocked by a stonecutter like the example addon's Stonecutter, and
 * {@code civmod:gametest_smith}, unlocked by a smithing table.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class StationTypeGameTests {

    private static final Identifier STONECUTTER = Identifier.fromNamespaceAndPath("civmod_example", "stonecutter");
    private static final Identifier MASON = CivMod.id("gametest_mason");
    private static final Identifier SMITH = CivMod.id("gametest_smith");

    private StationTypeGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        // Only on the GameTest server: the client test must not see test Jobs in the Station Types overview.
        if (System.getProperty("neoforge.enabledGameTestNamespaces") != null) {
            event.register(ModJobs.KEY, helper -> {
                helper.register(MASON, Job.builder(JobCategory.PRODUCING).parent(ModJobs.CRAFTSPERSON).unlockItem(() -> Items.STONECUTTER)
                        .icon(() -> Items.STONE_BRICKS).profile(CraftspersonProfile::new).build());
                helper.register(SMITH, Job.builder(JobCategory.PRODUCING).parent(ModJobs.CRAFTSPERSON).unlockItem(() -> Items.SMITHING_TABLE)
                        .icon(() -> Items.ANVIL).profile(CraftspersonProfile::new).build());
            });
        }
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "station_types_unlock", StationTypeGameTests::unlock);
            register(helper, "station_types_item_unlocks_two_jobs", StationTypeGameTests::itemUnlocksTwoJobs);
            register(helper, "station_types_overwrite", StationTypeGameTests::overwrite);
            register(helper, "station_types_library_per_job", StationTypeGameTests::libraryPerJob);
            register(helper, "station_types_fallback_held_elsewhere", StationTypeGameTests::fallbackHeldElsewhere);
            register(helper, "station_types_catch_up_held_elsewhere", StationTypeGameTests::catchUpHeldElsewhere);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    static Job job(Identifier id) {
        return ModJobs.REGISTRY.getValue(id);
    }

    /** Sets a JobBlock's level in the world and its settlement entry. */
    static void setLevel(GameTestHelper helper, BlockPos relative, int level) {
        helper.getBlockEntity(relative, JobBlockEntity.class).setJobLevel(level);
        SettlementManager.setLevel(SettlementData.get(helper.getLevel()), helper.absolutePos(relative), level, SettlementManager.Listener.NONE);
    }

    /** The JobBlock's menu as a survival player in front of it sees it. */
    static JobBlockMenu menu(GameTestHelper helper, Player player, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        player.snapTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(pos);
        return new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(level, pos, block(level, pos).jobType(), entity.getJobLevel()));
    }

    /**
     * Unlocks an item's Jobs at a JobBlock the way a player does: the item on the cursor, a click on a Station Types slot.
     *
     * @return whether the slot took the item
     */
    static boolean unlock(GameTestHelper helper, BlockPos pos, int slot, Item item) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = menu(helper, player, pos);
        menu.setCarried(new ItemStack(item));
        return menu.clickMenuButton(player, JobBlockMenu.stationTypeButtonId(slot)) && menu.getCarried().isEmpty();
    }

    static SettlementBlock block(ServerLevel level, BlockPos pos) {
        return SettlementData.get(level).settlementAt(pos).flatMap(found -> found.block(pos)).orElseThrow();
    }

    private static List<Job> offered(ServerLevel level, BlockPos pos) {
        return JobPlaces.lines(block(level, pos)).stream().map(JobPlaces.Line::job).toList();
    }

    /**
     * A level-2 Craftsperson JobBlock offers only its Base Job until a stonecutter goes into the open Station Types slot:
     * the item is consumed and the Stonecutter is unlocked (job picker, scroll work point). Closed and locked slots and
     * items that unlock nothing are refused and keep the item.
     */
    private static void unlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        Job stonecutter = job(STONECUTTER);
        helper.assertTrue(stonecutter.unlockItem().equals(Optional.of(Items.STONECUTTER)), "The Stonecutter names its unlock item");
        helper.assertTrue(craftsperson.unlockItem().equals(Optional.of(Items.CRAFTING_TABLE)), "The Craftsperson's station is the first slot");
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        BlockPos cutter = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONECUTTER);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = menu(helper, player, pos);
        helper.assertTrue(menu.getTabCount() > JobBlockMenu.TAB_STATION_TYPES && menu.patternJobs().contains(stonecutter),
                "A Station Types tab and a Patterns tab for the Stonecutter: " + menu.patternJobs());
        helper.assertTrue(StationTypeTab.of(level, pos).item(0) == Items.CRAFTING_TABLE && StationTypeTab.of(level, pos).open() == 1,
                "Level 1: only the crafting table slot");

        menu.setCarried(new ItemStack(Items.STONECUTTER, 2));
        helper.assertFalse(menu.clickMenuButton(player, JobBlockMenu.stationTypeButtonId(1)), "Level 1: slot 2 is closed");
        helper.assertTrue(menu.getCarried().getCount() == 2, "A refused item is kept");

        setLevel(helper, relative, 2);
        helper.assertTrue(offered(level, pos).equals(List.of(craftsperson)), "Level 2 without Station Types: only the Base Job " + offered(level, pos));
        helper.assertTrue(ScrollOfAssignmentItem.workPlaceJob(level, block(level, pos), cutter).isEmpty(), "No stonecutter work point yet");
        menu = menu(helper, player, pos);
        menu.setCarried(new ItemStack(Items.STONECUTTER, 2));
        helper.assertFalse(menu.clickMenuButton(player, JobBlockMenu.stationTypeButtonId(0)), "The first slot is locked");
        menu.setCarried(new ItemStack(Items.DIRT, 2));
        helper.assertFalse(menu.clickMenuButton(player, JobBlockMenu.stationTypeButtonId(1)), "Dirt unlocks nothing");
        helper.assertTrue(menu.getCarried().is(Items.DIRT) && menu.getCarried().getCount() == 2, "Dirt is kept");

        menu.setCarried(new ItemStack(Items.STONECUTTER, 2));
        helper.assertTrue(menu.clickMenuButton(player, JobBlockMenu.stationTypeButtonId(1)), "The stonecutter is taken");
        helper.assertTrue(menu.getCarried().getCount() == 1, "One stonecutter consumed: " + menu.getCarried());
        helper.assertTrue(block(level, pos).stationTypes().get(1) == Items.STONECUTTER, "Slot 2 holds the stonecutter");
        helper.assertTrue(offered(level, pos).contains(stonecutter) && offered(level, pos).getFirst() == craftsperson,
                "The job picker offers the Stonecutter: " + offered(level, pos));
        helper.assertTrue(StationTypeTab.of(level, pos).unlocked().contains(stonecutter) && StationTypeTab.of(level, pos).open() == 2,
                "The tab shows it: " + StationTypeTab.of(level, pos));
        helper.assertTrue(ScrollOfAssignmentItem.workPlaceJob(level, block(level, pos), cutter).filter(stonecutter::equals).isPresent(),
                "The unlock item is no work place: the stonecutter in the world is the Stonecutter's work point");
        helper.assertFalse(menu.clickMenuButton(player, JobBlockMenu.stationTypeButtonId(1)), "Unlocked already: refused");
        helper.assertTrue(menu.getCarried().getCount() == 1, "Kept");
        helper.succeed();
    }

    /**
     * One item unlocks every Job naming it: a stonecutter unlocks the example addon's Stonecutter and the GameTest Mason;
     * the "?" overview lists both under the stonecutter. Datapacks override an unlock item ({@code unlock_item}).
     */
    private static void itemUnlocksTwoJobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job stonecutter = job(STONECUTTER);
        Job mason = job(MASON);
        Job smith = job(SMITH);
        List<StationTypes.Unlock> overview = StationTypes.overview(ModJobs.CRAFTSPERSON.get());
        helper.assertTrue(overview.stream().anyMatch(unlock -> unlock.item() == Items.STONECUTTER && unlock.jobs().size() == 2
                && unlock.jobs().containsAll(List.of(stonecutter, mason)))
                && overview.contains(new StationTypes.Unlock(Items.SMITHING_TABLE, List.of(smith))),
                "\"?\": every unlock item with all Jobs it unlocks: " + overview);
        helper.assertTrue(job(CivMod.id("gametest_dummy")).unlockItem().equals(Optional.of(Items.STICK)), "Datapack override of the unlock item");
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        setLevel(helper, relative, 2);
        helper.assertTrue(unlock(helper, pos, 1, Items.STONECUTTER), "Stonecutter in slot 2");
        helper.assertTrue(offered(level, pos).size() == 3 && offered(level, pos).getFirst() == ModJobs.CRAFTSPERSON.get()
                && offered(level, pos).containsAll(List.of(stonecutter, mason)), "Both unlocked: " + offered(level, pos));
        helper.succeed();
    }

    /**
     * A new item in a filled slot replaces it: the Stonecutter settler falls back to the Craftsperson, the Stonecutter's
     * library stays, locked (unusable, not editable); unlocking it again works the library again.
     */
    private static void overwrite(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        Job stonecutter = job(STONECUTTER);
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        setLevel(helper, relative, 2);
        helper.assertTrue(unlock(helper, pos, 1, Items.STONECUTTER), "Stonecutter unlocked");
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        helper.assertTrue(SettlementWorld.assign(level, pos, settler, stonecutter) == JobAssignments.AssignResult.OK, "Stonecutter assigned");
        helper.assertTrue(Patterns.setOutput(level, pos, stonecutter, 0, Items.STONE_BRICKS).orElseThrow().checked(), "Bricks checked");

        helper.assertTrue(unlock(helper, pos, 1, Items.SMITHING_TABLE), "The smithing table replaces the stonecutter");
        SettlementBlock block = block(level, pos);
        helper.assertTrue(block.stationTypes().get(1) == Items.SMITHING_TABLE && !offered(level, pos).contains(stonecutter),
                "The Stonecutter is no longer offered: " + offered(level, pos));
        helper.assertTrue(block.assignee(settler.getUUID()).map(SettlementBlock.Assignee::job).equals(Optional.of(craftsperson)),
                "The settler falls back to the Craftsperson at the block");
        helper.assertTrue(settler.getJobs().size() == 1 && settler.getJobs().getFirst().jobType() == craftsperson, "And in its job list: "
                + settler.getJobs());
        helper.assertTrue(Patterns.pattern(block, stonecutter, 0).filter(Pattern::checked).isPresent(), "The Stonecutter's library is kept");
        PatternTab tab = PatternTab.of(level, pos, stonecutter);
        helper.assertTrue(tab.locked() && tab.unusable(0) && !tab.follows(RecipeType.STONECUTTING), "Greyed out: " + tab);
        helper.assertTrue(Patterns.usable(level, block, stonecutter).isEmpty(), "Not usable");
        helper.assertTrue(Patterns.setOutput(level, pos, stonecutter, 1, Items.STONE_SLAB).isEmpty(), "Not editable");
        helper.assertTrue(StationTypeTab.of(level, pos).libraries().contains(stonecutter), "Its Patterns tab stays");

        // Level 2 only: a bigger JobBlock would reach into the neighbouring tests' settlements.
        helper.assertTrue(unlock(helper, pos, 1, Items.STONECUTTER), "The stonecutter again replaces the smithing table");
        helper.assertTrue(Patterns.usable(level, block(level, pos), stonecutter).size() == 1 && !PatternTab.of(level, pos, stonecutter).locked(),
                "The library works again with its checkmark");
        settler.discard();
        helper.succeed();
    }

    /**
     * Every Job unlocked at a JobBlock has its own library: the same slot holds a crafting pattern for the Craftsperson
     * and a stonecutting one for the Stonecutter; the settler works only its Job's library ("can make"); libraries and
     * Station Types are saved, and an old save's shared library is dropped without a crash.
     */
    private static void libraryPerJob(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        Job stonecutter = job(STONECUTTER);
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        helper.assertTrue(Patterns.setOutput(level, pos, stonecutter, 0, Items.STONE_BRICKS).isEmpty(), "Not unlocked: no Stonecutter library");
        setLevel(helper, relative, 2);
        helper.assertTrue(unlock(helper, pos, 1, Items.STONECUTTER), "Stonecutter unlocked");
        helper.assertTrue(Patterns.setOutput(level, pos, craftsperson, 0, Items.STICK).orElseThrow().checked(), "Craftsperson: sticks");
        helper.assertTrue(Patterns.setOutput(level, pos, stonecutter, 0, Items.STONE_BRICKS).orElseThrow().checked(), "Stonecutter: bricks, same slot");
        helper.assertTrue(Patterns.setOutput(level, pos, stonecutter, 1, Items.STICK).isEmpty(), "No stonecutting recipe makes sticks");
        SettlementBlock block = block(level, pos);
        helper.assertTrue(block.patterns(craftsperson).size() == 1 && block.patterns(craftsperson).getFirst().output() == Items.STICK
                && block.patterns(stonecutter).size() == 1 && block.patterns(stonecutter).getFirst().output() == Items.STONE_BRICKS,
                "Two libraries: " + block.patterns(craftsperson) + " / " + block.patterns(stonecutter));
        helper.assertTrue(PatternTab.of(level, pos, stonecutter).follows(RecipeType.STONECUTTING)
                && PatternTab.of(level, pos, craftsperson).follows(RecipeType.CRAFTING), "Each tab follows its Job's recipe types");

        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pos).orElseThrow();
        helper.assertTrue(JobAssignments.assign(data, pos, UUID.randomUUID(), "Stella", settlement.id(), List.of(), stonecutter)
                == JobAssignments.AssignResult.OK, "Stonecutter assigned");
        helper.assertTrue(Producers.canMake(level, settlement, Items.STONE_BRICKS), "The Stonecutter makes bricks");
        helper.assertFalse(Producers.canMake(level, settlement, Items.STICK), "Not the Craftsperson's sticks");

        JsonElement json = Settlement.CODEC.encodeStart(JsonOps.INSTANCE, settlement).getOrThrow();
        SettlementBlock loaded = Settlement.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().block(pos).orElseThrow();
        helper.assertTrue(loaded.patterns(stonecutter).equals(block.patterns(stonecutter)) && loaded.patterns(craftsperson).equals(block.patterns(craftsperson))
                && loaded.stationTypes().equals(block.stationTypes()), "Libraries and Station Types are saved");
        JsonObject saved = blockJson(json, pos);
        saved.remove("libraries");
        saved.add("patterns", JsonOps.INSTANCE.createList(Stream.of(
                Pattern.CODEC.encodeStart(JsonOps.INSTANCE, block.patterns(craftsperson).getFirst()).getOrThrow())));
        saved.addProperty("check_limit", 7);
        SettlementBlock old = Settlement.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().block(pos).orElseThrow();
        helper.assertTrue(old.patterns(craftsperson).isEmpty() && old.libraryJobs().isEmpty(), "An old shared library is dropped");
        helper.succeed();
    }

    /**
     * A settler that holds the Base Job at another JobBlock is unassigned instead of falling back when a Station Type
     * is replaced (a settler holds each Job once): Craftsperson at block A and Mechanic at block B, the smithing table
     * replaces B's crafter, and the settler leaves B. An unloaded settler (only in the data) leaves B as well; a
     * settler without the Base Job elsewhere still falls back. The left Job's library forgets its checkmark limit.
     */
    private static void fallbackHeldElsewhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        Job mechanic = ModJobs.MECHANIC.get();
        BlockPos a = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        BlockPos relativeB = new BlockPos(1, 1, 5);
        BlockPos b = CraftspersonGameTests.craftsperson(helper, relativeB);
        setLevel(helper, relativeB, 2);
        helper.assertTrue(unlock(helper, b, 1, Items.CRAFTER), "Mechanic unlocked at B");
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(a).orElseThrow();
        helper.assertTrue(data.settlementAt(b).filter(settlement::equals).isPresent(), "A and B in one settlement");
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        helper.assertTrue(SettlementWorld.assign(level, a, settler, craftsperson) == JobAssignments.AssignResult.OK, "Craftsperson at A");
        helper.assertTrue(SettlementWorld.assign(level, b, settler, mechanic) == JobAssignments.AssignResult.OK, "Mechanic at B");
        Patterns.enforceLimit(level, b, mechanic, settler.getUUID(), Patterns.BASE_CHECKMARKS);
        helper.assertTrue(block(level, b).workers(mechanic).contains(settler.getUUID()), "B's Mechanic library knows the settler's limit");

        helper.assertTrue(unlock(helper, b, 1, Items.SMITHING_TABLE), "The smithing table replaces the crafter");
        helper.assertFalse(block(level, b).isAssignee(settler.getUUID()), "Craftsperson at A already: the settler leaves B");
        helper.assertTrue(settler.getJobs().equals(List.of(new SettlerJob(craftsperson, a))),
                "Only the Craftsperson at A is left: " + settler.getJobs());
        helper.assertFalse(block(level, b).workers(mechanic).contains(settler.getUUID()), "B's Mechanic library forgets the settler");

        UUID unloaded = UUID.randomUUID();
        UUID single = UUID.randomUUID();
        helper.assertTrue(unlock(helper, b, 1, Items.CRAFTER), "The crafter again");
        setLevel(helper, relativeB, 3);
        setLevel(helper, new BlockPos(1, 1, 1), 3);
        helper.assertTrue(JobAssignments.assign(data, a, unloaded, "Una", settlement.id(), List.of(), craftsperson) == JobAssignments.AssignResult.OK,
                "Unloaded Una: Craftsperson at A");
        helper.assertTrue(JobAssignments.assign(data, b, unloaded, "Una", settlement.id(), List.of(craftsperson), mechanic) == JobAssignments.AssignResult.OK,
                "Una: Mechanic at B");
        helper.assertTrue(JobAssignments.assign(data, b, single, "Sid", settlement.id(), List.of(), mechanic) == JobAssignments.AssignResult.OK,
                "Sid: Mechanic at B only");
        helper.assertTrue(unlock(helper, b, 1, Items.SMITHING_TABLE), "Replaced again");
        helper.assertFalse(block(level, b).isAssignee(unloaded), "Unloaded Una leaves B too");
        helper.assertTrue(block(level, b).assignee(single).map(SettlementBlock.Assignee::job).equals(Optional.of(craftsperson)),
                "Sid holds no Craftsperson elsewhere: falls back");
        settler.discard();
        JobAssignments.unassign(data, a);
        JobAssignments.unassign(data, b);
        // Back to level 1: a bigger block left in the settlement data would push later tests' blocks out.
        setLevel(helper, relativeB, 1);
        setLevel(helper, new BlockPos(1, 1, 1), 1);
        helper.succeed();
    }

    /**
     * The catch-up of a settler that was unloaded while its Job fell back (an older save): it holds the Craftsperson at
     * block A and finds itself listed as Craftsperson at block B, where its Mechanic is no longer unlocked. It leaves B
     * instead of holding the Craftsperson twice.
     */
    private static void catchUpHeldElsewhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job craftsperson = ModJobs.CRAFTSPERSON.get();
        Job mechanic = ModJobs.MECHANIC.get();
        BlockPos a = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        BlockPos b = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(a).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        helper.assertTrue(SettlementWorld.assign(level, a, settler, craftsperson) == JobAssignments.AssignResult.OK, "Craftsperson at A");
        // An older save: B fell back to the Craftsperson while the settler, unloaded, still held the Mechanic there.
        helper.assertTrue(JobAssignments.assign(data, b, settler.getUUID(), "Cora", settlement.id(), List.of(), craftsperson)
                == JobAssignments.AssignResult.OK, "B lists the settler as Craftsperson");
        settler.addJob(new SettlerJob(mechanic, b));
        // Directly, not through the AI step: the test chunks are not forced, the settler may not tick in time.
        settler.checkMembership(level);
        helper.assertTrue(settler.getJobs().equals(List.of(new SettlerJob(craftsperson, a))),
                "Only the Craftsperson at A is left: " + settler.getJobs());
        helper.assertFalse(block(level, b).isAssignee(settler.getUUID()), "B no longer lists the settler");
        settler.discard();
        JobAssignments.unassign(data, a);
        helper.succeed();
    }

    /** The saved JobBlock at a position inside a saved settlement. */
    private static JsonObject blockJson(JsonElement json, BlockPos pos) {
        return find(json, pos).orElseThrow();
    }

    private static Optional<JsonObject> find(JsonElement json, BlockPos pos) {
        if (json.isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray()) {
                Optional<JsonObject> found = find(element, pos);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (!json.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = json.getAsJsonObject();
        if (object.has("job") && object.has("level") && object.has("pos")
                && BlockPos.CODEC.parse(JsonOps.INSTANCE, object.get("pos")).result().filter(pos::equals).isPresent()) {
            return Optional.of(object);
        }
        for (String key : object.keySet()) {
            Optional<JsonObject> found = find(object.get(key), pos);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
