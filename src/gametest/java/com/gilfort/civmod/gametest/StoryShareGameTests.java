package com.gilfort.civmod.gametest;

import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.chronicle.Chronicle;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.StoryShare;
import com.gilfort.civmod.origin.OriginLine;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the daily story share (vault {@code Systems/Settler Origin.md}, {@code Systems/Thought Bubbles.md}): the roll and its
 * config, the story bubble a settler with something to tell shows and the talk that tells one story aspect. The roll is quiet in the
 * GameTests ({@link QuietStories}); a test forces it with {@code StoryShare.script} and gives the quiet roll back at the end.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class StoryShareGameTests {

    private static final int ROLLED_DAYS = 3000;

    private StoryShareGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "story_share_roll_per_day", StoryShareGameTests::rollPerDay);
            register(helper, "story_share_config", StoryShareGameTests::config);
            register(helper, "story_share_spent_day_saved", StoryShareGameTests::spentDaySaved);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static int hits(UUID settler, int days) {
        int hits = 0;
        for (int day = 0; day < days; day++) {
            if (StoryShare.hit(settler, day)) {
                hits++;
            }
        }
        return hits;
    }

    /** The roll is the same for a settler and a day however often it is asked (a reload cannot reroll), and a settler hits about 1 day in 3. */
    private static void rollPerDay(GameTestHelper helper) {
        StoryShare.script(null);
        try {
            UUID settler = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
            UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
            for (int day = 0; day < 50; day++) {
                boolean asked = StoryShare.hit(settler, day);
                boolean askedAgain = StoryShare.hit(settler, day);
                helper.assertTrue(asked == askedAgain, "Day " + day + " rolls the same every time");
            }
            int hits = hits(settler, ROLLED_DAYS);
            helper.assertTrue(hits > ROLLED_DAYS * 0.30 && hits < ROLLED_DAYS * 0.37, "About 1 day in 3 hits: " + hits + " of " + ROLLED_DAYS);
            boolean differ = false;
            for (int day = 0; day < 50 && !differ; day++) {
                differ = StoryShare.hit(settler, day) != StoryShare.hit(other, day);
            }
            helper.assertTrue(differ, "Two settlers do not share their days");
            helper.succeed();
        } finally {
            QuietStories.restore();
        }
    }

    /** The server config sets N: 1 hits every day, 5 about 1 day in 5. */
    private static void config(GameTestHelper helper) {
        StoryShare.script(null);
        int oneIn = CivModConfig.SETTLER_STORY_SHARE_ONE_IN.getAsInt();
        try {
            helper.assertTrue(oneIn == 3, "The default is 1 day in 3: " + oneIn);
            UUID settler = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
            CivModConfig.SETTLER_STORY_SHARE_ONE_IN.set(1);
            helper.assertTrue(hits(settler, 200) == 200, "1 means every day: " + hits(settler, 200));
            CivModConfig.SETTLER_STORY_SHARE_ONE_IN.set(5);
            int hits = hits(settler, ROLLED_DAYS);
            helper.assertTrue(hits > ROLLED_DAYS * 0.17 && hits < ROLLED_DAYS * 0.23, "About 1 day in 5 hits: " + hits + " of " + ROLLED_DAYS);
            helper.succeed();
        } finally {
            CivModConfig.SETTLER_STORY_SHARE_ONE_IN.set(oneIn);
            QuietStories.restore();
        }
    }

    /** The day a settler used up its chance is saved with its chronicle; a chronicle from an older save has none. */
    private static void spentDaySaved(GameTestHelper helper) {
        Chronicle spent = Chronicle.EMPTY.withTold(OriginLine.Slot.PLACE).withStorySpent(42L);
        var encoded = Chronicle.CODEC.encodeStart(JsonOps.INSTANCE, spent).getOrThrow();
        Chronicle decoded = Chronicle.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        helper.assertTrue(decoded.equals(spent) && decoded.storySpentDay() == 42L, "Saved and loaded: " + decoded);
        helper.assertTrue(Chronicle.EMPTY.storySpentDay() == Chronicle.NO_DAY, "A new chronicle has no spent day");
        var old = Chronicle.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonObject()).getOrThrow();
        helper.assertTrue(old.equals(Chronicle.EMPTY), "A chronicle without the field loads as empty: " + old);
        helper.succeed();
    }
}
