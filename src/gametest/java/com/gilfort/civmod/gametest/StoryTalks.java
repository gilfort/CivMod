package com.gilfort.civmod.gametest;

import com.gilfort.civmod.job.PlayerTalk;
import com.gilfort.civmod.job.StoryShare;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;

/**
 * Talks to settlers on story days (tests): every settler has something to tell on a day, but a settler tells only one aspect a day, so
 * each talk moves the clock to the same time of the next day first. Closing it puts the clock back and gives the quiet roll back. The
 * class exists only in the GameTest source set.
 */
final class StoryTalks implements AutoCloseable {

    private final ServerLevel level;
    private final long startClock;

    private StoryTalks(ServerLevel level) {
        this.level = level;
        this.startClock = level.getOverworldClockTime();
    }

    /**
     * Forces the roll: every day is a story day until the result is closed.
     *
     * @param helper the test's helper
     * @return the session; close it in a {@code finally}
     */
    static StoryTalks begin(GameTestHelper helper) {
        StoryShare.script(true);
        return new StoryTalks(helper.getLevel());
    }

    /** Moves the clock to the same time of the next day. */
    void nextDay() {
        setClock(level, level.getOverworldClockTime() + DayClock.DAY_TICKS);
    }

    /**
     * Lets the player talk to the settler on the next day.
     *
     * @param helper  the test's helper
     * @param settler the settler
     * @param player  the player
     * @return what the settler said
     */
    PlayerTalk.Result talkNextDay(GameTestHelper helper, Settler settler, ServerPlayer player) {
        nextDay();
        return PlayerTalk.talk(settler, player).orElseThrow(() -> helper.assertionException("The settler did not talk"));
    }

    @Override
    public void close() {
        setClock(level, startClock);
        QuietStories.restore();
    }

    /**
     * @param level the level
     * @param clock the overworld clock value to set
     */
    static void setClock(ServerLevel level, long clock) {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), clock);
    }
}
