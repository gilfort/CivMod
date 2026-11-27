package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.gametest.InnGameTests.Inn;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the beer of the visitors (spec #325, story #327; vault {@code Systems/NPC Population.md} and
 * {@code Systems/Entertainment.md}): in the Tavern evening a visitor of the inn fetches a beer at the counter of its Pantry like a
 * settler, sips it standing, never takes a seat and gets no points. The live tests give the visitor its AI and run in an environment
 * of their own, because they set the clock; the catch-up test calls the missed evening itself.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VisitorDrinksGameTests {

    /** 17:50: inside the Tavern evening, more than an hour before the Tavern Host's shift ends. */
    private static final long EVENING = 12500L;
    /** 19:05: 950 ticks are left until 20:00. */
    private static final long LAST_HOUR_BEFORE = 13050L;
    /** 19:00: exactly 1000 ticks are left until 20:00. */
    private static final long LAST_HOUR_FROM = 13000L;
    /** Ticks a beer lasts at the least: 3 sips of 60 ticks and 2 pauses of 100. */
    private static final int SHORTEST_BEER = 3 * 60 + 2 * 100;
    /** Ticks from taking a beer to its last sip at the most: 5 sips of 60 ticks and 4 pauses of 200, with a margin. */
    private static final int LONGEST_BEER = 5 * 60 + 4 * 200 + 100;
    private static final BlockPos JUKEBOX = new BlockPos(5, 1, 1);

    private VisitorDrinksGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "visitor_drinks_evening", VisitorDrinksGameTests::evening);
            register(helper, "visitor_drinks_beside_settler", VisitorDrinksGameTests::besideSettler);
            register(helper, "visitor_drinks_no_beer", VisitorDrinksGameTests::noBeer);
            register(helper, "visitor_drinks_last_hour", VisitorDrinksGameTests::lastHour);
            register(helper, "visitor_drinks_catch_up", VisitorDrinksGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static List<long[]> forceInnChunks(GameTestHelper helper) {
        return LumberjackGameTests.forceChunks(helper, new BlockPos(-8, 0, -8), new BlockPos(15, 0, 15));
    }

    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                count += container.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static long drinks(Settler settler, long day) {
        return settler.getWork().entertainment().drinks().on(day);
    }

    private static long hostExp(Settler host) {
        return host.getSkills().get(ModJobs.TAVERN_HOST.get()).exp();
    }

    /** The stock of the inn's Pantry: five pieces of bread and the beers asked for. */
    private static Container stocked(ServerLevel level, Inn inn, int beers) {
        Container stock = (Container) level.getBlockEntity(inn.pantry());
        stock.setItem(0, new ItemStack(Items.BREAD, 5));
        if (beers > 0) {
            stock.setItem(1, new ItemStack(ModItems.BEER.get(), beers));
        }
        return stock;
    }

    private static void end(GameTestHelper helper, ServerLevel level, Inn inn, List<long[]> forced) {
        level.getEntitiesOfClass(Cushion.class, helper.getBounds()).forEach(Cushion::discard);
        InnGameTests.cleanUp(helper, inn);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
    }

    /**
     * A visitor in the Tavern evening, with no seat in the room: it eats, fetches a beer at the counter (the Tavern Host gains EXP,
     * the stock goes down), stands and sips it in 3 to 5 sips, and the empty bottle goes back to the Pantry. It takes three beers and
     * no more, though the stock holds more, and it gains no Entertainment.
     */
    private static void evening(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceInnChunks(helper);
        Inn inn = VisitorDayGameTests.newInn(helper);
        Container stock = stocked(level, inn, 5);
        long day = InnGameTests.today(helper);
        Settler visitor = VisitorDayGameTests.oneVisitor(helper, inn);
        Settler host = inn.host().orElseThrow();
        long[] taken = new long[1];
        helper.startSequence()
                .thenExecute(() -> InnGameTests.setClock(level, day, EVENING))
                .thenWaitUntil(() -> helper.assertTrue(count(stock, ModItems.BEER.get()) < 5, "The visitor fetches a beer, " + visitor.visitorDay()))
                .thenExecute(() -> {
                    taken[0] = level.getGameTime();
                    helper.assertTrue(count(stock, ModItems.BEER.get()) == 4, "One beer is taken from the stock");
                    helper.assertTrue(drinks(visitor, day) == 1, "It counts for the evening");
                    helper.assertTrue(hostExp(host) > 0, "The Tavern Host gains EXP for the hand-out");
                    helper.assertTrue(visitor.getMainHandItem().is(ModItems.BEER.get()), "The visitor holds the beer: " + visitor.getMainHandItem());
                })
                .thenWaitUntil(() -> helper.assertTrue(visitor.isSipping(), "It sips, the arm up at its mouth"))
                .thenExecute(() -> helper.assertFalse(visitor.isSitting(), "It stands: the seats are the settlers'"))
                .thenWaitUntil(() -> helper.assertTrue(count(stock, Items.GLASS_BOTTLE) == 1, "The first beer is finished, its bottle back in the Pantry"))
                .thenExecute(() -> {
                    long lasted = level.getGameTime() - taken[0];
                    helper.assertTrue(lasted >= SHORTEST_BEER && lasted <= LONGEST_BEER, "A beer lasts 3 to 5 sips, not one go: " + lasted + " ticks");
                    helper.assertTrue(visitor.getMainHandItem().isEmpty() && !visitor.isSipping(), "The empty bottle is out of its hand");
                })
                .thenWaitUntil(() -> helper.assertTrue(count(stock, Items.GLASS_BOTTLE) == 3, "Three beers are drunk: " + count(stock, Items.GLASS_BOTTLE)))
                .thenExecuteAfter(400, () -> {
                    helper.assertTrue(count(stock, ModItems.BEER.get()) == 2 && drinks(visitor, day) == 3,
                            "Three beers an evening, not more: stock " + count(stock, ModItems.BEER.get()));
                    helper.assertTrue(visitor.getWork().entertainment().value() == 0.0
                            && visitor.getWork().entertainment().drinks().pointsOn(day) == 0.0,
                            "A visitor gains no Entertainment: " + visitor.getWork().entertainment());
                    helper.assertFalse(visitor.isSitting(), "It never sat down");
                    helper.assertFalse(visitor.isRemoved(), "It stays");
                    end(helper, level, inn, forced);
                })
                .thenSucceed();
    }

    /**
     * A settler sits on one of two seats and a visitor drinks in the same room, with the second seat free. Both fetch beer at the same
     * counter. The visitor never takes a seat, the settler keeps its own and gets the points, the visitor none.
     */
    private static void besideSettler(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceInnChunks(helper);
        Inn inn = VisitorDayGameTests.newInn(helper);
        Container stock = stocked(level, inn, 6);
        long day = InnGameTests.today(helper);
        helper.setBlock(JUKEBOX, Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        TavernGameTests.cushion(helper, new BlockPos(6, 1, 4), 90.0F);
        TavernGameTests.cushion(helper, new BlockPos(6, 1, 6), 90.0F);
        Settler visitor = VisitorDayGameTests.oneVisitor(helper, inn);
        Settler guest = TavernDrinksGameTests.guestWithLateBed(helper, inn.pantry(), new BlockPos(3, 1, 4));
        helper.startSequence()
                .thenExecute(() -> InnGameTests.setClock(level, day, EVENING))
                .thenWaitUntil(() -> helper.assertTrue(drinks(visitor, day) >= 1 && drinks(guest, day) >= 1,
                        "Both fetch a beer: visitor " + drinks(visitor, day) + ", guest " + drinks(guest, day)))
                .thenWaitUntil(() -> helper.assertTrue(guest.getWork().entertainment().drinks().pointsOn(day) > 0.0,
                        "The guest finished a beer and was paid"))
                .thenExecute(() -> {
                    helper.assertTrue(guest.isSitting() && guest.getWork().tavernSeat(day).isPresent(), "The settler holds its seat");
                    helper.assertFalse(visitor.isSitting(), "The visitor stands, with a free seat in the room");
                    helper.assertTrue(visitor.getWork().tavernSeat(day).isEmpty() && !visitor.getWork().holdsTavernSeat(inn.pantry(), day),
                            "The visitor books no seat");
                    helper.assertTrue(visitor.getWork().entertainment().value() == 0.0, "The visitor gains nothing: " + visitor.getWork().entertainment());
                    helper.assertTrue(count(stock, ModItems.BEER.get()) <= 4, "Both took from the same stock: " + count(stock, ModItems.BEER.get()));
                    guest.discard();
                    end(helper, level, inn, forced);
                })
                .thenSucceed();
    }

    /** Without beer in the Pantry the visitor eats, fetches nothing and stays: the inn works as before. */
    private static void noBeer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceInnChunks(helper);
        Inn inn = VisitorDayGameTests.newInn(helper);
        Container stock = stocked(level, inn, 0);
        long day = InnGameTests.today(helper);
        Settler visitor = VisitorDayGameTests.oneVisitor(helper, inn);
        helper.startSequence()
                .thenExecute(() -> InnGameTests.setClock(level, day, EVENING))
                .thenWaitUntil(() -> helper.assertTrue(count(stock, Items.BREAD) == 4, "The visitor eats, " + visitor.visitorDay()))
                .thenExecuteAfter(450, () -> {
                    helper.assertTrue(drinks(visitor, day) == 0 && visitor.getMainHandItem().isEmpty() && !visitor.isSipping(),
                            "No beer, no drinking");
                    helper.assertFalse(visitor.isRemoved(), "It stays");
                    end(helper, level, inn, forced);
                })
                .thenSucceed();
    }

    /** With less than 1000 ticks left until 20:00 a visitor fetches no beer, like a settler; with 1000 or more it does. */
    private static void lastHour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = forceInnChunks(helper);
        Inn inn = VisitorDayGameTests.newInn(helper);
        Container stock = stocked(level, inn, 3);
        long day = InnGameTests.today(helper);
        Settler visitor = VisitorDayGameTests.oneVisitor(helper, inn);
        helper.startSequence()
                .thenExecute(() -> InnGameTests.setClock(level, day, LAST_HOUR_BEFORE))
                .thenWaitUntil(() -> helper.assertTrue(count(stock, Items.BREAD) == 4, "The visitor eats, " + visitor.visitorDay()))
                .thenExecuteAfter(450, () -> {
                    helper.assertTrue(count(stock, ModItems.BEER.get()) == 3 && drinks(visitor, day) == 0, "Less than an hour left: no beer is fetched");
                    InnGameTests.setClock(level, day, LAST_HOUR_FROM);
                })
                .thenWaitUntil(() -> helper.assertTrue(count(stock, ModItems.BEER.get()) < 3, "One hour left: the visitor fetches a beer"))
                .thenExecute(() -> end(helper, level, inn, forced))
                .thenSucceed();
    }

    /**
     * A missed evening: the settlers of the settlement drink 2 beers each from the stock, the visitor of the inn drinks nothing and
     * takes nothing from the stock.
     */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inn inn = VisitorDayGameTests.newInn(helper);
        Container stock = (Container) level.getBlockEntity(inn.pantry());
        stock.setItem(0, new ItemStack(ModItems.BEER.get(), 3));
        Settler visitor = VisitorDayGameTests.oneVisitor(helper, inn);
        visitor.setNoAi(true);
        long missed = DayClock.day(level) - 1;
        Settler first = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(3, 1, 2));
        Settler second = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(4, 1, 2));
        Settler third = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(5, 1, 2));

        TavernGameTests.catchUpYesterday(helper, first, second, third);
        helper.assertTrue(drinks(first, missed) == 2 && drinks(second, missed) == 1 && drinks(third, missed) == 0,
                "The settlers drink what the stock holds: " + drinks(first, missed) + ", " + drinks(second, missed) + ", " + drinks(third, missed));
        helper.assertTrue(count(stock, ModItems.BEER.get()) == 0 && count(stock, Items.GLASS_BOTTLE) == 3,
                "Three beers went to the settlers, three bottles are back: stock " + count(stock, ModItems.BEER.get()));
        helper.assertTrue(drinks(visitor, missed) == 0 && drinks(visitor, DayClock.day(level)) == 0 && visitor.getWork().entertainment().value() == 0.0,
                "The visitor drank nothing: " + visitor.getWork().entertainment());
        first.discard();
        second.discard();
        third.discard();
        InnGameTests.end(helper, inn);
    }
}
