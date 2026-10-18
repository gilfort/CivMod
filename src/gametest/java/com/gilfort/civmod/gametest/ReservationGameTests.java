package com.gilfort.civmod.gametest;

import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.Reservations;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Clerk reservations (#79): items held for a Delivery task are invisible to everybody else, consumed on
 * pickup, released on cancel and at the end of the day; a shortfall at pickup fails and requeues the task.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ReservationGameTests {

    private static final UUID DELIVERYMAN = UUID.fromString("00000000-0000-0000-0000-00000000d079");

    private ReservationGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "reservation_hidden", ReservationGameTests::hidden);
            register(helper, "reservation_pickup", ReservationGameTests::pickup);
            register(helper, "reservation_released", ReservationGameTests::released);
            register(helper, "reservation_shortfall", ReservationGameTests::shortfall);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Customers, producers and other Deliverymen see the stock minus what is reserved; the task holding it sees all. */
    private static void hidden(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Container own = (Container) level.getBlockEntity(clerk);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        own.setItem(0, new ItemStack(Items.BREAD, 2));
        chest.setItem(0, new ItemStack(Items.BREAD, 8));
        chest.setItem(1, new ItemStack(Items.APPLE, 3));
        DeliveryTask task = takenTask(helper, clerk, DayClock.day(level));
        Settlement settlement = SettlementData.get(level).settlementAt(clerk).orElseThrow();
        Reservations.reserve(settlement, clerk, task.id(), Items.BREAD, 6, DayClock.day(level));

        Stock others = ClerkStorage.stock(level, clerk, true);
        helper.assertTrue(others.count(stack -> stack.is(Items.BREAD)) == 4, "Reserved bread is hidden: " + others.count(stack -> stack.is(Items.BREAD)));
        helper.assertTrue(others.counts().get(Items.BREAD) == 4 && others.counts().get(Items.APPLE) == 3, "Counts: " + others.counts());
        helper.assertTrue(ClerkStorage.stock(level, clerk, false).count(stack -> stack.is(Items.BREAD)) == 0,
                "Block slots only: their 2 bread may be the reserved ones");
        helper.assertTrue(ClerkStorage.stock(level, clerk, true, task.id()).count(stack -> stack.is(Items.BREAD)) == 10,
                "The task holding the reservation sees all");
        int taken = others.take(Items.BREAD, 10).stream().mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(taken == 4, "A customer takes only the unreserved bread: " + taken);

        Stock rest = ClerkStorage.stock(level, clerk, true);
        helper.assertTrue(!rest.contains(stack -> stack.is(Items.BREAD)) && rest.contains(stack -> stack.is(Items.APPLE)), "Nothing unreserved left");
        helper.assertTrue(rest.contents().stream().noneMatch(stack -> stack.is(Items.BREAD)), "Reserved slots are not offered");
        helper.assertTrue(rest.takeOne(0).isEmpty() && rest.takeOne(27).isEmpty(), "A reserved slot gives nothing");
        helper.assertTrue(bread(own) + bread(chest) == 6, "The reserved bread stays in the storage");
        helper.succeed();
    }

    /** Pickup takes the reserved amount and consumes the reservation; the task stays locked to its Deliveryman. */
    private static void pickup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        long day = DayClock.day(level);
        DeliveryTask task = takenTask(helper, clerk, day);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Reservations.reserve(settlement, clerk, task.id(), Items.BREAD, 6, day);

        Reservations.Pickup pickup = Reservations.pickUp(data, settlement, clerk, task.id(), ClerkStorage.stock(level, clerk, true, task.id()), day);
        helper.assertTrue(!pickup.failed() && pickup.taken().stream().mapToInt(ItemStack::getCount).sum() == 6, "Picked up: " + pickup);
        helper.assertTrue(bread(chest) == 4 && breadFor(level, clerk) == 4, "The rest is free for everybody: " + breadFor(level, clerk));
        helper.assertTrue(Reservations.reserved(settlement, clerk, day, null).isEmpty(), "The reservation is consumed");
        helper.assertTrue(Deliveries.get(settlement, task.id()).flatMap(found -> found.takerOn(day)).isPresent(), "The task is still taken");
        helper.succeed();
    }

    /** Releasing the task (cancel) or the end of the day releases its reservation. */
    private static void released(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        long day = DayClock.day(level);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();

        DeliveryTask task = takenTask(helper, clerk, day);
        Reservations.reserve(settlement, clerk, task.id(), Items.BREAD, 6, day);
        helper.assertTrue(breadFor(level, clerk) == 4, "Reserved");
        Deliveries.release(data, settlement, task.id());
        Deliveries.take(data, settlement, task.id(), DELIVERYMAN, "Dora", day);
        helper.assertTrue(breadFor(level, clerk) == 10, "Cancelled: all bread free again, also when the task is taken again");

        Deliveries.take(data, settlement, task.id(), DELIVERYMAN, "Dora", day - 1);
        Reservations.reserve(settlement, clerk, task.id(), Items.BREAD, 6, day - 1);
        helper.assertTrue(breadFor(level, clerk) == 10, "A reservation of yesterday is over");
        Deliveries.done(data, settlement, task.id());
        helper.succeed();
    }

    /**
     * Someone took reserved items the view still counted: the pickup fails, puts back what it took, releases the
     * reservation and frees the task for another try.
     */
    private static void shortfall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        long day = DayClock.day(level);
        DeliveryTask task = takenTask(helper, clerk, day);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Reservations.reserve(settlement, clerk, task.id(), Items.BREAD, 6, day);
        helper.assertTrue(breadFor(level, clerk) == 4, "Reserved");
        chest.setItem(0, new ItemStack(Items.BREAD, 3));

        Reservations.Pickup pickup = Reservations.pickUp(data, settlement, clerk, task.id(), ClerkStorage.stock(level, clerk, true, task.id()), day);
        helper.assertTrue(pickup.failed() && pickup.taken().isEmpty(), "The pickup fails: " + pickup);
        Container own = (Container) level.getBlockEntity(clerk);
        helper.assertTrue(bread(own) + bread(chest) == 3, "What was taken is back in the storage (own slots first): " + bread(own) + bread(chest));
        helper.assertTrue(Reservations.reserved(settlement, clerk, day, null).isEmpty(), "The reservation is released");
        DeliveryTask requeued = Deliveries.get(settlement, task.id()).orElseThrow();
        helper.assertTrue(requeued.takerOn(day).isEmpty(), "The task is open again for any Deliveryman");
        Deliveries.done(data, settlement, task.id());
        helper.succeed();
    }

    private static int breadFor(ServerLevel level, BlockPos clerk) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.BREAD));
    }

    /**
     * @return an open pickup locked to the test Deliveryman on that day (any Delivery task can hold a reservation)
     */
    static DeliveryTask takenTask(GameTestHelper helper, BlockPos clerk, long day) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        BlockPos source = helper.absolutePos(new BlockPos(0, 1, 4));
        Deliveries.request(data, settlement, source, clerk, level.getGameTime(), false);
        Deliveries.take(data, settlement, source, DELIVERYMAN, "Dora", day);
        return Deliveries.get(settlement, source).orElseThrow();
    }

    private static int bread(Container container) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(Items.BREAD)) {
                count += container.getItem(i).getCount();
            }
        }
        return count;
    }
}
