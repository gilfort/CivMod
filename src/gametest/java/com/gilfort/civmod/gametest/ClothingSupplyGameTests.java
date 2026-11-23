package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.clothing.ClothingRules;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModClothing;
import com.gilfort.civmod.registry.ModClothing.ClothSet;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Holds;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the clothing supply (vault {@code Systems/Needs.md}, decisions of 2026-11-21 and 2026-11-23): a settler with a missing or
 * worn-out piece asks the Home Clerk of its JobBlock in the hourly search, the Clerk's store goes first, the Tailor makes what is
 * not there, and the piece arrives like a tool. The seam is the settler's need check on the settlement level: what it asks for and
 * what it wears afterwards.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClothingSupplyGameTests {

    /** Skill 30 is tier 2: simple clothing. */
    private static final int TIER_2_SKILL = 30;
    /** Skill 45 is tier 3: better clothing. */
    private static final int TIER_3_SKILL = 45;
    /** Skill 85 is tier 5: highest clothing. */
    private static final int TIER_5_SKILL = 85;
    private static final long DAY = 24000L;
    /** Work time of the previous workday a catch-up of the Tailor covers. */
    private static final long WORK_TICKS = 4000L;
    /** The durability of the test pieces, so that 90 points used leave 10 %. */
    private static final int MAX_DAMAGE = 100;
    private static final List<ArmorType> TYPES = List.of(ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS);

    private ClothingSupplyGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "clothing_supply_request", ClothingSupplyGameTests::request);
            TestFunctions.register(helper, "clothing_supply_tier_asked_no_step_down", ClothingSupplyGameTests::tierAsked);
            TestFunctions.register(helper, "clothing_supply_tier_one_asks_nothing", ClothingSupplyGameTests::tierOneAsksNothing);
            TestFunctions.register(helper, "clothing_supply_store_first", ClothingSupplyGameTests::storeFirst);
            TestFunctions.register(helper, "clothing_supply_tailor_piece_arrives", ClothingSupplyGameTests::tailorPieceArrives);
            TestFunctions.register(helper, "clothing_supply_replacement_below_20", ClothingSupplyGameTests::replacementBelow20);
            TestFunctions.register(helper, "clothing_supply_worn_piece_asks_same_rank", ClothingSupplyGameTests::wornPieceAsksSameRank);
            TestFunctions.register(helper, "clothing_supply_switch_off", ClothingSupplyGameTests::switchOff);
            TestFunctions.register(helper, "clothing_supply_request_ends", ClothingSupplyGameTests::requestEnds);
        });
    }

    // ---------------------------------------------------------------- layout

    private static Settler wearer(GameTestHelper helper, int skill) {
        return wearerAt(helper, new BlockPos(1, 1, 13), skill);
    }

    /** A Miner of the given skill without clothes but with a tool of its own, so that only clothing is missing; its Home Clerk is the working Clerk. */
    private static Settler wearerAt(GameTestHelper helper, BlockPos at, int skill) {
        Settler settler = ToolSupplyGameTests.toolWorker(helper, at, ModJobs.MINER.get(), skill, new ItemStack(Items.DIAMOND_PICKAXE));
        wear(settler);
        return settler;
    }

    private static Item piece(ClothSet set, ArmorType type) {
        return ModClothing.piece(set, type).get();
    }

    private static Set<Item> set(ClothSet set) {
        return TYPES.stream().map(type -> piece(set, type)).collect(Collectors.toSet());
    }

    private static long today(ServerLevel level) {
        return Math.floorDiv(level.getOverworldClockTime(), DAY);
    }

    /** The need check of the given day: the clothing step with its hourly search of the collect inventory runs inside. */
    private static void check(GameTestHelper helper, Settler settler, long day) {
        settler.getWork().checkNeedsNow(helper.getLevel(), day);
    }

    /** What the settler asked the settlement for in clothing, its reserved requests for cloth. */
    private static List<Request> pieceRequests(Settlement settlement, Settler settler) {
        return settlement.requests().stream()
                .filter(request -> request.reservedFor().filter(ReservedFor.settler(settler.getUUID())::equals).isPresent()
                        && ClothingRules.rank(request.item().toStack()) != ClothingRules.NO_RANK)
                .toList();
    }

    private static Set<Item> askedItems(Settlement settlement, Settler settler) {
        return pieceRequests(settlement, settler).stream().map(request -> request.item().item()).collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------- the request

    /**
     * A settler of tier 2 without clothes asks the Home Clerk for one piece per slot of the set of its tier. The search runs once an
     * hour: asking again in the same hour posts nothing, the next day looks again.
     */
    private static void request(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        Settler settler = wearer(helper, TIER_2_SKILL);
        long day = today(level);

        check(helper, settler, day);
        List<Request> asked = pieceRequests(settlement, settler);
        helper.assertTrue(asked.size() == 4, "One request per missing piece: " + settlement.requests());
        helper.assertTrue(askedItems(settlement, settler).equals(set(ClothSet.SIMPLE)), "Simple Cloth for tier 2: " + askedItems(settlement, settler));
        helper.assertTrue(asked.stream().allMatch(found -> found.amount() == 1 && found.target().equals(Optional.of(clerk)) && found.parent().isEmpty()),
                "One piece each, for the Home Clerk: " + asked);

        check(helper, settler, day);
        helper.assertTrue(pieceRequests(settlement, settler).size() == 4, "The same need posts no second request: " + settlement.requests());

        // The hourly search: withdrawn requests are not posted again within the hour, the next day looks again.
        asked.forEach(found -> Requests.withdrawReserved(data, settlement, found.id()));
        check(helper, settler, day);
        helper.assertTrue(pieceRequests(settlement, settler).isEmpty(), "No search within the hour: " + settlement.requests());
        check(helper, settler, day + 1);
        helper.assertTrue(pieceRequests(settlement, settler).size() == 4, "The next day asks again: " + settlement.requests());
        ToolSupplyGameTests.finish(helper, settler);
    }

    /**
     * The tier decides the set that is asked for (tiers 3 and 4 better, tier 5 highest clothing), and nobody can make it: the request
     * stays at that tier, it does not step down.
     */
    private static void tierAsked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of());
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        Settler better = wearer(helper, TIER_3_SKILL);
        long day = today(level);
        check(helper, better, day);
        helper.assertTrue(askedItems(settlement, better).equals(set(ClothSet.MEDIUM)), "Tier 3 asks for Medium Cloth: " + askedItems(settlement, better));
        check(helper, better, day + 3);
        helper.assertTrue(askedItems(settlement, better).equals(set(ClothSet.MEDIUM)) && pieceRequests(settlement, better).size() == 4,
                "Nobody makes it, the request stays: " + settlement.requests());
        Settler best = wearerAt(helper, new BlockPos(1, 1, 17), TIER_5_SKILL);
        check(helper, best, day);
        helper.assertTrue(askedItems(settlement, best).equals(set(ClothSet.HIGH)), "Tier 5 asks for High Cloth: " + askedItems(settlement, best));
        ToolSupplyGameTests.finish(helper, better, best);
    }

    /** Nobody asks for clothes before tier 2. */
    private static void tierOneAsksNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of());
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        Settler novice = wearer(helper, 15);
        check(helper, novice, today(level));
        helper.assertTrue(pieceRequests(settlement, novice).isEmpty(), "Tier 1 asks for nothing: " + settlement.requests());
        helper.assertTrue(worn(novice, EquipmentSlot.HEAD).isEmpty(), "Nothing is worn");
        ToolSupplyGameTests.finish(helper, novice);
    }

    private static ItemStack worn(Settler settler, EquipmentSlot slot) {
        return settler.getClothing().worn(slot);
    }

    // ---------------------------------------------------------------- pieces and collect inventories

    /** A cloth piece with the test durability, {@code damage} points used. */
    private static ItemStack damaged(ClothSet set, ArmorType type, int damage) {
        ItemStack stack = new ItemStack(piece(set, type));
        stack.set(DataComponents.MAX_DAMAGE, MAX_DAMAGE);
        stack.setDamageValue(damage);
        return stack;
    }

    /** Puts the pieces on in the order head, chest, legs, feet; a missing one leaves its slot empty. */
    private static void wear(Settler settler, ItemStack... pieces) {
        for (int i = 0; i < ClothingRules.SLOTS.size(); i++) {
            settler.getInventory().setItem(Settler.ARMOR_START + i, i < pieces.length ? pieces[i] : ItemStack.EMPTY);
        }
    }

    /** The first {@code count} pieces of a set in the order head, chest, legs, feet. */
    private static ItemStack[] pieces(ClothSet set, int count) {
        return TYPES.stream().limit(count).map(type -> damaged(set, type, 0)).toArray(ItemStack[]::new);
    }

    /** A chest next to the JobBlock of the settler becomes its collect inventory (no Deliveryman serves the Clerk, so it carries by itself). */
    private static Container collectChest(GameTestHelper helper, Settler settler) {
        helper.setBlock(new BlockPos(3, 1, 13), Blocks.CHEST);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 13));
        helper.assertTrue(JobAssignments.cyclePoint(SettlementData.get(helper.getLevel()), ToolSupplyGameTests.jobBlock(settler), chest, false, true)
                == JobAssignments.PointResult.COLLECT, "The chest is the collect inventory");
        return (Container) helper.getLevel().getBlockEntity(chest);
    }

    private static boolean has(Container container, Item item) {
        return container.hasAnyMatching(stack -> stack.is(item));
    }

    // ---------------------------------------------------------------- the store, the Tailor, the replacement

    /**
     * A fitting piece in the Clerk's store is used first: the lowest rank that fits goes to the settler's collect inventory and on its
     * body, and nothing is ordered for it; a piece of too low a rank does not fit, so a tier 3 settler orders its helmet.
     */
    private static void storeFirst(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of(new ItemStack(piece(ClothSet.SIMPLE, ArmorType.HELMET)), new ItemStack(piece(ClothSet.SIMPLE, ArmorType.HELMET)),
                new ItemStack(piece(ClothSet.HIGH, ArmorType.CHESTPLATE)), new ItemStack(piece(ClothSet.SIMPLE, ArmorType.CHESTPLATE)),
                new ItemStack(piece(ClothSet.HIGH, ArmorType.LEGGINGS))));
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        Settler settler = wearer(helper, TIER_2_SKILL);
        collectChest(helper, settler);
        long day = today(level);

        check(helper, settler, day);
        helper.assertTrue(worn(settler, EquipmentSlot.HEAD).is(piece(ClothSet.SIMPLE, ArmorType.HELMET)), "The helmet from the store is on");
        helper.assertTrue(worn(settler, EquipmentSlot.CHEST).is(piece(ClothSet.SIMPLE, ArmorType.CHESTPLATE)),
                "The lowest rank that fits goes first: " + worn(settler, EquipmentSlot.CHEST));
        helper.assertTrue(worn(settler, EquipmentSlot.LEGS).is(piece(ClothSet.HIGH, ArmorType.LEGGINGS)), "A higher rank fits a lower tier");
        helper.assertTrue(askedItems(settlement, settler).equals(Set.of(piece(ClothSet.SIMPLE, ArmorType.BOOTS))) && pieceRequests(settlement, settler).size() == 1,
                "Only the boots are ordered: " + settlement.requests());
        helper.assertTrue(ToolSupplyGameTests.free(level, clerk, piece(ClothSet.HIGH, ArmorType.CHESTPLATE)) == 1
                && ToolSupplyGameTests.free(level, clerk, piece(ClothSet.SIMPLE, ArmorType.HELMET)) == 1, "The rest stays in the store: " + ClerkStorage.stock(level, clerk, true).counts());

        Settler better = wearerAt(helper, new BlockPos(1, 1, 17), TIER_3_SKILL);
        check(helper, better, day);
        helper.assertTrue(askedItems(settlement, better).contains(piece(ClothSet.MEDIUM, ArmorType.HELMET)),
                "A simple helmet does not fit tier 3: " + askedItems(settlement, better));
        helper.assertTrue(worn(better, EquipmentSlot.HEAD).isEmpty(), "It took nothing");
        ToolSupplyGameTests.finish(helper, settler, better);
    }

    /**
     * The Tailor makes a piece nobody has: the settler asks for its boots, the Tailor sews them from wool and string, the finished
     * pair waits in the Clerk for the settler and is on its feet at the next search; the request is gone.
     */
    private static void tailorPieceArrives(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item boots = piece(ClothSet.SIMPLE, ArmorType.BOOTS);
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of(new ItemStack(Items.WOOL.blue(), 4), new ItemStack(Items.STRING)));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        BlockPos shop = TailorGameTests.tailorShop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTING_TABLE);
        Settler tailor = ToolSupplyGameTests.worker(helper, data, settlement, shop, new BlockPos(2, 1, 7), "Tessa", TailorGameTests.tailor());
        TailorGameTests.checkOnly(helper, shop, boots);
        Settler settler = wearer(helper, TIER_2_SKILL);
        helper.assertTrue(JobAssignments.cyclePoint(data, ToolSupplyGameTests.jobBlock(settler), clerk, false, true) == JobAssignments.PointResult.COLLECT,
                "The Clerk is the collect inventory");
        wear(settler, pieces(ClothSet.SIMPLE, 3));
        long day = today(level);

        check(helper, settler, day);
        List<Request> asked = pieceRequests(settlement, settler);
        helper.assertTrue(asked.size() == 1 && asked.getFirst().item().is(boots), "The settler asks for the boots: " + settlement.requests());

        CraftspersonGameTests.catchUp(level, tailor, WORK_TICKS);
        helper.assertTrue(pieceRequests(settlement, settler).isEmpty(), "The Tailor finished the request (status " + tailor.getWork().status(shop) + "): "
                + settlement.requests());
        helper.assertTrue(Holds.heldFor(settlement, ReservedFor.settler(settler.getUUID())).getOrDefault(clerk, Map.of()).getOrDefault(ItemKey.of(boots), 0) == 1,
                "The boots wait in the Clerk for the settler: " + Holds.heldFor(settlement, ReservedFor.settler(settler.getUUID())));
        helper.assertTrue(ToolSupplyGameTests.free(level, clerk, boots) == 0, "Nobody else sees them");

        check(helper, settler, day + 1);
        helper.assertTrue(worn(settler, EquipmentSlot.FEET).is(boots), "The settler wears the boots: " + worn(settler, EquipmentSlot.FEET));
        helper.assertTrue(Holds.heldFor(settlement, ReservedFor.settler(settler.getUUID())).isEmpty() && pieceRequests(settlement, settler).isEmpty(),
                "Nothing is held or asked any more");
        ToolSupplyGameTests.cleanUp(helper, settler, tailor);
        TestAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /**
     * A piece below 20 % makes the settler ask for one of the same rank; a healthy piece from the store replaces it, and the worn
     * piece goes back into the collect inventory.
     */
    private static void replacementBelow20(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of(new ItemStack(piece(ClothSet.SIMPLE, ArmorType.HELMET))));
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        Settler settler = wearer(helper, TIER_2_SKILL);
        Container chest = collectChest(helper, settler);
        wear(settler, damaged(ClothSet.SIMPLE, ArmorType.HELMET, 90), damaged(ClothSet.SIMPLE, ArmorType.CHESTPLATE, 0),
                damaged(ClothSet.SIMPLE, ArmorType.LEGGINGS, 0), damaged(ClothSet.SIMPLE, ArmorType.BOOTS, 0));

        check(helper, settler, today(level));
        ItemStack head = worn(settler, EquipmentSlot.HEAD);
        helper.assertTrue(head.is(piece(ClothSet.SIMPLE, ArmorType.HELMET)) && head.getDamageValue() == 0, "The healthy helmet from the store is on: " + head);
        helper.assertTrue(chest.hasAnyMatching(stack -> stack.is(piece(ClothSet.SIMPLE, ArmorType.HELMET)) && stack.getDamageValue() == 90),
                "The worn helmet went back to the collect inventory");
        helper.assertTrue(pieceRequests(settlement, settler).isEmpty(), "Nothing is asked: " + settlement.requests());
        ToolSupplyGameTests.finish(helper, settler);
    }

    /**
     * A worn-out piece is asked for with the same rank, or a higher one when the tier needs it. The worn piece stays on until the
     * replacement is there, and the need still counts it.
     */
    private static void wornPieceAsksSameRank(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of());
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        Settler settler = wearer(helper, TIER_2_SKILL);
        wear(settler, damaged(ClothSet.MEDIUM, ArmorType.HELMET, 90), damaged(ClothSet.SIMPLE, ArmorType.CHESTPLATE, 0),
                damaged(ClothSet.SIMPLE, ArmorType.LEGGINGS, 0), damaged(ClothSet.SIMPLE, ArmorType.BOOTS, 85));
        Settler better = wearerAt(helper, new BlockPos(1, 1, 17), TIER_3_SKILL);
        wear(better, damaged(ClothSet.MEDIUM, ArmorType.HELMET, 0), damaged(ClothSet.MEDIUM, ArmorType.CHESTPLATE, 0),
                damaged(ClothSet.MEDIUM, ArmorType.LEGGINGS, 0), damaged(ClothSet.SIMPLE, ArmorType.BOOTS, 90));
        long day = today(level);

        check(helper, settler, day);
        helper.assertTrue(askedItems(settlement, settler).equals(Set.of(piece(ClothSet.MEDIUM, ArmorType.HELMET), piece(ClothSet.SIMPLE, ArmorType.BOOTS)))
                && pieceRequests(settlement, settler).size() == 2, "A worn piece is asked for at its own rank: " + settlement.requests());
        check(helper, better, day);
        helper.assertTrue(askedItems(settlement, better).equals(Set.of(piece(ClothSet.MEDIUM, ArmorType.BOOTS))),
                "Tier 3 needs better clothing even for a worn simple piece: " + askedItems(settlement, better));
        helper.assertTrue(worn(settler, EquipmentSlot.HEAD).getDamageValue() == 90 && worn(better, EquipmentSlot.FEET).getDamageValue() == 90,
                "The worn pieces stay on until the replacement is there");
        ToolSupplyGameTests.finish(helper, settler, better);
    }

    // ---------------------------------------------------------------- the switch and the end of a request

    /** With the Clerk's supply switched off nothing is asked and the store is not touched; switched on, the next search asks. */
    private static void switchOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of(new ItemStack(piece(ClothSet.SIMPLE, ArmorType.HELMET))));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        Settler settler = wearer(helper, TIER_2_SKILL);
        collectChest(helper, settler);
        long day = today(level);

        helper.assertFalse(JobAssignments.setAutoSupply(data, clerk, false), "The Clerk switches the supply off");
        check(helper, settler, day);
        helper.assertTrue(settlement.requests().isEmpty() && worn(settler, EquipmentSlot.HEAD).isEmpty(),
                "No request, nothing taken from the store: " + settlement.requests());
        helper.assertTrue(ToolSupplyGameTests.free(level, clerk, piece(ClothSet.SIMPLE, ArmorType.HELMET)) == 1, "The store keeps its helmet");

        JobAssignments.setAutoSupply(data, clerk, true);
        check(helper, settler, day + 1);
        helper.assertTrue(worn(settler, EquipmentSlot.HEAD).is(piece(ClothSet.SIMPLE, ArmorType.HELMET)) && pieceRequests(settlement, settler).size() == 3,
                "On again: the helmet comes and the rest is asked for: " + settlement.requests());
        ToolSupplyGameTests.finish(helper, settler);
    }

    /**
     * A request ends when the piece is worn (by hand too), when the Job goes away and when the settler is gone.
     */
    private static void requestEnds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of());
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        Settler owner = wearer(helper, TIER_2_SKILL);
        Settler leaves = wearerAt(helper, new BlockPos(1, 1, 17), TIER_2_SKILL);
        Settler dies = wearerAt(helper, new BlockPos(1, 1, 21), TIER_2_SKILL);
        long day = today(level);
        for (Settler settler : List.of(owner, leaves, dies)) {
            check(helper, settler, day);
        }
        helper.assertTrue(settlement.requests().size() == 12, "Three settlers asked for four pieces: " + settlement.requests());

        helper.assertTrue(owner.getClothing().equip(new ItemStack(piece(ClothSet.SIMPLE, ArmorType.HELMET))).isPresent(), "The player puts a helmet on");
        helper.assertTrue(pieceRequests(settlement, owner).size() == 3 && !askedItems(settlement, owner).contains(piece(ClothSet.SIMPLE, ArmorType.HELMET)),
                "The request for the helmet ended: " + askedItems(settlement, owner));

        leaves.removeJob(ToolSupplyGameTests.jobBlock(leaves));
        helper.assertTrue(pieceRequests(settlement, leaves).isEmpty() && settlement.requests().size() == 7, "The Job went away: " + settlement.requests());

        dies.discard();
        helper.assertTrue(pieceRequests(settlement, dies).isEmpty() && settlement.requests().size() == 3, "The settler is gone: " + settlement.requests());
        ToolSupplyGameTests.finish(helper, owner, leaves);
    }
}
