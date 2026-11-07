package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.Entertain;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCategory;
import com.gilfort.civmod.api.MissionProfile;
import com.gilfort.civmod.api.MobEffect;
import com.gilfort.civmod.api.ServiceAct;
import com.gilfort.civmod.api.ServiceContext;
import com.gilfort.civmod.api.ServiceEffect;
import com.gilfort.civmod.api.ServiceProfile;
import com.gilfort.civmod.api.WorkSpeed;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.ServiceActNumbers;
import com.gilfort.civmod.job.ServiceActs;
import com.gilfort.civmod.job.SpeedBoost;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Bonuses;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestSettlements;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Service profile, the Service loop and the effects (spec "Service and Bard", vault
 * {@code Systems/Job Categories.md}). Registers three Service Jobs with a JobBlock each, the way an addon does:
 * {@code civmod:gametest_service_window} (one stationary act, 09:00 to 11:00), {@code civmod:gametest_service_radius}
 * (the same with one {@link Entertain}), {@code civmod:gametest_service_targets} (every built-in effect and a recorder
 * for each kind of target).
 * <p>
 * The tests that move the clock have an environment of their own ({@code civmod:service_window}); the others share
 * {@code civmod:service_open}, where the window is open (10:00) and the clock stands still.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ServiceGameTests {

    private static final Identifier WINDOW = CivMod.id("gametest_service_window");
    private static final Identifier RADIUS = CivMod.id("gametest_service_radius");
    private static final Identifier TARGETS = CivMod.id("gametest_service_targets");

    /** 09:00 to 11:00. */
    private static final int OPEN = ServiceAct.clock(9, 0);
    private static final int CLOSE = ServiceAct.clock(11, 0);
    static final BlockPos JOB_BLOCK = new BlockPos(1, 1, 1);

    /** What a custom effect saw. An addon writes the same: the class uses {@code com.gilfort.civmod.api} only. */
    private static final class Recorder implements ServiceEffect {
        private final Targets targets;
        private final List<Integer> settlers = new CopyOnWriteArrayList<>();
        private final List<Integer> players = new CopyOnWriteArrayList<>();
        private final List<Vec3> centers = new CopyOnWriteArrayList<>();

        Recorder(Targets targets) {
            this.targets = targets;
        }

        @Override
        public Targets targets() {
            return targets;
        }

        @Override
        public void apply(ServiceContext context) {
            settlers.add(context.settlers().size());
            players.add(context.players().size());
            centers.add(context.center());
        }
    }

    private static final Recorder FOR_SETTLERS = new Recorder(ServiceEffect.Targets.SETTLERS);
    private static final Recorder FOR_PLAYERS = new Recorder(ServiceEffect.Targets.PLAYERS);
    private static final Recorder FOR_BOTH = new Recorder(ServiceEffect.Targets.BOTH);

    private ServiceGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(CivModApi.JOB_REGISTRY, helper -> {
            helper.register(WINDOW, service(ServiceProfile.of(ServiceAct.stationary("listen", OPEN, CLOSE, 8.0, new Entertain(1.0)))));
            helper.register(RADIUS, service(ServiceProfile.of(ServiceAct.stationary("listen", OPEN, CLOSE, 8.0, new Entertain(1.0)))));
            helper.register(TARGETS, service(ServiceProfile.of(ServiceAct.stationary("show", OPEN, CLOSE, 8.0, FOR_SETTLERS, FOR_PLAYERS, FOR_BOTH,
                    new Entertain(1.0), new WorkSpeed(50.0, 2.0), new MobEffect(MobEffects.SPEED, 200, 1)))));
        });
        event.register(Registries.BLOCK, helper -> {
            block(helper, WINDOW);
            block(helper, RADIUS);
            block(helper, TARGETS);
        });
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "service_table", ServiceGameTests::table);
            register(helper, "service_window", ServiceGameTests::window);
            register(helper, "service_radius", ServiceGameTests::radius);
            register(helper, "service_targets", ServiceGameTests::targets);
            register(helper, "service_wrong_category", ServiceGameTests::wrongCategory);
            register(helper, "service_builtin_jobs", ServiceGameTests::builtInJobs);
        });
    }

    static Job service(ServiceProfile profile) {
        return Job.builder(JobCategory.SERVICE).icon(() -> Items.BELL).baseExp(10).profile(() -> profile).build();
    }

    static void block(RegisterEvent.RegisterHelper<Block> helper, Identifier id) {
        helper.register(id, CivModApi.jobBlock(() -> ModJobs.REGISTRY.getValue(id),
                BlockBehaviour.Properties.of().strength(2.5F).setId(ResourceKey.create(Registries.BLOCK, id))));
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    /** The performer and the settlement of a test. */
    record Scene(Settlement settlement, Settler performer, Job job, List<long[]> forced) {
    }

    /** A JobBlock of the Job at (1, 1, 1) on a stone floor and a settler working it, with plenty of food. */
    static Scene scene(GameTestHelper helper, Identifier jobId) {
        return scene(helper, jobId, jobId);
    }

    /** {@link #scene(GameTestHelper, Identifier)} for a JobBlock whose id is not the Job's (the Stage of the Bard). */
    static Scene scene(GameTestHelper helper, Identifier blockId, Identifier jobId) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 15; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(14, 0, 8));
        TestSettlements.setBlock(helper, JOB_BLOCK, BuiltInRegistries.BLOCK.getValue(blockId));
        BlockPos jobBlock = helper.absolutePos(JOB_BLOCK);
        Settlement settlement = SettlementData.get(level).settlementAt(jobBlock).orElseThrow();
        Job job = ModJobs.REGISTRY.getValue(jobId);
        Settler performer = settler(helper, settlement, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))), "Performer", true);
        helper.assertTrue(SettlementWorld.assign(level, jobBlock, performer, job) == JobAssignments.AssignResult.OK, "Service Job assigned");
        return new Scene(settlement, performer, job, forced);
    }

    static Settler settler(GameTestHelper helper, Settlement settlement, Vec3 at, String name, boolean member) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal(name));
        level.addFreshEntity(settler);
        if (member) {
            settler.joinSettlement(SettlementData.get(level), settlement);
        }
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        return settler;
    }

    /** The center of an act at the JobBlock: the bottom center of the block. */
    static Vec3 center(GameTestHelper helper) {
        return Vec3.atBottomCenterOf(helper.absolutePos(JOB_BLOCK));
    }

    static Settler listener(GameTestHelper helper, Scene scene, double dx, String name) {
        return settler(helper, scene.settlement(), center(helper).add(dx, 0.0, 0.0), name, true);
    }

    private static ServerPlayer player(GameTestHelper helper, Vec3 at) {
        return TestPlayers.add(helper, at);
    }

    static double entertainment(Settler settler) {
        return settler.getWork().entertainment().value();
    }

    static int exp(Settler settler, Job job) {
        return settler.getSkills().get(job).exp();
    }

    static boolean works(Settler settler, Job job) {
        return settler.getWork().activeJob().filter(active -> active.jobType() == job).isPresent();
    }

    static void setDayTime(ServerLevel level, long dayTime) {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), DayClock.day(level) * DayClock.DAY_TICKS + dayTime);
    }

    static void cleanUp(ServerLevel level, Scene scene, Settler... others) {
        scene.performer().discard();
        for (Settler other : others) {
            other.discard();
        }
        scene.forced().forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    // ---------------------------------------------------------------- tests

    /**
     * The pure numbers: times on the clock, window edges, the radius, the effects' numbers, the speed boost rules, how the
     * boost adds to the other speed bonuses and how datapack numbers merge into the acts.
     */
    private static void table(GameTestHelper helper) {
        // Times on the clock: 0 is 06:00.
        int[][] clocks = {{6, 0, 0}, {11, 0, 5000}, {17, 30, 11500}, {20, 0, 14000}, {5, 30, 23500}, {0, 0, 18000}, {12, 15, 6250}};
        for (int[] row : clocks) {
            helper.assertTrue(ServiceAct.clock(row[0], row[1]) == row[2], "Clock " + row[0] + ":" + row[1] + " = " + ServiceAct.clock(row[0], row[1]));
        }
        // The window starts at its first tick and ends before its last.
        ServiceAct act = ServiceAct.stationary("a", 5000, 14000, 8.0);
        boolean[] open = {false, true, true, true, false, false};
        long[] times = {4999, 5000, 9000, 13999, 14000, 23999};
        for (int i = 0; i < times.length; i++) {
            helper.assertTrue(act.isOpen(times[i]) == open[i], "Window at " + times[i]);
        }
        // The radius: on the edge counts, a sphere around the center.
        Vec3 zero = Vec3.ZERO;
        double[][] spots = {{8.0, 0, 0}, {8.01, 0, 0}, {5.6, 5.6, 0}, {6.0, 6.0, 0}, {0, 0, -8.0}, {0, 8.0, 0.1}};
        boolean[] reached = {true, false, true, false, true, false};
        for (int i = 0; i < spots.length; i++) {
            helper.assertTrue(act.reaches(zero, new Vec3(spots[i][0], spots[i][1], spots[i][2])) == reached[i], "Radius spot " + i);
        }
        // An act that cannot exist is refused.
        helper.assertTrue(refused(() -> ServiceAct.stationary("a", 5000, 5000, 8.0)), "Empty window");
        helper.assertTrue(refused(() -> ServiceAct.stationary("a", 5000, 24001, 8.0)), "Window past the day");
        helper.assertTrue(refused(() -> ServiceAct.stationary("A b", 0, 100, 8.0)), "Bad id");
        helper.assertTrue(refused(() -> ServiceAct.stationary("a", 0, 100, 65.0)), "Radius above the cap");
        helper.assertTrue(refused(() -> ServiceAct.stationary("a", 0, 100, -1.0)), "Negative radius");
        // The effects' numbers.
        WorkSpeed speed = new WorkSpeed(20.0, 3.0);
        helper.assertTrue(speed.ticks() == 3000L && speed.numbers().equals(Map.of("percent", 20.0, "hours", 3.0)), "WorkSpeed numbers");
        helper.assertTrue(speed.withNumbers(Map.of("hours", 1.5, "unknown", 9.0)).equals(new WorkSpeed(20.0, 1.5)), "WorkSpeed hours changed, unknown ignored");
        helper.assertTrue(new Entertain(1.0).withNumbers(Map.of("points", 2.5)).equals(new Entertain(2.5)), "Entertain points changed");
        MobEffect regeneration = new MobEffect(MobEffects.REGENERATION, 200, 0);
        helper.assertTrue(regeneration.targets() == ServiceEffect.Targets.PLAYERS && regeneration.numbers().equals(Map.of("duration", 200.0, "amplifier", 0.0)),
                "MobEffect is for players and lists duration and amplifier");
        helper.assertTrue(regeneration.withNumbers(Map.of("amplifier", 2.0)).equals(new MobEffect(MobEffects.REGENERATION, 200, 2)), "Amplifier changed");
        helper.assertTrue(ServiceEffect.Targets.BOTH.settlers() && ServiceEffect.Targets.BOTH.players() && !ServiceEffect.Targets.SETTLERS.players()
                && !ServiceEffect.Targets.PLAYERS.settlers(), "Targets");
        // The speed boost of a settler: one at a time, never stacked.
        SpeedBoost boost = SpeedBoost.NONE.start(20.0, 1000L, 4000L);
        helper.assertTrue(near(boost.share(999L), 0.0) && near(boost.share(1000L), 0.2) && near(boost.share(3999L), 0.2) && near(boost.share(4000L), 0.0),
                "A boost runs from its start to its end");
        helper.assertTrue(near(boost.averageShare(3500L, 4500L), 0.1) && near(boost.averageShare(0L, 1000L), 0.0), "Averaged over a span");
        helper.assertTrue(boost.start(10.0, 2000L, 9000L).equals(boost), "A weaker boost is ignored while a stronger one runs");
        helper.assertTrue(boost.start(30.0, 2000L, 3000L).equals(new SpeedBoost(30.0, 2000L, 3000L)), "A stronger boost replaces it");
        helper.assertTrue(boost.start(20.0, 3000L, 6000L).equals(new SpeedBoost(20.0, 1000L, 6000L)), "An equal boost extends it");
        helper.assertTrue(boost.start(10.0, 5000L, 6000L).equals(new SpeedBoost(10.0, 5000L, 6000L)), "A boost after the end starts anew");
        var encoded = SpeedBoost.CODEC.encodeStart(JsonOps.INSTANCE, boost).getOrThrow();
        helper.assertTrue(SpeedBoost.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(boost), "The boost survives a save");
        // Bonuses add: blessing 20 % + boost 10 % + 5 points of Entertainment (2 % each) = 1.4.
        double factor = EntertainmentRules.FALLBACK.speedFactor(5.0, 1.2 + 0.1);
        helper.assertTrue(near(factor, 1.4), "Speed bonuses add: " + factor);
        // Datapack numbers: later files override per field, effects are matched by position.
        List<ServiceAct> declared = List.of(ServiceAct.stationary("show", 5000, 14000, 8.0, new Entertain(1.0), new WorkSpeed(20.0, 3.0)));
        JobBalancing merged = JobBalancing.merge(List.<JsonElement>of(
                JsonParser.parseString("{\"service_acts\": {\"show\": {\"start\": 6000, \"radius\": 4.5}}}"),
                JsonParser.parseString("{\"service_acts\": {\"show\": {\"radius\": 6.0, \"effects\": [{\"points\": 2}, {\"hours\": 1}]}}}")));
        List<String> problems = new java.util.ArrayList<>();
        List<ServiceAct> resolved = ServiceActs.resolve(declared, merged.serviceActs().orElseThrow(), problems::add);
        ServiceAct expected = ServiceAct.stationary("show", 6000, 14000, 6.0, new Entertain(2.0), new WorkSpeed(20.0, 1.0));
        helper.assertTrue(problems.isEmpty() && resolved.equals(List.of(expected)), "Merged numbers: " + resolved + " " + problems);
        Map<String, ServiceActNumbers> faulty = Map.of("show", new ServiceActNumbers(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of(List.of(Map.of("points", 2.0)))), "gone", ServiceActNumbers.NONE);
        problems.clear();
        helper.assertTrue(ServiceActs.resolve(declared, faulty, problems::add).equals(declared) && problems.size() == 2,
                "A list of another length and an unknown act are ignored and reported: " + problems);
        Map<String, ServiceActNumbers> backwards = Map.of("show", new ServiceActNumbers(java.util.Optional.of(15000), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty()));
        problems.clear();
        helper.assertTrue(ServiceActs.resolve(declared, backwards, problems::add).equals(declared) && problems.size() == 1, "A window that ends before it starts is ignored");
        helper.succeed();
    }

    private static boolean refused(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** The settler works the Job inside the window only: not at 08:00, standing there at 10:00 (acting once), not at 11:00. */
    private static void window(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper, WINDOW);
        Settler hearer = listener(helper, scene, 3.0, "Hearer");
        Settler performer = scene.performer();
        helper.assertTrue(DayClock.dayTime(level.getOverworldClockTime()) < OPEN, "The test starts before the window");
        performer.getWork().decideNow(level);
        helper.assertTrue(!works(performer, scene.job()), "Does not work the Job before the window: " + performer.getWork().activeJob());
        helper.assertTrue(entertainment(hearer) == 0.0, "Nobody entertained before the window");
        helper.startSequence()
                .thenExecute(() -> {
                    setDayTime(level, OPEN + 1000L);
                    performer.getWork().decideNow(level);
                })
                .thenWaitUntil(() -> helper.assertTrue(entertainment(hearer) >= 1.0, "The act is played at the place (status "
                        + performer.getWork().status(performer.getJobs().get(0).jobBlock()) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(works(performer, scene.job()), "Works the Job inside the window");
                    helper.assertTrue(performer.getWork().status(performer.getJobs().get(0).jobBlock()) == JobStatus.SERVING, "Serving");
                    helper.assertTrue(exp(performer, scene.job()) == 10, "EXP: base 10 times JobBlock level 1: " + exp(performer, scene.job()));
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(entertainment(hearer) == 1.0, "The act plays once a day: " + entertainment(hearer));
                    helper.assertTrue(exp(performer, scene.job()) == 10, "EXP only for the act played");
                    setDayTime(level, CLOSE);
                    performer.getWork().decideNow(level);
                    helper.assertTrue(!works(performer, scene.job()), "Does not work the Job after the window: " + performer.getWork().activeJob());
                    cleanUp(level, scene, hearer);
                })
                .thenSucceed();
    }

    /** Listeners just inside the radius hear the act, those just outside, the performer itself and non-members do not. */
    private static void radius(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper, RADIUS);
        Settler inside = listener(helper, scene, 7.9, "Inside");
        Settler outside = listener(helper, scene, 8.1, "Outside");
        Settler close = listener(helper, scene, 1.0, "Close");
        Settler stranger = settler(helper, scene.settlement(), center(helper).add(2.0, 0.0, 2.0), "Stranger", false);
        Settler performer = scene.performer();
        helper.startSequence()
                .thenExecute(() -> performer.getWork().decideNow(level))
                .thenWaitUntil(() -> helper.assertTrue(entertainment(close) >= 1.0, "The act is played"))
                .thenExecute(() -> {
                    helper.assertTrue(entertainment(inside) == 1.0, "Just inside the radius hears it: " + entertainment(inside));
                    helper.assertTrue(entertainment(outside) == 0.0, "Just outside does not: " + entertainment(outside));
                    helper.assertTrue(entertainment(stranger) == 0.0, "A settler of no settlement does not");
                    helper.assertTrue(entertainment(performer) == 0.0, "The performer does not hear itself");
                    cleanUp(level, scene, inside, outside, close, stranger);
                })
                .thenSucceed();
    }

    /**
     * Each effect reaches the targets it names: a custom effect for settlers, players and both sees the right lists, the
     * built-in ones act on settlers (Entertain, WorkSpeed adding to the blessing) and on players (MobEffect).
     */
    private static void targets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper, TARGETS);
        Settler near = listener(helper, scene, 2.0, "Near");
        Settler far = listener(helper, scene, 5.0, "Far");
        Settler out = listener(helper, scene, 9.0, "Out");
        ServerPlayer hearing = player(helper, center(helper).add(3.0, 0.0, 0.0));
        ServerPlayer deaf = player(helper, center(helper).add(12.0, 0.0, 0.0));
        long now = level.getOverworldClockTime();
        SettlementData.get(level).setBonuses(scene.settlement(), Bonuses.NONE.start(Bonuses.Kind.WORK_SPEED, now - 10L, now + Bonuses.BLESSING_TICKS));
        Settler performer = scene.performer();
        helper.startSequence()
                .thenExecute(() -> performer.getWork().decideNow(level))
                .thenWaitUntil(() -> helper.assertTrue(!FOR_BOTH.settlers.isEmpty(), "The act is played"))
                .thenExecute(() -> {
                    helper.assertTrue(FOR_SETTLERS.settlers.equals(List.of(2)) && FOR_SETTLERS.players.equals(List.of(0)),
                            "A settlers effect gets the settlers only: " + FOR_SETTLERS.settlers + " " + FOR_SETTLERS.players);
                    helper.assertTrue(FOR_PLAYERS.settlers.equals(List.of(0)) && FOR_PLAYERS.players.equals(List.of(1)),
                            "A players effect gets the players only: " + FOR_PLAYERS.settlers + " " + FOR_PLAYERS.players);
                    helper.assertTrue(FOR_BOTH.settlers.equals(List.of(2)) && FOR_BOTH.players.equals(List.of(1)),
                            "An effect for both gets both: " + FOR_BOTH.settlers + " " + FOR_BOTH.players);
                    helper.assertTrue(FOR_BOTH.centers.equals(List.of(center(helper))), "The center is the JobBlock: " + FOR_BOTH.centers);
                    helper.assertTrue(entertainment(near) == 1.0 && entertainment(far) == 1.0 && entertainment(out) == 0.0, "Entertain gives settlers in range a point");
                    helper.assertTrue(near.getWork().speedBoost().percent() == 50.0 && near.getWork().speedBoost().until() - near.getWork().speedBoost().from() == 2000L,
                            "WorkSpeed boosts the settlers in range for two hours: " + near.getWork().speedBoost());
                    helper.assertTrue(out.getWork().speedBoost().equals(SpeedBoost.NONE), "Not the ones out of range");
                    // Blessing 20 % + boost 50 % + 1 point of Entertainment (2 %): the shares add.
                    double factor = near.getWork().speedFactor(level);
                    helper.assertTrue(near(factor, 1.72), "The boost adds to the blessing and Entertainment: " + factor);
                    helper.assertTrue(near(out.getWork().speedFactor(level), 1.2), "Out of range only the blessing counts: " + out.getWork().speedFactor(level));
                    MobEffectInstance speed = hearing.getEffect(MobEffects.SPEED);
                    helper.assertTrue(speed != null && speed.getAmplifier() == 1 && speed.getDuration() > 150, "MobEffect reaches the player in range: " + speed);
                    helper.assertTrue(!deaf.hasEffect(MobEffects.SPEED), "Not the player out of range");
                    helper.assertTrue(!near.hasEffect(MobEffects.SPEED), "A MobEffect is for players unless it names settlers");
                    helper.assertTrue(exp(performer, scene.job()) == 10, "The performer got the EXP of the act");
                    TestPlayers.remove(hearing);
                    TestPlayers.remove(deaf);
                    cleanUp(level, scene, near, far, out);
                })
                .thenSucceed();
    }

    /** A profile of another category fails with a clear message, both ways, like the other profiles. */
    private static void wrongCategory(GameTestHelper helper) {
        Job mission = Job.builder(JobCategory.MISSION).icon(() -> Items.STICK).profile(() -> ServiceProfile.of()).build();
        Job service = Job.builder(JobCategory.SERVICE).icon(() -> Items.STICK).profile(() -> MissionProfile.NEUTRAL).build();
        for (Job wrong : List.of(mission, service)) {
            String message = null;
            try {
                wrong.profile(com.gilfort.civmod.api.CategoryProfile.class);
            } catch (IllegalStateException e) {
                message = e.getMessage();
            }
            helper.assertTrue(message != null && message.contains("MISSION") && message.contains("SERVICE"), "A wrong profile fails and names both categories: " + message);
        }
        Job right = service(ServiceProfile.of(ServiceAct.stationary("a", 0, 100, 1.0)));
        helper.assertTrue(right.profile(ServiceProfile.class).isPresent() && right.profile(MissionProfile.class).isEmpty(), "The typed read finds the Service profile");
        helper.succeed();
    }

    /** The Priest, the Tavern Host and the Administrator are Service Jobs without a profile: they keep their own handling. */
    private static void builtInJobs(GameTestHelper helper) {
        for (Job job : List.of(ModJobs.PRIEST.get(), ModJobs.TAVERN_HOST.get(), ModJobs.ADMINISTRATOR.get())) {
            helper.assertTrue(job.category() == JobCategory.SERVICE && job.profile(ServiceProfile.class).isEmpty(), job + " has no Service profile");
        }
        helper.succeed();
    }
}
