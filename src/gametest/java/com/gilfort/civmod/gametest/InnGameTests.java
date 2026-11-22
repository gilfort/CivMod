package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.inn.GuestBeds;
import com.gilfort.civmod.inn.InnRules;
import com.gilfort.civmod.inn.Inns;
import com.gilfort.civmod.inn.Visitors;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.origin.Talent;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.InnSlots;
import com.gilfort.civmod.settlement.InnState;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;
import com.gilfort.civmod.settlement.Visit;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the inn (vault {@code Systems/NPC Population.md}, spec Inn and visitors): the guest beds of a Pantry, the three
 * arrival checks a day, the reputation, how long a visitor stays and how it leaves, the catch-up of an unloaded settlement and
 * what happens when the inn falls away. The chances come from {@link InnRules} set to 0 % or 100 %, so no test depends on chance;
 * where a roll is needed, {@link Inns#script} answers it. A test sets the clock to the day time of a check and plays the checks.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class InnGameTests {

    /** Day time of the morning check (06:00). */
    static final long MORNING = 0L;
    /** Day time of the noon check (12:00). */
    static final long NOON = 6000L;
    /** Day time of the evening check (18:00). */
    static final long EVENING = 12000L;
    /** The reasons of the visitor channel. */
    private static final Set<Identifier> VISITOR_REASONS = Set.of(CivMod.id("passing_through"), CivMod.id("looking_for_work"),
            CivMod.id("heard_of_this_place"));
    /** Where the guest beds of a test inn stand, as the head of the bed, all within the Tavern radius of the Pantry. */
    static final List<BlockPos> BED_SPOTS = List.of(new BlockPos(3, 1, 3), new BlockPos(5, 1, 3), new BlockPos(7, 1, 3),
            new BlockPos(3, 1, 6), new BlockPos(5, 1, 6), new BlockPos(7, 1, 6));
    /** The day and the day time (08:00) every test starts at. */
    static final long START_DAY = 10L;
    static final long START_TIME = 2000L;
    static final BlockPos PANTRY = new BlockPos(1, 1, 1);
    private static final BlockPos SECOND_PANTRY = new BlockPos(9, 1, 1);

    private InnGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "inn_guest_beds", InnGameTests::guestBeds);
            register(helper, "inn_rules", InnGameTests::rulesFile);
            register(helper, "inn_arrival", InnGameTests::arrival);
            register(helper, "inn_no_host", InnGameTests::noHost);
            register(helper, "inn_part_time_host", InnGameTests::partTimeHost);
            register(helper, "inn_arrival_fraction_hit", test -> fraction(test, MasteryRolls.HIT, 2));
            register(helper, "inn_arrival_fraction_miss", test -> fraction(test, MasteryRolls.MISS, 1));
            register(helper, "inn_arrival_capped_by_beds", InnGameTests::cappedByBeds);
            register(helper, "inn_reputation", InnGameTests::reputation);
            register(helper, "inn_visitor_levels", test -> levels(test, 1, 3));
            register(helper, "inn_visitor_levels_best_settler", test -> levels(test, 10, 10));
            register(helper, "inn_stay_and_leave", InnGameTests::stayAndLeave);
            register(helper, "inn_stay_longer", InnGameTests::stayLonger);
            register(helper, "inn_stay_longer_declined", InnGameTests::stayLongerDeclined);
            register(helper, "inn_catch_up", InnGameTests::catchUp);
            register(helper, "inn_catch_up_no_host", InnGameTests::catchUpNoHost);
            register(helper, "inn_dissolved", InnGameTests::dissolved);
            register(helper, "inn_visitor_without_stay", InnGameTests::visitorWithoutStay);
            register(helper, "inn_pantry_removed", InnGameTests::pantryRemoved);
            register(helper, "inn_guest_bed_unbound", InnGameTests::guestBedUnbound);
            register(helper, "inn_host_break", InnGameTests::hostBreak);
            register(helper, "inn_limit", InnGameTests::innLimit);
            register(helper, "inn_guest_bed_poi", InnGameTests::guestBedPoi);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ------------------------------------------------------------------------------------------------------------- setup

    /**
     * An inn under test.
     *
     * @param pantry     the Pantry
     * @param settlement its settlement
     * @param host       the Tavern Host assigned to the Pantry, if there is one
     */
    record Inn(BlockPos pantry, Settlement settlement, Optional<Settler> host) {
    }

    /** The rules a test runs with: the chance of every check and the days a visitor stays, the rest as shipped. */
    static InnRules rules(double base, double scale, int stayDays) {
        InnRules shipped = InnRules.FALLBACK;
        return new InnRules(base, scale, shipped.bedsPerLevel(), stayDays, stayDays, shipped.extendChance(), shipped.extendDays(),
                shipped.minLevelCap(), shipped.defaultPriceAmount());
    }

    /** A Pantry of a level at (1, 1, 1) of a new settlement on a stone floor. */
    private static BlockPos pantry(GameTestHelper helper, int level) {
        BlockPos pantry = TavernGameTests.pantry(helper);
        SettlementManager.setLevel(SettlementData.get(helper.getLevel()), pantry, level, SettlementManager.Listener.NONE);
        return pantry;
    }

    /** A Pantry of a level with guest beds and, if asked for, a Tavern Host, in a new settlement on a stone floor. */
    static Inn inn(GameTestHelper helper, int pantryLevel, int beds, boolean withHost) {
        // A test that failed before left its scripts behind; every test starts without them.
        Inns.script(null);
        Inns.scriptHostCover(null);
        ServerLevel level = helper.getLevel();
        // The tests of a batch share the clock: every test starts at 08:00 of the same day, before the first check it plays.
        setClock(level, START_DAY, START_TIME);
        BlockPos pantry = pantry(helper, pantryLevel);
        SettlementData data = SettlementData.get(level);
        for (BlockPos spot : BED_SPOTS.subList(0, beds)) {
            BlockPos bed = VillagerConversionGameTests.bed(helper, spot);
            helper.assertTrue(GuestBeds.toggle(level, data, pantry, bed) == GuestBeds.Result.BOUND, "Guest bed bound at " + spot);
        }
        Optional<Settler> host = Optional.empty();
        if (withHost) {
            host = Optional.of(host(helper, pantry, new BlockPos(2, 1, 2)));
        }
        return new Inn(pantry, data.settlementAt(pantry).orElseThrow(), host);
    }

    static Settler host(GameTestHelper helper, BlockPos pantry, BlockPos at) {
        Settler settler = TavernGameTests.settler(helper, pantry, at);
        TavernGameTests.host(helper, pantry, settler);
        return settler;
    }

    /** Sets the overworld clock, which the arrival checks read. */
    static void setClock(ServerLevel level, long day, long dayTime) {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), day * DayClock.DAY_TICKS + dayTime);
    }

    /** Sets the clock to the day time of a check and plays the checks that have not been played yet. */
    static void play(GameTestHelper helper, Inn inn, long day, long dayTime) {
        setClock(helper.getLevel(), day, dayTime);
        Inns.run(helper.getLevel(), inn.settlement());
    }

    static long today(GameTestHelper helper) {
        return DayClock.day(helper.getLevel());
    }

    /** The visitors that stand within 20 blocks of the Pantry now (the test structure itself is empty and tiny). */
    static List<Settler> visitors(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(Settler.class, new AABB(helper.absolutePos(PANTRY)).inflate(20.0), Settler::isVisitor);
    }

    private static List<UUID> ids(List<Settler> settlers) {
        return settlers.stream().map(Settler::getUUID).toList();
    }

    /** A player near the Pantry who keeps the chat lines it gets. */
    static ServerPlayer listener(GameTestHelper helper, List<Component> chat) {
        return TestPlayers.add(helper, Vec3.atCenterOf(helper.absolutePos(PANTRY)), chat::add);
    }

    static boolean says(List<Component> chat, String key) {
        return chat.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key));
    }

    /** Ends a test: the visitors, the host and the Tavern Host's place are gone, the rules and scripts are back. */
    static void end(GameTestHelper helper, Inn inn) {
        cleanUp(helper, inn);
        helper.succeed();
    }

    /** {@link #end}, for a test that goes on with a sequence and succeeds at its end. */
    static void cleanUp(GameTestHelper helper, Inn inn) {
        visitors(helper).forEach(Settler::discard);
        inn.host().ifPresent(Settler::discard);
        SettlementData data = SettlementData.get(helper.getLevel());
        TestAssignments.unassign(data, inn.pantry());
        // The settlement stays in the data until the batch ends: its inn must not go on drawing visitors.
        data.updateInn(inn.settlement(), ignored -> InnState.NONE);
        InnRules.use(InnRules.FALLBACK);
        Inns.script(null);
        Inns.scriptHostCover(null);
    }

    // ------------------------------------------------------------------------------------------------------ guest beds

    /**
     * The Scroll's guest bed rules: a bed within the Tavern radius is bound, the same bed again unbinds it, a Pantry holds
     * 2 beds per level, a bed outside the radius, a block that is no bed and a bed a settler owns are refused, and the normal bed
     * assignment never hands out a guest bed.
     */
    private static void guestBeds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pantry = pantry(helper, 1);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(pantry).orElseThrow();
        BlockPos near = VillagerConversionGameTests.bed(helper, new BlockPos(3, 1, 3));
        BlockPos second = VillagerConversionGameTests.bed(helper, new BlockPos(5, 1, 3));
        BlockPos third = VillagerConversionGameTests.bed(helper, new BlockPos(7, 1, 3));
        BlockPos far = VillagerConversionGameTests.bed(helper, new BlockPos(14, 1, 3));
        BlockPos owned = VillagerConversionGameTests.bed(helper, new BlockPos(9, 1, 3));

        helper.assertTrue(GuestBeds.toggle(level, data, pantry, helper.absolutePos(new BlockPos(1, 1, 3))) == GuestBeds.Result.NOT_A_BED,
                "A stone block is no bed");
        helper.assertTrue(GuestBeds.toggle(level, data, pantry, far) == GuestBeds.Result.TOO_FAR, "A bed beyond the Tavern radius is refused");
        helper.assertTrue(GuestBeds.toggle(level, data, pantry, near) == GuestBeds.Result.BOUND, "A bed in the radius is bound");
        helper.assertTrue(GuestBeds.toggle(level, data, pantry, second.south()) == GuestBeds.Result.BOUND,
                "The foot of a bed binds its head");
        helper.assertTrue(settlement.inn().bedsOf(pantry).equals(List.of(near, second)), "Two guest beds: " + settlement.inn().bedsOf(pantry));
        helper.assertTrue(GuestBeds.toggle(level, data, pantry, third) == GuestBeds.Result.FULL, "A level 1 Pantry holds 2 beds");

        Settler tenant = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 6));
        helper.assertTrue(SettlerBeds.assign(level, data, Optional.of(settlement), tenant.getUUID(), owned) == SettlerBeds.AssignResult.ASSIGNED,
                "The Scroll gives a settler an ordinary bed");
        helper.assertTrue(GuestBeds.toggle(level, data, pantry, owned) == GuestBeds.Result.TAKEN, "A bed a settler owns is refused");
        helper.assertTrue(SettlerBeds.assign(level, data, Optional.of(settlement), tenant.getUUID(), near) == SettlerBeds.AssignResult.GUEST_BED,
                "The Scroll never gives a guest bed to a settler");

        Settler newcomer = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 7));
        helper.assertTrue(SettlerBeds.ensureBed(level, data, settlement, newcomer.getUUID(), pantry).equals(Optional.of(third)),
                "A settler looking for a bed skips the guest beds: " + settlement.bedOf(newcomer.getUUID()));

        helper.assertTrue(GuestBeds.toggle(level, data, pantry, near) == GuestBeds.Result.UNBOUND, "The same bed unbinds it");
        helper.assertTrue(settlement.inn().bedsOf(pantry).equals(List.of(second)), "One guest bed left: " + settlement.inn().bedsOf(pantry));
        Settler another = TavernGameTests.settler(helper, pantry, new BlockPos(2, 1, 8));
        helper.assertTrue(SettlerBeds.ensureBed(level, data, settlement, another.getUUID(), pantry).equals(Optional.of(near)),
                "An unbound bed is free again: " + settlement.bedOf(another.getUUID()));

        SettlementManager.setLevel(data, pantry, 3, SettlementManager.Listener.NONE);
        BlockPos extra = VillagerConversionGameTests.bed(helper, new BlockPos(5, 1, 6));
        helper.assertTrue(GuestBeds.toggle(level, data, pantry, extra) == GuestBeds.Result.BOUND, "A level 3 Pantry holds a third guest bed");
        List.of(tenant, newcomer, another).forEach(Settler::discard);
        helper.succeed();
    }

    /** The shipped rules file holds the defaults of the code; a file with fewer keys keeps them for the rest. */
    private static void rulesFile(GameTestHelper helper) {
        helper.assertTrue(InnRules.get().equals(InnRules.FALLBACK), "The shipped rules are the defaults: " + InnRules.get());
        InnRules some = InnRules.merge(List.of(JsonParser.parseString("{\"beds_per_level\": 3, \"base_chance\": 0.5}")));
        helper.assertTrue(some.bedsPerLevel() == 3 && some.baseChance() == 0.5 && some.reputationScale() == InnRules.FALLBACK.reputationScale(),
                "A pack changes what it names: " + some);
        helper.assertTrue(InnRules.merge(List.of()).equals(InnRules.FALLBACK), "No file at all: the defaults");
        helper.assertTrue(InnRules.get().bedCapacity(2) == 4, "Two guest beds per level of the Pantry");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------- arrivals

    /**
     * A check with a Tavern Host on shift brings a visitor with a past: a name, a gender, an origin with one of the reasons of the
     * visitor channel and no job. It stands near the Pantry, sleeps in the first guest bed and is no member of the settlement.
     */
    private static void arrival(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 2, true);
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = listener(helper, chat);
        play(helper, inn, today(helper), NOON);

        List<Settler> visitors = visitors(helper);
        helper.assertTrue(visitors.size() == 1, "One visitor at a Pantry of level 1 with a 100 % chance: " + visitors.size());
        Settler visitor = visitors.get(0);
        helper.assertTrue(visitor.getJobs().isEmpty() && !inn.settlement().members().contains(visitor.getUUID()), "No job and no membership");
        helper.assertTrue(!visitor.isHomeless() && visitor.getSettlementId() == -1, "Not homeless either: it belongs to no settlement");
        helper.assertTrue(visitor.getPast().origin().isPresent(), "The visitor has an origin");
        SettlerOrigin origin = visitor.getPast().origin().get();
        helper.assertTrue(origin.name().isPresent() && visitor.hasCustomName(), "A rolled name is shown");
        helper.assertTrue(origin.reason().filter(VISITOR_REASONS::contains).isPresent(), "A reason of the visitor channel: " + origin.reason());
        helper.assertTrue(origin.rescuer().isEmpty(), "Nobody took a visitor in: " + origin.rescuer());
        BlockPos firstBed = helper.absolutePos(BED_SPOTS.get(0));
        helper.assertTrue(inn.settlement().inn().visitIn(firstBed).filter(visit -> visit.id().equals(visitor.getUUID())).isPresent(),
                "The visitor takes the first guest bed: " + inn.settlement().inn().visits());
        helper.assertTrue(Math.abs(visitor.blockPosition().getX() - inn.pantry().getX()) <= 5
                && Math.abs(visitor.blockPosition().getZ() - inn.pantry().getZ()) <= 5, "Within 5 blocks of the Pantry: " + visitor.blockPosition());
        helper.assertTrue(chat.isEmpty(), "A visitor arrives without a message: " + chat);
        TestPlayers.remove(player);
        end(helper, inn);
    }

    /** A check without a Tavern Host lapses; the next one with a Tavern Host on shift counts. */
    private static void noHost(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 2, false);
        play(helper, inn, today(helper), NOON);
        helper.assertTrue(visitors(helper).isEmpty(), "No Tavern Host, no visitor: " + visitors(helper).size());

        Settler host = host(helper, inn.pantry(), new BlockPos(2, 1, 2));
        play(helper, inn, today(helper), EVENING);
        helper.assertTrue(visitors(helper).size() == 1, "The next check with a Tavern Host on shift brings a visitor: " + visitors(helper).size());
        host.discard();
        end(helper, new Inn(inn.pantry(), inn.settlement(), Optional.empty()));
    }

    /** A Tavern Host who works only the evening brings visitors with the evening check and not with the others. */
    private static void partTimeHost(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 2, true);
        Inns.scriptHostCover((pantry, day, dayTime) -> dayTime == EVENING);
        long day = today(helper);
        play(helper, inn, day, NOON);
        helper.assertTrue(visitors(helper).isEmpty(), "The noon check is not in the shift: " + visitors(helper).size());
        play(helper, inn, day, EVENING);
        helper.assertTrue(visitors(helper).size() == 1, "The evening check is: " + visitors(helper).size());
        play(helper, inn, day + 1, MORNING);
        play(helper, inn, day + 1, NOON);
        helper.assertTrue(visitors(helper).size() == 1, "The checks of the next day before the evening bring nobody: " + visitors(helper).size());
        play(helper, inn, day + 1, EVENING);
        helper.assertTrue(visitors(helper).size() == 2, "The next evening brings the second visitor: " + visitors(helper).size());
        end(helper, inn);
    }

    /**
     * A Pantry of level 3 expects 1.5 visitors at a base chance of 50 %: one is certain, the second comes when the roll hits.
     *
     * @param roll     the answer of the roll for the fraction
     * @param expected the visitors after one check
     */
    private static void fraction(GameTestHelper helper, double roll, int expected) {
        InnRules.use(rules(0.5, 0.0, 3));
        Inn inn = inn(helper, 3, 6, true);
        Inns.script(MasteryRolls.scripted(roll));
        play(helper, inn, today(helper), NOON);
        helper.assertTrue(visitors(helper).size() == expected, "Expected " + expected + " visitors for the roll " + roll + ": " + visitors(helper).size());
        end(helper, inn);
    }

    /** More visitors are expected than there are free guest beds: the beds are the cap, and a later check finds them taken. */
    private static void cappedByBeds(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 3, 2, true);
        long day = today(helper);
        play(helper, inn, day, NOON);
        helper.assertTrue(visitors(helper).size() == 2, "Three are expected, two beds are free: " + visitors(helper).size());
        play(helper, inn, day, EVENING);
        helper.assertTrue(visitors(helper).size() == 2, "The beds are taken, nobody more comes: " + visitors(helper).size());
        end(helper, inn);
    }

    /** Settlers whose fire need is met draw visitors, settlers without a hearth draw none. */
    private static void reputation(GameTestHelper helper) {
        InnRules.use(rules(0.0, 1.0, 3));
        Inn inn = inn(helper, 1, 2, true);
        Settler other = TavernGameTests.settler(helper, inn.pantry(), new BlockPos(2, 1, 8));
        long day = today(helper);
        play(helper, inn, day, NOON);
        helper.assertTrue(visitors(helper).isEmpty(), "No hearth, the reputation is 0: " + visitors(helper).size());

        helper.setBlock(new BlockPos(4, 1, 10), Blocks.CAMPFIRE);
        play(helper, inn, day, EVENING);
        helper.assertTrue(visitors(helper).size() == 1, "With a hearth the reputation is 1, a Pantry of level 1 expects one visitor: " + visitors(helper).size());
        other.discard();
        end(helper, inn);
    }

    /**
     * A visitor rolls levels only in the Jobs its upbringing favours, between 1 and the cap, which is the best skill level of the
     * settlers and at least 3; every other Job stays at level 1.
     *
     * @param bestSettlerLevel the level a settler of the settlement has in a Job
     * @param cap              the highest level a visitor can roll
     */
    private static void levels(GameTestHelper helper, int bestSettlerLevel, int cap) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 3, 6, true);
        inn.host().orElseThrow().getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(bestSettlerLevel, 0));
        long day = today(helper);
        play(helper, inn, day, NOON);
        play(helper, inn, day, EVENING);
        List<Settler> visitors = visitors(helper);
        helper.assertTrue(visitors.size() == 6, "Six visitors: " + visitors.size());
        for (Settler visitor : visitors) {
            for (Job job : ModJobs.REGISTRY) {
                int level = visitor.getSkills().get(job).level();
                helper.assertTrue(Talent.favours(visitor, job) ? level >= 1 && level <= cap : level == 1,
                        "Level " + level + " in " + job.id() + ", favoured " + Talent.favours(visitor, job) + ", cap " + cap);
            }
        }
        end(helper, inn);
    }

    // ---------------------------------------------------------------------------------------------------- stay, leaving

    /** A visitor stays its days, then leaves quietly and its guest bed is free for the next one. */
    private static void stayAndLeave(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 2));
        Inn inn = inn(helper, 1, 1, true);
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = listener(helper, chat);
        long day = today(helper);
        play(helper, inn, day, NOON);
        Settler first = visitors(helper).get(0);

        play(helper, inn, day + 2, MORNING);
        helper.assertTrue(!first.isRemoved() && visitors(helper).size() == 1, "Two days later in the morning it is still here");
        play(helper, inn, day + 2, NOON);
        helper.assertTrue(first.isRemoved(), "After two days it has moved on");
        helper.assertTrue(visitors(helper).size() == 1 && !ids(visitors(helper)).contains(first.getUUID()), "Its bed is free, a new visitor took it");
        helper.assertTrue(chat.isEmpty(), "Nobody is told that it left: " + chat);
        TestPlayers.remove(player);
        end(helper, inn);
    }

    /** Once per visitor the player can ask it to stay longer; it works on a hit, a second ask is refused, and the visitor says goodbye. */
    private static void stayLonger(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 2));
        Inn inn = inn(helper, 1, 1, true);
        Inns.script(MasteryRolls.scripted(MasteryRolls.HIT));
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = listener(helper, chat);
        long day = today(helper);
        play(helper, inn, day, NOON);
        Settler visitor = visitors(helper).get(0);

        helper.assertTrue(Visitors.askToStay(helper.getLevel(), visitor) == Visitors.Ask.EXTENDED, "The ask works on a hit");
        helper.assertTrue(Visitors.askToStay(helper.getLevel(), visitor) == Visitors.Ask.ALREADY_ASKED, "A second ask is refused");
        Inns.script(null);
        play(helper, inn, day + 2, NOON);
        helper.assertTrue(!visitor.isRemoved(), "It stays two days more");
        helper.assertTrue(chat.isEmpty(), "No note while it stays: " + chat);
        UUID id = visitor.getUUID();
        play(helper, inn, day + 4, NOON);
        helper.assertTrue(visitor.isRemoved() && !ids(visitors(helper)).contains(id), "After four days it has moved on");
        helper.assertTrue(says(chat, "civmod.visitor.moved_on"), "A visitor the player asked to stay says goodbye: " + chat);
        TestPlayers.remove(player);
        end(helper, inn);
    }

    /** An ask that does not work leaves the stay as it was, cannot be repeated, and the goodbye still comes. */
    private static void stayLongerDeclined(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 2));
        Inn inn = inn(helper, 1, 1, true);
        Inns.script(MasteryRolls.scripted(MasteryRolls.MISS));
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = listener(helper, chat);
        long day = today(helper);
        play(helper, inn, day, NOON);
        Settler visitor = visitors(helper).get(0);

        helper.assertTrue(Visitors.askToStay(helper.getLevel(), visitor) == Visitors.Ask.DECLINED, "The ask does not work on a miss");
        helper.assertTrue(Visitors.askToStay(helper.getLevel(), visitor) == Visitors.Ask.ALREADY_ASKED, "It cannot be asked again");
        Inns.script(null);
        play(helper, inn, day + 2, NOON);
        helper.assertTrue(visitor.isRemoved(), "It goes after its two days");
        helper.assertTrue(says(chat, "civmod.visitor.moved_on"), "The goodbye comes: " + chat);
        TestPlayers.remove(player);
        end(helper, inn);
    }

    // --------------------------------------------------------------------------------------------------------- catch-up

    /**
     * Five days pass while nobody plays the checks: the visitors whose stay still runs are there when the Pantry loads, capped by
     * the free beds; the ones who would have left leave no trace.
     */
    private static void catchUp(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 2));
        Inn inn = inn(helper, 1, 2, true);
        long day = today(helper);
        play(helper, inn, day + 5, MORNING);

        long now = InnSlots.latest(helper.getLevel().getOverworldClockTime());
        List<Settler> visitors = visitors(helper);
        helper.assertTrue(visitors.size() == 2, "Two guest beds, two visitors: " + visitors.size());
        helper.assertTrue(inn.settlement().inn().visits().size() == 2, "Two stays: " + inn.settlement().inn().visits());
        helper.assertTrue(inn.settlement().inn().visits().values().stream().allMatch(visit -> visit.leaveSlot() > now),
                "Only stays that still run: " + inn.settlement().inn().visits().values().stream().map(Visit::leaveSlot).toList() + " at " + now);
        helper.assertTrue(inn.settlement().inn().lastSlot() == now, "The checks are played up to now: " + inn.settlement().inn().lastSlot());
        end(helper, inn);
    }

    /** The missed checks run with the same condition: without a Tavern Host nobody comes. */
    private static void catchUpNoHost(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 2));
        Inn inn = inn(helper, 1, 2, false);
        play(helper, inn, today(helper) + 5, MORNING);
        helper.assertTrue(visitors(helper).isEmpty() && inn.settlement().inn().visits().isEmpty(), "No Tavern Host, no visitors in the catch-up");
        end(helper, inn);
    }

    // ------------------------------------------------------------------------------------------------- the inn falls away

    /** A settlement that dissolves takes its visitors with it: a puff and a note, and no homeless settler is left behind. */
    private static void dissolved(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 2, true);
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = listener(helper, chat);
        play(helper, inn, today(helper), NOON);
        Settler visitor = visitors(helper).get(0);

        TestSettlements.dissolve(helper.getLevel(), inn.settlement());
        helper.assertTrue(visitor.isRemoved(), "The visitor vanishes");
        helper.assertTrue(says(chat, "civmod.visitor.dissolved"), "With a note: " + chat);
        helper.assertTrue(visitors(helper).isEmpty() && inn.host().orElseThrow().isHomeless(), "Only the settler is homeless, no visitor is");
        TestPlayers.remove(player);
        end(helper, inn);
    }

    /** A visitor whose stay is gone from the settlement (it ended while the visitor was unloaded) removes itself without a word. */
    private static void visitorWithoutStay(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 2, true);
        List<Component> chat = new ArrayList<>();
        ServerPlayer player = listener(helper, chat);
        play(helper, inn, today(helper), NOON);
        Settler visitor = visitors(helper).get(0);
        visitor.checkMembership(helper.getLevel());
        helper.assertFalse(visitor.isRemoved(), "A visitor with a stay stays");

        SettlementData.get(helper.getLevel()).updateInn(inn.settlement(), state -> state.withoutVisit(visitor.getUUID()));
        visitor.checkMembership(helper.getLevel());
        helper.assertTrue(visitor.isRemoved(), "A visitor without a stay goes");
        helper.assertTrue(chat.isEmpty(), "Quietly: " + chat);
        TestPlayers.remove(player);
        end(helper, inn);
    }

    /** A removed Pantry lets the visitor stay until the next morning; it leaves then. */
    private static void pantryRemoved(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 2, true);
        long day = today(helper);
        play(helper, inn, day, NOON);
        Settler visitor = visitors(helper).get(0);

        SettlementManager.remove(SettlementData.get(helper.getLevel()), inn.pantry(), day, SettlementManager.Listener.NONE);
        play(helper, inn, day, EVENING);
        helper.assertTrue(!visitor.isRemoved(), "The visitor stays through the evening");
        helper.assertTrue(visitors(helper).size() == 1, "Nobody new comes to a Pantry that is gone: " + visitors(helper).size());
        play(helper, inn, day + 1, MORNING);
        helper.assertTrue(visitor.isRemoved(), "It leaves the next morning");
        helper.assertTrue(!inn.settlement().inn().isGuestBed(helper.absolutePos(BED_SPOTS.get(0))), "The guest beds of a removed Pantry are free beds again");
        end(helper, inn);
    }

    /** A guest bed that is unbound moves its visitor to another free guest bed; with none left the visitor leaves the next morning. */
    private static void guestBedUnbound(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 2, true);
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        long day = today(helper);
        play(helper, inn, day, NOON);
        Settler visitor = visitors(helper).get(0);
        BlockPos first = helper.absolutePos(BED_SPOTS.get(0));
        BlockPos second = helper.absolutePos(BED_SPOTS.get(1));

        helper.assertTrue(GuestBeds.toggle(level, data, inn.pantry(), first) == GuestBeds.Result.UNBOUND, "The first guest bed is unbound");
        play(helper, inn, day, EVENING);
        helper.assertTrue(inn.settlement().inn().visitIn(second).filter(visit -> visit.id().equals(visitor.getUUID())).isPresent(),
                "The visitor moved to the other guest bed: " + inn.settlement().inn().visits());

        helper.assertTrue(GuestBeds.toggle(level, data, inn.pantry(), second) == GuestBeds.Result.UNBOUND, "The last guest bed is unbound");
        play(helper, inn, day + 1, MORNING);
        helper.assertTrue(visitor.isRemoved(), "Without a guest bed it leaves the next morning");
        end(helper, inn);
    }

    /** A Tavern Host who is gone changes nothing for the visitor who is there; only the next check lapses. */
    private static void hostBreak(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 2, true);
        long day = today(helper);
        play(helper, inn, day, NOON);
        TestAssignments.unassign(SettlementData.get(helper.getLevel()), inn.pantry());
        play(helper, inn, day, EVENING);
        helper.assertTrue(visitors(helper).size() == 1, "The visitor stays and no second one comes: " + visitors(helper).size());
        end(helper, inn);
    }

    /** Whether the vanilla home POI of the bed still has its ticket, so a Villager may take it. */
    private static boolean poiFree(ServerLevel level, BlockPos bed) {
        return level.getPoiManager().getInRange(type -> type.is(PoiTypes.HOME), bed, 0, PoiManager.Occupancy.HAS_SPACE)
                .anyMatch(record -> record.getPos().equals(bed));
    }

    /**
     * A guest bed is claimed in the vanilla POI system, so a Villager cannot take it as its home. Unbinding it, and the fall of
     * its Pantry, free it again.
     */
    private static void guestBedPoi(GameTestHelper helper) {
        Inn inn = inn(helper, 1, 2, false);
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos first = helper.absolutePos(BED_SPOTS.get(0));
        BlockPos second = helper.absolutePos(BED_SPOTS.get(1));
        helper.assertTrue(!poiFree(level, first) && !poiFree(level, second), "Bound guest beds are claimed");

        helper.assertTrue(GuestBeds.toggle(level, data, inn.pantry(), first) == GuestBeds.Result.UNBOUND, "The first guest bed is unbound");
        helper.assertTrue(poiFree(level, first), "An unbound bed is free for everybody again");
        helper.assertTrue(!poiFree(level, second), "The other guest bed stays claimed");

        long day = today(helper);
        SettlementManager.remove(data, inn.pantry(), day, SettlementManager.Listener.NONE);
        play(helper, inn, day, EVENING);
        helper.assertTrue(poiFree(level, second), "The guest bed of a removed Pantry is free again");
        end(helper, inn);
    }

    /** Only the first Pantries with guest beds up to {@code maxInnsPerSettlement} are inns; the others draw nobody. */
    private static void innLimit(GameTestHelper helper) {
        InnRules.use(rules(1.0, 0.0, 3));
        Inn inn = inn(helper, 1, 1, true);
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        TestSettlements.setBlock(helper, SECOND_PANTRY, ModBlocks.PANTRY_BLOCK.get());
        BlockPos second = helper.absolutePos(SECOND_PANTRY);
        Settler secondHost = host(helper, second, new BlockPos(10, 1, 2));
        BlockPos bed = VillagerConversionGameTests.bed(helper, new BlockPos(11, 1, 5));
        int limit = CivModConfig.MAX_INNS_PER_SETTLEMENT.get();
        CivModConfig.MAX_INNS_PER_SETTLEMENT.set(1);
        try {
            helper.assertTrue(GuestBeds.toggle(level, data, second, bed) == GuestBeds.Result.BOUND_OVER_LIMIT, "The second inn is over the limit");
            long day = today(helper);
            play(helper, inn, day, NOON);
            List<Visit> stays = List.copyOf(inn.settlement().inn().visits().values());
            helper.assertTrue(stays.size() == 1 && stays.get(0).pantry().equals(inn.pantry()), "Only the first inn has a visitor: " + stays);

            CivModConfig.MAX_INNS_PER_SETTLEMENT.set(2);
            play(helper, inn, day, EVENING);
            helper.assertTrue(inn.settlement().inn().visitsOf(second).size() == 1, "With room for two inns the second one draws too: " + inn.settlement().inn().visits());
        } finally {
            CivModConfig.MAX_INNS_PER_SETTLEMENT.set(limit);
        }
        secondHost.discard();
        TestAssignments.unassign(data, second);
        end(helper, inn);
    }
}
