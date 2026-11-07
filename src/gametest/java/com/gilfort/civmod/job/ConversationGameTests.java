package com.gilfort.civmod.job;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.gametest.LumberjackGameTests;
import com.gilfort.civmod.gametest.TavernGameTests;
import com.gilfort.civmod.job.SocialGameTests.Rolls;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the conversations between settlers (vault {@code Systems/Entertainment.md}): the walk to the partner, a
 * walk that finds no way, the points, the cooldown and the daily limit, the reservation on both settlers, each state
 * that cannot be interrupted, a seated Tavern guest, a Bard stand-in (heard first, then talked), the speech bubble,
 * the step the settlers go back to, and the places where nothing is simulated. The rolls are scripted. Most tests have
 * no AI and drive the conversation tick by tick; the walk test lets the settlers walk.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ConversationGameTests {

    private static final double CONVERSATION = SocialGameTests.CONVERSATION;
    private static final double NOTHING = SocialGameTests.NOTHING;
    /** Ticks enough to walk up, talk for 120 and end. */
    private static final int WHOLE = 130;

    private ConversationGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "conversation_walk", ConversationGameTests::walk);
            register(helper, "conversation_blocked_walk", ConversationGameTests::blockedWalk);
            register(helper, "conversation_points", ConversationGameTests::points);
            register(helper, "conversation_cooldown_limit", ConversationGameTests::cooldownAndLimit);
            register(helper, "conversation_reserved", ConversationGameTests::reserved);
            register(helper, "conversation_not_interruptible", ConversationGameTests::notInterruptible);
            register(helper, "conversation_seated_guest_partner", ConversationGameTests::seatedGuestPartner);
            register(helper, "conversation_seated_guest_never_rolls", ConversationGameTests::seatedGuestNeverRolls);
            register(helper, "conversation_bard_stand_in", ConversationGameTests::heardThenTalked);
            register(helper, "conversation_bubble", ConversationGameTests::bubble);
            register(helper, "conversation_resumes_step", ConversationGameTests::resumesStep);
            register(helper, "conversation_partner_leaves", ConversationGameTests::partnerLeaves);
            register(helper, "conversation_no_players_no_catch_up", ConversationGameTests::playersAndCatchUp);
            register(helper, "conversation_config_switch", ConversationGameTests::configSwitch);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Lets the conversation run for some ticks, the settler that rolled first. */
    private static void run(int ticks, Settler... settlers) {
        for (int i = 0; i < ticks; i++) {
            for (Settler settler : settlers) {
                settler.getWork().socialTick();
            }
        }
    }

    private static double value(Settler settler) {
        return settler.getWork().entertainment().value();
    }

    /** No points and no mark of a conversation (the decay mark of the day may be set). */
    private static boolean untouched(Settler settler) {
        Entertainment e = settler.getWork().entertainment();
        return e.value() == 0.0 && e.talk().equals(Entertainment.Talk.NONE);
    }

    private static boolean talking(Settler a, Settler b) {
        return a.getWork().social().talkingTo().equals(java.util.Optional.of(b)) && b.getWork().social().talkingTo().equals(java.util.Optional.of(a));
    }

    private static boolean free(Settler... settlers) {
        for (Settler settler : settlers) {
            if (settler.getWork().social().inConversation() || settler.getWork().talking()) {
                return false;
            }
        }
        return true;
    }

    /** Turns the clock of the cooldown of a settler back by some ticks (the last conversation was that much longer ago). */
    private static void age(Settler settler, long ticks) {
        Entertainment e = settler.getWork().entertainment();
        Entertainment.Talk t = e.talk();
        settler.getWork().setEntertainment(new Entertainment(e.value(), e.decayedDay(), e.today(), e.todayDay(), e.tavernDay(), e.recordDay(), e.roundDay(), e.stageDay(),
                new Entertainment.Talk(t.day(), t.count(), t.lastTick() - ticks, t.points())));
    }

    /** Lets one conversation run from the roll to its end: {@code a} rolls, {@code b} is the partner next to it. */
    private static void talkOnce(ServerLevel level, Rolls rolls, Settler a, Settler b) {
        rolls.value = CONVERSATION;
        a.getWork().decideNow(level);
        rolls.value = NOTHING;
        run(WHOLE, a, b);
    }

    private static boolean facing(Settler who, Settler target) {
        double yaw = Mth.atan2(target.getZ() - who.getZ(), target.getX() - who.getX()) * (180.0 / Math.PI) - 90.0;
        return Math.abs(Mth.wrapDegrees(who.yBodyRot - yaw)) < 5.0;
    }

    private static boolean shown(Settler settler, int count) {
        ThoughtBubble.Show bubble = new ThoughtBubble.Show(settler.getId(), ThoughtBubble.CONVERSATION, Items.PAPER, true);
        return settler.getWork().recentThoughts().stream().filter(bubble::equals).count() == count;
    }

    /** What the walking test saw. */
    private static final class Seen {
        private boolean started;
        private boolean talked;
        private double distance;
        private double partnerMoved;
        private boolean partnerFaced;
        private boolean bubbles;
    }

    /**
     * The settlers have AI: the one whose roll succeeded walks to 1 block from the other, who stops and turns; they talk
     * about 6 seconds; each gets 0.5 and both are free again.
     */
    private static void walk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(NOTHING);
        Settler roller = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler partner = SocialGameTests.settler(helper, pantry, 7, 4, rolls);
        roller.setNoAi(false);
        partner.setNoAi(false);
        Seen seen = new Seen();
        helper.succeedWhen(() -> {
            if (!seen.started) {
                // A path can only be found from the ground, so wait until both stand on it.
                helper.assertTrue(roller.onGround() && partner.onGround(), "The settlers land");
                seen.started = true;
                rolls.value = CONVERSATION;
                roller.getWork().decideNow(level);
                rolls.value = NOTHING;
                helper.assertTrue(talking(roller, partner), "The roll pairs the two: reserved on both");
                helper.assertTrue(partner.getWork().talking() && roller.getWork().talking(), "Both are in the conversation's activity");
            }
            if (!seen.talked && roller.getWork().social().isTalking()) {
                seen.talked = true;
                seen.distance = Math.sqrt(roller.distanceToSqr(partner));
                seen.partnerMoved = partner.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(7, 1, 4))));
                seen.partnerFaced = facing(partner, roller);
                seen.bubbles = shown(roller, 1) && shown(partner, 1);
            }
            helper.assertTrue(seen.talked, "The settler that rolled reaches its partner, now at " + roller.position() + ", partner at "
                    + partner.position() + ", " + roller.getWork().activity() + ", with " + roller.getWork().social().talkingTo().isPresent()
                    + ", navigation done " + roller.getNavigation().isDone());
            helper.assertTrue(seen.distance <= 1.6, "Talks within about 1 block: " + seen.distance);
            helper.assertTrue(seen.partnerMoved < 0.8, "The partner stopped where it was: moved " + seen.partnerMoved);
            helper.assertTrue(seen.partnerFaced, "The partner turned towards the one who came");
            helper.assertTrue(seen.bubbles, "A speech bubble above both");
            helper.assertTrue(free(roller, partner), "The conversation ends after about 6 seconds");
            helper.assertTrue(value(roller) == 0.5 && value(partner) == 0.5, "0.5 each: " + value(roller) + " and " + value(partner));
            roller.discard();
            partner.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A walk that comes nowhere is dropped after 5 seconds, without points and without cooldown; the two are free to talk again. */
    private static void blockedWalk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(CONVERSATION);
        Settler roller = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler partner = SocialGameTests.settler(helper, pantry, 7, 4, rolls);
        // Without AI the roller cannot get anywhere: as good as a walk with no way.
        roller.getWork().decideNow(level);
        rolls.value = NOTHING;
        helper.assertTrue(talking(roller, partner) && roller.getWork().talking(), "The roller walks to its partner");
        run(99, roller, partner);
        helper.assertTrue(talking(roller, partner), "Still trying after 99 ticks");
        run(1, roller, partner);
        helper.assertTrue(free(roller, partner), "Dropped after 100 ticks (5 seconds)");
        helper.assertTrue(untouched(roller) && untouched(partner), "No points, no cooldown, no count: " + roller.getWork().entertainment());
        rolls.value = CONVERSATION;
        roller.getWork().decideNow(level);
        helper.assertTrue(talking(roller, partner), "The same pair may try again at once");
        SocialGameTests.end(roller, partner);
        helper.succeed();
    }

    /** Each gets 0.5, counted for the day, with the cooldown marked at the end; the partner's mark is the same. */
    private static void points(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(NOTHING);
        Settler a = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler b = SocialGameTests.settler(helper, pantry, 3, 4, rolls);
        long day = DayClock.day(level);
        long start = level.getGameTime();
        rolls.value = CONVERSATION;
        a.getWork().decideNow(level);
        rolls.value = NOTHING;
        helper.assertTrue(talking(a, b), "A rolls and pairs with B");
        run(5, a, b);
        helper.assertTrue(a.getWork().social().isTalking(), "Next to each other: they talk at once");
        helper.assertTrue(a.isSwinging() && b.isSwinging(), "An arm swing from both when they start to talk");
        helper.assertTrue(value(a) == 0.0 && value(b) == 0.0, "No points before the end");
        run(WHOLE, a, b);
        helper.assertTrue(free(a, b), "Over");
        for (Settler settler : List.of(a, b)) {
            Entertainment.Talk talk = settler.getWork().entertainment().talk();
            helper.assertTrue(value(settler) == 0.5 && talk.on(day) == 1 && talk.pointsOn(day) == 0.5 && talk.lastTick() == start,
                    "0.5 points, one conversation today, marked at the end: " + settler.getWork().entertainment());
        }
        SocialGameTests.end(a, b);
        helper.succeed();
    }

    /** The cooldown of 2000 ticks and the limit of 2 a day, for the settler that rolls and for the one that is addressed. */
    private static void cooldownAndLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(NOTHING);
        Settler a = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler b = SocialGameTests.settler(helper, pantry, 3, 4, rolls);
        Settler c = SocialGameTests.settler(helper, pantry, 2, 6, rolls);
        long day = DayClock.day(level);

        talkOnce(level, rolls, a, b);
        helper.assertTrue(free(a, b) && value(a) == 0.5, "The first conversation happened");

        // Right after: A is on cooldown and does not start; B does not start; C does not pick A or B.
        rolls.value = CONVERSATION;
        a.getWork().decideNow(level);
        b.getWork().decideNow(level);
        helper.assertTrue(free(a, b), "A and B are on cooldown: nobody starts, nobody is picked");
        c.getWork().decideNow(level);
        helper.assertTrue(free(a, b, c), "C finds only settlers on cooldown");

        // 1999 ticks after: still on cooldown; 2000: ready.
        age(a, 1999);
        age(b, 1999);
        a.getWork().decideNow(level);
        helper.assertTrue(free(a, b), "1999 ticks after: cooldown");
        age(a, 1);
        age(b, 1);
        // C is fresh, but A and B are still too recent to be picked; only a ready pair talks.
        a.getWork().decideNow(level);
        helper.assertTrue(talking(a, b), "2000 ticks after: ready again");
        rolls.value = NOTHING;
        run(WHOLE, a, b);
        helper.assertTrue(free(a, b) && value(a) == 1.0 && a.getWork().entertainment().talk().on(day) == 2, "The second conversation of the day");

        // The limit: the cooldown is over but A and B talked twice today.
        age(a, 5000);
        age(b, 5000);
        rolls.value = CONVERSATION;
        a.getWork().decideNow(level);
        helper.assertTrue(free(a, b), "Two conversations a day at most, for the roller");
        c.getWork().decideNow(level);
        helper.assertTrue(free(a, b, c), "...and a settler at its limit is not picked either");

        // The partner's limit alone is enough: C is fresh, B is at its limit.
        Settler d = SocialGameTests.settler(helper, pantry, 3, 6, rolls);
        d.getWork().decideNow(level);
        helper.assertTrue(talking(c, d) || talking(d, c), "Fresh settlers still talk: the limit is per settler");
        rolls.value = NOTHING;
        run(WHOLE, c, d);

        // The next day starts at 0 again.
        Entertainment e = a.getWork().entertainment();
        a.getWork().setEntertainment(new Entertainment(e.value(), e.decayedDay(), e.today(), e.todayDay(), e.tavernDay(), e.recordDay(), e.roundDay(), e.stageDay(),
                new Entertainment.Talk(day - 1, 2, e.talk().lastTick() - 5000, e.talk().points())));
        Entertainment f = b.getWork().entertainment();
        b.getWork().setEntertainment(new Entertainment(f.value(), f.decayedDay(), f.today(), f.todayDay(), f.tavernDay(), f.recordDay(), f.roundDay(), f.stageDay(),
                new Entertainment.Talk(day - 1, 2, f.talk().lastTick() - 5000, f.talk().points())));
        rolls.value = CONVERSATION;
        a.getWork().decideNow(level);
        helper.assertTrue(talking(a, b), "A new day: the count starts again");
        SocialGameTests.end(a, b, c, d);
        helper.succeed();
    }

    /** Two rolls at the same moment never pair a settler twice: the first reserves both, the second finds the partner busy. */
    private static void reserved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(CONVERSATION);
        Settler a = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler b = SocialGameTests.settler(helper, pantry, 4, 4, rolls);
        Settler c = SocialGameTests.settler(helper, pantry, 6, 4, rolls);
        a.getWork().decideNow(level);
        helper.assertTrue(talking(a, b), "A, B and C are in a row; A picks the nearest, B");
        c.getWork().decideNow(level);
        helper.assertTrue(c.getWork().social().talkingTo().isEmpty(), "C rolls at the same moment, but B is taken and A is taken: " + c.getWork().social().talkingTo());
        helper.assertTrue(talking(a, b), "A and B keep their conversation");
        b.getWork().decideNow(level);
        helper.assertTrue(talking(a, b), "B rolls too, but is held by its conversation");
        SocialGameTests.end(a, b, c);
        helper.succeed();
    }

    /** Neither a sleeper, nor one away on a Mission, nor a Clerk with a customer at the counter takes part. */
    private static void notInterruptible(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(CONVERSATION);

        // Sleeping.
        BlockPos head = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(head.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
        Settler sleeper = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        helper.assertTrue(sleeper.startSleeping(head) && sleeper.isSleeping(), "The settler sleeps");
        Settler awake = SocialGameTests.settler(helper, pantry, 4, 4, rolls);
        awake.getWork().decideNow(level);
        helper.assertTrue(free(awake, sleeper), "Nobody talks to a sleeper");
        SocialGameTests.end(sleeper);

        // Away on a Mission, and walking out to one.
        BlockPos board = helper.absolutePos(new BlockPos(10, 1, 10));
        MissionTrip leaving = new MissionTrip(board, CivMod.id("invite_villagers_plains"), 100_000L);
        Settler walkingOut = SocialGameTests.settler(helper, pantry, 5, 4, rolls);
        walkingOut.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                java.util.Optional.of(leaving)));
        awake.getWork().decideNow(level);
        helper.assertTrue(free(awake, walkingOut), "Nobody talks to a settler walking out to a Mission");
        SocialGameTests.end(walkingOut);
        Settler away = SocialGameTests.settler(helper, pantry, 5, 4, rolls);
        away.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                java.util.Optional.of(leaving.withExit(helper.absolutePos(new BlockPos(5, 1, 4))))));
        awake.getWork().decideNow(level);
        helper.assertTrue(free(awake, away), "Nobody talks to a settler away on a Mission");
        away.getWork().decideNow(level);
        helper.assertTrue(free(awake, away), "...and it talks to nobody");
        SocialGameTests.end(away);

        // A Clerk with a customer at the counter; without one it talks.
        TestSettlements.setBlock(helper, new BlockPos(8, 1, 8), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerkBlock = helper.absolutePos(new BlockPos(8, 1, 8));
        Settler clerk = SocialGameTests.settler(helper, pantry, 5, 4, rolls);
        clerk.addJob(new SettlerJob(ModJobs.CLERK.get(), clerkBlock));
        ClerkCounter.serve(level, clerkBlock, UUID.randomUUID());
        awake.getWork().decideNow(level);
        helper.assertTrue(free(awake, clerk), "Nobody talks to a Clerk with a customer at the counter");
        clerk.getWork().decideNow(level);
        helper.assertTrue(free(awake, clerk), "...and it talks to nobody");
        SocialGameTests.end(awake, clerk);
        helper.succeed();
    }

    /** A guest sits on a Cushion at the Pantry's Tavern after work; the Tavern Host stands next to it. */
    private static void seatedGuest(GameTestHelper helper, boolean guestRolls) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        BlockPos pantry = TavernGameTests.pantry(helper);
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 2));
        TavernGameTests.host(helper, pantry, host);
        Cushion cushion = TavernGameTests.cushion(helper, new BlockPos(6, 1, 4), 90.0F);
        Rolls rolls = new Rolls(NOTHING);
        host.getWork().social().script(rolls);
        Settler guest = TavernGameTests.settler(helper, pantry, new BlockPos(3, 1, 4));
        guest.getFood().setLevel(com.gilfort.civmod.food.SettlerFood.MAX);
        guest.getWork().social().script(rolls);
        guest.setNoAi(false);
        helper.succeedWhen(() -> {
            helper.assertTrue(guest.isSitting() && guest.getWork().seatedGuest(), "Sits down at its seat, now at " + guest.position());
            guest.setNoAi(true);
            host.snapTo(guest.getX() + 1.0, guest.getY(), guest.getZ(), 0.0F, 0.0F);
            rolls.value = CONVERSATION;
            if (guestRolls) {
                guest.getWork().decideNow(level);
                helper.assertTrue(free(host, guest), "A seated guest never starts a conversation");
                helper.assertTrue(rolls.calls > 0, "(it did roll)");
            } else {
                host.getWork().decideNow(level);
                helper.assertTrue(talking(host, guest), "A seated guest can be the partner of a conversation");
                rolls.value = NOTHING;
                run(5, host, guest);
                helper.assertTrue(host.getWork().social().isTalking() && guest.isSitting() && guest.getWork().seatedGuest(),
                        "The guest stays seated and keeps its activity");
                run(WHOLE, host, guest);
                helper.assertTrue(free(host, guest) && guest.isSitting() && value(guest) == 0.5 && value(host) == 0.5,
                        "Over: the guest still sits, both got 0.5: " + value(guest) + ", " + value(host));
            }
            TestAssignments.unassign(SettlementData.get(level), pantry);
            guest.discard();
            host.discard();
            cushion.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    private static void seatedGuestPartner(GameTestHelper helper) {
        seatedGuest(helper, false);
    }

    private static void seatedGuestNeverRolls(GameTestHelper helper) {
        seatedGuest(helper, true);
    }

    /**
     * Heard first, then talked: the Bard is an ordinary settler, so its +1 and the +0.5 of the talk both count. The Bard
     * is not on this branch; the +1 comes from another source of Entertainment ({@code add}), which counts the same way.
     */
    private static void heardThenTalked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(NOTHING);
        Settler listener = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler bard = SocialGameTests.settler(helper, pantry, 3, 4, rolls);
        long day = DayClock.day(level);
        listener.getWork().setEntertainment(listener.getWork().entertainment().add(1.0, day));
        talkOnce(level, rolls, listener, bard);
        helper.assertTrue(free(listener, bard), "Over");
        Entertainment e = listener.getWork().entertainment();
        helper.assertTrue(e.value() == 1.5 && e.pointsOn(day) == 1.5 && e.talk().pointsOn(day) == 0.5,
                "Heard (+1) then talked (+0.5): 1.5 in total, 0.5 of it from the talk: " + e);
        helper.assertTrue(value(bard) == 0.5, "The one talked to gets its 0.5 as well");
        SocialGameTests.end(listener, bard);
        helper.succeed();
    }

    /** A speech bubble above both (a paper, no problem mark), sent again while the talk lasts. */
    private static void bubble(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(NOTHING);
        Settler a = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler b = SocialGameTests.settler(helper, pantry, 3, 4, rolls);
        helper.assertFalse(ThoughtBubble.CONVERSATION.problem(), "An activity bubble: no red mark, hidden by 'Problems only'");
        rolls.value = CONVERSATION;
        a.getWork().decideNow(level);
        rolls.value = NOTHING;
        helper.assertTrue(shown(a, 0) && shown(b, 0), "Nothing yet while the roller walks");
        run(2, a, b);
        helper.assertTrue(shown(a, 1) && shown(b, 1), "Above both when they start to talk");
        run(58, a, b);
        helper.assertTrue(shown(a, 1) && shown(b, 1), "The first one still runs");
        run(1, a, b);
        helper.assertTrue(shown(a, 2) && shown(b, 2), "Sent again after the 60 ticks the client shows one");
        run(WHOLE, a, b);
        helper.assertTrue(shown(a, 2) && shown(b, 2) && free(a, b), "Two bubbles over the 6 seconds");
        SocialGameTests.end(a, b);
        helper.succeed();
    }

    /** Both go back to their previous step: a Clerk walking to its counter walks on after the talk. */
    private static void resumesStep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(NOTHING);
        TestSettlements.setBlock(helper, new BlockPos(8, 1, 8), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerkBlock = helper.absolutePos(new BlockPos(8, 1, 8));
        Settler clerk = SocialGameTests.settler(helper, pantry, 3, 4, rolls);
        clerk.addJob(new SettlerJob(ModJobs.CLERK.get(), clerkBlock));
        Settler friend = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        clerk.getWork().decideNow(level);
        Schedule.Activity before = clerk.getWork().activity();
        java.util.Optional<SettlerJob> job = clerk.getWork().activeJob();
        helper.assertTrue(before == Schedule.Activity.JOB && job.isPresent(), "The Clerk is on its way to work: " + before);
        rolls.value = CONVERSATION;
        friend.getWork().decideNow(level);
        rolls.value = NOTHING;
        helper.assertTrue(talking(friend, clerk) && clerk.getWork().talking(), "The Clerk is stopped");
        run(WHOLE, friend, clerk);
        helper.assertTrue(free(friend, clerk), "Over");
        helper.assertTrue(clerk.getWork().activity() == before && clerk.getWork().activeJob().equals(job),
                "The Clerk is back on its job: " + clerk.getWork().activity());
        helper.assertTrue(friend.getWork().activity() == Schedule.Activity.IDLE, "The friend had nothing to do and has not now: " + friend.getWork().activity());
        // A decision after the talk works as before.
        clerk.getWork().decideNow(level);
        helper.assertTrue(clerk.getWork().activity() == before, "The next decision goes on with the job");
        SocialGameTests.end(friend, clerk);
        helper.succeed();
    }

    /** The partner is gone halfway: the conversation is dropped without points and without a cooldown, the roller is free. */
    private static void partnerLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(NOTHING);
        Settler a = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler b = SocialGameTests.settler(helper, pantry, 3, 4, rolls);
        rolls.value = CONVERSATION;
        a.getWork().decideNow(level);
        rolls.value = NOTHING;
        run(50, a, b);
        helper.assertTrue(a.getWork().social().isTalking(), "Talking");
        b.discard();
        run(1, a);
        helper.assertTrue(free(a), "A is free once B is gone");
        helper.assertTrue(untouched(a), "No points, no cooldown: " + a.getWork().entertainment());
        SocialGameTests.end(a);
        helper.succeed();
    }

    /** A player never takes part, and a quiet decision (catch-up) rolls nothing and starts nothing. */
    private static void playersAndCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(CONVERSATION);
        Settler a = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Player player = SocialGameTests.player(helper, GameType.SURVIVAL, 3, 4);
        a.getWork().decideNow(level);
        helper.assertTrue(rolls.calls == 1 && free(a), "Only a player near: the roll is made and nothing happens");
        player.discard();
        Settler b = SocialGameTests.settler(helper, pantry, 4, 4, rolls);
        int calls = rolls.calls;
        a.getWork().decideQuietly(level);
        b.getWork().decideQuietly(level);
        helper.assertTrue(rolls.calls == calls && free(a, b), "No conversation while catching up");
        helper.assertTrue(untouched(a) && untouched(b), "...and no points");
        SocialGameTests.end(a, b);
        helper.succeed();
    }

    /** With the config {@code socialLife} off nobody talks. */
    private static void configSwitch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(CONVERSATION);
        Settler a = SocialGameTests.settler(helper, pantry, 2, 4, rolls);
        Settler b = SocialGameTests.settler(helper, pantry, 3, 4, rolls);
        boolean was = CivModConfig.SOCIAL_LIFE.get();
        try {
            CivModConfig.SOCIAL_LIFE.set(false);
            a.getWork().decideNow(level);
            helper.assertTrue(free(a, b) && rolls.calls == 0, "Switched off: no check, no conversation");
            CivModConfig.SOCIAL_LIFE.set(true);
            a.getWork().decideNow(level);
            helper.assertTrue(talking(a, b), "Switched on: they talk");
        } finally {
            CivModConfig.SOCIAL_LIFE.set(was);
        }
        SocialGameTests.end(a, b);
        helper.succeed();
    }
}
