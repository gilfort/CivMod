package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.entertainment.Tavern;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.gametest.InnGameTests.Inn;
import com.gilfort.civmod.inn.InnRules;
import com.gilfort.civmod.inn.VisitorStroll;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the day sitting of a visitor (vault {@code Systems/NPC Population.md}, "The visitor's day"): by day it strolls for
 * 30 to 90 s and sits for 60 to 180 s on a free seat around its Pantry, in turns. They build on the inn of {@link InnGameTests} and
 * the helpers of {@link VisitorDayGameTests}. A visitor decides every 5 seconds, so the tests call its decision themselves: 30 s are
 * 6 decisions, 90 s are 18, 60 s are 12 and 180 s are 36. The lengths come from the random source of {@link VisitorStroll#script}:
 * {@link #SHORTEST} always rolls the lower bound, {@link #LONGEST} the upper one.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VisitorSittingGameTests {

    private static final int STROLL_MIN = 6;
    private static final int STROLL_MAX = 18;
    private static final int SIT_MIN = 12;
    private static final int SIT_MAX = 36;
    /** 18:00: after the work of every settler has ended. */
    private static final long EVERY_SETTLER_DONE = 12000L;
    /** Where the visitor stands and where the first Cushion lies (11 blocks from the Pantry at most). */
    private static final BlockPos SPOT = new BlockPos(4, 1, 5);
    private static final BlockPos OTHER_SPOT = new BlockPos(5, 1, 6);
    /** A Cushion that is within the Tavern radius of the Pantry (1, 1, 1), far from the places of the guests of the crowd test. */
    private static final BlockPos FAR_SPOT = new BlockPos(10, 1, 6);

    private VisitorSittingGameTests() {
    }

    /** No pull to the Pantry, every length at its lower bound. */
    private static RandomSource shortest() {
        return rolls(false);
    }

    /** No pull to the Pantry, every length at its upper bound. */
    private static RandomSource longest() {
        return rolls(true);
    }

    private static RandomSource rolls(boolean upper) {
        return new LegacyRandomSource(0L) {
            @Override
            public double nextDouble() {
                return MasteryRolls.MISS;
            }

            @Override
            public int nextInt(int bound) {
                return upper ? bound - 1 : 0;
            }
        };
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "visitor_sitting_sits_on_cushion", VisitorSittingGameTests::sitsOnCushion);
            register(helper, "visitor_sitting_interval_bounds", VisitorSittingGameTests::intervalBounds);
            register(helper, "visitor_sitting_no_seat", VisitorSittingGameTests::noSeat);
            register(helper, "visitor_sitting_stands_up_at_evening", VisitorSittingGameTests::standsUpAtEvening);
            register(helper, "visitor_sitting_seat_removed", VisitorSittingGameTests::seatRemoved);
            register(helper, "visitor_sitting_pantry_removed", VisitorSittingGameTests::pantryRemoved);
            register(helper, "visitor_sitting_player_seat", VisitorSittingGameTests::playerSeat);
            register(helper, "visitor_sitting_two_visitors", VisitorSittingGameTests::twoVisitors);
            register(helper, "visitor_sitting_hurt", VisitorSittingGameTests::hurt);
            register(helper, "visitor_sitting_conversation", VisitorSittingGameTests::conversation);
            register(helper, "visitor_sitting_crowd_no_entertainment", VisitorSittingGameTests::crowdNoEntertainment);
            register(helper, "visitor_sitting_settlers_book", VisitorSittingGameTests::settlersBook);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ------------------------------------------------------------------------------------------------------------- setup

    private static void decide(ServerLevel level, Settler visitor, int times) {
        for (int i = 0; i < times; i++) {
            visitor.visitorDay().decideNow(level);
        }
    }

    /** Decides one step at a time and fails if the visitor sits at any of them. */
    private static void decideNeverSitting(GameTestHelper helper, Settler visitor, int times, String what) {
        for (int i = 0; i < times; i++) {
            visitor.visitorDay().decideNow(helper.getLevel());
            helper.assertFalse(visitor.isSitting(), what + ", step " + (i + 1));
        }
    }

    /** The lone visitor of an inn at noon, standing at the spot where a Cushion lies, with the shortest lengths scripted. */
    private static Settler visitorAt(GameTestHelper helper, Inn inn, BlockPos spot) {
        Settler visitor = VisitorDayGameTests.oneVisitor(helper, inn);
        VisitorDayGameTests.place(helper, visitor, spot, new double[] {VisitorDayGameTests.NOTHING});
        VisitorStroll.script(shortest());
        return visitor;
    }

    /** Runs the visitor through its first stroll until it sits on the Cushion at its spot. */
    private static void sitDown(GameTestHelper helper, Settler visitor) {
        decide(helper.getLevel(), visitor, STROLL_MIN + 1);
        helper.assertTrue(visitor.isSitting(), "The visitor sits after its first stroll: " + visitor.visitorDay());
    }

    private static void end(GameTestHelper helper, Inn inn, List<Settler> settlers) {
        helper.getLevel().getEntitiesOfClass(Cushion.class, helper.getBounds()).forEach(Cushion::discard);
        VisitorDayGameTests.end(helper, inn, settlers);
    }

    // ------------------------------------------------------------------------------------------------------------- cycle

    /**
     * A visitor strolls for 30 s, sits down on a free Cushion looking the way of the Cushion, sits for 60 s, then stands up and
     * strolls again.
     */
    private static void sitsOnCushion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        Cushion cushion = TavernGameTests.cushion(helper, SPOT, 90.0F);

        decideNeverSitting(helper, visitor, STROLL_MIN, "A visitor strolls for its first 30 s");
        decide(level, visitor, 1);
        helper.assertTrue(visitor.isSitting(), "Then it sits down: " + visitor.visitorDay());
        helper.assertTrue(visitor.getYRot() == 90.0F, "It looks the way of the Cushion: " + visitor.getYRot());
        helper.assertTrue(Math.abs(visitor.getY() - (cushion.getBoundingBox().maxY + 0.15)) < 1.0E-6,
                "It sits on the surface of the Cushion: " + visitor.getY());
        decide(level, visitor, SIT_MIN - 1);
        helper.assertTrue(visitor.isSitting(), "It sits for 60 s");
        decide(level, visitor, 1);
        helper.assertFalse(visitor.isSitting(), "Then it stands up");
        decideNeverSitting(helper, visitor, STROLL_MIN - 1, "and strolls for 30 s again");
        decide(level, visitor, 1);
        helper.assertTrue(visitor.isSitting(), "before it sits down again");
        end(helper, inn, List.of());
    }

    /** The upper bounds: a stroll lasts up to 90 s and a sitting up to 180 s. */
    private static void intervalBounds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        VisitorStroll.script(longest());
        TavernGameTests.cushion(helper, SPOT, 0.0F);

        decideNeverSitting(helper, visitor, STROLL_MAX, "A visitor strolls for up to 90 s");
        decide(level, visitor, 1);
        helper.assertTrue(visitor.isSitting(), "Then it sits down");
        decide(level, visitor, SIT_MAX - 1);
        helper.assertTrue(visitor.isSitting(), "It sits for up to 180 s");
        decide(level, visitor, 1);
        helper.assertFalse(visitor.isSitting(), "Then it stands up");
        end(helper, inn, List.of());
    }

    /** Without a free seat it keeps strolling, and it looks again for a seat only once per cycle. */
    private static void noSeat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);

        decideNeverSitting(helper, visitor, STROLL_MIN + 1, "Without a seat the visitor strolls on");
        TavernGameTests.cushion(helper, SPOT, 0.0F);
        // The seat search of this cycle failed: the Cushion that comes now is found by the next one, a whole stroll later.
        decideNeverSitting(helper, visitor, STROLL_MIN - 1, "No second search inside the cycle");
        decide(level, visitor, 1);
        helper.assertTrue(visitor.isSitting(), "At the end of the next stroll it finds the Cushion");
        end(helper, inn, List.of());
    }

    // ---------------------------------------------------------------------------------------------------------- the evening

    /** At 17:30 a seated visitor stands up, and in the evening it never sits down again, although a Cushion is free. */
    private static void standsUpAtEvening(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        TavernGameTests.cushion(helper, SPOT, 0.0F);
        sitDown(helper, visitor);

        InnGameTests.setClock(level, InnGameTests.today(helper), SettlerWork.WORK_END);
        decide(level, visitor, 1);
        helper.assertFalse(visitor.isSitting(), "At 17:30 the visitor stands up");
        decideNeverSitting(helper, visitor, STROLL_MAX + SIT_MIN + 5, "In the evening it never sits");
        end(helper, inn, List.of());
    }

    // ------------------------------------------------------------------------------------------------- seat and Pantry gone

    /** A Cushion that is removed under a sitting visitor stands it up. */
    private static void seatRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        Cushion cushion = TavernGameTests.cushion(helper, SPOT, 0.0F);
        sitDown(helper, visitor);

        cushion.discard();
        decide(level, visitor, 1);
        helper.assertFalse(visitor.isSitting(), "Without its seat the visitor stands up");
        end(helper, inn, List.of());
    }

    /** A Pantry that is removed stands a sitting visitor up, and no visitor of it sits down again. */
    private static void pantryRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        TavernGameTests.cushion(helper, SPOT, 0.0F);
        sitDown(helper, visitor);

        SettlementManager.remove(SettlementData.get(level), inn.pantry(), InnGameTests.today(helper), SettlementManager.Listener.NONE);
        decide(level, visitor, 1);
        helper.assertFalse(visitor.isSitting(), "Without its Pantry the visitor stands up");
        decideNeverSitting(helper, visitor, STROLL_MAX + 2 * STROLL_MIN, "It strolls on and finds no seat of a Pantry that is gone");
        helper.assertTrue(!visitor.isRemoved(), "It is not sent away");
        end(helper, inn, List.of());
    }

    /** A seat a player sits on is skipped, and a player who takes the seat of a sitting visitor stands it up. */
    private static void playerSeat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        Cushion taken = TavernGameTests.cushion(helper, SPOT, 0.0F);
        Cushion free = TavernGameTests.cushion(helper, OTHER_SPOT, 0.0F);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.startRiding(taken, true, false);

        sitDown(helper, visitor);
        helper.assertTrue(visitor.position().distanceToSqr(free.position()) < 1.0, "It sits on the free Cushion, not on the one a player rides: "
                + visitor.position());
        player.stopRiding();
        player.startRiding(free, true, false);
        decide(level, visitor, 1);
        helper.assertFalse(visitor.isSitting(), "A player took its seat: it stands up");
        player.stopRiding();
        end(helper, inn, List.of());
    }

    /** One Cushion holds one visitor: the second keeps strolling until it finds a Cushion of its own. */
    private static void twoVisitors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InnRules.use(InnGameTests.rules(2.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 2, true);
        InnGameTests.play(helper, inn, InnGameTests.today(helper), InnGameTests.NOON);
        List<Settler> visitors = InnGameTests.visitors(helper);
        helper.assertTrue(visitors.size() == 2, "Two visitors came: " + visitors.size());
        Settler first = visitors.get(0);
        Settler second = visitors.get(1);
        double[] nothing = {VisitorDayGameTests.NOTHING};
        VisitorDayGameTests.place(helper, first, SPOT, nothing);
        VisitorDayGameTests.place(helper, second, SPOT, nothing);
        VisitorStroll.script(shortest());
        TavernGameTests.cushion(helper, SPOT, 0.0F);

        for (int i = 0; i < STROLL_MIN + 1; i++) {
            decide(level, first, 1);
            decide(level, second, 1);
        }
        helper.assertTrue(first.isSitting() != second.isSitting(), "One Cushion seats one visitor: " + first.isSitting() + ", " + second.isSitting());
        Settler waiting = first.isSitting() ? second : first;
        Cushion other = TavernGameTests.cushion(helper, OTHER_SPOT, 0.0F);
        VisitorDayGameTests.place(helper, waiting, OTHER_SPOT, nothing);
        decide(level, waiting, STROLL_MIN);
        helper.assertTrue(waiting.isSitting() && waiting.position().distanceToSqr(other.position()) < 1.0,
                "The second one sits down on the second Cushion: " + waiting.position());
        helper.assertTrue(first.isSitting() && second.isSitting(), "Both sit now");
        end(helper, inn, List.of());
    }

    // ------------------------------------------------------------------------------------------------------------- standing up

    /** Any hit stands a sitting visitor up, and it strolls on. */
    private static void hurt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        TavernGameTests.cushion(helper, SPOT, 0.0F);
        sitDown(helper, visitor);

        visitor.hurtServer(level, level.damageSources().generic(), 1.0F);
        helper.assertFalse(visitor.isSitting(), "A hit stands the visitor up");
        decideNeverSitting(helper, visitor, STROLL_MIN, "It strolls on for a whole stroll");
        decide(level, visitor, 1);
        helper.assertTrue(visitor.isSitting(), "and sits down at the end of it");
        end(helper, inn, List.of());
    }

    /** A settler that starts a conversation with a sitting visitor stands it up; after the talk it strolls on. */
    private static void conversation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        TavernGameTests.cushion(helper, SPOT, 0.0F);
        sitDown(helper, visitor);
        double[] roll = {VisitorDayGameTests.NOTHING};
        Settler settler = VisitorDayGameTests.settler(helper, inn, new BlockPos(3, 1, 5), roll);

        roll[0] = VisitorDayGameTests.CONVERSATION;
        settler.getWork().decideNow(level);
        roll[0] = VisitorDayGameTests.NOTHING;
        helper.assertFalse(visitor.isSitting(), "A conversation stands the visitor up");
        VisitorDayGameTests.talk(VisitorDayGameTests.WHOLE_TALK, settler, visitor);
        decideNeverSitting(helper, visitor, STROLL_MIN, "After the talk it strolls");
        VisitorDayGameTests.end(helper, inn, List.of(settler));
    }

    // ----------------------------------------------------------------------------------------------------- the Tavern evening

    /**
     * A visitor that still sits when the evening count runs counts for the crowd (three settlers and a visitor are a crowd of 4),
     * and gets no points.
     */
    private static void crowdNoEntertainment(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        long day = InnGameTests.today(helper);
        Settler visitor = visitorAt(helper, inn, FAR_SPOT);
        TavernGameTests.cushion(helper, FAR_SPOT, 0.0F);
        sitDown(helper, visitor);
        InnGameTests.setClock(level, day, VisitorDayGameTests.EVENING_MEAL);
        Settler host = inn.host().orElseThrow();
        Settler first = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(3, 1, 4));
        TavernGameTests.seat(helper, first);

        Tavern.countAt(level, inn.pantry(), day, VisitorDayGameTests.EVENING_MEAL);
        helper.assertTrue(VisitorDayGameTests.value(first) == 0.0 && VisitorDayGameTests.value(host) == 0.0,
                "The host, a guest and the visitor are only three: no crowd");

        Settler second = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(4, 1, 4));
        TavernGameTests.seat(helper, second);
        Tavern.countAt(level, inn.pantry(), day, VisitorDayGameTests.EVENING_MEAL);
        helper.assertTrue(VisitorDayGameTests.value(host) == 3.0 && VisitorDayGameTests.value(first) == 3.0
                && VisitorDayGameTests.value(second) == 3.0, "With the seated visitor the crowd is 4 and the others are paid");
        helper.assertTrue(VisitorDayGameTests.value(visitor) == 0.0 && !visitor.getWork().entertainment().tavernPaid(day),
                "The seated visitor gets no points");
        helper.assertTrue(!visitor.getWork().holdsTavernSeat(inn.pantry(), day), "It holds no booked seat");
        end(helper, inn, List.of(first, second));
    }

    /**
     * The seat of a visitor is free for the settlers of the evening: the visitor stands up at 17:30, and a settler, which finishes work
     * between 17:30 and 18:00, books the only Cushion as it does every evening.
     */
    private static void settlersBook(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        long day = InnGameTests.today(helper);
        Settler visitor = visitorAt(helper, inn, SPOT);
        TavernGameTests.cushion(helper, SPOT, 0.0F);
        sitDown(helper, visitor);
        Settler settler = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(3, 1, 4));
        settler.getFood().setLevel(SettlerFood.MAX);

        InnGameTests.setClock(level, day, SettlerWork.WORK_END);
        decide(level, visitor, 1);
        helper.assertFalse(visitor.isSitting(), "The visitor has stood up at 17:30");
        InnGameTests.setClock(level, day, EVERY_SETTLER_DONE);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().holdsTavernSeat(inn.pantry(), day), "The settler books the Cushion the visitor sat on");
        end(helper, inn, List.of(settler));
    }
}
