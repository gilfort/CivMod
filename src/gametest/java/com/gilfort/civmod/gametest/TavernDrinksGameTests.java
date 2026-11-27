package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.job.Schedule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the drinks of the Tavern evening (spec #325, story #326; vault {@code Systems/Entertainment.md}): a seated guest
 * fetches a beer at the counter of a Pantry whose Tavern Host is on shift, sips it at its seat and gains a little Entertainment
 * while the Tavern condition holds. The live tests run in the environment {@code civmod:after_work} (18:06, the clock stands
 * still) with settlers that walk; the catch-up tests simulate a missed evening.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class TavernDrinksGameTests {

    private static final double EPSILON = 1.0E-6;
    /** Ticks a beer lasts at the least: 3 sips of 60 ticks and 2 pauses of 100. */
    private static final int SHORTEST_BEER = 3 * 60 + 2 * 100;
    /** Ticks from taking a beer to its last sip at the most: 5 sips of 60 ticks, 4 pauses of 200 and the walk back to the seat. */
    private static final int LONGEST_BEER = 5 * 60 + 4 * 200 + 200;
    private static final BlockPos JUKEBOX = new BlockPos(5, 1, 1);

    private TavernDrinksGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "tavern_drinks_evening", TavernDrinksGameTests::evening);
            register(helper, "tavern_drinks_no_condition", TavernDrinksGameTests::noCondition);
            register(helper, "tavern_drinks_no_beer", TavernDrinksGameTests::noBeer);
            register(helper, "tavern_drinks_last_hour", TavernDrinksGameTests::lastHour);
            register(helper, "tavern_drinks_begun_beer_after_shift", TavernDrinksGameTests::begunBeerAfterShift);
            register(helper, "tavern_drinks_catch_up", TavernDrinksGameTests::catchUp);
            register(helper, "tavern_drinks_catch_up_no_condition", TavernDrinksGameTests::catchUpNoCondition);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** What a live test sets up: a Pantry with a stock, its Tavern Host, one seat and one guest who walks. */
    private record Scene(BlockPos pantry, Container stock, Settler host, Settler guest, Cushion seat) {

        void end(GameTestHelper helper) {
            TavernGameTests.end(helper, pantry, List.of(host, guest));
        }
    }

    /**
     * A guest whose bedtime is late, so no test clock set before 20:15 sends it to bed. It walks (its AI is on) and has eaten.
     */
    private static Settler guestWithLateBed(GameTestHelper helper, BlockPos pantry, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settler guest = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        do {
            guest.setUUID(UUID.randomUUID());
        } while (Schedule.dayTimes(guest.getUUID(), DayClock.day(level)).bed() < 14100L);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        guest.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        level.addFreshEntity(guest);
        guest.joinSettlement(data, data.settlementAt(pantry).orElseThrow());
        for (int i = Settler.POCKETS_START; i < Settler.POCKETS_START + Settler.POCKETS_SIZE; i++) {
            guest.getInventory().setItem(i, ItemStack.EMPTY);
        }
        guest.getFood().setAppetite(1.0F);
        guest.getFood().setLevel(com.gilfort.civmod.food.SettlerFood.MAX);
        return guest;
    }

    private static Scene scene(GameTestHelper helper, int beers, boolean jukebox) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Container stock = (Container) level.getBlockEntity(pantry);
        if (beers > 0) {
            stock.setItem(0, new ItemStack(ModItems.BEER.get(), beers));
        }
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 2));
        TavernGameTests.host(helper, pantry, host);
        if (jukebox) {
            helper.setBlock(JUKEBOX, Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        }
        Cushion seat = TavernGameTests.cushion(helper, new BlockPos(6, 1, 4), 90.0F);
        Settler guest = guestWithLateBed(helper, pantry, new BlockPos(3, 1, 4));
        return new Scene(pantry, stock, host, guest, seat);
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

    private static int pockets(Settler settler, Item item) {
        int count = 0;
        for (int slot = Settler.POCKETS_START; slot < Settler.POCKETS_START + Settler.POCKETS_SIZE; slot++) {
            if (settler.getInventory().getItem(slot).is(item)) {
                count += settler.getInventory().getItem(slot).getCount();
            }
        }
        return count;
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    private static double drinkPoints(Settler guest, long day) {
        return guest.getWork().entertainment().drinks().pointsOn(day);
    }

    /** What one beer gives at the Tavern Host's skill level now. */
    private static double beerAt(Settler host) {
        return EntertainmentRules.get().drinkPoints(host.getWork().effectiveLevel(host.getJobs().getFirst()));
    }

    private static long hostExp(Settler host) {
        return host.getSkills().get(ModJobs.TAVERN_HOST.get()).exp();
    }

    /**
     * A full evening for one guest with a jukebox: it fetches a beer at the counter (the Tavern Host gains EXP, the stock goes
     * down), carries it to its seat, sips it for 3 to 5 sips and gets half a point; the empty bottle goes back to the Pantry
     * when it fetches the next beer. After three beers it takes no more, though the stock holds some.
     */
    private static void evening(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = DayClock.day(level);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        Scene scene = scene(helper, 5, true);
        Settler guest = scene.guest();
        long[] taken = new long[1];
        double[] firstBeer = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(count(scene.stock(), ModItems.BEER.get()) < 5, "The seated guest fetches a beer"))
                .thenExecute(() -> {
                    taken[0] = level.getGameTime();
                    helper.assertTrue(count(scene.stock(), ModItems.BEER.get()) == 4, "One beer is taken from the stock");
                    helper.assertTrue(guest.getWork().entertainment().drinks().on(day) == 1, "It counts for the evening");
                    helper.assertTrue(hostExp(scene.host()) > 0, "The Tavern Host gains EXP for the hand-out");
                    helper.assertTrue(guest.getMainHandItem().is(ModItems.BEER.get()), "The guest holds the beer: " + guest.getMainHandItem());
                    helper.assertTrue(near(drinkPoints(guest, day), 0.0), "Nothing is gained before the last sip");
                })
                .thenWaitUntil(() -> helper.assertTrue(pockets(guest, Items.GLASS_BOTTLE) == 1, "The first beer is finished, its bottle in the pockets"))
                .thenExecute(() -> {
                    long lasted = level.getGameTime() - taken[0];
                    helper.assertTrue(lasted >= SHORTEST_BEER && lasted <= LONGEST_BEER,
                            "A beer lasts 3 to 5 sips, not one go: " + lasted + " ticks");
                    firstBeer[0] = drinkPoints(guest, day);
                    helper.assertTrue(near(firstBeer[0], beerAt(scene.host())), "Half a point times the host's skill: " + firstBeer[0]);
                    helper.assertTrue(guest.isSitting(), "It finished the beer at its seat");
                    helper.assertTrue(guest.getMainHandItem().isEmpty(), "The empty bottle is out of its hand");
                })
                .thenWaitUntil(() -> helper.assertTrue(drinkPoints(guest, day) > 2.5 * firstBeer[0],
                        "Three beers are drunk, points " + drinkPoints(guest, day)))
                .thenExecuteAfter(400, () -> {
                    helper.assertTrue(count(scene.stock(), ModItems.BEER.get()) == 2 && guest.getWork().entertainment().drinks().on(day) == 3,
                            "Three beers an evening, not more: stock " + count(scene.stock(), ModItems.BEER.get()));
                    helper.assertTrue(count(scene.stock(), Items.GLASS_BOTTLE) == 2 && pockets(guest, Items.GLASS_BOTTLE) == 1,
                            "Two bottles went back with the next order, the last one is in the pockets: stock " + count(scene.stock(), Items.GLASS_BOTTLE)
                                    + ", pockets " + pockets(guest, Items.GLASS_BOTTLE));
                    double beers = drinkPoints(guest, day);
                    helper.assertTrue(beers > 3 * 0.5 && beers < 3 * 0.5 * 1.05, "Three beers give about 1.5 points: " + beers);
                    helper.assertTrue(near(guest.getWork().entertainment().value(), EntertainmentRules.get().tavernPoints() + beers),
                            "The Tavern base of 2 plus the beers: " + guest.getWork().entertainment());
                    scene.end(helper);
                    scene.seat().discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** A lone drinker in an empty room gains nothing: two settlers present, no jukebox. The beer is drunk all the same. */
    private static void noCondition(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = DayClock.day(level);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        Scene scene = scene(helper, 3, false);
        Settler guest = scene.guest();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(pockets(guest, Items.GLASS_BOTTLE) == 1, "The guest drank a beer"))
                .thenExecute(() -> {
                    helper.assertTrue(guest.getWork().entertainment().drinks().on(day) == 1 && count(scene.stock(), ModItems.BEER.get()) == 2,
                            "One beer is gone from the stock");
                    helper.assertTrue(near(drinkPoints(guest, day), 0.0) && near(guest.getWork().entertainment().value(), 0.0),
                            "Two settlers, no jukebox: no Tavern condition, no points: " + guest.getWork().entertainment());
                    scene.end(helper);
                    scene.seat().discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** Without beer in the Pantry the evening works as before: the guest sits, is paid and fetches nothing. */
    private static void noBeer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = DayClock.day(level);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        Scene scene = scene(helper, 0, true);
        Settler guest = scene.guest();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(guest.isSitting(), "The guest sits down"))
                .thenExecuteAfter(450, () -> {
                    helper.assertTrue(guest.isSitting() && guest.getMainHandItem().isEmpty(), "It stays at its seat with empty hands");
                    helper.assertTrue(guest.getWork().entertainment().drinks().on(day) == 0, "No beer was fetched");
                    helper.assertTrue(near(guest.getWork().entertainment().value(), EntertainmentRules.get().tavernPoints()),
                            "The Tavern pays as before: " + guest.getWork().entertainment());
                    scene.end(helper);
                    scene.seat().discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** With less than 1000 ticks left until 20:00 no beer is fetched; with 1000 or more it is. */
    private static void lastHour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        Scene scene = scene(helper, 3, true);
        Settler guest = scene.guest();
        // 19:05: 950 ticks are left.
        ServiceGameTests.setDayTime(level, 13050L);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(guest.isSitting(), "The guest sits down"))
                .thenExecuteAfter(450, () -> {
                    helper.assertTrue(count(scene.stock(), ModItems.BEER.get()) == 3 && guest.getMainHandItem().isEmpty(),
                            "Less than an hour left: no beer is fetched");
                    // 19:00: exactly 1000 ticks are left.
                    ServiceGameTests.setDayTime(level, 13000L);
                })
                .thenWaitUntil(() -> helper.assertTrue(count(scene.stock(), ModItems.BEER.get()) < 3, "One hour left: the guest fetches a beer"))
                .thenExecute(() -> {
                    scene.end(helper);
                    scene.seat().discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** A beer begun is finished after 20:00 (with its points), and no new one is fetched while the Tavern Host is off shift. */
    private static void begunBeerAfterShift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = DayClock.day(level);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        Scene scene = scene(helper, 4, true);
        Settler guest = scene.guest();
        ServiceGameTests.setDayTime(level, 13000L);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(count(scene.stock(), ModItems.BEER.get()) == 3, "The guest fetches a beer at 19:00"))
                .thenExecute(() -> ServiceGameTests.setDayTime(level, 14050L))
                .thenWaitUntil(() -> helper.assertTrue(pockets(guest, Items.GLASS_BOTTLE) == 1, "The beer is finished after 20:00"))
                .thenExecuteAfter(400, () -> {
                    helper.assertTrue(guest.getWork().entertainment().drinks().pointsOn(day) > 0.0,
                            "Its points are paid: the Tavern condition of the evening still holds");
                    helper.assertTrue(count(scene.stock(), ModItems.BEER.get()) == 3 && guest.getWork().entertainment().drinks().on(day) == 1,
                            "No new beer after 20:00");
                    scene.end(helper);
                    scene.seat().discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /**
     * A missed evening under the Tavern condition: each settler present drinks 2 beers, limited by the stock. The beers leave
     * the stock, the bottles go back, the points are written and the Tavern Host gains EXP.
     */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        long missed = DayClock.day(level) - 1;
        Container stock = (Container) level.getBlockEntity(pantry);
        stock.setItem(0, new ItemStack(ModItems.BEER.get(), 3));
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 2));
        TavernGameTests.host(helper, pantry, host);
        Settler first = TavernGameTests.settler(helper, pantry, new BlockPos(3, 1, 2));
        Settler second = TavernGameTests.settler(helper, pantry, new BlockPos(4, 1, 2));
        Settler third = TavernGameTests.settler(helper, pantry, new BlockPos(5, 1, 2));

        TavernGameTests.catchUpYesterday(helper, first);
        double beer = EntertainmentRules.get().drinkPoints(1);
        helper.assertTrue(first.getWork().entertainment().drinks().on(missed) == 2 && count(stock, ModItems.BEER.get()) == 1,
                "The first settler drank 2 beers: stock " + count(stock, ModItems.BEER.get()));
        double gained = drinkPoints(first, missed);
        helper.assertTrue(gained >= 2 * beer - EPSILON && gained < 2 * beer * 1.05 && near(first.getWork().entertainment().value(), 2.0 + gained),
                "Tavern base 2 plus 2 beers of 0.5 each, plus the host's skill: " + first.getWork().entertainment());
        helper.assertTrue(count(stock, Items.GLASS_BOTTLE) == 2, "Both bottles are back in the Pantry: " + count(stock, Items.GLASS_BOTTLE));
        helper.assertTrue(hostExp(host) > 0, "The Tavern Host gains EXP for the hand-outs");

        TavernGameTests.catchUpYesterday(helper, second);
        helper.assertTrue(second.getWork().entertainment().drinks().on(missed) == 1 && count(stock, ModItems.BEER.get()) == 0,
                "The stock holds one beer for the second settler: " + second.getWork().entertainment().drinks());
        TavernGameTests.catchUpYesterday(helper, third);
        helper.assertTrue(third.getWork().entertainment().drinks().on(missed) == 0 && near(third.getWork().entertainment().value(), 2.0),
                "Nothing left: the third settler gets the Tavern base only: " + third.getWork().entertainment());
        helper.assertTrue(count(stock, Items.GLASS_BOTTLE) == 3, "Three bottles in all");
        TavernGameTests.end(helper, pantry, List.of(host));
        third.discard();
        first.discard();
        second.discard();
        helper.succeed();
    }

    /** No Tavern condition in the missed evening (three members, no jukebox): nobody drinks and the stock stays. */
    private static void catchUpNoCondition(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        long missed = DayClock.day(level) - 1;
        Container stock = (Container) level.getBlockEntity(pantry);
        stock.setItem(0, new ItemStack(ModItems.BEER.get(), 3));
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 2));
        TavernGameTests.host(helper, pantry, host);
        Settler first = TavernGameTests.settler(helper, pantry, new BlockPos(3, 1, 2));
        Settler second = TavernGameTests.settler(helper, pantry, new BlockPos(4, 1, 2));

        TavernGameTests.catchUpYesterday(helper, first, second);
        helper.assertTrue(count(stock, ModItems.BEER.get()) == 3 && first.getWork().entertainment().drinks().on(missed) == 0
                && second.getWork().entertainment().drinks().on(missed) == 0, "Three members and no jukebox: nobody drinks");
        helper.assertTrue(near(first.getWork().entertainment().value(), 0.0), "And the Tavern pays nothing");
        TavernGameTests.end(helper, pantry, List.of(host, first, second));
        helper.succeed();
    }
}
