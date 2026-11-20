package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.job.AnimalFarmerProfile;
import com.gilfort.civmod.job.JobDrops;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Holds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for shearing sheep (vault {@code Jobs/Animal Farmer.md}, decisions of 2026-11-20): a second entry of the sheep
 * that needs shears in the collect inventory, gives 1 to 3 wool and costs no food.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AnimalFarmerShearGameTests {

    private static final BlockPos SHEEP = new BlockPos(6, 1, 6);
    private static final Identifier SHEEP_TYPE = Identifier.withDefaultNamespace("sheep");

    private AnimalFarmerShearGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "animal_farmer_shear_wool", AnimalFarmerShearGameTests::shearsGiveWool);
            register(helper, "animal_farmer_shear_without_shears", AnimalFarmerShearGameTests::withoutShears);
            register(helper, "animal_farmer_shear_switched_off", AnimalFarmerShearGameTests::switchedOff);
            register(helper, "animal_farmer_shear_found_entries", AnimalFarmerShearGameTests::foundEntries);
            register(helper, "animal_farmer_shear_orders_shears", AnimalFarmerShearGameTests::ordersShears);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static AnimalFarmerProfile farmer() {
        return (AnimalFarmerProfile) AnimalFarmerGameTests.profile();
    }

    private static int wool(List<ItemStack> stacks) {
        return stacks.stream().filter(stack -> stack.is(ItemTags.WOOL)).mapToInt(ItemStack::getCount).sum();
    }

    /**
     * A sheep, shears in the chest and no food at all: every roll gives 1 to 3 wool and nothing else, the shears are the
     * tool that wears, and the farmer is not out of food.
     */
    private static void shearsGiveWool(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        AnimalFarmerGameTests.animal(helper, EntityTypes.SHEEP, SHEEP, false);
        BlockPos chest = AnimalFarmerFoodGameTests.chest(helper, new ItemStack(Items.SHEARS));
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        ScanResult scan = AnimalFarmerFoodGameTests.scan(helper);
        ResourceCollectionProfile.Context context = AnimalFarmerGameTests.context(helper, settler, scan, Set.of());
        ServerLevel level = helper.getLevel();
        // With the settler, so the shears wear.
        JobDrops.Tools tools = JobDrops.Tools.of(level, settler, () -> ClerkStorage.stock(level, chest, true));
        helper.assertFalse(farmer().noFood(context, tools), "Shearing needs no food");
        int fewest = Integer.MAX_VALUE;
        int most = 0;
        for (int i = 0; i < 60; i++) {
            ResourceCollectionProfile.Produced produced = farmer().produce(context, tools).orElseThrow();
            int count = wool(produced.stacks());
            helper.assertTrue(count == produced.stacks().stream().mapToInt(ItemStack::getCount).sum(), "Only wool: " + produced.stacks());
            helper.assertTrue(produced.worn().isEmpty(), "The axe is spared: " + produced.worn());
            fewest = Math.min(fewest, count);
            most = Math.max(most, count);
        }
        helper.assertTrue(fewest >= 1 && most <= 3, "1 to 3 wool per shearing: " + fewest + " to " + most);
        helper.assertTrue(fewest < most, "The amount varies over 60 rolls");
        ItemStack shears = ((Container) level.getBlockEntity(chest)).getItem(0);
        helper.assertTrue(shears.is(Items.SHEARS) && shears.getDamageValue() == 60, "Every shearing wears the shears by 1: " + shears.getDamageValue());
        settler.discard();
        helper.succeed();
    }

    /** Without shears the shear entry is skipped; the slaughter entry still works and uses up its food. */
    private static void withoutShears(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        AnimalFarmerGameTests.animal(helper, EntityTypes.SHEEP, SHEEP, false);
        BlockPos chest = AnimalFarmerFoodGameTests.chest(helper, new ItemStack(Items.WHEAT, 20));
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        ResourceCollectionProfile.Context context = AnimalFarmerGameTests.context(helper, settler, AnimalFarmerFoodGameTests.scan(helper), Set.of());
        JobDrops.Tools tools = AnimalFarmerFoodGameTests.collect(helper, chest);
        for (int i = 0; i < 10; i++) {
            ResourceCollectionProfile.Produced produced = farmer().produce(context, tools).orElseThrow();
            helper.assertTrue(produced.stacks().stream().anyMatch(stack -> stack.is(Items.MUTTON)), "Every roll slaughters: " + produced.stacks());
        }
        helper.assertValueEqual(AnimalFarmerFoodGameTests.inChest(helper, Items.WHEAT), 0, "Every roll cost its food");
        helper.assertTrue(farmer().noFood(context, tools), "Without shears and food the farmer is out of food");
        helper.assertTrue(farmer().produce(context, tools).isEmpty(), "and rolls nothing");
        settler.discard();
        helper.succeed();
    }

    /** The shear entry switched off: no shearing, the slaughter entry is as before (food cost, loot, "No food"). */
    private static void switchedOff(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        AnimalFarmerGameTests.animal(helper, EntityTypes.SHEEP, SHEEP, false);
        BlockPos chest = AnimalFarmerFoodGameTests.chest(helper, new ItemStack(Items.SHEARS), new ItemStack(Items.WHEAT, 20));
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 2), "Herder");
        ResourceCollectionProfile.Context context = AnimalFarmerGameTests.context(helper, settler, AnimalFarmerFoodGameTests.scan(helper),
                Set.of(AnimalFarmerProfile.SHEAR_SHEEP));
        JobDrops.Tools tools = AnimalFarmerFoodGameTests.collect(helper, chest);
        for (int i = 0; i < 10; i++) {
            ResourceCollectionProfile.Produced produced = farmer().produce(context, tools).orElseThrow();
            helper.assertTrue(produced.stacks().stream().anyMatch(stack -> stack.is(Items.MUTTON)), "Every roll slaughters: " + produced.stacks());
        }
        helper.assertValueEqual(AnimalFarmerFoodGameTests.inChest(helper, Items.WHEAT), 0, "Every roll cost its food");
        helper.assertTrue(farmer().noFood(context, tools), "Shears do not keep the farmer working while the entry is off");
        helper.assertTrue(farmer().produce(context, tools).isEmpty(), "and nothing is rolled");
        // Excluding the sheep instead leaves the shear entry alone.
        ResourceCollectionProfile.Context slaughterOff = AnimalFarmerGameTests.context(helper, settler, AnimalFarmerFoodGameTests.scan(helper),
                Set.of(SHEEP_TYPE));
        ResourceCollectionProfile.Produced shorn = farmer().produce(slaughterOff, tools).orElseThrow();
        helper.assertTrue(wool(shorn.stacks()) >= 1 && shorn.stacks().stream().noneMatch(stack -> stack.is(Items.MUTTON)), "Only the slaughter is off: " + shorn.stacks());
        helper.assertFalse(farmer().noShears(context, AnimalFarmerFoodGameTests.collect(helper, AnimalFarmerFoodGameTests.chest(helper))), "No shears are asked for while the entry is off");
        settler.discard();
        helper.succeed();
    }

    /** The Found tab lists the sheep and the shear entry; the shear entry is only listed while a sheep is counted. */
    private static void foundEntries(GameTestHelper helper) {
        BlockPos jobBlock = AnimalFarmerGameTests.pen(helper);
        AnimalFarmerGameTests.animal(helper, EntityTypes.COW, new BlockPos(6, 1, 3), false);
        ScanResult cows = AnimalFarmerFoodGameTests.scan(helper);
        helper.assertFalse(farmer().found(cows, Optional.empty()).stream().anyMatch(found -> found.type().equals(AnimalFarmerProfile.SHEAR_SHEEP)),
                "No shear entry without a sheep");
        AnimalFarmerGameTests.animal(helper, EntityTypes.SHEEP, SHEEP, false);
        ScanResult both = AnimalFarmerFoodGameTests.scan(helper);
        List<ResourceCollectionProfile.Found> found = farmer().found(both, Optional.empty());
        helper.assertTrue(found.stream().anyMatch(entry -> entry.type().equals(SHEEP_TYPE)), "The sheep itself is listed: " + found);
        helper.assertTrue(found.stream().anyMatch(entry -> entry.type().equals(AnimalFarmerProfile.SHEAR_SHEEP) && !entry.icon().isEmpty()),
                "The shear entry is listed: " + found);
        helper.assertTrue(farmer().mainResources(both).contains(AnimalFarmerProfile.SHEAR_SHEEP), "The shear entry counts as an output of the Job");
        helper.assertFalse(farmer().droppedItems(helper.getLevel(), jobBlock, AnimalFarmerProfile.SHEAR_SHEEP, ItemStack.EMPTY).isEmpty(),
                "Wool is what the shear entry gives");
        helper.succeed();
    }

    /**
     * An Animal Farmer at work with a sheep, food and the entry on but no shears anywhere asks its Home Clerk for shears,
     * reserved for itself, once. It still works (slaughters) meanwhile.
     */
    private static void ordersShears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AnimalFarmerFoodGameTests.Farm farm = AnimalFarmerFoodGameTests.farm(helper, EntityTypes.SHEEP, false, new ItemStack(Items.WHEAT, 8));
        Holds.hold(farm.settlement(), farm.clerk(), farm.me(), ItemKey.of(Items.WHEAT), 8);
        helper.succeedWhen(() -> {
            helper.assertTrue(farm.requests().stream().anyMatch(request -> request.item().is(Items.SHEARS) && request.amount() == 1
                    && request.target().equals(Optional.of(farm.clerk()))), "The farmer asks for shears: " + farm.settlement().requests());
            helper.assertTrue(farm.requests().stream().filter(request -> request.item().is(Items.SHEARS)).count() == 1, "Only one request: " + farm.requests());
            helper.assertTrue(AnimalFarmerFoodGameTests.gaveLoot(farm, Items.MUTTON), "It slaughters while it waits for the shears");
            farm.close(level);
        });
    }
}
