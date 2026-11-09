package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ServiceAct;
import com.gilfort.civmod.entertainment.Stage;
import com.gilfort.civmod.entertainment.Tavern;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Bard's evening (#227, vault {@code Systems/Entertainment.md}, Decisions of 2026-11-09): its work ends
 * at the usual time, it lives the evening like everybody else (meal, Tavern) and at 18:30 it walks to its Stage and plays.
 * Each test moves the clock of the whole level, so each has an environment of its own (the clock stands still otherwise).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BardEveningGameTests {

    /** The Stage of the scene ({@link ServiceGameTests#JOB_BLOCK}). */
    private static final BlockPos STAGE = ServiceGameTests.JOB_BLOCK;
    /** A Pantry far enough from the Bard's start (more than the Tavern radius of 12 blocks) and from the Stage. */
    private static final BlockPos PANTRY = new BlockPos(14, 1, 8);
    /** Bread in the Bard's pockets for its evening meal. */
    private static final int BREAD = 3;
    /** A hungry food level: the evening meal is eaten from the pockets. */
    private static final int HUNGRY_FOOD = 4;

    private BardEveningGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "bard_evening_meal_then_stage", BardEveningGameTests::mealThenStage);
            register(helper, "bard_evening_tavern_guest", BardEveningGameTests::tavernGuest);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static JobStatus status(Settler bard) {
        return bard.getWork().status(bard.getJobs().get(0).jobBlock());
    }

    private static int bread(Settler settler) {
        return settler.getInventory().getItem(Settler.POCKETS_START).getCount();
    }

    /**
     * At 18:20 the Bard's work is over: it eats from its pockets. At 18:30 it is still eating and finishes the meal first;
     * then it walks to the Stage and plays the stage act for the settler next to it.
     */
    private static void mealThenStage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = StageGameTests.scene(helper);
        Settler hearer = ServiceGameTests.listener(helper, scene, 3.0, "Hearer");
        Settler bard = scene.performer();
        bard.getFood().setLevel(HUNGRY_FOOD);
        bard.getFood().setSaturation(0.0F);
        bard.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, BREAD));
        long day = DayClock.day(level);
        Schedule.DayTimes times = bard.getWork().dayTimes(day);
        helper.assertTrue(times.workEnd() < ServiceAct.clock(18, 0) && times.appointment().equals(new Schedule.Appointment(ServiceAct.clock(18, 30),
                ServiceAct.clock(20, 0))), "Work ends as usual, the stage act is the appointment: " + times);
        helper.startSequence()
                .thenExecute(() -> {
                    bard.getWork().decideNow(level);
                    helper.assertFalse(bard.getWork().atWork(), "18:20: the evening meal, not the Stage");
                    ServiceGameTests.setDayTime(level, ServiceAct.clock(18, 31));
                    bard.getWork().decideNow(level);
                    helper.assertTrue(!bard.getWork().atWork() && bread(bard) == BREAD, "18:31, still eating: the meal is finished first");
                })
                .thenWaitUntil(() -> helper.assertTrue(ServiceGameTests.entertainment(hearer) > 0.0, "The stage act is played after the meal (status "
                        + status(bard) + ", at " + bard.position() + ", bread " + bread(bard) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(bread(bard) < BREAD && bard.getFood().level() > HUNGRY_FOOD, "The Bard ate first: " + bread(bard) + " bread");
                    helper.assertTrue(status(bard) == JobStatus.PLAYING, "Playing on the Stage: " + status(bard));
                    ServiceGameTests.cleanUp(level, scene, hearer);
                })
                .thenSucceed();
    }

    /**
     * Before 18:30 the Bard is an ordinary Tavern guest: three settlers give nothing, the Bard as the fourth makes the crowd
     * and is paid with them. At 18:31 it gives up its seat and heads for the Stage; from then on it plays the Bard.
     */
    private static void tavernGuest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServiceGameTests.Scene scene = StageGameTests.scene(helper);
        TestSettlements.setBlock(helper, PANTRY, ModBlocks.PANTRY_BLOCK.get());
        BlockPos pantry = helper.absolutePos(PANTRY);
        long day = DayClock.day(level);
        long dayTime = DayClock.dayTime(level.getOverworldClockTime());
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(14, 1, 7));
        TavernGameTests.host(helper, pantry, host);
        Settler a = TavernGameTests.settler(helper, pantry, new BlockPos(12, 1, 7));
        Settler b = TavernGameTests.settler(helper, pantry, new BlockPos(13, 1, 6));
        TavernGameTests.seat(helper, a, b);
        Settler bard = scene.performer();
        Tavern.countAt(level, pantry, day, dayTime);
        helper.assertTrue(TavernGameTests.value(a) == 0.0, "Without the Bard three are present: nothing");

        Vec3 near = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(12, 1, 5)));
        bard.teleportTo(near.x, near.y, near.z);
        bard.getFood().setLevel(SettlerFood.MAX);
        TavernGameTests.seat(helper, bard);
        helper.assertTrue(bard.getWork().tavernSeat(day).isPresent() && !Stage.performs(level, bard), "18:10: the Bard books a seat like a guest");
        Tavern.countAt(level, pantry, day, dayTime);
        helper.assertTrue(TavernGameTests.value(a) > 0.0 && TavernGameTests.value(bard) == TavernGameTests.value(a),
                "The Bard makes four and is paid like the others: " + TavernGameTests.value(bard) + " / " + TavernGameTests.value(a));

        ServiceGameTests.setDayTime(level, ServiceAct.clock(18, 31));
        bard.getWork().decideNow(level);
        helper.assertTrue(bard.getWork().tavernSeat(day).isEmpty(), "18:31: the seat is free again");
        helper.assertTrue(Stage.performs(level, bard) && ServiceGameTests.works(bard, scene.job()), "Heads for the Stage: " + bard.getWork().activeJob());
        TavernGameTests.end(helper, pantry, List.of(host, a, b));
        ServiceGameTests.cleanUp(level, scene);
        helper.succeed();
    }
}
