package com.gilfort.civmod.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.RaceNames;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSpawner;
import com.gilfort.civmod.settlement.JobType;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementColors;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Story 3 (settlers: spawn, names, homeless, colours).
 * <p>
 * Uses fresh {@link SettlementData} instances so parallel tests in the shared test level cannot interfere.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlerGameTests {

    private SettlerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "settler_scheduling_and_fill_up", SettlerGameTests::schedulingAndFillUp);
            register(helper, "settler_names_without_duplicates", SettlerGameTests::namesWithoutDuplicates);
            register(helper, "settler_palette_excludes_red", SettlerGameTests::paletteExcludesRed);
            register(helper, "settler_homeless_joining", SettlerGameTests::homelessJoining);
            register(helper, "settler_settlement_persistence", SettlerGameTests::settlementPersistence);
            register(helper, "settler_arrival_in_world", SettlerGameTests::arrivalInWorld);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Settlement found(SettlementData data, BlockPos pos, long gameTime) {
        return SettlementManager.place(data, pos, JobType.LUMBERJACK, 1, gameTime, RandomSource.create(1L), SettlementManager.Listener.NONE)
                .settlement();
    }

    /** Founding schedules settlers 600 ticks later; only missing settlers up to the target are spawned. */
    private static void schedulingAndFillUp(GameTestHelper helper) {
        Settlement settlement = found(new SettlementData(), BlockPos.ZERO, 1000L);
        helper.assertTrue(settlement.settlersDue() == 1000L + SettlementManager.SETTLER_DELAY_TICKS, "Settlers not scheduled 600 ticks after founding");
        helper.assertTrue(SettlementColors.PALETTE.contains(settlement.color()), "Settlement colour not from palette");
        helper.assertTrue(SettlerSpawner.missingSettlers(0, 4) == 4, "Empty settlement should get 4 settlers");
        helper.assertTrue(SettlerSpawner.missingSettlers(3, 4) == 1, "Three homeless joined: only one missing");
        helper.assertTrue(SettlerSpawner.missingSettlers(6, 4) == 0, "No settlers beyond the target");
        helper.succeed();
    }

    /** Names avoid used ones while alternatives exist; the human name list is loaded. */
    private static void namesWithoutDuplicates(GameTestHelper helper) {
        RandomSource random = RandomSource.create(42L);
        List<String> candidates = List.of("A", "B", "C");
        Set<String> used = new HashSet<>(List.of("A", "B"));
        for (int i = 0; i < 20; i++) {
            helper.assertTrue(RaceNames.pick(candidates, used, random).orElseThrow().equals("C"), "Picked a used name although C was free");
        }
        helper.assertTrue(RaceNames.pick(candidates, Set.of("A", "B", "C"), random).isPresent(), "All used: should still return a name");
        helper.assertTrue(RaceNames.pick(List.of(), used, random).isEmpty(), "No candidates should give no name");
        RaceNames.NameSet human = RaceNames.get(Races.HUMAN);
        helper.assertTrue(human.npc().size() >= 50 && human.settlement().size() >= 30, "Human name list not loaded: " + human);
        helper.succeed();
    }

    /** The settlement palette contains no red (reserved for errors and the unconnected marker). */
    private static void paletteExcludesRed(GameTestHelper helper) {
        for (int color : SettlementColors.PALETTE) {
            int r = color >> 16 & 0xFF;
            int g = color >> 8 & 0xFF;
            int b = color & 0xFF;
            helper.assertFalse(r > 180 && g < 100 && b < 100, "Palette contains a red tone: " + Integer.toHexString(color));
        }
        helper.succeed();
    }

    /** Homeless settlers find the nearest settlement with a connected JobBlock within 32 blocks. */
    private static void homelessJoining(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement near = found(data, new BlockPos(0, 64, 0), 0L);
        found(data, new BlockPos(200, 64, 0), 0L);
        helper.assertTrue(SettlementManager.settlementNear(data, new BlockPos(30, 70, 0), 32.0).orElse(null) == near,
                "Settler at 30 should join the settlement at 0");
        helper.assertTrue(SettlementManager.settlementNear(data, new BlockPos(100, 64, 0), 32.0).isEmpty(),
                "Settler at 100 is too far from both settlements");
        helper.succeed();
    }

    /** Name, colour, settler timer and members survive a save / load round trip. */
    private static void settlementPersistence(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = found(data, BlockPos.ZERO, 5L);
        data.setName(settlement, "Stonebrook");
        data.addMember(settlement, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData loaded = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        Settlement copy = loaded.settlements().iterator().next();
        helper.assertTrue(copy.name().orElse("").equals("Stonebrook"), "Name lost");
        helper.assertTrue(copy.color() == settlement.color(), "Colour lost");
        helper.assertTrue(copy.settlersDue() == settlement.settlersDue(), "Settler timer lost");
        helper.assertTrue(copy.members().size() == 1, "Members lost");
        helper.succeed();
    }

    /** Settlers spawn near the anchor with unique names, the settlement gets a name, dissolving makes them homeless. */
    private static void arrivalInWorld(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = found(data, anchor, helper.getLevel().getGameTime());
        List<Settler> settlers = SettlerSpawner.arrive(helper.getLevel(), data, settlement, anchor);
        try {
            helper.assertTrue(settlers.size() == 4, "Expected 4 starting settlers, got " + settlers.size());
            Set<String> names = new HashSet<>();
            for (Settler settler : settlers) {
                helper.assertTrue(settler.hasCustomName() && names.add(settler.getName().getString()), "Settler without or with duplicate name");
                helper.assertTrue(settler.getSettlementId() == settlement.id() && settler.getRace().equals(Races.HUMAN), "Wrong membership or race");
                helper.assertTrue(settler.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(anchor)) <= 8.0 * 8.0, "Settler spawned too far away");
            }
            helper.assertTrue(settlement.name().isPresent(), "Settlement was not named");
            helper.assertTrue(settlement.members().size() == 4 && settlement.settlersDue() == Settlement.SETTLERS_ARRIVED, "Membership or timer wrong");
            helper.assertTrue(SettlerSpawner.arrive(helper.getLevel(), data, settlement, anchor).isEmpty(), "Second arrival must not spawn more settlers");

            SettlementManager.remove(data, anchor, SettlementWorld.listener(helper.getLevel()));
            helper.assertTrue(settlers.stream().allMatch(Settler::isHomeless), "Settlers of a dissolved settlement should be homeless");
            helper.succeed();
        } finally {
            settlers.forEach(Settler::discard);
        }
    }
}
