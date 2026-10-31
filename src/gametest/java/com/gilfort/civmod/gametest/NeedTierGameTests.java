package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.NeedState;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the need tiers (vault {@code Systems/Needs.md}): the tier from the capped levels, the grace day, the
 * downgrade to the top of the lower tier and the instant climb back.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class NeedTierGameTests {

    private NeedTierGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "need_tier_bounds", NeedTierGameTests::bounds);
            register(helper, "need_state_downgrade", NeedTierGameTests::downgrade);
            register(helper, "need_state_climb", NeedTierGameTests::climb);
            register(helper, "need_state_saved", NeedTierGameTests::saved);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Lv 1–20, 21–40, 41–60, 61–80, 81+. */
    private static void bounds(GameTestHelper helper) {
        int[][] tierOfLevel = {{1, 1}, {20, 1}, {21, 2}, {40, 2}, {41, 3}, {60, 3}, {61, 4}, {80, 4}, {81, 5}, {100, 5}};
        for (int[] pair : tierOfLevel) {
            helper.assertTrue(NeedState.tierOf(pair[0]) == pair[1], "Level " + pair[0] + " is tier " + pair[1] + ": " + NeedState.tierOf(pair[0]));
        }
        int[][] topOfTier = {{1, 20}, {2, 40}, {3, 60}, {4, 80}, {5, 100}};
        for (int[] pair : topOfTier) {
            helper.assertTrue(NeedState.topLevel(pair[0]) == pair[1], "Top of tier " + pair[0] + ": " + NeedState.topLevel(pair[0]));
        }
        helper.succeed();
    }

    /** Tier 3 and everything below unmet: 45 → one grace day → 40 → 20, then it stays at the bottom and keeps complaining. */
    private static void downgrade(GameTestHelper helper) {
        int tier = 3;
        int noneMet = 0;
        NeedState state = NeedState.START.morning(10L, tier, noneMet);
        helper.assertTrue(state.limit(45) == 45 && state.inGrace(), "Day 1 is the grace day at the full level: " + state);

        state = state.morning(11L, tier, noneMet);
        helper.assertTrue(state.limit(45) == 40 && state.inGrace(), "Next morning at the top of tier 2, its grace day: " + state);

        state = state.morning(12L, tier, noneMet);
        helper.assertTrue(state.limit(45) == 20 && state.inGrace(), "Tier 2 is unmet too: 20: " + state);

        state = state.morning(13L, tier, noneMet);
        helper.assertTrue(state.limit(45) == 20 && state.inGrace(), "Nothing below tier 1: " + state);

        NeedState lowerMet = NeedState.START.morning(10L, tier, 2).morning(11L, tier, 2).morning(12L, tier, 2);
        helper.assertTrue(lowerMet.limit(45) == 40 && !lowerMet.inGrace(), "Stays at the tier that is met: " + lowerMet);

        NeedState sameDay = NeedState.START.morning(10L, tier, noneMet).morning(10L, tier, noneMet);
        helper.assertTrue(sameDay.limit(45) == 45, "A second check on the same day is not the next morning: " + sameDay);

        NeedState met = NeedState.START.morning(10L, tier, 5);
        helper.assertTrue(met.equals(new NeedState(NeedState.MAX_TIER, NeedState.NO_DAY, 10L)), "Met: nothing happens: " + met);
        helper.succeed();
    }

    /** Met again: back at once; a grace day ends without a downgrade; a new, higher tier gets its own grace day. */
    private static void climb(GameTestHelper helper) {
        int tier = 3;
        NeedState down = NeedState.START.morning(10L, tier, 0).morning(11L, tier, 0).morning(12L, tier, 0);
        NeedState back = down.climb(tier, 5);
        helper.assertTrue(back.limit(45) == 45 && !back.inGrace(), "Met again: instantly at the full level: " + back);

        NeedState partly = down.climb(tier, 2);
        helper.assertTrue(partly.limit(45) == 40 && !partly.inGrace(), "Tier 2 met again, tier 3 not: 40: " + partly);

        NeedState grace = NeedState.START.morning(10L, tier, 0);
        NeedState endedGrace = grace.climb(tier, 3);
        helper.assertTrue(!endedGrace.inGrace() && endedGrace.morning(11L, tier, 3).limit(45) == 45, "The grace day ended: no downgrade: " + endedGrace);

        NeedState unmetButNoNews = grace.climb(tier, 0);
        helper.assertTrue(unmetButNoNews.equals(grace), "Still unmet: the re-check changes nothing: " + unmetButNoNews);

        helper.assertTrue(NeedState.START.morning(10L, 1, 5).settled(1), "Met, nothing to climb to");
        helper.assertFalse(grace.settled(tier), "A grace day may end: re-check");
        helper.assertFalse(down.settled(tier), "Works below its tier: re-check");

        NeedState levelledUp = NeedState.START.morning(10L, 1, 1).morning(11L, 2, 1);
        helper.assertTrue(levelledUp.limit(21) == 21 && levelledUp.inGrace(), "A settler reaching tier 2 gets its grace day first: " + levelledUp);
        helper.succeed();
    }

    /** The current need tier, the grace mark and the day of the last check survive saving. */
    private static void saved(GameTestHelper helper) {
        NeedState state = new NeedState(2, 11L, 12L);
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        NeedState loaded = NeedState.CODEC.parse(ops, NeedState.CODEC.encodeStart(ops, state).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.equals(state), "Saved: " + loaded);
        NeedState fresh = NeedState.CODEC.parse(ops, NeedState.CODEC.encodeStart(ops, NeedState.START).getOrThrow()).getOrThrow();
        helper.assertTrue(fresh.equals(NeedState.START), "No day stays no day: " + fresh);
        helper.succeed();
    }
}
