package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.entertainment.Tavern;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.gametest.InnGameTests.Inn;
import com.gilfort.civmod.inn.InnRules;
import com.gilfort.civmod.inn.Inns;
import com.gilfort.civmod.inn.VisitorStroll;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.Visit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the day of a visitor (vault {@code Systems/NPC Population.md}, spec Inn and visitors): where it sleeps, what it
 * eats, how it counts in the Tavern evening, and how it takes part in the social life. They build on the inn of
 * {@link InnGameTests}: a visitor arrives through an arrival check, then the test sets the clock to the part of the day it looks at.
 * The tests that watch a visitor walk give it its AI; the others call its decisions themselves.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VisitorDayGameTests {

    /** 21:00: after every settler's bedtime. */
    private static final long NIGHT = 15000L;
    /** 23:30: the Tavern evening, after the evening check, before bedtime. */
    private static final long EVENING_MEAL = 12500L;
    /** 07:00: after every settler's waking time. */
    private static final long MORNING_UP = 1000L;
    /** The roll of the meeting check that is a conversation. */
    private static final double CONVERSATION = 0.0;
    /** The roll of the meeting check that is a greeting (conversation share 0.2, greeting share 0.3). */
    private static final double GREETING = 0.25;
    /** The roll of the meeting check that is neither. */
    private static final double NOTHING = 0.99;
    /** Ticks enough to walk up, talk for 120 and end. */
    private static final int WHOLE_TALK = 130;

    private VisitorDayGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "visitor_day_sleeps_in_guest_bed", VisitorDayGameTests::sleepsInGuestBed);
            register(helper, "visitor_day_leaving_frees_bed", VisitorDayGameTests::leavingFreesBed);
            register(helper, "visitor_day_hired_frees_bed", VisitorDayGameTests::hiredFreesBed);
            register(helper, "visitor_day_eats_once", VisitorDayGameTests::eatsOnce);
            register(helper, "visitor_day_no_food_leaves", VisitorDayGameTests::noFoodLeaves);
            register(helper, "visitor_day_tavern_crowd", VisitorDayGameTests::tavernCrowd);
            register(helper, "visitor_day_settler_talks", VisitorDayGameTests::settlerTalks);
            register(helper, "visitor_day_settler_at_daily_limit", VisitorDayGameTests::settlerAtDailyLimit);
            register(helper, "visitor_day_visitor_talks", VisitorDayGameTests::visitorTalks);
            register(helper, "visitor_day_visitor_greets", VisitorDayGameTests::visitorGreets);
            register(helper, "visitor_day_social_rules", VisitorDayGameTests::socialRules);
            register(helper, "visitor_day_stroll_inside_border", VisitorDayGameTests::strollInsideBorder);
            register(helper, "visitor_day_stroll_pull", VisitorDayGameTests::strollPull);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ------------------------------------------------------------------------------------------------------------- setup

    /** A Pantry with one guest bed and a Tavern Host, and the one visitor of the noon check, with no chance left to bring a second. */
    private static Settler oneVisitor(GameTestHelper helper, Inn inn) {
        InnGameTests.play(helper, inn, InnGameTests.today(helper), InnGameTests.NOON);
        List<Settler> visitors = InnGameTests.visitors(helper);
        helper.assertTrue(visitors.size() == 1, "One visitor arrived: " + visitors.size());
        return visitors.get(0);
    }

    private static Inn newInn(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        return InnGameTests.inn(helper, 1, 1, true);
    }

    /**
     * Forces the chunks a visitor can stand in: it appears up to 5 blocks from the Pantry, which may lie in the chunk next to the
     * one of the test origin, and an entity only ticks in a chunk that is forced or has all its neighbours loaded.
     */
    private static List<long[]> forceInnChunks(GameTestHelper helper) {
        return LumberjackGameTests.forceChunks(helper, new BlockPos(-8, 0, -8), new BlockPos(15, 0, 15));
    }

    private static int bread(Container pantry) {
        int count = 0;
        for (int slot = 0; slot < pantry.getContainerSize(); slot++) {
            if (pantry.getItem(slot).is(Items.BREAD)) {
                count += pantry.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static Container pantryBox(ServerLevel level, Inn inn) {
        return (Container) level.getBlockEntity(inn.pantry());
    }

    private static Visit stay(Inn inn, Settler visitor) {
        return inn.settlement().inn().visits().get(visitor.getUUID());
    }

    /** A fed settler of the inn's settlement, with its meeting rolls scripted. */
    private static Settler settler(GameTestHelper helper, Inn inn, BlockPos at, double[] roll) {
        Settler settler = TavernGameTests.settler(helper, inn.pantry(), at);
        settler.getFood().setLevel(SettlerFood.MAX);
        settler.getWork().scriptSocialRolls(() -> roll[0]);
        return settler;
    }

    /** Stands the visitor at a position, without AI, and scripts its meeting rolls. */
    private static void place(GameTestHelper helper, Settler visitor, BlockPos at, double[] roll) {
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(at));
        visitor.teleportTo(spot.x, spot.y, spot.z);
        visitor.setNoAi(true);
        visitor.getWork().scriptSocialRolls(() -> roll[0]);
    }

    private static void talk(int ticks, Settler... settlers) {
        for (int i = 0; i < ticks; i++) {
            for (Settler settler : settlers) {
                settler.getWork().socialTick();
            }
        }
    }

    private static double value(Settler settler) {
        return settler.getWork().entertainment().value();
    }

    private static void end(GameTestHelper helper, Inn inn, List<Settler> settlers) {
        settlers.forEach(Settler::discard);
        InnGameTests.end(helper, inn);
    }

    // ------------------------------------------------------------------------------------------------- night and meals

    /**
     * At night the visitor walks to its guest bed and sleeps in it; in the morning it gets up and the bed is free again.
     */
    private static void sleepsInGuestBed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceInnChunks(helper);
        Inn inn = newInn(helper);
        long day = InnGameTests.today(helper);
        Settler visitor = oneVisitor(helper, inn);
        BlockPos bed = helper.absolutePos(InnGameTests.BED_SPOTS.get(0));
        helper.startSequence()
                .thenExecute(() -> InnGameTests.setClock(level, day, NIGHT))
                .thenWaitUntil(() -> {
                    helper.assertTrue(visitor.isSleeping(), "The visitor sleeps at night, now at " + visitor.position() + ", bed " + bed + ", "
                            + visitor.visitorDay() + ", clock " + DayClock.dayTime(level));
                    helper.assertTrue(visitor.getSleepingPos().filter(bed::equals).isPresent(), "In its guest bed: " + visitor.getSleepingPos());
                    helper.assertTrue(level.getBlockState(bed).getValue(BedBlock.OCCUPIED), "The bed is taken");
                })
                .thenExecute(() -> InnGameTests.setClock(level, day + 1, MORNING_UP))
                .thenWaitUntil(() -> {
                    helper.assertFalse(visitor.isSleeping(), "The visitor gets up in the morning");
                    helper.assertFalse(level.getBlockState(bed).getValue(BedBlock.OCCUPIED), "The bed is free again");
                })
                .thenExecute(() -> {
                    InnGameTests.cleanUp(helper, inn);
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** A visitor that is hired while it sleeps gets up and frees its guest bed. */
    private static void hiredFreesBed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = newInn(helper);
        Settler visitor = oneVisitor(helper, inn);
        visitor.setNoAi(true);
        BlockPos bed = helper.absolutePos(InnGameTests.BED_SPOTS.get(0));
        helper.assertTrue(visitor.startSleeping(bed), "The visitor lies in its guest bed");

        visitor.becomeSettler(SettlementData.get(level), inn.settlement());
        helper.assertFalse(visitor.isSleeping(), "The new settler is up");
        helper.assertFalse(level.getBlockState(bed).getValue(BedBlock.OCCUPIED), "The guest bed is free again");
        visitor.discard();
        InnGameTests.end(helper, inn);
    }

    /** A visitor that leaves while it sleeps frees its guest bed. */
    private static void leavingFreesBed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InnRules.use(InnGameTests.rules(1.0, 0.0, 1));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        long day = InnGameTests.today(helper);
        Settler visitor = oneVisitor(helper, inn);
        visitor.setNoAi(true);
        BlockPos bed = helper.absolutePos(InnGameTests.BED_SPOTS.get(0));
        helper.assertTrue(visitor.startSleeping(bed) && level.getBlockState(bed).getValue(BedBlock.OCCUPIED), "The visitor lies in its guest bed");

        InnGameTests.play(helper, inn, day + 1, InnGameTests.NOON);
        helper.assertTrue(visitor.isRemoved(), "Its stay is over and it is gone");
        helper.assertFalse(level.getBlockState(bed).getValue(BedBlock.OCCUPIED), "The bed is free again");
        InnGameTests.end(helper, inn);
    }

    /** In the evening the visitor goes to the Pantry and eats one piece of food, once, and takes no more that evening. */
    private static void eatsOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceInnChunks(helper);
        Inn inn = newInn(helper);
        Container pantry = pantryBox(level, inn);
        pantry.setItem(0, new ItemStack(Items.BREAD, 5));
        long day = InnGameTests.today(helper);
        Settler visitor = oneVisitor(helper, inn);
        helper.startSequence()
                .thenExecute(() -> InnGameTests.setClock(level, day, EVENING_MEAL))
                .thenWaitUntil(() -> helper.assertTrue(bread(pantry) == 4, "The visitor eats one bread: " + bread(pantry) + " left, "
                        + visitor.visitorDay() + ", pantry " + inn.pantry() + ", clock " + DayClock.dayTime(level)))
                .thenExecuteAfter(250, () -> {
                    helper.assertTrue(bread(pantry) == 4, "Once an evening: " + bread(pantry) + " left");
                    helper.assertTrue(!visitor.isRemoved(), "It stays");
                    InnGameTests.cleanUp(helper, inn);
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** An empty Pantry sends the visitor away: it leaves the next morning with the note "no food". */
    private static void noFoodLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceInnChunks(helper);
        Inn inn = newInn(helper);
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = InnGameTests.listener(helper, chat);
        long day = InnGameTests.today(helper);
        Settler visitor = oneVisitor(helper, inn);
        long planned = stay(inn, visitor).leaveSlot();
        long nextMorning = (day + 1) * 3;
        helper.assertTrue(planned > nextMorning, "It planned to stay longer: " + planned);
        helper.startSequence()
                .thenExecute(() -> InnGameTests.setClock(level, day, EVENING_MEAL))
                .thenWaitUntil(() -> helper.assertTrue(stay(inn, visitor).leaveSlot() == nextMorning,
                        "With nothing to eat it leaves the next morning: " + stay(inn, visitor) + ", " + visitor.visitorDay() + ", pantry "
                                + inn.pantry() + ", clock " + DayClock.dayTime(level)))
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(!visitor.isRemoved() && chat.isEmpty(), "It stays the evening and the night: " + chat);
                    InnGameTests.play(helper, inn, day + 1, InnGameTests.MORNING);
                    helper.assertTrue(visitor.isRemoved(), "It leaves at the morning check");
                    helper.assertTrue(InnGameTests.says(chat, "civmod.visitor.no_food"), "With the note that there was no food: " + chat);
                    TestPlayers.remove(player);
                    InnGameTests.cleanUp(helper, inn);
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------------------------------------------- Tavern evening

    /**
     * A visitor counts towards the crowd of the Tavern evening: three settlers are no crowd, three settlers and a visitor are. It
     * never takes a seat a settler wants, and it gets no Entertainment points.
     */
    private static void tavernCrowd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = newInn(helper);
        long day = InnGameTests.today(helper);
        Settler visitor = oneVisitor(helper, inn);
        place(helper, visitor, new BlockPos(4, 1, 2), new double[] {NOTHING});
        InnGameTests.setClock(level, day, EVENING_MEAL);
        Settler host = inn.host().orElseThrow();
        Settler first = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(3, 1, 4));
        TavernGameTests.seat(helper, first);

        Tavern.countAt(level, inn.pantry(), day, EVENING_MEAL);
        helper.assertTrue(value(first) == 0.0 && value(host) == 0.0, "The host, a guest and the visitor are only three: no crowd");

        Settler second = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(4, 1, 4));
        TavernGameTests.seat(helper, second);
        Tavern.countAt(level, inn.pantry(), day, EVENING_MEAL);
        helper.assertTrue(value(host) == 3.0 && value(first) == 3.0 && value(second) == 3.0,
                "The host, two guests and the visitor are four: " + value(host) + ", " + value(first) + ", " + value(second));
        helper.assertTrue(value(visitor) == 0.0 && !visitor.getWork().entertainment().tavernPaid(day), "The visitor gets no points");
        helper.assertTrue(first.getWork().holdsTavernSeat(inn.pantry(), day) && second.getWork().holdsTavernSeat(inn.pantry(), day),
                "The settlers hold their seats");
        helper.assertTrue(!visitor.getWork().holdsTavernSeat(inn.pantry(), day) && !visitor.isSitting(), "The visitor takes no seat");
        level.getEntitiesOfClass(Cushion.class, helper.getBounds()).forEach(Cushion::discard);
        end(helper, inn, List.of(first, second));
    }

    // ------------------------------------------------------------------------------------------------------- social life

    /** A conversation of a settler with a visitor gives the settler 0.5 and the visitor nothing, and counts for the settler's day. */
    private static void settlerTalks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = newInn(helper);
        Settler visitor = oneVisitor(helper, inn);
        double[] roll = {NOTHING};
        Settler settler = settler(helper, inn, new BlockPos(3, 1, 5), roll);
        place(helper, visitor, new BlockPos(4, 1, 5), roll);

        roll[0] = CONVERSATION;
        settler.getWork().decideNow(level);
        roll[0] = NOTHING;
        talk(WHOLE_TALK, settler, visitor);
        long day = DayClock.day(level);
        helper.assertTrue(value(settler) == 0.5 && settler.getWork().entertainment().talk().on(day) == 1,
                "The settler gets 0.5 for one conversation: " + settler.getWork().entertainment());
        helper.assertTrue(value(visitor) == 0.0, "The visitor gets no points: " + value(visitor));
        end(helper, inn, List.of(settler));
    }

    /** A settler that held its two conversations of the day does not talk to a visitor either; no points come. */
    private static void settlerAtDailyLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = newInn(helper);
        Settler visitor = oneVisitor(helper, inn);
        double[] roll = {NOTHING};
        Settler settler = settler(helper, inn, new BlockPos(3, 1, 5), roll);
        place(helper, visitor, new BlockPos(4, 1, 5), roll);
        long day = DayClock.day(level);
        long long_ago = level.getGameTime() - 100_000L;
        settler.getWork().setEntertainment(settler.getWork().entertainment().talked(0.0, day, long_ago).talked(0.0, day, long_ago));

        roll[0] = CONVERSATION;
        settler.getWork().decideNow(level);
        roll[0] = NOTHING;
        talk(WHOLE_TALK, settler, visitor);
        helper.assertTrue(value(settler) == 0.0 && settler.getWork().entertainment().talk().on(day) == 2,
                "Two a day is the limit, with a visitor as with a settler: " + settler.getWork().entertainment());
        end(helper, inn, List.of(settler));
    }

    /** The visitor takes part in the meeting check: it starts a conversation with a settler, who gets 0.5; the visitor gets nothing. */
    private static void visitorTalks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = newInn(helper);
        Settler visitor = oneVisitor(helper, inn);
        double[] roll = {NOTHING};
        Settler settler = settler(helper, inn, new BlockPos(3, 1, 5), roll);
        place(helper, visitor, new BlockPos(4, 1, 5), roll);

        roll[0] = CONVERSATION;
        visitor.visitorDay().decideNow(level);
        roll[0] = NOTHING;
        talk(WHOLE_TALK, visitor, settler);
        helper.assertTrue(value(settler) == 0.5, "The settler talked with the visitor: " + settler.getWork().entertainment());
        helper.assertTrue(value(visitor) == 0.0, "The visitor gets no points: " + value(visitor));
        end(helper, inn, List.of(settler));
    }

    /** The visitor greets a settler it sees (an arm swing); a greeting gives no points. */
    private static void visitorGreets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = newInn(helper);
        Settler visitor = oneVisitor(helper, inn);
        double[] roll = {NOTHING};
        Settler settler = settler(helper, inn, new BlockPos(3, 1, 5), roll);
        place(helper, visitor, new BlockPos(4, 1, 5), roll);

        roll[0] = GREETING;
        visitor.visitorDay().decideNow(level);
        helper.assertTrue(visitor.isSwinging(), "The visitor greets the settler with an arm swing");
        helper.assertTrue(value(settler) == 0.0 && value(visitor) == 0.0, "A greeting gives no points");
        end(helper, inn, List.of(settler));
    }

    /**
     * The meeting check of a visitor follows the rules of a settler: nothing happens beyond the radius of 6 blocks, and the
     * config {@code socialLife} switches it off.
     */
    private static void socialRules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = newInn(helper);
        Settler visitor = oneVisitor(helper, inn);
        int[] rolls = {0};
        double[] roll = {CONVERSATION};
        Settler settler = settler(helper, inn, new BlockPos(2, 1, 5), roll);
        place(helper, visitor, new BlockPos(9, 1, 5), roll);
        visitor.getWork().scriptSocialRolls(() -> {
            rolls[0]++;
            return roll[0];
        });

        visitor.visitorDay().decideNow(level);
        helper.assertTrue(!visitor.isSwinging() && value(settler) == 0.0, "A settler 7 blocks away is beyond the radius");

        visitor.teleportTo(settler.getX() + 2.0, settler.getY(), settler.getZ());
        boolean was = CivModConfig.SOCIAL_LIFE.get();
        try {
            CivModConfig.SOCIAL_LIFE.set(false);
            int before = rolls[0];
            visitor.visitorDay().decideNow(level);
            helper.assertTrue(rolls[0] == before, "Switched off: the visitor does not even roll");
            CivModConfig.SOCIAL_LIFE.set(true);
            visitor.visitorDay().decideNow(level);
            helper.assertTrue(rolls[0] > before, "Switched on: the visitor rolls");
        } finally {
            CivModConfig.SOCIAL_LIFE.set(was);
        }
        end(helper, inn, List.of(settler));
    }

    // ------------------------------------------------------------------------------------------------------- strolling

    /** A big inn: the Pantry of level 3 makes a border of 30 blocks, and a stone floor reaches beyond it to the east. */
    private static Inn wideInn(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 3, 1, true);
        for (int x = -10; x <= 45; x++) {
            for (int z = -25; z <= 25; z++) {
                helper.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE);
            }
        }
        return inn;
    }

    /** The visitor stands this many blocks east of the Pantry: 3 blocks inside the border. */
    private static final int EDGE = 27;
    /** The visitor stands this many blocks east of the Pantry: far from the border, farther than a hop reaches from the Pantry. */
    private static final int MIDDLE = 15;

    /**
     * A visitor strolls through the whole settlement and not only around its Pantry: its hops reach beyond the Tavern radius, and
     * none of them ends outside the border, although the visitor stands near its edge.
     */
    private static void strollInsideBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(-10, 0, -25), new BlockPos(45, 0, 25));
        Inn inn = wideInn(helper);
        Settler visitor = oneVisitor(helper, inn);
        place(helper, visitor, new BlockPos(1 + EDGE, 1, 1), new double[] {NOTHING});
        Inns.script(MasteryRolls.scripted(MasteryRolls.MISS));

        int hops = 0;
        int beyondTavern = 0;
        int outside = 0;
        for (int i = 0; i < 300; i++) {
            Optional<Vec3> hop = VisitorStroll.hop(visitor);
            if (hop.isPresent()) {
                hops++;
                BlockPos end = BlockPos.containing(hop.get());
                if (!JobAssignments.insideBorder(inn.settlement(), end)) {
                    outside++;
                }
                if (Math.sqrt(end.distSqr(inn.pantry())) > EntertainmentRules.get().tavernRadius()) {
                    beyondTavern++;
                }
            }
        }
        helper.assertTrue(hops > 100, "The visitor finds places to stroll to: " + hops + " of 300");
        helper.assertTrue(outside == 0, "No hop ends outside the border: " + outside);
        helper.assertTrue(beyondTavern > 0, "It strolls beyond the Tavern radius: " + beyondTavern);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        end(helper, inn, List.of());
    }

    /**
     * The light pull to the Pantry: the hops that rolled the pull end closer to the Pantry than the visitor stands (most of them, as
     * vanilla's pull is a cone and not a line; about 9 in 10), the hops that did not roll it have no such tendency.
     */
    private static void strollPull(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(-10, 0, -25), new BlockPos(45, 0, 25));
        Inn inn = wideInn(helper);
        Settler visitor = oneVisitor(helper, inn);
        // Well inside the border: no hop is dropped for ending outside of it, which would pull the average inwards.
        place(helper, visitor, new BlockPos(1 + MIDDLE, 1, 1), new double[] {NOTHING});
        Vec3 pantry = Vec3.atCenterOf(inn.pantry());

        Inns.script(MasteryRolls.scripted(MasteryRolls.HIT));
        int hops = 0;
        int closer = 0;
        for (int i = 0; i < 100; i++) {
            Optional<Vec3> hop = VisitorStroll.hop(visitor);
            if (hop.isPresent()) {
                hops++;
                if (hop.get().distanceTo(pantry) < visitor.position().distanceTo(pantry)) {
                    closer++;
                }
            }
        }
        helper.assertTrue(hops > 50 && closer >= 0.75 * hops, "The hops with the pull end closer to the Pantry: " + closer + " of " + hops);

        Inns.script(MasteryRolls.scripted(MasteryRolls.MISS));
        hops = 0;
        double total = 0.0;
        for (int i = 0; i < 300; i++) {
            Optional<Vec3> hop = VisitorStroll.hop(visitor);
            if (hop.isPresent()) {
                hops++;
                total += hop.get().distanceTo(pantry);
            }
        }
        double start = visitor.position().distanceTo(pantry);
        helper.assertTrue(hops > 100 && total / hops > start - 0.5,
                "Without the pull the hops do not head for the Pantry: " + (total / hops) + " on average, from " + start);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        end(helper, inn, List.of());
    }
}
