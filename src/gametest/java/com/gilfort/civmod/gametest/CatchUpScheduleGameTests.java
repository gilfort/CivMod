package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for catch-up on the Schedule: live play and catch-up share one set of per-day flags (morning handled,
 * evening meal eaten, foraging day) that is saved with the settler, and saves with pending catch-up from before still
 * load.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class CatchUpScheduleGameTests {

    private static final long DAY = 24000L;

    private CatchUpScheduleGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "catch_up_day_flags_shared", CatchUpScheduleGameTests::dayFlagsShared);
            register(helper, "catch_up_old_save_loads", CatchUpScheduleGameTests::oldSaveLoads);
            register(helper, "food_day_round_trip", CatchUpScheduleGameTests::dayRoundTrip);
        });
    }

    /**
     * Every day survives saving, also day -1 (the "no day" marker of old saves); "no day" stays "no day", and an old
     * save's -1 still reads as "no day".
     */
    private static void dayRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        SettlerFood food = settler.getFood();
        food.setMorningDay(-1L);
        food.setEatenDay(-1L);
        food.startForaging(-1L);
        food.startWaiting(-1L, 700L);
        CompoundTag tag = save(level, settler);
        Settler loaded = reload(level, tag);
        helper.assertTrue(loaded.getFood().waitDay() == -1L && loaded.getFood().waitTime() == 700L,
                "Start of waiting for food lost on save: " + loaded.getFood().waitDay() + " / " + loaded.getFood().waitTime());
        helper.assertTrue(loaded.getFood().morningDay() == -1L && loaded.getFood().eatenDay() == -1L,
                "Day -1 lost on save: " + loaded.getFood().morningDay() + " / " + loaded.getFood().eatenDay());
        helper.assertTrue(loaded.getFood().isForaging() && loaded.getFood().forageDay() == -1L,
                "Foraging on day -1 lost on save: " + loaded.getFood().forageDay());

        Settler fresh = reload(level, save(level, ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT)));
        helper.assertTrue(fresh.getFood().morningDay() == SettlerFood.NO_DAY && !fresh.getFood().isForaging(),
                "No day stays no day: " + fresh.getFood().morningDay() + " / " + fresh.getFood().forageDay());

        // A save from before the day format: -1 meant "no day".
        CompoundTag old = tag.copy();
        CompoundTag oldFood = old.getCompoundOrEmpty("food");
        oldFood.remove("day_format");
        old.put("food", oldFood);
        Settler legacy = reload(level, old);
        helper.assertTrue(legacy.getFood().morningDay() == SettlerFood.NO_DAY && !legacy.getFood().isForaging(),
                "Old save's -1 is no day: " + legacy.getFood().morningDay() + " / " + legacy.getFood().forageDay());
        settler.discard();
        loaded.discard();
        fresh.discard();
        legacy.discard();
        helper.succeed();
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * A caught-up day marks the same per-day flags live play reads (morning handled, evening meal eaten), and they
     * survive saving and loading the settler.
     */
    private static void dayFlagsShared(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), DAY) * DAY;
        // Two days back: the test clock lies on day 0, and old saves used day -1 as "no day".
        long missed = Math.floorDiv(dayStart - 2 * DAY, DAY);
        settler.getWork().setCatchUp(missed * DAY, (missed + 1) * DAY);
        CatchUp.runNow(level, List.of(settler));
        SettlerFood food = settler.getFood();
        helper.assertTrue(food.morningDay() == missed, "Morning of the caught-up day not marked: " + food.morningDay());
        helper.assertTrue(food.eatenDay() == missed, "Evening meal of the caught-up day not marked: " + food.eatenDay());

        Settler loaded = reload(level, save(level, settler));
        helper.assertTrue(loaded.getFood().morningDay() == missed && loaded.getFood().eatenDay() == missed,
                "Per-day flags lost on save: " + loaded.getFood().morningDay() + " / " + loaded.getFood().eatenDay());
        loaded.discard();
        settler.discard();
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
    }

    /**
     * A settler saved before catch-up used the Schedule (per-day flags of an older day, pending catch-up of a missed day)
     * loads, catches up the missed day with the saved scan and marks that day.
     */
    private static void oldSaveLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            // The live scan is saved with the settler; the catch-up needs it.
            if (done[0] || LumberjackGameTests.foundNothing(settler, settler.getJobs().get(0))) {
                return;
            }
            done[0] = true;
            long clock = level.getOverworldClockTime();
            long dayStart = Math.floorDiv(clock, DAY) * DAY;
            // Two days back: the test clock lies on day 0, and old saves used day -1 as "no day".
            long missed = Math.floorDiv(dayStart - 2 * DAY, DAY);
            CompoundTag tag = save(level, settler);
            CompoundTag food = tag.getCompoundOrEmpty("food");
            food.putLong("morning_day", missed - 1);
            food.putLong("eaten_day", missed - 1);
            food.putLong("forage_day", -1L);
            // Saved before the day format: -1 is "no day".
            food.remove("day_format");
            tag.put("food", food);
            CompoundTag buffers = tag.getCompoundOrEmpty("buffers");
            CompoundTag catchUp = new CompoundTag();
            catchUp.putLong("active_clock", missed * DAY);
            catchUp.putLong("from", missed * DAY);
            catchUp.putLong("to", (missed + 1) * DAY);
            buffers.put("catch_up", catchUp);
            tag.put("buffers", buffers);

            settler.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
            helper.assertTrue(settler.getFood().morningDay() == missed - 1, "Saved morning flag lost: " + settler.getFood().morningDay());
            helper.assertTrue(settler.getWork().snapshot().catchUp().from() == missed * DAY, "Pending catch-up lost: "
                    + settler.getWork().snapshot().catchUp());
            Container container = (Container) level.getBlockEntity(jobBlock);
            int before = count(container) + settler.getWork().buffer(jobBlock).count();
            CatchUp.runNow(level, List.of(settler));
            int produced = count(container) + settler.getWork().buffer(jobBlock).count() - before;
            helper.assertTrue(produced >= 40, "About a day of wood expected: " + produced);
            helper.assertTrue(settler.getFood().morningDay() == missed && settler.getFood().eatenDay() == missed,
                    "Caught-up day not marked: " + settler.getFood().morningDay() + " / " + settler.getFood().eatenDay());
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    private static CompoundTag save(ServerLevel level, Settler settler) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        return output.buildResult();
    }

    private static Settler reload(ServerLevel level, CompoundTag tag) {
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.LOAD);
        settler.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        return settler;
    }

    private static int count(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }
}
