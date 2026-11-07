package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.entertainment.Tavern;
import com.gilfort.civmod.entertainment.TavernSeats;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the record thought bubble of a Tavern guest (vault {@code Systems/Entertainment.md}, {@code Systems/Thought
 * Bubbles.md}): one bubble per guest and evening at a random time after sitting down, none where a jukebox holds a disc,
 * none for a settler who is not a guest, none in catch-up.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class RecordBubbleGameTests {

    /** A game time long after any delay of the bubble. */
    private static final long LATER = 100_000L;

    private RecordBubbleGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "record_bubble_once", RecordBubbleGameTests::once);
            register(helper, "record_bubble_with_disc", RecordBubbleGameTests::withDisc);
            register(helper, "record_bubble_at_home", RecordBubbleGameTests::atHome);
            register(helper, "record_bubble_catch_up", RecordBubbleGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The record bubbles a settler has sent. */
    private static long records(Settler settler) {
        return settler.getWork().recentThoughts().stream().filter(show -> show.bubble() == ThoughtBubble.RECORD).count();
    }

    /** A Tavern with a Tavern Host and one Cushion; a settler who booked it and sits there. */
    private record SeatedGuest(BlockPos pantry, Settler host, Cushion cushion, Settler guest) {

        void end(GameTestHelper helper) {
            TestAssignments.unassign(SettlementData.get(helper.getLevel()), pantry);
            host.discard();
            guest.discard();
            cushion.discard();
        }
    }

    private static SeatedGuest seatedGuest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        long day = DayClock.day(level);
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 2));
        TavernGameTests.host(helper, pantry, host);
        Cushion cushion = TavernGameTests.cushion(helper, new BlockPos(6, 1, 4), 90.0F);
        Settler guest = TavernGameTests.settler(helper, pantry, new BlockPos(3, 1, 4));
        guest.getFood().setLevel(SettlerFood.MAX);
        guest.getWork().decideNow(level);
        helper.assertTrue(guest.getWork().tavernSeat(day).isPresent(), "The guest booked a seat");
        TavernSeats.Seat seat = TavernSeats.find(level, pantry, 12, guest.getWork().tavernSeat(day).orElseThrow()).orElseThrow();
        guest.sitDown(seat.position(), seat.facing());
        return new SeatedGuest(pantry, host, cushion, guest);
    }

    /** One bubble per guest and evening, with a music disc and no problem mark, a while after sitting down. */
    private static void once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = DayClock.day(level);
        SeatedGuest tavern = seatedGuest(helper);
        Settler guest = tavern.guest();
        long now = level.getGameTime();

        helper.assertFalse(ThoughtBubble.RECORD.problem(), "An activity bubble: no red mark");
        Tavern.countAt(level, tavern.pantry(), day, TavernGameTests.EVENING, now);
        Tavern.countAt(level, tavern.pantry(), day, TavernGameTests.EVENING, now + 1);
        helper.assertTrue(records(guest) == 0, "Not at once: the bubble comes at a random time after sitting down");

        helper.assertFalse(guest.getWork().recordBubbleDue(tavern.pantry().above(), day, now + LATER), "Only the guest's own Pantry asks it");
        Tavern.countAt(level, tavern.pantry(), day, TavernGameTests.EVENING, now + LATER);
        helper.assertTrue(guest.getWork().recentThoughts().contains(new ThoughtBubble.Show(guest.getId(), ThoughtBubble.RECORD, Items.MUSIC_DISC_CAT, true)),
                "The bubble with a music disc: " + guest.getWork().recentThoughts());
        Tavern.countAt(level, tavern.pantry(), day, TavernGameTests.EVENING, now + LATER + 17);
        Tavern.countAt(level, tavern.pantry(), day, TavernGameTests.EVENING, now + 2 * LATER);
        helper.assertTrue(records(guest) == 1, "Once per evening, not again: " + records(guest));
        helper.assertTrue(guest.getWork().entertainment().recordSettled(day), "The evening is marked");
        helper.assertFalse(guest.getWork().entertainment().recordSettled(day + 1), "The next evening is free again");
        helper.assertTrue(records(tavern.host()) == 0, "The Tavern Host is no guest");
        tavern.end(helper);
        helper.succeed();
    }

    /** A jukebox that holds a disc within 8 blocks of the Pantry: no bubble, not even after the disc is gone. */
    private static void withDisc(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = DayClock.day(level);
        SeatedGuest tavern = seatedGuest(helper);
        Settler guest = tavern.guest();
        BlockPos jukebox = new BlockPos(5, 1, 1);
        helper.setBlock(jukebox, Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        long now = level.getGameTime();

        Tavern.countAt(level, tavern.pantry(), day, TavernGameTests.EVENING, now);
        Tavern.countAt(level, tavern.pantry(), day, TavernGameTests.EVENING, now + LATER);
        helper.assertTrue(records(guest) == 0, "Music plays: no bubble");
        helper.setBlock(jukebox, Blocks.JUKEBOX);
        Tavern.countAt(level, tavern.pantry(), day, TavernGameTests.EVENING, now + 2 * LATER);
        helper.assertTrue(records(guest) == 0, "The guest has seen this evening's answer; no bubble later in the evening");
        tavern.end(helper);
        helper.succeed();
    }

    /** A settler who is not seated in the Tavern, even one in reach of the Pantry, shows no bubble. */
    private static void atHome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long day = DayClock.day(level);
        BlockPos pantry = TavernGameTests.pantry(helper);
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 2));
        TavernGameTests.host(helper, pantry, host);
        Settler neighbour = TavernGameTests.settler(helper, pantry, new BlockPos(3, 1, 4));
        Settler far = TavernGameTests.settler(helper, pantry, new BlockPos(1, 1, 1 + 12 + 1));
        long now = level.getGameTime();

        Tavern.countAt(level, pantry, day, TavernGameTests.EVENING, now);
        Tavern.countAt(level, pantry, day, TavernGameTests.EVENING, now + LATER);
        helper.assertTrue(records(neighbour) == 0 && records(far) == 0 && records(host) == 0, "Nobody sits in the Tavern: no bubble");
        helper.assertFalse(neighbour.getWork().entertainment().recordSettled(day), "Nothing is marked");
        List.of(host, neighbour, far).forEach(Settler::discard);
        TestAssignments.unassign(SettlementData.get(level), pantry);
        helper.succeed();
    }

    /** A missed Tavern evening is simulated without any bubble. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 2));
        TavernGameTests.host(helper, pantry, host);
        Settler guest = TavernGameTests.settler(helper, pantry, new BlockPos(3, 1, 2));
        List<Settler> crowd = List.of(TavernGameTests.settler(helper, pantry, new BlockPos(4, 1, 2)), TavernGameTests.settler(helper, pantry, new BlockPos(5, 1, 2)));

        TavernGameTests.catchUpYesterday(helper, guest);
        helper.assertTrue(guest.getWork().entertainment().tavernPaid(DayClock.day(level) - 1), "The missed evening was simulated");
        helper.assertTrue(guest.getWork().recentThoughts().stream().noneMatch(show -> show.bubble() == ThoughtBubble.RECORD), "No bubble in catch-up");
        helper.assertFalse(guest.getWork().entertainment().recordSettled(DayClock.day(level) - 1), "The simulated evening shows nothing and marks nothing");
        TestAssignments.unassign(SettlementData.get(level), pantry);
        host.discard();
        guest.discard();
        crowd.forEach(Settler::discard);
        helper.succeed();
    }
}
