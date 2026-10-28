package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.FarmCurve;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.job.AnimalScan;
import com.gilfort.civmod.job.SavedScan;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Animal Farmer (vault {@code Jobs/Animal Farmer.md}): the loot of the adult animals around its hay
 * bale without killing them, eggs from chickens, exclusions, the Found tab entries and the saved scan.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AnimalFarmerGameTests {

    private static final double EPSILON = 1.0E-6;
    private static final Identifier COW = Identifier.withDefaultNamespace("cow");
    private static final Identifier SHEEP = Identifier.withDefaultNamespace("sheep");
    private static final Identifier CHICKEN = Identifier.withDefaultNamespace("chicken");
    /** Where the test pens put the hay bale (relative). */
    private static final BlockPos HAY = new BlockPos(4, 1, 4);

    private AnimalFarmerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "animal_farmer_delivers_pen_loot", AnimalFarmerGameTests::deliversPenLoot);
            register(helper, "animal_farmer_egg_share", AnimalFarmerGameTests::eggShare);
            register(helper, "animal_farmer_exclusion", AnimalFarmerGameTests::exclusion);
            register(helper, "animal_farmer_found_and_saved", AnimalFarmerGameTests::foundAndSaved);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static ResourceCollectionProfile profile() {
        return ModJobs.ANIMAL_FARMER.get().profile(ResourceCollectionProfile.class).orElseThrow();
    }

    /** A standing animal (no AI), adult or baby, at a relative position. */
    static <T extends Animal> T animal(GameTestHelper helper, EntityType<T> type, BlockPos relative, boolean baby) {
        T animal = helper.spawn(type, relative);
        animal.setNoAi(true);
        animal.setBaby(baby);
        return animal;
    }

    /** A grass pen with a level-2 Gatherer JobBlock at (1, 1, 1), the hay bale in slot 1 and a hay bale at {@link #HAY}. */
    private static BlockPos pen(GameTestHelper helper) {
        BlockPos relative = GathererGameTests.gathererBlock(helper);
        StationTypeGameTests.setLevel(helper, relative, 2);
        BlockPos jobBlock = helper.absolutePos(relative);
        TestAssignments.setStationType(SettlementData.get(helper.getLevel()), jobBlock, 1, Items.HAY_BLOCK);
        helper.setBlock(HAY, Blocks.HAY_BLOCK);
        return jobBlock;
    }

    private static Settler animalFarmer(GameTestHelper helper, BlockPos jobBlock) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        TestAssignments.assign(data, jobBlock, settler.getUUID(), "Herder", data.settlementAt(jobBlock).orElseThrow().id(), List.of(),
                ModJobs.ANIMAL_FARMER.get());
        settler.addJob(new SettlerJob(ModJobs.ANIMAL_FARMER.get(), jobBlock));
        return settler;
    }

    /**
     * An Animal Farmer with an axe delivers the loot of the two cows in its pen; the cows and the baby sheep stay
     * alive, the baby is not counted and gives nothing. The scan counts adults only and the farm curve uses the data
     * values ({@code k} 0.1, cap 20 / 40 / 60 / 80 / 100).
     */
    private static void deliversPenLoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos jobBlock = pen(helper);
        List<Animal> animals = List.of(animal(helper, EntityTypes.COW, new BlockPos(6, 1, 6), false),
                animal(helper, EntityTypes.COW, new BlockPos(6, 1, 3), false), animal(helper, EntityTypes.SHEEP, new BlockPos(3, 1, 6), true));
        Job job = ModJobs.ANIMAL_FARMER.get();
        helper.assertValueEqual(job.farmCurve(), Optional.of(new FarmCurve(0.1, List.of(20, 40, 60, 80, 100))), "Animal Farmer curve");
        ScanResult scan = profile().createScan(helper.absolutePos(HAY), profile().scanRadius(2)).runAll(level);
        helper.assertValueEqual(scan.foundBlocks(), List.of(COW), "Adult animals only");
        helper.assertValueEqual(profile().farmCount(scan), 2, "Two cows counted");
        assertNear(helper, job.productivity(scan, 2), 0.24, "Two cows");
        helper.assertValueEqual(profile().scanRadius(3), 15, "Radius 5 × JobBlock level");
        Settler settler = animalFarmer(helper, jobBlock);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        helper.succeedWhen(() -> {
            Map<Item, Integer> buffer = settler.getWork().buffer(jobBlock).counts();
            helper.assertTrue(buffer.getOrDefault(Items.BEEF, 0) + buffer.getOrDefault(Items.LEATHER, 0) > 0,
                    "No cow loot yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertFalse(buffer.containsKey(Items.MUTTON) || buffer.keySet().stream().anyMatch(item -> item.getDefaultInstance().is(ItemTags.WOOL)), "The baby sheep gives nothing: " + buffer);
            helper.assertTrue(animals.stream().allMatch(Animal::isAlive), "Every animal stays alive");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A production context at the hay bale with a settler holding a stone axe. */
    private static ResourceCollectionProfile.Context context(GameTestHelper helper, Settler settler, ScanResult scan, Set<Identifier> excluded) {
        ServerLevel level = helper.getLevel();
        BlockPos poi = helper.absolutePos(HAY);
        ItemStack axe = new ItemStack(Items.STONE_AXE);
        return new ResourceCollectionProfile.Context(level, settler, scan, poi, axe, excluded, 1, Set.of(),
                type -> profile().dropped(level, poi, type, axe));
    }

    /** All items of {@code rolls} productions. */
    private static List<List<ItemStack>> roll(GameTestHelper helper, ResourceCollectionProfile.Context context, int rolls) {
        List<List<ItemStack>> outputs = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            outputs.add(profile().produce(context).orElseThrow().stacks());
        }
        return outputs;
    }

    /** With chickens only, about every fifth output is an egg instead of their loot; the chickens stay alive. */
    private static void eggShare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = pen(helper);
        List<Animal> chickens = List.of(animal(helper, EntityTypes.CHICKEN, new BlockPos(6, 1, 6), false),
                animal(helper, EntityTypes.CHICKEN, new BlockPos(6, 1, 3), false));
        ScanResult scan = profile().createScan(helper.absolutePos(HAY), 10).runAll(level);
        helper.assertValueEqual(scan.foundBlocks(), List.of(CHICKEN), "Chickens found");
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        int rolls = 600;
        long eggs = roll(helper, context(helper, settler, scan, Set.of()), rolls).stream()
                .filter(stacks -> stacks.stream().anyMatch(stack -> stack.is(Items.EGG) || stack.is(Items.BROWN_EGG) || stack.is(Items.BLUE_EGG))).count();
        double share = (double) eggs / rolls;
        // 20 % expected; 600 rolls give a standard deviation of 1.6 %.
        helper.assertTrue(share > 0.14 && share < 0.26, "Egg share " + share);
        helper.assertTrue(chickens.stream().allMatch(Animal::isAlive), "The chickens stay alive");
        settler.discard();
        helper.succeed();
    }

    /** Excluding the sheep leaves only cow loot in the roll. */
    private static void exclusion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = pen(helper);
        animal(helper, EntityTypes.COW, new BlockPos(6, 1, 6), false);
        animal(helper, EntityTypes.SHEEP, new BlockPos(6, 1, 3), false);
        animal(helper, EntityTypes.SHEEP, new BlockPos(3, 1, 6), false);
        ScanResult scan = profile().createScan(helper.absolutePos(HAY), 10).runAll(level);
        helper.assertValueEqual(scan.foundBlocks(), List.of(COW, SHEEP), "Cows and sheep found");
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        List<ItemStack> all = roll(helper, context(helper, settler, scan, Set.of()), 100).stream().flatMap(List::stream).toList();
        helper.assertTrue(all.stream().anyMatch(stack -> stack.is(ItemTags.WOOL) || stack.is(Items.MUTTON)), "Sheep loot without exclusion");
        List<ItemStack> excluded = roll(helper, context(helper, settler, scan, Set.of(SHEEP)), 100).stream().flatMap(List::stream).toList();
        helper.assertFalse(excluded.stream().anyMatch(stack -> stack.is(ItemTags.WOOL) || stack.is(Items.MUTTON)), "No sheep loot: " + excluded);
        helper.assertTrue(excluded.stream().anyMatch(stack -> stack.is(Items.BEEF)), "Cow loot");
        settler.discard();
        helper.succeed();
    }

    /** The Found tab lists each animal type as its spawn egg with name and count; the scan survives a save. */
    private static void foundAndSaved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = pen(helper);
        animal(helper, EntityTypes.COW, new BlockPos(6, 1, 6), false);
        animal(helper, EntityTypes.COW, new BlockPos(6, 1, 3), false);
        animal(helper, EntityTypes.PIG, new BlockPos(3, 1, 6), false);
        ScanResult scan = profile().createScan(helper.absolutePos(HAY), 10).runAll(level);
        List<ResourceCollectionProfile.Found> found = profile().found(scan, Optional.empty());
        helper.assertValueEqual(found.stream().map(ResourceCollectionProfile.Found::type).toList(),
                List.of(COW, Identifier.withDefaultNamespace("pig")), "Found types");
        ItemStack cow = found.getFirst().icon();
        helper.assertTrue(cow.is(Items.COW_SPAWN_EGG), "Cow spawn egg: " + cow);
        String name = cow.getHoverName().getString();
        helper.assertTrue(name.contains("Cow") && name.contains("2"), "Name and count: " + name);
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SavedScan saved = SavedScan.of(jobBlock, helper.absolutePos(HAY), 10, 3, scan).orElseThrow(() -> new AssertionError("Animal scan is saved"));
        SavedScan loaded = SavedScan.CODEC.parse(ops, SavedScan.CODEC.encodeStart(ops, saved).getOrThrow()).getOrThrow();
        helper.assertValueEqual(loaded.result(), scan, "Saved scan");
        helper.assertTrue(((AnimalScan.Result) scan).has(EntityTypes.COW), "The scan exposes its entity types");
        helper.succeed();
    }

    private static void assertNear(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < EPSILON, what + ": " + actual + " (expected " + expected + ")");
    }
}
