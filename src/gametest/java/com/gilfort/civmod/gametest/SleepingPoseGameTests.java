package com.gilfort.civmod.gametest;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.SleepingPose;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Tests of the {@link SleepingPose} choice (ticket #63): settler and clock in, pose out.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SleepingPoseGameTests {

    private static final long DAY = 24000L;

    private SleepingPoseGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "sleeping_pose_deterministic", SleepingPoseGameTests::deterministic);
            register(helper, "sleeping_pose_distribution", SleepingPoseGameTests::distribution);
            register(helper, "sleeping_pose_night", SleepingPoseGameTests::night);
        });
    }

    /** The same settler and night always give the same pose (every client computes it on its own). */
    private static void deterministic(GameTestHelper helper) {
        Random random = new Random(1L);
        for (int i = 0; i < 200; i++) {
            UUID settler = new UUID(random.nextLong(), random.nextLong());
            long clock = random.nextInt(1_000_000);
            helper.assertTrue(SleepingPose.of(settler, clock) == SleepingPose.of(new UUID(settler.getMostSignificantBits(),
                    settler.getLeastSignificantBits()), clock), "Same settler and time give the same pose");
        }
        helper.succeed();
    }

    /** About 40 % left side, 40 % right side, 20 % back: across settlers and across nights of one settler. */
    private static void distribution(GameTestHelper helper) {
        Random random = new Random(2L);
        Map<SleepingPose, Integer> settlers = new EnumMap<>(SleepingPose.class);
        for (int i = 0; i < 10000; i++) {
            settlers.merge(SleepingPose.of(new UUID(random.nextLong(), random.nextLong()), 7 * DAY + 14000L), 1, Integer::sum);
        }
        assertShares(helper, settlers, 10000, "settlers in one night");

        UUID settler = new UUID(random.nextLong(), random.nextLong());
        Map<SleepingPose, Integer> nights = new EnumMap<>(SleepingPose.class);
        for (int night = 0; night < 10000; night++) {
            nights.merge(SleepingPose.of(settler, night * DAY + 14000L), 1, Integer::sum);
        }
        assertShares(helper, nights, 10000, "nights of one settler");
        helper.succeed();
    }

    /** One pose for the whole night: from going to bed (20:00) to waking late (06:30); the next night may differ. */
    private static void night(GameTestHelper helper) {
        Random random = new Random(3L);
        int changed = 0;
        for (int i = 0; i < 200; i++) {
            UUID settler = new UUID(random.nextLong(), random.nextLong());
            long evening = 5 * DAY + 14000L; // 20:00 of day 5
            SleepingPose pose = SleepingPose.of(settler, evening);
            helper.assertTrue(SleepingPose.of(settler, 5 * DAY + 17999L) == pose, "Same pose before midnight");
            helper.assertTrue(SleepingPose.of(settler, 6 * DAY - 1L) == pose, "Same pose at 05:59");
            helper.assertTrue(SleepingPose.of(settler, 6 * DAY + 500L) == pose, "Same pose at 06:30");
            if (SleepingPose.of(settler, 6 * DAY + 14000L) != pose) {
                changed++;
            }
        }
        helper.assertTrue(changed > 80, "The pose changes from night to night for many settlers, changed: " + changed);
        helper.succeed();
    }

    private static void assertShares(GameTestHelper helper, Map<SleepingPose, Integer> counts, int total, String what) {
        assertShare(helper, counts.getOrDefault(SleepingPose.LEFT_SIDE, 0), total, 0.40, "left side, " + what);
        assertShare(helper, counts.getOrDefault(SleepingPose.RIGHT_SIDE, 0), total, 0.40, "right side, " + what);
        assertShare(helper, counts.getOrDefault(SleepingPose.BACK, 0), total, 0.20, "back, " + what);
    }

    private static void assertShare(GameTestHelper helper, int count, int total, double expected, String what) {
        double share = count / (double) total;
        helper.assertTrue(Math.abs(share - expected) <= 0.03, "Share " + what + ": " + share + ", expected about " + expected);
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }
}
