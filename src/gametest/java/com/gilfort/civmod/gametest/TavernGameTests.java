package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.entertainment.Tavern;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.entertainment.TavernSeats;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Tavern evening (vault {@code Systems/Entertainment.md}): the count of present settlers with its two
 * conditions and the points, the seats, the evening choice, the walk to the seat and the catch-up of a missed evening.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class TavernGameTests {

    /** Day time 17:36, the environment {@code civmod:evening}: inside the Tavern evening. */
    static final long EVENING = 11600L;
    private static final BlockPos PANTRY = new BlockPos(1, 1, 1);
    private static final double EPSILON = 1.0E-9;

    private TavernGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "tavern_crowd", TavernGameTests::crowd);
            register(helper, "tavern_jukebox", TavernGameTests::jukebox);
            register(helper, "tavern_both_conditions", TavernGameTests::bothConditions);
            register(helper, "tavern_one_payment", TavernGameTests::onePayment);
            register(helper, "tavern_no_host_on_shift", TavernGameTests::noHostOnShift);
            register(helper, "tavern_minute_count", TavernGameTests::minuteCount);
            register(helper, "tavern_seats", TavernGameTests::seats);
            register(helper, "tavern_evening_choice", TavernGameTests::evening);
            register(helper, "tavern_walk_and_sit", TavernGameTests::walkAndSit);
            register(helper, "tavern_bedtime", TavernGameTests::bedtime);
            register(helper, "tavern_catch_up", TavernGameTests::catchUp);
            register(helper, "tavern_catch_up_jukebox", TavernGameTests::catchUpJukebox);
            register(helper, "tavern_jukebox_unloaded_chunk", TavernGameTests::jukeboxUnloadedChunk);
            register(helper, "tavern_seat_taken_by_player", TavernGameTests::seatTakenByPlayer);
            register(helper, "tavern_only_seated_guests_paid", TavernGameTests::onlySeatedGuestsPaid);
            register(helper, "tavern_catch_up_unloaded_pantry", TavernGameTests::catchUpUnloadedPantry);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A stone floor under the whole test area. */
    static void floor(GameTestHelper helper) {
        for (int x = -8; x < 16; x++) {
            for (int z = -8; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** A Pantry at (1, 1, 1) of a new settlement on a stone floor. */
    static BlockPos pantry(GameTestHelper helper) {
        floor(helper);
        TestSettlements.setBlock(helper, PANTRY, ModBlocks.PANTRY_BLOCK.get());
        return helper.absolutePos(PANTRY);
    }

    /** A settler of the Pantry's settlement at a position, holding no work. */
    static Settler settler(GameTestHelper helper, BlockPos pantry, BlockPos relative) {
        SettlementData data = SettlementData.get(helper.getLevel());
        return ClerkGameTests.settler(helper, relative, data, data.settlementAt(pantry).orElseThrow());
    }

    /** Assigns a settler as the Tavern Host of the Pantry. */
    static void host(GameTestHelper helper, BlockPos pantry, Settler host) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = data.settlementAt(pantry).orElseThrow();
        TestAssignments.assign(data, pantry, host.getUUID(), "Host", settlement.id(), List.of());
        host.addJob(new SettlerJob(ModJobs.TAVERN_HOST.get(), pantry));
    }

    static double value(Settler settler) {
        return settler.getWork().entertainment().value();
    }

    private static void end(GameTestHelper helper, BlockPos pantry, List<Settler> settlers) {
        TestAssignments.unassign(SettlementData.get(helper.getLevel()), pantry);
        settlers.forEach(Settler::discard);
        helper.getLevel().getEntitiesOfClass(Cushion.class, helper.getBounds()).forEach(Cushion::discard);
    }

    /**
     * Gives each guest a Cushion of its own within the Tavern radius and lets it book one (the test runs in the evening, the
     * Tavern Host must be assigned already). Only booked guests are paid by the Tavern.
     */
    static void seat(GameTestHelper helper, Settler... guests) {
        for (Settler guest : guests) {
            cushion(helper, new BlockPos(8, 1, 3 + helper.getLevel().getEntitiesOfClass(Cushion.class, helper.getBounds()).size()), 0.0F);
            guest.getFood().setLevel(SettlerFood.MAX);
            guest.getWork().decideNow(helper.getLevel());
        }
    }

    /** Moves the frozen clock to the same time on the next day; the guests book again. Only for a test with an environment of its own. */
    private static long nextEvening(GameTestHelper helper, Settler... guests) {
        ServerLevel level = helper.getLevel();
        long next = DayClock.day(level) + 1;
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD),
                next * DayClock.DAY_TICKS + DayClock.dayTime(level));
        for (Settler guest : guests) {
            guest.getWork().decideNow(level);
        }
        return next;
    }

    /** The Tavern Host counts among the 4; three present give nothing; one far away is not present. */
    private static void crowd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        long day = DayClock.day(level);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        Settler a = settler(helper, pantry, new BlockPos(3, 1, 2));
        Settler b = settler(helper, pantry, new BlockPos(4, 1, 2));
        Settler far = settler(helper, pantry, new BlockPos(1, 1, 1 + 12 + 1));
        List<Settler> all = List.of(host, a, b, far);
        seat(helper, a, b);

        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(all.stream().allMatch(settler -> value(settler) == 0.0), "Three present, no jukebox: nothing");

        Settler c = settler(helper, pantry, new BlockPos(5, 1, 2));
        seat(helper, c);
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(Math.abs(value(host) - 3.0) < EPSILON, "The Tavern Host counts and is paid: " + value(host));
        helper.assertTrue(Math.abs(value(a) - 3.0) < EPSILON && Math.abs(value(b) - 3.0) < EPSILON && Math.abs(value(c) - 3.0) < EPSILON,
                "Four present: +3 each");
        helper.assertTrue(value(far) == 0.0, "13 blocks away is not present");
        end(helper, pantry, List.of(host, a, b, c, far));
        helper.succeed();
    }

    /** A jukebox that holds a disc within 8 blocks is condition B alone; without a disc or too far it is none. */
    private static void jukebox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        long day = DayClock.day(level);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        Settler guest = settler(helper, pantry, new BlockPos(3, 1, 2));
        seat(helper, guest);

        helper.setBlock(new BlockPos(5, 1, 1), Blocks.JUKEBOX);
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(value(guest) == 0.0 && value(host) == 0.0, "A jukebox without a disc is no condition");
        helper.setBlock(new BlockPos(1 + 9, 1, 1), Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(value(guest) == 0.0, "A jukebox 9 blocks away is too far");
        helper.setBlock(new BlockPos(1 + 8, 1, 1), Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(Math.abs(value(guest) - 3.0) < EPSILON && Math.abs(value(host) - 3.0) < EPSILON,
                "A jukebox with a disc within 8 blocks: +3 for two settlers, got " + value(guest));
        helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(9, 1, 1))).is(Tavern.JUKEBOXES), "The jukebox is in the block tag");
        end(helper, pantry, List.of(host, guest));
        helper.succeed();
    }

    /** Both conditions multiply the base points by the factor (3 × 1.5), a datapack value. */
    private static void bothConditions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        long day = DayClock.day(level);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        List<Settler> all = new ArrayList<>(List.of(host));
        for (int i = 0; i < 3; i++) {
            all.add(settler(helper, pantry, new BlockPos(3 + i, 1, 2)));
        }
        seat(helper, all.subList(1, 4).toArray(new Settler[0]));
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(all.stream().allMatch(settler -> Math.abs(value(settler) - 4.5) < EPSILON), "Both conditions: 3 × 1.5 = 4.5");

        EntertainmentRules rules = EntertainmentRules.FALLBACK;
        helper.assertTrue(rules.tavernPoints(3, false) == 0.0 && rules.tavernPoints(4, false) == 3.0 && rules.tavernPoints(1, true) == 3.0
                && rules.tavernPoints(4, true) == 4.5, "The rule table");
        EntertainmentRules other = new EntertainmentRules(1.0, 5.0, 8.0, 0.02, 2.0, 2.0, 2, 12, 8);
        helper.assertTrue(other.tavernPoints(2, true) == 4.0 && other.tavernPoints(1, false) == 0.0, "The factor and the crowd are datapack values");
        end(helper, pantry, all);
        helper.succeed();
    }

    /** One payment per evening: more counts add nothing; the next evening pays again; the value stays at most 10. */
    private static void onePayment(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        long day = DayClock.day(level);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        List<Settler> all = new ArrayList<>(List.of(host));
        for (int i = 0; i < 3; i++) {
            all.add(settler(helper, pantry, new BlockPos(3 + i, 1, 2)));
        }
        seat(helper, all.subList(1, 4).toArray(new Settler[0]));
        Tavern.countAt(level, pantry, day, EVENING);
        Tavern.countAt(level, pantry, day, EVENING + 100);
        helper.assertTrue(all.stream().allMatch(settler -> Math.abs(value(settler) - 3.0) < EPSILON), "Two counts, one payment");
        Settler newcomer = settler(helper, pantry, new BlockPos(2, 1, 4));
        seat(helper, newcomer);
        Tavern.countAt(level, pantry, day, EVENING + 200);
        helper.assertTrue(Math.abs(value(newcomer) - 3.0) < EPSILON && Math.abs(value(host) - 3.0) < EPSILON,
                "A settler who comes later is paid at its first count, the others are not paid again");
        all.add(newcomer);

        Settler[] guests = all.subList(1, 5).toArray(new Settler[0]);
        long next = nextEvening(helper, guests);
        Tavern.countAt(level, pantry, next, EVENING);
        // The guests took the decay of the new day when they decided again (1 point); the Tavern Host did not decide.
        helper.assertTrue(Math.abs(value(host) - 6.0) < EPSILON && all.subList(1, 5).stream().allMatch(settler -> Math.abs(value(settler) - 5.0) < EPSILON),
                "The next evening pays again: the host " + value(host) + ", a guest " + value(all.get(1)));
        all.get(1).getWork().setEntertainment(Entertainment.of(9.0));
        long third = nextEvening(helper, guests);
        Tavern.countAt(level, pantry, third, EVENING);
        helper.assertTrue(value(all.get(1)) == EntertainmentRules.MAX, "Never above 10");
        end(helper, pantry, all);
        helper.succeed();
    }

    /** No Tavern Host assigned, or none on shift: no count. */
    private static void noHostOnShift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        long day = DayClock.day(level);
        List<Settler> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            all.add(settler(helper, pantry, new BlockPos(2 + i, 1, 2)));
        }
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(all.stream().allMatch(settler -> value(settler) == 0.0), "No Tavern Host: nothing");

        host(helper, pantry, all.get(0));
        seat(helper, all.subList(1, 4).toArray(new Settler[0]));
        Tavern.countAt(level, pantry, day, 14000L);
        helper.assertTrue(all.stream().allMatch(settler -> value(settler) == 0.0), "At 20:00 the host is off shift: nothing");
        Tavern.countAt(level, pantry, day, 11000L);
        helper.assertTrue(all.stream().allMatch(settler -> value(settler) == 0.0), "Before 17:30 it is not the evening: nothing");
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(all.stream().allMatch(settler -> Math.abs(value(settler) - 3.0) < EPSILON), "On shift in the evening: paid");
        end(helper, pantry, all);
        helper.succeed();
    }

    /** A Cushion standing on the floor at a position, looking the way of the yaw. */
    static Cushion cushion(GameTestHelper helper, BlockPos relative, float yaw) {
        Cushion cushion = EntityTypes.CUSHION.create(helper.getLevel(), EntitySpawnReason.SPAWN_ITEM_USE);
        Vec3 position = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        cushion.snapTo(position, yaw, 0.0F);
        helper.getLevel().addFreshEntity(cushion);
        return cushion;
    }

    /** The Cushion within 12 blocks, the entity tag, the block tag; facing and height; too far is no seat. */
    private static void seats(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        Cushion near = cushion(helper, new BlockPos(4, 1, 1), 90.0F);
        Cushion edge = cushion(helper, new BlockPos(1, 1, 1 + 12), 180.0F);
        Cushion far = cushion(helper, new BlockPos(1, 1, 1 + 13), 0.0F);
        ArmorStand chair = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        chair.snapTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 4))));
        level.addFreshEntity(chair);
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        helper.setBlock(new BlockPos(7, 1, 6), Blocks.OAK_PLANKS);

        List<TavernSeats.Seat> seats = TavernSeats.around(level, pantry, EntertainmentRules.get().tavernRadius());
        helper.assertTrue(seats.size() == 4, "Cushion, edge cushion, armor stand of the test tag, stairs of the test tag: " + seats.size());
        TavernSeats.Seat seat = TavernSeats.find(level, pantry, 12, "e:" + near.getUUID()).orElseThrow();
        helper.assertTrue(seat.facing() == 90.0F, "A guest looks the way the Cushion does: " + seat.facing());
        helper.assertTrue(Math.abs(seat.position().y - (near.getY() + 0.25)) < 1.0E-6 && Math.abs(seat.position().x - near.getX()) < 1.0E-6,
                "It sits on the surface of the Cushion: " + seat.position());
        helper.assertTrue(TavernSeats.find(level, pantry, 12, "e:" + edge.getUUID()).isPresent(), "12 blocks away is a seat");
        helper.assertTrue(TavernSeats.find(level, pantry, 12, "e:" + far.getUUID()).isEmpty(), "13 blocks away is none");
        TavernSeats.Seat stairs = seats.stream().filter(found -> found.key().startsWith("b:")).findFirst().orElseThrow();
        helper.assertTrue(stairs.facing() == Direction.EAST.toYRot() && stairs.walkTo().equals(helper.absolutePos(new BlockPos(6, 2, 6))),
                "A seat block faces the way of its blockstate: " + stairs.facing());
        helper.assertTrue(seats.stream().noneMatch(found -> found.key().equals("b:" + helper.absolutePos(new BlockPos(7, 1, 6)).asLong())),
                "Other blocks are no seats");
        near.discard();
        edge.discard();
        far.discard();
        chair.discard();
        helper.succeed();
    }

    /** A seat counts as taken while a player sits on it (rides it); free again when the player gets up. */
    private static void seatTakenByPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        Cushion cushion = cushion(helper, new BlockPos(3, 1, 1), 0.0F);
        TavernSeats.Seat seat = TavernSeats.find(level, pantry, 12, "e:" + cushion.getUUID()).orElseThrow();
        helper.assertFalse(TavernSeats.occupied(level, seat), "Nobody sits on it");
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.startRiding(cushion, true, false);
        helper.assertTrue(TavernSeats.occupied(level, seat), "A player sits on it");
        player.stopRiding();
        helper.assertFalse(TavernSeats.occupied(level, seat), "The player got up");
        cushion.discard();
        helper.succeed();
    }

    /** A settler of the settlement who has eaten and is not on a Mission: only the evening decides what it does. */
    private static Settler guest(GameTestHelper helper, BlockPos pantry, BlockPos relative) {
        Settler guest = settler(helper, pantry, relative);
        guest.getFood().setLevel(SettlerFood.MAX);
        return guest;
    }

    private static void decide(GameTestHelper helper, Settler... settlers) {
        for (Settler settler : settlers) {
            settler.getWork().decideNow(helper.getLevel());
        }
    }

    /**
     * The choice of the evening: the nearest Pantry with a Tavern Host on shift and a free seat, one settler per seat,
     * the same every evening, nobody with full Entertainment, nobody without a Tavern Host, nobody without a seat.
     */
    private static void evening(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        long day = DayClock.day(level);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        Cushion first = cushion(helper, new BlockPos(5, 1, 1), 0.0F);
        Cushion second = cushion(helper, new BlockPos(5, 1, 3), 0.0F);
        Settler a = guest(helper, pantry, new BlockPos(3, 1, 4));
        Settler b = guest(helper, pantry, new BlockPos(3, 1, 5));
        Settler c = guest(helper, pantry, new BlockPos(3, 1, 6));
        Settler full = guest(helper, pantry, new BlockPos(3, 1, 7));
        full.getWork().setEntertainment(Entertainment.of(EntertainmentRules.MAX));
        List<Settler> all = List.of(host, a, b, c, full);

        decide(helper, a);
        helper.assertTrue(a.getWork().tavernSeat(day).isEmpty(), "No Tavern Host assigned: home");
        host(helper, pantry, host);
        decide(helper, a, full);
        helper.assertTrue(a.getWork().tavernSeat(day).isPresent(), "Host on shift, seat free: books a seat");
        helper.assertTrue(full.getWork().tavernSeat(day).isEmpty(), "Full Entertainment goes home");
        String seatOfA = a.getWork().tavernSeat(day).orElseThrow();
        decide(helper, a);
        helper.assertTrue(a.getWork().tavernSeat(day).equals(Optional.of(seatOfA)), "The same seat at every decision of the evening");

        decide(helper, b, c);
        helper.assertTrue(b.getWork().tavernSeat(day).isPresent() && !b.getWork().tavernSeat(day).equals(a.getWork().tavernSeat(day)),
                "The second settler gets the other seat");
        helper.assertTrue(c.getWork().tavernSeat(day).isEmpty(), "Two seats, three guests: the third goes home");
        decide(helper, c);
        helper.assertTrue(c.getWork().tavernSeat(day).isEmpty(), "Still no seat");

        // A player sits down on the seat of a: a goes home for the evening, even though another seat comes free.
        Cushion cushionOfA = ("e:" + first.getUUID()).equals(seatOfA) ? first : second;
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.startRiding(cushionOfA, true, false);
        decide(helper, a);
        helper.assertTrue(a.getWork().tavernSeat(day).isEmpty(), "Its seat is taken by a player: home");
        Cushion third = cushion(helper, new BlockPos(5, 1, 5), 0.0F);
        decide(helper, a, c);
        helper.assertTrue(a.getWork().tavernSeat(day).isEmpty(), "Once lost, no new seat that evening");
        helper.assertTrue(c.getWork().tavernSeat(day).equals(Optional.of("e:" + third.getUUID())), "A new free seat goes to the one waiting");
        player.stopRiding();

        // The Tavern Host's shift is over at 20:00: nothing is booked.
        Settler late = guest(helper, pantry, new BlockPos(4, 1, 7));
        TestAssignments.unassign(SettlementData.get(level), pantry);
        decide(helper, late, b);
        helper.assertTrue(late.getWork().tavernSeat(day).isEmpty(), "Host gone: no new seat");
        helper.assertTrue(b.getWork().tavernSeat(day).isPresent(), "A guest with a seat stays until bedtime");
        TestAssignments.unassign(SettlementData.get(level), pantry);
        all.forEach(Settler::discard);
        late.discard();
        first.discard();
        second.discard();
        third.discard();
        helper.succeed();
    }

    /** A guest walks to its seat, sits down looking the way of the Cushion, and nobody rides the Cushion. */
    private static void walkAndSit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        BlockPos pantry = pantry(helper);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        Cushion cushion = cushion(helper, new BlockPos(6, 1, 4), 90.0F);
        Settler guest = guest(helper, pantry, new BlockPos(3, 1, 4));
        guest.setNoAi(false);
        helper.succeedWhen(() -> {
            helper.assertTrue(guest.isSitting(), "Sits down at its seat, now at " + guest.position());
            Vec3 seat = new Vec3(cushion.getX(), cushion.getBoundingBox().maxY, cushion.getZ());
            helper.assertTrue(guest.position().distanceToSqr(seat) < 1.0E-6, "On the Cushion: " + guest.position() + " instead of " + seat);
            helper.assertTrue(guest.getYRot() == 90.0F && guest.yBodyRot == 90.0F, "Facing the way of the Cushion: " + guest.yBodyRot);
            helper.assertTrue(cushion.getPassengers().isEmpty(), "Nobody rides the Cushion");
            helper.assertTrue(guest.getBbHeight() < 1.5F, "The sitting hitbox");
            TestAssignments.unassign(SettlementData.get(level), pantry);
            guest.discard();
            host.discard();
            cushion.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** At bedtime a seated guest stands up and goes to bed. */
    private static void bedtime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        BlockPos pantry = pantry(helper);
        long day = DayClock.day(level);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        Cushion cushion = cushion(helper, new BlockPos(6, 1, 4), 0.0F);
        Settler guest = guest(helper, pantry, new BlockPos(3, 1, 4));
        guest.setNoAi(false);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(guest.isSitting(), "Sits down first, now at " + guest.position()))
                .thenExecute(() -> level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD),
                        day * DayClock.DAY_TICKS + 14600L))
                .thenWaitUntil(() -> helper.assertFalse(guest.isSitting(), "Stands up at bedtime"))
                .thenExecuteAfter(20, () -> {
                    helper.assertFalse(guest.isSitting(), "Does not go back to the seat");
                    TestAssignments.unassign(SettlementData.get(level), pantry);
                    guest.discard();
                    host.discard();
                    cushion.discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    /** Catch-up of the missed day before the clock's day for the settlers, one at a time. */
    static void catchUpYesterday(GameTestHelper helper, Settler... settlers) {
        ServerLevel level = helper.getLevel();
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), DayClock.DAY_TICKS) * DayClock.DAY_TICKS;
        for (Settler settler : settlers) {
            settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
            settler.getWork().setCatchUp(dayStart - DayClock.DAY_TICKS, dayStart);
            CatchUp.runNow(level, List.of(settler));
        }
    }

    /**
     * A missed evening: every member counts as present, a Tavern Host must be assigned, nobody is paid twice for a day,
     * the jukebox counts as in live play.
     */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        long missed = DayClock.day(level) - 1;
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        List<Settler> guests = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            guests.add(settler(helper, pantry, new BlockPos(3 + i, 1, 2)));
        }
        Settler[] guestArray = guests.toArray(new Settler[0]);

        catchUpYesterday(helper, guestArray);
        helper.assertTrue(guests.stream().allMatch(settler -> value(settler) == 0.0), "No Tavern Host assigned: nothing");

        host(helper, pantry, host);
        catchUpYesterday(helper, guestArray);
        helper.assertTrue(guests.stream().allMatch(settler -> Math.abs(value(settler) - 3.0) < EPSILON && settler.getWork().entertainment().tavernPaid(missed)),
                "Four members, the Tavern Host assigned: +3 each, got " + value(guests.get(0)));
        catchUpYesterday(helper, guestArray);
        helper.assertTrue(guests.stream().allMatch(settler -> Math.abs(value(settler) - 3.0) < EPSILON), "The same day is not paid twice");
        helper.assertTrue(value(host) == 0.0, "The Tavern Host was not caught up");
        end(helper, pantry, List.of(host));
        guests.forEach(Settler::discard);
        helper.succeed();
    }

    /** Catch-up with a jukebox: B alone for a small settlement, both conditions for a big one. */
    private static void catchUpJukebox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        Settler guest = settler(helper, pantry, new BlockPos(3, 1, 2));
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        catchUpYesterday(helper, guest);
        helper.assertTrue(Math.abs(value(guest) - 3.0) < EPSILON, "Two members, a jukebox with a disc: +3, got " + value(guest));

        Settler a = settler(helper, pantry, new BlockPos(4, 1, 2));
        Settler b = settler(helper, pantry, new BlockPos(5, 1, 2));
        catchUpYesterday(helper, a);
        helper.assertTrue(Math.abs(value(a) - 4.5) < EPSILON, "Four members and the jukebox: 3 × 1.5, got " + value(a));
        end(helper, pantry, List.of(host, guest, a, b));
        helper.succeed();
    }

    /** The jukebox is read in loaded chunks only: a chunk that is not loaded is neither loaded nor counted. */
    private static void jukeboxUnloadedChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos nowhere = new BlockPos(29_999_000, 64, 29_999_000);
        helper.assertFalse(level.hasChunkAt(nowhere), "Nobody loaded that chunk");
        helper.assertFalse(Tavern.jukeboxHoldsDisc(level, nowhere, 8), "An unloaded chunk holds no jukebox for the Tavern");
        helper.assertFalse(level.hasChunkAt(nowhere), "And looking does not load it");

        BlockPos pantry = pantry(helper);
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        helper.assertTrue(Tavern.jukeboxHoldsDisc(level, pantry, 8), "A loaded chunk is read");
        helper.succeed();
    }

    /**
     * Only the Tavern Host and the guests who booked a seat at this Pantry are paid. Everybody within 12 blocks counts for the
     * crowd, but a Tavern without seats does nothing, however many settlers rest nearby.
     */
    private static void onlySeatedGuestsPaid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper);
        long day = DayClock.day(level);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        Settler a = guest(helper, pantry, new BlockPos(3, 1, 2));
        Settler b = guest(helper, pantry, new BlockPos(4, 1, 2));
        Settler resting = guest(helper, pantry, new BlockPos(5, 1, 2));
        List<Settler> all = List.of(host, a, b, resting);

        decide(helper, a, b, resting);
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(all.stream().allMatch(settler -> value(settler) == 0.0), "No seats: the Tavern does nothing, not even for the Tavern Host");

        seat(helper, a, b);
        decide(helper, resting);
        helper.assertTrue(a.getWork().tavernSeat(day).isPresent() && b.getWork().tavernSeat(day).isPresent() && resting.getWork().tavernSeat(day).isEmpty(),
                "Two seats: two guests book, the third rests at home");
        Tavern.countAt(level, pantry, day, EVENING);
        helper.assertTrue(Math.abs(value(host) - 3.0) < EPSILON && Math.abs(value(a) - 3.0) < EPSILON && Math.abs(value(b) - 3.0) < EPSILON,
                "The host and the booked guests are paid: four are present, the resting settler counts for the crowd");
        helper.assertTrue(value(resting) == 0.0 && !resting.getWork().entertainment().tavernPaid(day), "The resting settler is not paid");
        end(helper, pantry, all);
        helper.succeed();
    }

    /**
     * A Pantry whose chunk is not loaded still hosts the missed evening: only the jukebox needs a loaded chunk. The settlement
     * stands in the data only, far away from the test.
     */
    private static void catchUpUnloadedPantry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos far = new BlockPos(29_999_000, 64, 29_999_000);
        TestSettlements.place(data, far, ModJobs.TAVERN_HOST.get(), 1);
        Settlement settlement = data.settlementAt(far).orElseThrow();
        helper.assertFalse(level.hasChunkAt(far), "Nobody loaded the chunk of the Pantry");

        List<Settler> members = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            members.add(ClerkGameTests.settler(helper, new BlockPos(2 + i, 1, 2), data, settlement));
        }
        Settler[] guests = members.toArray(new Settler[0]);
        long missed = DayClock.day(level) - 1;

        catchUpYesterday(helper, guests);
        helper.assertTrue(members.stream().allMatch(settler -> value(settler) == 0.0), "No Tavern Host assigned: nothing");

        TestAssignments.assign(data, far, UUID.randomUUID(), "Host", settlement.id(), List.of());
        catchUpYesterday(helper, guests);
        helper.assertTrue(members.stream().allMatch(settler -> Math.abs(value(settler) - 3.0) < EPSILON && settler.getWork().entertainment().tavernPaid(missed)),
                "The Pantry is not loaded, its host is assigned: four members, +3 each, got " + value(members.get(0)));
        helper.assertFalse(level.hasChunkAt(far), "And the catch-up did not load it");
        TestAssignments.unassign(data, far);
        members.forEach(Settler::discard);
        helper.succeed();
    }

    /** The server tick runs the count in the evening (environment civmod:evening) without any call from the test. */
    private static void minuteCount(GameTestHelper helper) {
        BlockPos pantry = pantry(helper);
        Settler host = settler(helper, pantry, new BlockPos(2, 1, 2));
        host(helper, pantry, host);
        List<Settler> all = new ArrayList<>(List.of(host));
        for (int i = 0; i < 3; i++) {
            all.add(settler(helper, pantry, new BlockPos(3 + i, 1, 2)));
        }
        seat(helper, all.subList(1, 4).toArray(new Settler[0]));
        helper.succeedWhen(() -> {
            helper.assertTrue(all.stream().allMatch(settler -> Math.abs(value(settler) - 3.0) < EPSILON), "Paid by the tick, day time " + DayClock.dayTime(helper.getLevel()) + ", host value " + value(host));
            end(helper, pantry, all);
        });
    }
}
