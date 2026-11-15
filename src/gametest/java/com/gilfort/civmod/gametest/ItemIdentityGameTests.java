package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.CraftPlan;
import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for item components in patterns, stock rules, requests and Delivery Slips (#267): a Potion of Healing is not
 * a water bottle, a custom name does not matter.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ItemIdentityGameTests {

    private static ItemKey healing() {
        return ItemKey.of(ItemKeyGameTests.potion(Potions.HEALING));
    }

    private static ItemKey water() {
        return ItemKey.of(ItemKeyGameTests.potion(Potions.WATER));
    }

    private ItemIdentityGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "item_identity_patterns_apart", ItemIdentityGameTests::patternsApart);
            TestFunctions.register(helper, "item_identity_exact_input", ItemIdentityGameTests::exactInput);
            TestFunctions.register(helper, "item_identity_stock_rule", ItemIdentityGameTests::stockRule);
            TestFunctions.register(helper, "item_identity_slip_and_saves", ItemIdentityGameTests::slipAndSaves);
        });
    }

    private static ItemStack renamedHealing() {
        ItemStack stack = ItemKeyGameTests.potion(Potions.HEALING);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Elixir"));
        return stack;
    }

    /** Two patterns for two potions differ, and the pattern for one does not serve a request for the other. */
    private static void patternsApart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pattern healing = new Pattern(0, healing(), 1, List.of(), Optional.empty(), false, true);
        Pattern water = new Pattern(1, water(), 1, List.of(), Optional.empty(), false, true);
        helper.assertTrue(!healing.output().equals(water.output()), "Two outputs");
        CraftPlan forHealing = CraftPlan.plan(level, CraftspersonGameTests.profile(), List.of(healing), healing(), 1, Map.of(), Map.of());
        helper.assertTrue(forHealing.missing().isEmpty() && forHealing.steps().size() == 1 && forHealing.steps().getFirst().pattern().equals(healing),
                "The healing pattern makes a Potion of Healing: " + forHealing);
        CraftPlan forWater = CraftPlan.plan(level, CraftspersonGameTests.profile(), List.of(healing), water(), 1, Map.of(), Map.of());
        helper.assertTrue(forWater.steps().isEmpty() && forWater.missing().equals(List.of(new Request.Missing(water(), 1))),
                "The healing pattern does not make a water bottle: " + forWater);
        CraftPlan renamed = CraftPlan.plan(level, CraftspersonGameTests.profile(), List.of(healing), ItemKey.of(renamedHealing()), 1, Map.of(),
                Map.of());
        helper.assertTrue(renamed.missing().isEmpty(), "A custom name is no other potion: " + renamed);
        helper.succeed();
    }

    /** A pattern with a Potion of Healing in its grid takes that potion from the stock, not a water bottle, and does not mind a custom name. */
    private static void exactInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pattern needsHealing = new Pattern(0, ItemKey.of(Items.GLASS_BOTTLE), 1, List.of(healing()), Optional.empty(), true, true);
        List<Pattern> checked = List.of(needsHealing);
        CraftPlan onlyWater = CraftPlan.plan(level, CraftspersonGameTests.profile(), checked, ItemKey.of(Items.GLASS_BOTTLE), 1, Map.of(),
                Map.of(water(), 5));
        helper.assertTrue(onlyWater.missing().equals(List.of(new Request.Missing(healing(), 1))),
                "A water bottle does not do for a Potion of Healing, and the missing input names the potion: " + onlyWater);
        CraftPlan withHealing = CraftPlan.plan(level, CraftspersonGameTests.profile(), checked, ItemKey.of(Items.GLASS_BOTTLE), 1, Map.of(),
                Map.of(water(), 5, healing(), 1));
        helper.assertTrue(withHealing.missing().isEmpty() && withHealing.takes().equals(Map.of(healing(), 1)), "The potion is taken: " + withHealing);
        CraftPlan held = CraftPlan.plan(level, CraftspersonGameTests.profile(), checked, ItemKey.of(Items.GLASS_BOTTLE), 1,
                Map.of(ItemKey.of(renamedHealing()), 1), Map.of());
        helper.assertTrue(held.missing().isEmpty(), "A renamed Potion of Healing in the buffer matches: " + held);
        helper.succeed();
    }

    /** A rule for one potion is not met by water bottles, but by the potion even with a custom name. */
    private static void stockRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        ItemStack water = ItemKeyGameTests.potion(Potions.WATER);
        chest.setItem(0, water);
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, healing(), 1, 0)), "Rule for Healing set");
        helper.assertTrue(!StockRules.setRule(data, clerk, StockRule.minimum(1, healing(), 2, 0)), "One rule per potion type");
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(1, water(), 1, 0)), "A rule for the other type is a different rule");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        List<Request> open = Requests.byPriority(settlement);
        helper.assertTrue(open.size() == 1 && open.getFirst().item().equals(healing()) && open.getFirst().amount() == 1,
                "Water in the chest does not meet the rule for Healing: " + open);
        ClerkStorage.stock(level, clerk, true).insert(renamedHealing());
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).stream().noneMatch(request -> request.amount() > 0),
                "A renamed Potion of Healing meets it: " + Requests.byPriority(settlement));
        TestAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /** A Delivery Slip lets only the listed potion through; patterns, rules and requests keep their potion when saved. */
    private static void slipAndSaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DeliverySlip slip = DeliverySlip.blank().withFilter(List.of(healing()));
        helper.assertTrue(slip.accepts(ItemKeyGameTests.potion(Potions.HEALING)) && slip.accepts(renamedHealing()), "The whitelist lets Healing through");
        helper.assertTrue(!slip.accepts(ItemKeyGameTests.potion(Potions.WATER)), "But no water bottle");
        helper.assertTrue(!slip.withBlacklistToggled().accepts(ItemKeyGameTests.potion(Potions.HEALING))
                && slip.withBlacklistToggled().accepts(ItemKeyGameTests.potion(Potions.WATER)), "A blacklist is the other way round");

        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Pattern pattern = new Pattern(3, healing(), 1, List.of(water(), ItemKey.of(Items.NETHER_WART)), Optional.empty(), true, true);
        helper.assertTrue(roundTrip(Pattern.CODEC, ops, pattern).equals(pattern), "A pattern keeps its potions in a save");
        StockRule rule = StockRule.minimum(0, healing(), 4, 0);
        helper.assertTrue(roundTrip(StockRule.CODEC, ops, rule).equals(rule), "A stock rule keeps its potion in a save");
        Request request = new Request(UUID.randomUUID(), healing(), 2, 0, Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new Request.Missing(water(), 1)), Optional.empty());
        helper.assertTrue(roundTrip(Request.CODEC, ops, request).equals(request), "A request keeps its potion and its missing input in a save");
        helper.succeed();
    }

    private static <T> T roundTrip(Codec<T> codec, net.minecraft.resources.RegistryOps<Tag> ops, T value) {
        Tag saved = codec.encodeStart(ops, value).getOrThrow();
        return codec.parse(ops, saved).getOrThrow();
    }
}
