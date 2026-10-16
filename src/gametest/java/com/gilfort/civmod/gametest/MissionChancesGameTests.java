package com.gilfort.civmod.gametest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.OutcomeRules;
import com.gilfort.civmod.registry.ModJobs;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the mission chances shown in the board GUI (vault {@code Systems/Mission Jobs.md}): which settler the
 * automatic pick rule would send on a mission, the death warning threshold and the outcome constants sent with the
 * missions tab.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionChancesGameTests {

    private static final UUID ALICE = new UUID(0L, 1L);
    private static final UUID BOB = new UUID(0L, 2L);
    private static final UUID CARL = new UUID(0L, 3L);

    private MissionChancesGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mission_chances_picker", MissionChancesGameTests::picker);
            register(helper, "mission_chances_tab", MissionChancesGameTests::tab);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static MissionOffer.Entry marked(String mission) {
        return new MissionOffer.Entry(CivMod.id(mission), true);
    }

    /**
     * The predicted picker follows the pick rule with every settler asking together: lowest skill first, assigned
     * missions to their assignee, never above the skill, never a locked mission without assignee; an unmarked mission
     * is predicted as if it were marked.
     */
    private static void picker(GameTestHelper helper) {
        Map<Identifier, Mission> pool = new LinkedHashMap<>();
        pool.put(CivMod.id("lv5"), MissionRunGameTests.mission(5, 1));
        pool.put(CivMod.id("lv10"), MissionRunGameTests.mission(10, 1));
        pool.put(CivMod.id("lv30"), MissionRunGameTests.mission(30, 1));
        List<MissionRules.Picker> settlers = List.of(
                new MissionRules.Picker(ALICE, ModJobs.ADVENTURER.get(), 12),
                new MissionRules.Picker(BOB, ModJobs.ADVENTURER.get(), 6),
                new MissionRules.Picker(CARL, ModJobs.MINER.get(), 40));
        List<MissionOffer.Entry> offer = List.of(marked("lv10"), marked("lv5"), new MissionOffer.Entry(CivMod.id("lv30"), false),
                new MissionOffer.Entry(CivMod.id("lv10"), true, Optional.of(ALICE), true),
                new MissionOffer.Entry(CivMod.id("lv10"), true, Optional.empty(), true), marked("lv5"));

        helper.assertTrue(MissionRules.picker(offer, pool, 1, settlers).equals(OptionalInt.of(1)), "Bob (lowest skill) picks the first Lv 5");
        helper.assertTrue(MissionRules.picker(offer, pool, 0, settlers).equals(OptionalInt.of(0)),
                "Alice takes the Lv 10 after her assigned one");
        helper.assertTrue(MissionRules.picker(offer, pool, 3, settlers).equals(OptionalInt.of(0)), "The assigned mission goes to Alice");
        helper.assertTrue(MissionRules.picker(offer, pool, 5, settlers).equals(OptionalInt.of(1)), "Bob takes the second Lv 5 next");
        helper.assertTrue(MissionRules.picker(offer, pool, 2, settlers).isEmpty(),
                "Nobody takes a mission above their skill (Carl's Mission Job may not take it)");
        helper.assertTrue(MissionRules.picker(offer, pool, 4, settlers).isEmpty(), "Nobody takes a lock without assignee");
        helper.assertTrue(MissionRules.picker(List.of(marked("lv5")), pool, 0, List.of(
                new MissionRules.Picker(ALICE, ModJobs.ADVENTURER.get(), 7), new MissionRules.Picker(BOB, ModJobs.ADVENTURER.get(), 7)))
                .equals(OptionalInt.of(0)), "Equal skills: the first settler of the board");
        helper.assertTrue(MissionRules.picker(List.of(marked("lv30")), pool, 0, List.of(new MissionRules.Picker(ALICE, ModJobs.ADVENTURER.get(), 30)))
                .equals(OptionalInt.of(0)), "At its level");
        helper.assertTrue(MissionRules.picker(List.of(marked("gone")), pool, 0, settlers).isEmpty(), "Unknown mission");
        helper.succeed();
    }

    /** Death chances from 20 % on are high; the missions tab carries the outcome constants to the client. */
    private static void tab(GameTestHelper helper) {
        helper.assertTrue(new MissionRules.Chances(0.5, 0.20).highDeath(), "20 % death is high");
        helper.assertFalse(new MissionRules.Chances(0.5, 0.19).highDeath(), "19 % death is not");
        helper.assertTrue(MissionRules.chances(OutcomeRules.FALLBACK, 30, 12).highDeath(), "18 levels under the recommendation");
        helper.assertFalse(MissionRules.chances(OutcomeRules.FALLBACK, 10, 10).highDeath(), "At the recommendation");

        OutcomeRules rules = new OutcomeRules(0.5, 0.01, 0.1, 0.9, 0.05, 0.02, 0.02, 0.5, 3.0, 1.0);
        JobBlockInfo.MissionTab tab = new JobBlockInfo.MissionTab(List.of(marked("lv5")), List.of(),
                List.of(new JobBlockInfo.MissionSettler(ALICE, "Alice", ModJobs.ADVENTURER.get(), 7)), rules);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        JobBlockInfo.MissionTab.STREAM_CODEC.encode(buffer, tab);
        JobBlockInfo.MissionTab read = JobBlockInfo.MissionTab.STREAM_CODEC.decode(buffer);
        helper.assertTrue(read.equals(tab), "Round trip: " + read);
        helper.assertTrue(JobBlockInfo.MissionTab.EMPTY.rules().equals(OutcomeRules.FALLBACK), "No board: fallback constants");
        helper.succeed();
    }
}
