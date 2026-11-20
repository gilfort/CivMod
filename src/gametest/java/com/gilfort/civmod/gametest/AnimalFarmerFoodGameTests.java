package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.job.AnimalFarmerProfile;
import com.gilfort.civmod.job.AnimalFood;
import com.gilfort.civmod.job.JobDrops;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Holds;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the food cost of the Animal Farmer (vault {@code Jobs/Animal Farmer.md}, decisions of 2026-11-15): every
 * roll uses up the breeding food of the rolled animal type from the collect inventory, here a chest at the edge of the pen.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AnimalFarmerFoodGameTests {

    private static final BlockPos CHEST = new BlockPos(8, 1, 8);
    private static final BlockPos ANIMAL = new BlockPos(6, 1, 6);
    private static final BlockPos OTHER_ANIMAL = new BlockPos(6, 1, 3);
    private static final int FOOD_PER_ROLL = 2;

    private AnimalFarmerFoodGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "animal_farmer_food_cost", AnimalFarmerFoodGameTests::costPerRoll);
            register(helper, "animal_farmer_food_by_type", AnimalFarmerFoodGameTests::foodByType);
            register(helper, "animal_farmer_food_type_drops_out", AnimalFarmerFoodGameTests::typeDropsOut);
            register(helper, "animal_farmer_food_several", AnimalFarmerFoodGameTests::severalFoods);
            register(helper, "animal_farmer_food_modded", AnimalFarmerFoodGameTests::moddedAnimal);
            register(helper, "animal_farmer_food_no_food_bubble", AnimalFarmerFoodGameTests::noFoodBubble);
            register(helper, "animal_farmer_food_held_for_farmer", AnimalFarmerFoodGameTests::heldForFarmer);
            register(helper, "animal_farmer_food_from_store", AnimalFarmerFoodGameTests::fromStore);
            register(helper, "animal_farmer_food_asks_what_can_be_made", AnimalFarmerFoodGameTests::asksWhatCanBeMade);
            register(helper, "animal_farmer_food_job_removed", AnimalFarmerFoodGameTests::jobRemoved);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static AnimalFarmerProfile farmer() {
        return (AnimalFarmerProfile) AnimalFarmerGameTests.profile();
    }

    /** Food for every roll without end: for tests that look at the loot and not at the cost. */
    static JobDrops.Tools endless() {
        return new JobDrops.Tools() {
            @Override
            public Optional<ItemStack> find(Predicate<ItemStack> kind) {
                return Optional.empty();
            }

            @Override
            public void wear(Predicate<ItemStack> kind) {
            }

            @Override
            public int count(Predicate<ItemStack> kind) {
                return Integer.MAX_VALUE;
            }

            @Override
            public boolean take(Predicate<ItemStack> kind, int amount) {
                return true;
            }
        };
    }

    /** A chest at the edge of the pen holding the given stacks: the collect inventory of these tests. */
    static BlockPos chest(GameTestHelper helper, ItemStack... stock) {
        helper.setBlock(CHEST, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        chest.clearContent();
        for (int i = 0; i < stock.length; i++) {
            chest.setItem(i, stock[i].copy());
        }
        return helper.absolutePos(CHEST);
    }

    static int inChest(GameTestHelper helper, Predicate<ItemStack> kind) {
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (kind.test(chest.getItem(i))) {
                count += chest.getItem(i).getCount();
            }
        }
        return count;
    }

    static int inChest(GameTestHelper helper, Item item) {
        return inChest(helper, stack -> stack.is(item));
    }

    static JobDrops.Tools collect(GameTestHelper helper, BlockPos chest) {
        ServerLevel level = helper.getLevel();
        return JobDrops.Tools.of(level, null, () -> ClerkStorage.stock(level, chest, true));
    }

    static ScanResult scan(GameTestHelper helper) {
        return AnimalFarmerGameTests.profile().createScan(helper.absolutePos(AnimalFarmerGameTests.HAY), 10).runAll(helper.getLevel());
    }

    /** One roll: a cow in the pen, 5 wheat in the chest. Each roll uses up 2 wheat and gives loot; 1 wheat is not enough. */
    private static void costPerRoll(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        AnimalFarmerGameTests.animal(helper, EntityTypes.COW, ANIMAL, false);
        BlockPos chest = chest(helper, new ItemStack(Items.WHEAT, 5));
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        ResourceCollectionProfile.Context context = AnimalFarmerGameTests.context(helper, settler, scan(helper), Set.of());
        JobDrops.Tools tools = collect(helper, chest);
        helper.assertFalse(farmer().noFood(context, tools), "Wheat is there");
        for (int expected = 3; expected >= 1; expected -= FOOD_PER_ROLL) {
            List<ItemStack> loot = farmer().produce(context, tools).orElseThrow().stacks();
            helper.assertFalse(loot.isEmpty(), "A roll with food gives loot");
            helper.assertValueEqual(inChest(helper, Items.WHEAT), expected, "A roll uses up 2 wheat");
        }
        helper.assertTrue(farmer().noFood(context, tools), "One wheat is not enough for a roll");
        helper.assertTrue(farmer().produce(context, tools).isEmpty(), "Without food no loot is given");
        helper.assertValueEqual(inChest(helper, Items.WHEAT), 1, "Nothing is used up without a roll");
        settler.discard();
        helper.succeed();
    }

    /** A cow and a sheep eat wheat, a pig carrots or potatoes, a chicken seeds; the other foods do not feed them. */
    private static void foodByType(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        feeds(helper, settler, EntityTypes.COW, Items.WHEAT, Items.CARROT);
        feeds(helper, settler, EntityTypes.SHEEP, Items.WHEAT, Items.CARROT);
        feeds(helper, settler, EntityTypes.PIG, Items.CARROT, Items.WHEAT);
        feeds(helper, settler, EntityTypes.PIG, Items.POTATO, Items.WHEAT);
        feeds(helper, settler, EntityTypes.CHICKEN, Items.WHEAT_SEEDS, Items.WHEAT);
        settler.discard();
        helper.succeed();
    }

    private static void feeds(GameTestHelper helper, Settler settler, EntityType<? extends Animal> type, Item food, Item other) {
        Animal animal = AnimalFarmerGameTests.animal(helper, type, ANIMAL, false);
        ResourceCollectionProfile.Context context = AnimalFarmerGameTests.context(helper, settler, scan(helper), Set.of());
        String name = type.getDescriptionId();
        BlockPos chest = chest(helper, new ItemStack(other, 10));
        helper.assertTrue(farmer().noFood(context, collect(helper, chest)), name + " is not fed with " + other);
        helper.assertTrue(farmer().produce(context, collect(helper, chest)).isEmpty(), name + " gives nothing for " + other);
        chest = chest(helper, new ItemStack(food, FOOD_PER_ROLL));
        helper.assertTrue(farmer().produce(context, collect(helper, chest)).filter(produced -> !produced.stacks().isEmpty()).isPresent(),
                name + " gives loot for " + food);
        helper.assertValueEqual(inChest(helper, food), 0, name + " used up its food");
        animal.discard();
    }

    /** With a cow and a pig and wheat only, the pig leaves the roll and the cow is still rolled. */
    private static void typeDropsOut(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        AnimalFarmerGameTests.animal(helper, EntityTypes.COW, ANIMAL, false);
        AnimalFarmerGameTests.animal(helper, EntityTypes.PIG, OTHER_ANIMAL, false);
        BlockPos chest = chest(helper, new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 64));
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        ResourceCollectionProfile.Context context = AnimalFarmerGameTests.context(helper, settler, scan(helper), Set.of());
        JobDrops.Tools tools = collect(helper, chest);
        int rolls = 60;
        List<ItemStack> loot = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            loot.addAll(farmer().produce(context, tools).orElseThrow().stacks());
        }
        helper.assertFalse(loot.stream().anyMatch(stack -> stack.is(Items.PORKCHOP)), "The pig has no food and is left out");
        helper.assertTrue(loot.stream().anyMatch(stack -> stack.is(Items.BEEF)), "The cow is still rolled");
        helper.assertValueEqual(inChest(helper, Items.WHEAT), 192 - rolls * FOOD_PER_ROLL, "Every roll cost the cow's wheat");
        settler.discard();
        helper.succeed();
    }

    /** A pig takes what is there: beetroot alone, or a carrot and a potato together. */
    private static void severalFoods(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        AnimalFarmerGameTests.animal(helper, EntityTypes.PIG, ANIMAL, false);
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        ResourceCollectionProfile.Context context = AnimalFarmerGameTests.context(helper, settler, scan(helper), Set.of());
        BlockPos chest = chest(helper, new ItemStack(Items.BEETROOT, FOOD_PER_ROLL));
        helper.assertTrue(farmer().produce(context, collect(helper, chest)).isPresent(), "Beetroot feeds a pig");
        helper.assertValueEqual(inChest(helper, Items.BEETROOT), 0, "The beetroot is used up");
        chest = chest(helper, new ItemStack(Items.CARROT), new ItemStack(Items.POTATO));
        helper.assertTrue(farmer().produce(context, collect(helper, chest)).isPresent(), "A carrot and a potato together are two feeds");
        helper.assertValueEqual(inChest(helper, stack -> !stack.isEmpty()), 0, "Both are used up");
        chest = chest(helper, new ItemStack(Items.POTATO, FOOD_PER_ROLL), new ItemStack(Items.CARROT, FOOD_PER_ROLL));
        helper.assertTrue(farmer().produce(context, collect(helper, chest)).isPresent(), "Enough of either");
        helper.assertValueEqual(inChest(helper, Items.CARROT), 0, "The carrots come first in registry order, wherever they lie");
        helper.assertValueEqual(inChest(helper, Items.POTATO), FOOD_PER_ROLL, "The potatoes stay");
        settler.discard();
        helper.succeed();
    }

    /**
     * An animal the mod does not know: the armadillo (counted through a drop pool entry of the test pack) eats spider eyes.
     * Its food comes from the animal itself, so no datapack entry is needed.
     */
    private static void moddedAnimal(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        AnimalFarmerGameTests.animal(helper, EntityTypes.ARMADILLO, ANIMAL, false);
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        ResourceCollectionProfile.Context context = AnimalFarmerGameTests.context(helper, settler, scan(helper), Set.of());
        BlockPos chest = chest(helper, new ItemStack(Items.WHEAT, 10));
        helper.assertTrue(farmer().noFood(context, collect(helper, chest)), "Wheat does not feed an armadillo");
        chest = chest(helper, new ItemStack(Items.SPIDER_EYE, FOOD_PER_ROLL));
        List<ItemStack> loot = farmer().produce(context, collect(helper, chest)).orElseThrow().stacks();
        helper.assertTrue(loot.stream().anyMatch(stack -> stack.is(Items.ARMADILLO_SCUTE)), "A scute for two spider eyes: " + loot);
        helper.assertValueEqual(inChest(helper, Items.SPIDER_EYE), 0, "The spider eyes are used up");
        settler.discard();
        helper.succeed();
    }

    // ---------------------------------------------------------------- the farmer at work

    private static final BlockPos STORE = new BlockPos(4, 1, 1);
    private static final BlockPos FARM_BLOCK = new BlockPos(1, 1, 13);
    private static final BlockPos FARM_HAY = new BlockPos(4, 1, 15);
    private static final BlockPos FARM_ANIMAL = new BlockPos(6, 1, 16);
    private static final BlockPos COLLECT_CHEST = new BlockPos(3, 1, 13);
    /** The corner of the farm that is furthest from the test origin. */
    private static final BlockPos FARM_FAR_CORNER = new BlockPos(9, 1, 21);

    /** The live set-up: a working Clerk with its chest, a Gatherer JobBlock 12 blocks away and a pen with a hay bale. */
    record Farm(Settler settler, BlockPos jobBlock, BlockPos clerk, Settlement settlement, List<long[]> forced) {

        ReservedFor me() {
            return ReservedFor.settler(settler.getUUID());
        }

        /** The requests the farmer posted. */
        List<Request> requests() {
            return settlement.requests().stream().filter(request -> request.reservedFor().filter(me()::equals).isPresent()).toList();
        }

        void close(ServerLevel level) {
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        }
    }

    /**
     * An Animal Farmer with an axe and one animal of the given type in its pen; the Clerk's store holds the given stacks.
     *
     * @param chestAsCollect whether the farmer collects at a chest next to its JobBlock instead of at the Clerk
     */
    static Farm farm(GameTestHelper helper, EntityType<? extends Animal> type, boolean chestAsCollect, ItemStack... store) {
        ServerLevel level = helper.getLevel();
        // The farm reaches from the Clerk at the origin to z 20: every block of it must lie in an entity-ticking chunk.
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, FARM_FAR_CORNER);
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(STORE));
        for (int i = 0; i < store.length; i++) {
            chest.setItem(i, store[i].copy());
        }
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 21; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
            }
        }
        helper.setBlock(FARM_HAY, Blocks.HAY_BLOCK);
        AnimalFarmerGameTests.animal(helper, type, FARM_ANIMAL, false);
        BlockPos jobBlock = helper.absolutePos(FARM_BLOCK);
        TestSettlements.place(helper, jobBlock, ModJobs.GATHERER.get(), 5);
        SettlementData data = SettlementData.get(level);
        TestAssignments.setStationType(data, jobBlock, 1, Items.HAY_BLOCK);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 12), "Herder");
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Herder", settlement.id(), List.of(), ModJobs.ANIMAL_FARMER.get())
                == JobAssignments.AssignResult.OK, "Farmer assigned");
        settler.addJob(new SettlerJob(ModJobs.ANIMAL_FARMER.get(), jobBlock));
        // Fed, and its morning routine is done: it goes to work at once.
        settler.getFood().setLevel(20);
        settler.getFood().setMorningDay(Math.floorDiv(level.getOverworldClockTime(), 24000L));
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        BlockPos collect = clerk;
        if (chestAsCollect) {
            helper.setBlock(COLLECT_CHEST, Blocks.CHEST);
            collect = helper.absolutePos(COLLECT_CHEST);
        }
        helper.assertTrue(JobAssignments.cyclePoint(data, jobBlock, collect, false, true) == JobAssignments.PointResult.COLLECT, "The collect inventory is set");
        return new Farm(settler, jobBlock, clerk, settlement, forced);
    }

    static boolean gaveLoot(Farm farm, Item... items) {
        return Arrays.stream(items).anyMatch(item -> farm.settler().getWork().buffer(farm.jobBlock()).counts().containsKey(ItemKey.of(item)));
    }

    /**
     * A farmer without any food for its cow stops with the status "No food" and the problem bubble, and asks its Home Clerk
     * for wheat, reserved for itself. It does not stall silently.
     */
    private static void noFoodBubble(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Farm farm = farm(helper, EntityTypes.COW, false);
        // The job is blocked for half a minute at a time and the status falls back to idle in between: remember that it showed.
        AtomicBoolean sawStatus = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (farm.settler().getWork().status(farm.jobBlock()) == JobStatus.NO_FOOD) {
                sawStatus.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(sawStatus.get(), "Status " + farm.settler().getWork().status(farm.jobBlock()) + " at " + farm.settler().blockPosition()
                    + " requests " + farm.settlement().requests() + " thoughts " + farm.settler().getWork().recentThoughts());
            helper.assertTrue(farm.settler().getWork().recentThoughts().stream().anyMatch(show -> show.bubble() == ThoughtBubble.NO_FOOD && show.on()),
                    "The problem bubble shows: " + farm.settler().getWork().recentThoughts());
            helper.assertTrue(farm.requests().stream().anyMatch(request -> request.item().is(Items.WHEAT) && request.amount() > 0
                    && request.target().equals(Optional.of(farm.clerk()))), "A request for wheat reached the Home Clerk: " + farm.settlement().requests());
            helper.assertFalse(gaveLoot(farm, Items.BEEF, Items.LEATHER), "No loot without food");
            farm.close(level);
        });
    }

    /**
     * Food that arrived for the farmer (held for it in the Home Clerk, which is its collect inventory) is released to it;
     * it works and every roll uses up 2 of it. Nothing more is asked for.
     */
    private static void heldForFarmer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Farm farm = farm(helper, EntityTypes.COW, false, new ItemStack(Items.WHEAT, 8));
        Holds.hold(farm.settlement(), farm.clerk(), farm.me(), ItemKey.of(Items.WHEAT), 8);
        helper.succeedWhen(() -> {
            helper.assertTrue(gaveLoot(farm, Items.BEEF, Items.LEATHER), "No loot yet, status " + farm.settler().getWork().status(farm.jobBlock()));
            int left = ClerkStorage.stock(level, farm.clerk(), true).count(stack -> stack.is(Items.WHEAT));
            helper.assertTrue(left < 8 && left % FOOD_PER_ROLL == 0, "Rolls used up wheat in pairs: " + left + " left");
            helper.assertTrue(Holds.heldFor(farm.settlement(), farm.me()).isEmpty() && farm.requests().isEmpty(), "Nothing held and nothing asked for");
            farm.close(level);
        });
    }

    /**
     * A chicken eats several seeds. Nothing lies in the store, so the farmer asks for the first seed a producer of the
     * settlement can make (pumpkin seeds, from a Craftsperson's checked pattern) and not for the first in registry order
     * (wheat seeds).
     */
    private static void asksWhatCanBeMade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Farm farm = farm(helper, EntityTypes.CHICKEN, false);
        SettlementData data = SettlementData.get(level);
        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        helper.assertTrue(TestAssignments.assign(data, craft, UUID.randomUUID(), "Maker", farm.settlement().id(), List.of(), CraftspersonGameTests.craftJob())
                == JobAssignments.AssignResult.OK, "Craftsperson assigned");
        helper.assertTrue(Patterns.setOutput(level, craft, CraftspersonGameTests.craftJob(), 0, ItemKey.of(Items.PUMPKIN_SEEDS)).orElseThrow().checked(),
                "Pumpkin seeds checked");
        helper.succeedWhen(() -> {
            helper.assertTrue(farm.requests().stream().anyMatch(request -> request.item().is(Items.PUMPKIN_SEEDS) && request.target().equals(Optional.of(farm.clerk()))),
                    "The farmer asks for the seeds that can be made: " + farm.settlement().requests());
            helper.assertTrue(farm.requests().size() == 1, "One request: " + farm.requests());
            farm.close(level);
        });
    }

    /**
     * The farmer loses its Job: its requests for food and what is held for it end, also when the foods of the animal types
     * are not known (after a restart or a tag reload the cache is empty).
     */
    private static void jobRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Farm farm = farm(helper, EntityTypes.COW, false);
        SettlementData data = SettlementData.get(level);
        Requests.postReserved(data, farm.settlement(), ItemKey.of(Items.WHEAT), 16, farm.clerk(), farm.me());
        Holds.hold(farm.settlement(), farm.clerk(), farm.me(), ItemKey.of(Items.CARROT), 4);
        helper.assertFalse(farm.requests().isEmpty() || Holds.heldFor(farm.settlement(), farm.me()).isEmpty(), "Set-up: a request and a hold");
        AnimalFood.forget();
        farm.settler().removeJob(farm.jobBlock());
        helper.assertTrue(farm.requests().isEmpty(), "The request for food is withdrawn: " + farm.requests());
        helper.assertTrue(Holds.heldFor(farm.settlement(), farm.me()).isEmpty(), "Nothing is held for the settler: " + Holds.heldFor(farm.settlement(), farm.me()));
        farm.close(level);
        helper.succeed();
    }

    /**
     * Food that lies in the Clerk's store is used before any is asked for: the farmer carries it into its collect chest
     * (no Deliveryman serves the Clerk) and works with it.
     */
    private static void fromStore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Farm farm = farm(helper, EntityTypes.PIG, true, new ItemStack(Items.POTATO, 8));
        helper.succeedWhen(() -> {
            helper.assertTrue(gaveLoot(farm, Items.PORKCHOP), "No loot yet, status " + farm.settler().getWork().status(farm.jobBlock()));
            helper.assertTrue(farm.settlement().requests().isEmpty(), "Nothing is asked for: " + farm.settlement().requests());
            helper.assertTrue(((Container) level.getBlockEntity(helper.absolutePos(COLLECT_CHEST))).hasAnyMatching(stack -> stack.is(Items.POTATO)),
                    "The potatoes are in the collect chest");
            farm.close(level);
        });
    }
}
