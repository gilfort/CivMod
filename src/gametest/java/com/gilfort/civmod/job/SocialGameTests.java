package com.gilfort.civmod.job;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.gametest.LumberjackGameTests;
import com.gilfort.civmod.gametest.TavernGameTests;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the meeting check and the greetings (vault {@code Systems/Entertainment.md}): the range, line of sight,
 * the pair cooldown, players, a seated Tavern guest, the states that cannot be interrupted, the config switch and the
 * catch-up. The rolls are scripted; settlers have no AI, so every {@code decideNow} runs the check once.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SocialGameTests {

    /** A roll in the greeting share (0.20 to 0.50). */
    static final double GREETING = 0.35;
    /** A roll in the conversation share (below 0.20). */
    static final double CONVERSATION = 0.1;
    /** A roll in the share of nothing (0.50 and above). */
    static final double NOTHING = 0.75;

    private SocialGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "social_greeting_range", SocialGameTests::greetingRange);
            register(helper, "social_greeting_wall", SocialGameTests::greetingWall);
            register(helper, "social_greeting_pair_cooldown", SocialGameTests::pairCooldown);
            register(helper, "social_greeting_rolls", SocialGameTests::rolls);
            register(helper, "social_greeting_player", SocialGameTests::playerGreeted);
            register(helper, "social_greeting_other_settlement", SocialGameTests::otherSettlement);
            register(helper, "social_seated_guest_greets", SocialGameTests::seatedGuestGreets);
            register(helper, "social_seated_guest_is_greeted", SocialGameTests::seatedGuestIsGreeted);
            register(helper, "social_not_interruptible", SocialGameTests::notInterruptible);
            register(helper, "social_config_switch", SocialGameTests::configSwitch);
            register(helper, "social_catch_up", SocialGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The scripted rolls of a settler: always the same number, counted. */
    static final class Rolls implements DoubleSupplier {
        double value;
        int calls;

        Rolls(double value) {
            this.value = value;
        }

        @Override
        public double getAsDouble() {
            calls++;
            return value;
        }
    }

    /** A fed settler of the Pantry's settlement with its rolls scripted. */
    static Settler settler(GameTestHelper helper, BlockPos pantry, int x, int z, Rolls rolls) {
        Settler settler = TavernGameTests.settler(helper, pantry, new BlockPos(x, 1, z));
        settler.getFood().setLevel(SettlerFood.MAX);
        settler.getWork().social().script(rolls);
        return settler;
    }

    private static boolean looksAt(Settler settler, Settler other) {
        return settler.getWork().social().looking().equals(java.util.Optional.of(other.getUUID()));
    }

    /** Lets the head turn run out, as it does over the next ticks. */
    private static void relax(Settler... settlers) {
        for (Settler settler : settlers) {
            for (int i = 0; i <= SettlerSocial.LOOK_TICKS; i++) {
                settler.getWork().socialTick();
            }
        }
    }

    static void end(Settler... settlers) {
        for (Settler settler : settlers) {
            settler.discard();
        }
    }

    /** A greeting works at the full 6 blocks, not at 7: head turn towards the other and one arm swing. */
    private static void greetingRange(GameTestHelper helper) {
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(GREETING);
        Settler near = settler(helper, pantry, 2, 4, rolls);
        Settler six = settler(helper, pantry, 8, 4, rolls);
        Settler far = settler(helper, pantry, 2, 12, rolls);
        Settler seven = settler(helper, pantry, 9, 12, rolls);
        near.getWork().decideNow(helper.getLevel());
        helper.assertTrue(looksAt(near, six), "Greets the one 6 blocks away");
        helper.assertTrue(near.isSwinging(), "One arm swing");
        far.getWork().decideNow(helper.getLevel());
        helper.assertTrue(far.getWork().social().looking().isEmpty() && !far.isSwinging(), "No greeting 7 blocks away");
        helper.assertTrue(rolls.calls == 1, "The one 7 blocks away is not even rolled for: " + rolls.calls);
        end(near, six, far, seven);
        helper.succeed();
    }

    /** A wall between the two blocks the greeting; without the wall they greet. */
    private static void greetingWall(GameTestHelper helper) {
        BlockPos pantry = TavernGameTests.pantry(helper);
        for (int y = 1; y <= 3; y++) {
            for (int z = 2; z <= 6; z++) {
                helper.setBlock(new BlockPos(4, y, z), Blocks.STONE);
            }
        }
        Rolls rolls = new Rolls(GREETING);
        Settler west = settler(helper, pantry, 2, 4, rolls);
        Settler east = settler(helper, pantry, 6, 4, rolls);
        west.getWork().decideNow(helper.getLevel());
        helper.assertTrue(rolls.calls == 1, "The roll is made: " + rolls.calls);
        helper.assertTrue(west.getWork().social().looking().isEmpty() && !west.isSwinging(), "No greeting through a wall");
        helper.assertFalse(west.getWork().social().onCooldown(east.getUUID(), helper.getLevel().getGameTime(), com.gilfort.civmod.social.SocialRules.get()),
                "A blocked greeting starts no cooldown");
        for (int y = 1; y <= 3; y++) {
            for (int z = 2; z <= 6; z++) {
                helper.setBlock(new BlockPos(4, y, z), Blocks.AIR);
            }
        }
        west.getWork().decideNow(helper.getLevel());
        helper.assertTrue(looksAt(west, east), "Greets once the wall is gone");
        end(west, east);
        helper.succeed();
    }

    /** The pair greets once per cooldown, from both sides; a third settler is still greeted. */
    private static void pairCooldown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(GREETING);
        Settler a = settler(helper, pantry, 2, 4, rolls);
        Settler b = settler(helper, pantry, 5, 4, rolls);
        Settler c = settler(helper, pantry, 2, 8, rolls);
        a.getWork().decideNow(level);
        helper.assertTrue(looksAt(a, b), "A greets the nearest, B");
        long now = level.getGameTime();
        com.gilfort.civmod.social.SocialRules rules = com.gilfort.civmod.social.SocialRules.get();
        helper.assertTrue(a.getWork().social().onCooldown(b.getUUID(), now, rules) && b.getWork().social().onCooldown(a.getUUID(), now, rules),
                "The cooldown holds on both sides");
        helper.assertFalse(a.getWork().social().onCooldown(c.getUUID(), now, rules), "Not with C");

        relax(a);
        a.getWork().decideNow(level);
        helper.assertTrue(looksAt(a, c) && rolls.calls == 2, "A skips B (no roll) and greets C: " + rolls.calls + " rolls");
        relax(a);
        a.getWork().decideNow(level);
        helper.assertTrue(a.getWork().social().looking().isEmpty() && rolls.calls == 2, "Both are on cooldown: no roll, no greeting");

        b.getWork().decideNow(level);
        helper.assertTrue(looksAt(b, c), "B does not greet A back; it greets C, the pair it has not greeted: " + b.getWork().social().looking());
        end(a, b, c);
        helper.succeed();
    }

    /** The shares of the roll: a conversation roll greets nobody (a player never talks), nothing is nothing, a greeting greets. */
    private static void rolls(GameTestHelper helper) {
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(CONVERSATION);
        Settler a = settler(helper, pantry, 2, 4, rolls);
        Player player = player(helper, GameType.SURVIVAL, 3, 4);
        a.getWork().decideNow(helper.getLevel());
        helper.assertTrue(rolls.calls == 1, "One roll for the player found: " + rolls.calls);
        helper.assertTrue(a.getWork().social().looking().isEmpty() && !a.isSwinging() && !a.getWork().social().inConversation(),
                "The conversation share greets nobody and never talks to a player");
        rolls.value = NOTHING;
        a.getWork().decideNow(helper.getLevel());
        helper.assertTrue(a.getWork().social().looking().isEmpty() && !a.isSwinging(), "Nothing is nothing");
        Settler b = settler(helper, pantry, 5, 4, rolls);
        rolls.value = GREETING;
        a.getWork().decideNow(helper.getLevel());
        helper.assertTrue(a.getWork().social().looking().isPresent(), "The greeting share greets");
        helper.assertTrue(b.getWork().social().looking().isEmpty(), "Only the one that rolled turns its head");
        player.discard();
        end(a, b);
        helper.succeed();
    }

    /** A mock player standing in the level at a position. */
    static Player player(GameTestHelper helper, GameType mode, int x, int z) {
        Player player = helper.makeMockPlayer(mode);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, 1, z)));
        player.snapTo(spot.x, spot.y, spot.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(player);
        return player;
    }

    /** A player in range and in sight is greeted at the greeting share, once per cooldown; a spectator is not. */
    private static void playerGreeted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(GREETING);
        Settler settler = settler(helper, pantry, 2, 4, rolls);
        Player spectator = player(helper, GameType.SPECTATOR, 6, 4);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().social().looking().isEmpty() && rolls.calls == 0, "A spectator is not greeted");
        spectator.discard();
        Player player = player(helper, GameType.SURVIVAL, 6, 4);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().social().looking().equals(java.util.Optional.of(player.getUUID())), "The player is greeted");
        helper.assertTrue(settler.isSwinging(), "With an arm swing");
        relax(settler);
        int calls = rolls.calls;
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().social().looking().isEmpty() && rolls.calls == calls, "Not again within the cooldown");
        player.discard();
        end(settler);
        helper.succeed();
    }

    /** A settler of another settlement is not greeted. */
    private static void otherSettlement(GameTestHelper helper) {
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(GREETING);
        Settler member = settler(helper, pantry, 2, 4, rolls);
        Settler stranger = TavernGameTests.settler(helper, pantry, new BlockPos(5, 1, 4));
        stranger.getFood().setLevel(SettlerFood.MAX);
        SettlementData data = SettlementData.get(helper.getLevel());
        stranger.joinSettlement(data, TestSettlements.place(data, helper.absolutePos(new BlockPos(200, 1, 200)), ModJobs.LUMBERJACK.get(), 1)
                .settlement().orElseThrow());
        helper.assertTrue(stranger.getSettlementId() != member.getSettlementId(), "Two settlements");
        member.getWork().decideNow(helper.getLevel());
        helper.assertTrue(member.getWork().social().looking().isEmpty() && rolls.calls == 0, "Not a settler of its settlement");
        Settler homeless = TavernGameTests.settler(helper, pantry, new BlockPos(4, 1, 4));
        homeless.becomeHomeless();
        member.getWork().decideNow(helper.getLevel());
        helper.assertTrue(member.getWork().social().looking().isEmpty() && rolls.calls == 0, "Nor a homeless one");
        end(member, stranger, homeless);
        helper.succeed();
    }

    /** A guest sits on a Cushion at the Pantry's Tavern after work; the Tavern Host and the guest greet or are greeted. */
    private static void seatedGuest(GameTestHelper helper, boolean guestRolls) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(15, 0, 15));
        BlockPos pantry = TavernGameTests.pantry(helper);
        Settler host = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 2));
        TavernGameTests.host(helper, pantry, host);
        Cushion cushion = TavernGameTests.cushion(helper, new BlockPos(6, 1, 4), 90.0F);
        // While the guest walks with AI both roll for nothing, so no greeting happens by chance and starts a cooldown.
        Rolls rolls = new Rolls(NOTHING);
        host.getWork().social().script(rolls);
        Settler guest = TavernGameTests.settler(helper, pantry, new BlockPos(3, 1, 4));
        guest.getFood().setLevel(SettlerFood.MAX);
        guest.getWork().social().script(rolls);
        guest.setNoAi(false);
        helper.succeedWhen(() -> {
            helper.assertTrue(guest.isSitting(), "Sits down at its seat, now at " + guest.position());
            guest.setNoAi(true);
            Settler roller = guestRolls ? guest : host;
            Settler other = guestRolls ? host : guest;
            rolls.value = GREETING;
            roller.getWork().decideNow(level);
            helper.assertTrue(looksAt(roller, other), (guestRolls ? "The seated guest greets the host" : "The host greets the seated guest")
                    + ": " + roller.getWork().social().looking());
            helper.assertTrue(guest.isSitting(), "The guest stays seated");
            TestAssignments.unassign(SettlementData.get(level), pantry);
            guest.discard();
            host.discard();
            cushion.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A seated Tavern guest takes part in greetings: it greets, and it stays seated. */
    private static void seatedGuestGreets(GameTestHelper helper) {
        seatedGuest(helper, true);
    }

    /** A seated Tavern guest is greeted. */
    private static void seatedGuestIsGreeted(GameTestHelper helper) {
        seatedGuest(helper, false);
    }

    /** One settler for each state that cannot be interrupted, a friend in sight; and the controls that can. */
    private static void notInterruptible(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(GREETING);
        Settler friend = settler(helper, pantry, 5, 4, rolls);

        // Control: a free settler greets.
        Settler free = settler(helper, pantry, 2, 4, rolls);
        helper.assertTrue(free.getWork().interruptible(level), "A settler at ease can be interrupted");
        free.getWork().decideNow(level);
        helper.assertTrue(looksAt(free, friend), "...and greets");
        end(free);

        // Sleeping.
        BlockPos head = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(head.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
        Settler sleeper = settler(helper, pantry, 2, 4, rolls);
        helper.assertTrue(sleeper.startSleeping(head) && sleeper.isSleeping(), "The settler sleeps");
        helper.assertFalse(sleeper.getWork().interruptible(level), "Sleeping cannot be interrupted");
        end(sleeper);

        // Away on a Mission, and walking out to one.
        BlockPos board = helper.absolutePos(new BlockPos(10, 1, 10));
        UUID noCustomer = UUID.randomUUID();
        MissionTrip leaving = new MissionTrip(board, CivMod.id("invite_villagers_plains"), 100_000L);
        Settler walkingOut = settler(helper, pantry, 2, 4, rolls);
        walkingOut.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                java.util.Optional.of(leaving)));
        helper.assertFalse(walkingOut.getWork().interruptible(level), "Walking out to a Mission cannot be interrupted");
        end(walkingOut);
        Settler away = settler(helper, pantry, 2, 4, rolls);
        away.getWork().restore(new SettlerWork.BuffersSnapshot(List.of(), List.of(), List.of(), SettlerWork.CatchUpState.NONE,
                java.util.Optional.of(leaving.withExit(helper.absolutePos(new BlockPos(2, 1, 4))))));
        helper.assertTrue(away.getWork().isAway() && !away.getWork().interruptible(level), "Away on a Mission cannot be interrupted");
        int before = rolls.calls;
        away.getWork().decideNow(level);
        helper.assertTrue(away.getWork().social().looking().isEmpty() && rolls.calls == before, "...and does not greet");
        end(away);

        // A Clerk: free without a customer, busy with one at the counter.
        TestSettlements.setBlock(helper, new BlockPos(8, 1, 8), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerkBlock = helper.absolutePos(new BlockPos(8, 1, 8));
        Settler clerk = settler(helper, pantry, 2, 4, rolls);
        clerk.addJob(new SettlerJob(ModJobs.CLERK.get(), clerkBlock));
        helper.assertTrue(clerk.getWork().interruptible(level), "A Clerk without a customer can be interrupted");
        ClerkCounter.serve(level, clerkBlock, noCustomer);
        helper.assertTrue(ClerkCounter.busy(level, clerkBlock), "A customer is at the counter");
        helper.assertFalse(clerk.getWork().interruptible(level), "A Clerk with a customer at the counter cannot be interrupted");
        before = rolls.calls;
        clerk.getWork().decideNow(level);
        helper.assertTrue(clerk.getWork().social().looking().isEmpty() && rolls.calls == before, "...and does not greet");
        end(clerk, friend);
        helper.succeed();
    }

    /** With the config {@code socialLife} off no roll is made and nobody greets. */
    private static void configSwitch(GameTestHelper helper) {
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(GREETING);
        Settler a = settler(helper, pantry, 2, 4, rolls);
        Settler b = settler(helper, pantry, 5, 4, rolls);
        boolean was = CivModConfig.SOCIAL_LIFE.get();
        try {
            CivModConfig.SOCIAL_LIFE.set(false);
            a.getWork().decideNow(helper.getLevel());
            helper.assertTrue(a.getWork().social().looking().isEmpty() && rolls.calls == 0, "Switched off: no check");
            CivModConfig.SOCIAL_LIFE.set(true);
            a.getWork().decideNow(helper.getLevel());
            helper.assertTrue(looksAt(a, b), "Switched on: greets");
        } finally {
            CivModConfig.SOCIAL_LIFE.set(was);
        }
        end(a, b);
        helper.succeed();
    }

    /** Catch-up simulates no greetings: a quiet decision makes no roll and nobody turns. */
    private static void catchUp(GameTestHelper helper) {
        BlockPos pantry = TavernGameTests.pantry(helper);
        Rolls rolls = new Rolls(GREETING);
        Settler a = settler(helper, pantry, 2, 4, rolls);
        Settler b = settler(helper, pantry, 5, 4, rolls);
        a.getWork().decideQuietly(helper.getLevel());
        b.getWork().decideQuietly(helper.getLevel());
        helper.assertTrue(rolls.calls == 0, "No roll while catching up: " + rolls.calls);
        helper.assertTrue(a.getWork().social().looking().isEmpty() && b.getWork().social().looking().isEmpty(), "Nobody turned");
        helper.assertTrue(a.getWork().entertainment().equals(b.getWork().entertainment()), "Greetings give no points");
        end(a, b);
        helper.succeed();
    }
}
