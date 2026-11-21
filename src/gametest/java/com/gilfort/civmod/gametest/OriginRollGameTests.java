package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.origin.Ancestor;
import com.gilfort.civmod.origin.Channel;
import com.gilfort.civmod.origin.Compass;
import com.gilfort.civmod.origin.Gender;
import com.gilfort.civmod.origin.OriginData;
import com.gilfort.civmod.origin.OriginEntry;
import com.gilfort.civmod.origin.OriginPlaces;
import com.gilfort.civmod.origin.OriginRoller;
import com.gilfort.civmod.origin.OriginText;
import com.gilfort.civmod.origin.Place;
import com.gilfort.civmod.origin.PlaceHit;
import com.gilfort.civmod.origin.RaceDefinition;
import com.gilfort.civmod.origin.RaceDefinition.NameList;
import com.gilfort.civmod.origin.RaceDefinition.SurnameRule;
import com.gilfort.civmod.origin.SettlerName;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.origin.SurnameType;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the rules of the origin roll and the lookup of real places (vault {@code Systems/Settler Origin.md}): what a
 * rolled origin looks like for each way a settler comes in, and what happens when a surname type or a place has nothing to offer.
 * The rolls go through {@link OriginRoller} with scripted random sources ({@link OriginRolls}); the channels themselves are tested
 * in {@link OriginArrivalGameTests}.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class OriginRollGameTests {

    private static final Identifier PLAINS = CivMod.id("plains");
    private static final Identifier STEPPE = CivMod.id("steppe");
    private static final Identifier DESERT = CivMod.id("desert");
    private static final Identifier FOREST = CivMod.id("forest");
    private static final Identifier COAST = CivMod.id("coast");
    private static final int ROLLS = 400;
    /** The draw of the surname type that lands on the second type of Human's weights (trade) for a man at a village. */
    private static final int TRADE_DRAW_AT_VILLAGE = 40;

    private OriginRollGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "origin_woman_never_patronymic", OriginRollGameTests::womanNeverPatronymic);
            register(helper, "origin_no_surname_type_left", OriginRollGameTests::noSurnameTypeLeft);
            register(helper, "origin_trade_ancestor", OriginRollGameTests::tradeAncestor);
            register(helper, "origin_place_surname", OriginRollGameTests::placeSurname);
            register(helper, "origin_place_without_hit_drops_out", OriginRollGameTests::placeWithoutHitDropsOut);
            register(helper, "origin_far_away_without_hits", OriginRollGameTests::farAwayWithoutHits);
            register(helper, "origin_place_by_channel", OriginRollGameTests::placeByChannel);
            register(helper, "origin_turning_point_fits_place", OriginRollGameTests::turningPointFitsPlace);
            register(helper, "origin_reason_by_channel", OriginRollGameTests::reasonByChannel);
            register(helper, "origin_first_names_avoid_used", OriginRollGameTests::firstNamesAvoidUsed);
            register(helper, "origin_name_culture", OriginRollGameTests::nameCulture);
            register(helper, "origin_lookup_one_type_per_tick", OriginRollGameTests::lookupOneTypePerTick);
            register(helper, "origin_lookup_in_world", OriginRollGameTests::lookupInWorld);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    private static RaceDefinition human() {
        return OriginData.race(Races.HUMAN).orElseThrow();
    }

    private static OriginRoller.Arrival arrival(Channel channel, Map<Identifier, PlaceHit> hits) {
        return new OriginRoller.Arrival(channel, hits, Optional.empty(), false, Set.of());
    }

    private static SettlerOrigin roll(OriginRoller.Arrival arrival, RandomSource random) {
        return OriginRoller.roll(human(), OriginData.firstNames(human(), OriginData.DEFAULT_CULTURE), arrival, random);
    }

    private static SettlerName.Surname surname(SettlerOrigin origin) {
        return origin.name().orElseThrow().surname();
    }

    private static Map<Identifier, PlaceHit> hits() {
        return Map.of(PLAINS, new PlaceHit(Compass.SOUTH, 1200), STEPPE, new PlaceHit(Compass.EAST, 3000),
                DESERT, new PlaceHit(Compass.WEST, 4500), COAST, new PlaceHit(Compass.NORTH, 4900));
    }

    // ---------------------------------------------------------------- tests

    /** A woman never gets a patronymic, a man never a matronymic; the same draws give a man his patronymic. */
    private static void womanNeverPatronymic(GameTestHelper helper) {
        RandomSource random = RandomSource.create(7L);
        int women = 0;
        for (int i = 0; i < ROLLS; i++) {
            SettlerOrigin origin = roll(arrival(Channel.STARTER, hits()), random);
            SurnameType type = surname(origin).type();
            helper.assertFalse(origin.gender() == Gender.FEMALE && type == SurnameType.PATRONYMIC, "A woman with a patronymic");
            helper.assertFalse(origin.gender() == Gender.MALE && type == SurnameType.MATRONYMIC, "A man with a matronymic");
            helper.assertFalse(type == SurnameType.NONE || type == SurnameType.CLAN, "Human weights switch " + type + " off");
            women += origin.gender() == Gender.FEMALE ? 1 : 0;
        }
        helper.assertTrue(women > ROLLS / 4 && women < ROLLS * 3 / 4, "Both genders are rolled: " + women + " women of " + ROLLS);

        SettlerOrigin woman = roll(arrival(Channel.VILLAGER, Map.of()), OriginRolls.picks(OriginRolls.FEMALE, 0, 0, 0));
        SettlerOrigin man = roll(arrival(Channel.VILLAGER, Map.of()), OriginRolls.picks(OriginRolls.MALE, 0, 0, 0));
        helper.assertTrue(surname(man).type() == SurnameType.PATRONYMIC, "The first draw is a patronymic for a man: " + surname(man));
        helper.assertTrue(surname(woman).type() == SurnameType.TRADE, "The same draw falls to the next type for a woman: " + surname(woman));
        helper.succeed();
    }

    /** A race whose only surname type is closed to the settler's gender gives that settler no surname. */
    private static void noSurnameTypeLeft(GameTestHelper helper) {
        RaceDefinition human = human();
        RaceDefinition onlyPatronymic = new RaceDefinition(human.id(), human.firstNames(),
                List.of(new SurnameRule(SurnameType.PATRONYMIC, 40, Optional.empty())), human.places(), human.upbringings(),
                human.turningPoints(), human.reasons(), Map.of());
        NameList names = OriginData.firstNames(onlyPatronymic, OriginData.DEFAULT_CULTURE);
        SettlerOrigin woman = OriginRoller.roll(onlyPatronymic, names, arrival(Channel.VILLAGER, Map.of()), OriginRolls.picks(OriginRolls.FEMALE, 0));
        SettlerOrigin man = OriginRoller.roll(onlyPatronymic, names, arrival(Channel.VILLAGER, Map.of()), OriginRolls.picks(OriginRolls.MALE, 0));
        helper.assertTrue(surname(woman).type() == SurnameType.NONE, "A woman has no surname: " + surname(woman));
        helper.assertTrue(woman.name().isPresent() && OriginText.name(woman.name().get()).getContents() instanceof PlainTextContents,
                "Her name is just the first name");
        helper.assertTrue(surname(man).type() == SurnameType.PATRONYMIC, "A man still has the patronymic: " + surname(man));
        helper.succeed();
    }

    /** A trade surname may name the father only when the trade fits the upbringing. */
    private static void tradeAncestor(GameTestHelper helper) {
        int craftsfolk = indexOf(human().upbringings(), "craftsfolk");
        int woodcutters = indexOf(human().upbringings(), "woodcutters");
        int smith = indexOf(human().surnameEntries().get(SurnameType.TRADE), "smith");
        SettlerOrigin fitting = roll(arrival(Channel.VILLAGER, Map.of()),
                OriginRolls.picks(OriginRolls.MALE, 0, craftsfolk, TRADE_DRAW_AT_VILLAGE, smith, 0));
        helper.assertTrue(surname(fitting).type() == SurnameType.TRADE && surname(fitting).param().equals(CivMod.id("smith").toString()),
                "A Smith: " + surname(fitting));
        helper.assertTrue(surname(fitting).ancestor().equals(Optional.of(Ancestor.FATHER)),
                "The first ancestor of a trade that fits the upbringing is the father: " + surname(fitting));
        Set<Object> ancestors = new HashSet<>();
        for (int draw = 0; draw < 4; draw++) {
            SettlerOrigin other = roll(arrival(Channel.VILLAGER, Map.of()),
                    OriginRolls.picks(OriginRolls.MALE, 0, woodcutters, TRADE_DRAW_AT_VILLAGE, smith, draw));
            ancestors.add(surname(other).ancestor().orElseThrow());
        }
        helper.assertTrue(ancestors.size() == 2 && !ancestors.contains(Ancestor.FATHER),
                "A trade that does not fit the upbringing is never the father's: " + ancestors);
        helper.succeed();
    }

    private static int indexOf(List<OriginEntry> entries, String path) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(CivMod.id(path))) {
                return i;
            }
        }
        throw new IllegalStateException("No entry " + path + " in " + entries);
    }

    /** A place surname names the biome type of the origin place, and only a real place gives one. */
    private static void placeSurname(GameTestHelper helper) {
        int placeDraw = 30;
        SettlerOrigin origin = roll(arrival(Channel.STARTER, Map.of(PLAINS, new PlaceHit(Compass.SOUTH, 1200))),
                OriginRolls.picks(OriginRolls.FEMALE, 0, 0, 0, placeDraw));
        helper.assertTrue(surname(origin).type() == SurnameType.PLACE && surname(origin).param().equals(PLAINS.toString()),
                "The place surname of a woman from the plains: " + surname(origin));
        RandomSource random = RandomSource.create(11L);
        for (int i = 0; i < ROLLS; i++) {
            SettlerOrigin villager = roll(arrival(Channel.VILLAGER, Map.of()), random);
            helper.assertFalse(surname(villager).type() == SurnameType.PLACE, "A village is no place for a surname");
        }
        helper.succeed();
    }

    /** Pilgrims and starters only come from a biome type that has a hit. */
    private static void placeWithoutHitDropsOut(GameTestHelper helper) {
        Map<Identifier, PlaceHit> onlyDesert = Map.of(DESERT, new PlaceHit(Compass.WEST, 4500));
        RandomSource random = RandomSource.create(3L);
        for (int i = 0; i < ROLLS; i++) {
            for (Channel channel : List.of(Channel.STARTER, Channel.PILGRIM)) {
                Place place = roll(arrival(channel, onlyDesert), random).place();
                helper.assertTrue(place.kind() == Place.Kind.BIOME && place.id().equals(DESERT) && place.direction().equals(Optional.of(Compass.WEST))
                        && place.days() == 5, channel.key() + " from the only place with a hit: " + place);
            }
        }
        helper.succeed();
    }

    /** With no hit at all a settler comes from far away, without a direction. */
    private static void farAwayWithoutHits(GameTestHelper helper) {
        RandomSource random = RandomSource.create(5L);
        for (int i = 0; i < ROLLS; i++) {
            for (Channel channel : List.of(Channel.STARTER, Channel.PILGRIM)) {
                SettlerOrigin origin = roll(arrival(channel, Map.of()), random);
                helper.assertTrue(origin.place().kind() == Place.Kind.FAR_AWAY && origin.place().direction().isEmpty() && origin.place().days() == 0,
                        channel.key() + " from far away: " + origin.place());
                helper.assertTrue(origin.turningPoint().isPresent(), "A turning point fits far away");
            }
        }
        helper.succeed();
    }

    /** Starters come from the nearer half of the places, pilgrims from any, eggs from any type without a direction, Villagers from a village. */
    private static void placeByChannel(GameTestHelper helper) {
        RandomSource random = RandomSource.create(9L);
        Set<Identifier> starters = new HashSet<>();
        Set<Identifier> pilgrims = new HashSet<>();
        Set<Identifier> eggs = new HashSet<>();
        for (int i = 0; i < ROLLS; i++) {
            starters.add(roll(arrival(Channel.STARTER, hits()), random).place().id());
            pilgrims.add(roll(arrival(Channel.PILGRIM, hits()), random).place().id());
            Place egg = roll(arrival(Channel.EGG, hits()), random).place();
            helper.assertTrue(egg.kind() == Place.Kind.BIOME && egg.direction().isEmpty() && egg.days() == 0, "An egg settler has no direction: " + egg);
            eggs.add(egg.id());
        }
        helper.assertTrue(starters.equals(Set.of(PLAINS, STEPPE)), "Starters from the two nearest places: " + starters);
        helper.assertTrue(pilgrims.equals(Set.of(PLAINS, STEPPE, DESERT, COAST)), "Pilgrims from any place with a hit: " + pilgrims);
        helper.assertTrue(eggs.equals(Set.of(PLAINS, STEPPE, DESERT, FOREST, COAST)), "Eggs from any biome type of the race: " + eggs);

        SettlerOrigin villager = roll(arrival(Channel.VILLAGER, hits()), random);
        helper.assertTrue(villager.place().equals(Place.village()), "A Villager comes from its village: " + villager.place());
        OriginRoller.Arrival mission = new OriginRoller.Arrival(Channel.MISSION, Map.of(), Optional.of(CivMod.id("desert_village")), false, Set.of());
        helper.assertTrue(roll(mission, random).place().equals(Place.mission(CivMod.id("desert_village"))), "A mission Villager takes the mission's place");
        OriginRoller.Arrival noPlace = new OriginRoller.Arrival(Channel.MISSION, Map.of(), Optional.empty(), false, Set.of());
        helper.assertTrue(roll(noPlace, random).place().equals(Place.village()), "Without a mission place it comes from the village");
        helper.succeed();
    }

    /** A turning point only fits the places it lists; one without a list fits every place. */
    private static void turningPointFitsPlace(GameTestHelper helper) {
        Map<Identifier, OriginEntry> byId = human().turningPoints().stream().collect(Collectors.toMap(OriginEntry::id, entry -> entry));
        RandomSource random = RandomSource.create(13L);
        Set<Identifier> seenInDesert = new HashSet<>();
        for (int i = 0; i < ROLLS; i++) {
            for (Map.Entry<Identifier, PlaceHit> hit : hits().entrySet()) {
                SettlerOrigin origin = roll(arrival(Channel.PILGRIM, Map.of(hit.getKey(), hit.getValue())), random);
                OriginEntry turningPoint = byId.get(origin.turningPoint().orElseThrow());
                helper.assertTrue(turningPoint.places().isEmpty() || turningPoint.places().contains(hit.getKey()),
                        turningPoint.id() + " does not fit " + hit.getKey());
                if (hit.getKey().equals(DESERT)) {
                    seenInDesert.add(turningPoint.id());
                }
            }
        }
        helper.assertTrue(seenInDesert.contains(CivMod.id("sandstorm")) && !seenInDesert.contains(CivMod.id("shipwreck")),
                "The desert has its own turning points and not the coast's: " + seenInDesert);
        helper.succeed();
    }

    /** The reason comes from the entries of the arrival channel. */
    private static void reasonByChannel(GameTestHelper helper) {
        Map<Identifier, OriginEntry> byId = human().reasons().stream().collect(Collectors.toMap(OriginEntry::id, entry -> entry));
        RandomSource random = RandomSource.create(17L);
        for (Channel channel : List.of(Channel.STARTER, Channel.PILGRIM, Channel.VILLAGER, Channel.MISSION, Channel.EGG)) {
            Set<Identifier> seen = new HashSet<>();
            for (int i = 0; i < ROLLS; i++) {
                Identifier reason = roll(arrival(channel, hits()), random).reason().orElseThrow();
                helper.assertTrue(byId.get(reason).channels().contains(channel.key()), reason + " is not a reason for " + channel.key());
                seen.add(reason);
            }
            long available = byId.values().stream().filter(entry -> entry.channels().contains(channel.key())).count();
            helper.assertTrue(seen.size() == available, "Every reason of " + channel.key() + " is rolled: " + seen.size() + " of " + available);
        }
        helper.assertTrue(roll(arrival(Channel.STARTER, hits()), random).reason().equals(Optional.of(CivMod.id("survivors"))), "Starters are survivors");
        helper.succeed();
    }

    /** A new settler avoids the first names of its settlement while another name is free. */
    private static void firstNamesAvoidUsed(GameTestHelper helper) {
        NameList names = OriginData.firstNames(human(), OriginData.DEFAULT_CULTURE);
        List<String> men = names.pool(Gender.MALE);
        Set<String> used = new HashSet<>(men.subList(0, men.size() - 1));
        OriginRoller.Arrival arrival = new OriginRoller.Arrival(Channel.STARTER, Map.of(), Optional.empty(), false, used);
        SettlerOrigin origin = roll(arrival, OriginRolls.picks(OriginRolls.MALE, 0));
        helper.assertTrue(origin.name().orElseThrow().first().equals(men.get(men.size() - 1)), "The one free name: " + origin.name());
        OriginRoller.Arrival everyName = new OriginRoller.Arrival(Channel.STARTER, Map.of(), Optional.empty(), false, new HashSet<>(men));
        helper.assertTrue(roll(everyName, OriginRolls.picks(OriginRolls.MALE, 0)).name().isPresent(), "All names used: a name is still rolled");
        OriginRoller.Arrival keep = new OriginRoller.Arrival(Channel.VILLAGER, Map.of(), Optional.empty(), true, Set.of());
        helper.assertTrue(roll(keep, OriginRolls.picks(OriginRolls.FEMALE, 0)).name().isEmpty(), "A settler that keeps its name gets no name parts");
        helper.succeed();
    }

    /** The config picks the name list; a culture the race has no list for falls back to English. */
    private static void nameCulture(GameTestHelper helper) {
        helper.assertTrue(CivModConfig.SETTLER_NAME_CULTURE.get().equals("en"), "The default culture is en");
        NameList english = OriginData.firstNames(human(), "en");
        helper.assertTrue(english.male().size() > 0 && english.female().size() > 0 && english.unisex().size() > 0,
                "The English list has male, female and unisex names");
        helper.assertTrue(OriginData.firstNames(human(), "xx").equals(english), "A culture without a list falls back to English");
        helper.succeed();
    }

    /** The lookup searches one biome type per call, keeps direction and distance of each hit, and drops types without one. */
    private static void lookupOneTypePerTick(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = SettlementManager.placeCenter(data, anchor, ModJobs.ADMINISTRATOR.get(), 1, Races.HUMAN, 0L,
                RandomSource.create(0L), SettlementManager.Listener.NONE).settlement().orElseThrow();
        List<String> searched = new ArrayList<>();
        OriginPlaces.script((level, origin, type) -> {
            searched.add(type.id().getPath());
            helper.assertTrue(origin.equals(anchor), "The search starts at the settlement: " + origin);
            return switch (type.id().getPath()) {
                case "plains" -> Optional.of(origin.offset(0, 0, 1200));
                case "steppe" -> Optional.of(origin.offset(3000, 0, 0));
                case "coast" -> Optional.of(origin.offset(-2300, 0, -2300));
                default -> Optional.empty();
            };
        });
        try {
            for (int step = 1; step <= 5; step++) {
                helper.assertTrue(OriginPlaces.advance(helper.getLevel(), data, settlement), "Step " + step + " searches");
                helper.assertTrue(searched.size() == step, "One biome type per step: " + searched);
            }
            helper.assertFalse(OriginPlaces.advance(helper.getLevel(), data, settlement), "The lookup is done after the last type");
            helper.assertTrue(searched.equals(List.of("plains", "steppe", "desert", "forest", "coast")), "In the order of the race: " + searched);
            Map<Identifier, PlaceHit> places = settlement.origins().places();
            helper.assertTrue(places.keySet().equals(Set.of(PLAINS, STEPPE, COAST)),
                    "Types without a hit have no entry: " + places.keySet());
            helper.assertTrue(places.get(PLAINS).equals(new PlaceHit(Compass.SOUTH, 1200)) && places.get(PLAINS).days() == 1, "Plains: " + places.get(PLAINS));
            helper.assertTrue(places.get(STEPPE).equals(new PlaceHit(Compass.EAST, 3000)) && places.get(STEPPE).days() == 3, "Steppe: " + places.get(STEPPE));
            helper.assertTrue(places.get(COAST).direction() == Compass.NORTH_WEST && places.get(COAST).blocks() == 3253, "Coast: " + places.get(COAST));
            helper.assertTrue(searched.size() == 5, "Nothing is searched twice: " + searched);
            helper.succeed();
        } finally {
            QuietOrigins.restore();
        }
    }

    /** The real search runs on the GameTest world: it finishes, and every hit lies inside the search radius. */
    private static void lookupInWorld(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = SettlementManager.placeCenter(data, anchor, ModJobs.ADMINISTRATOR.get(), 1, Races.HUMAN, 0L,
                RandomSource.create(0L), SettlementManager.Listener.NONE).settlement().orElseThrow();
        OriginPlaces.script(null);
        try {
            while (OriginPlaces.advance(helper.getLevel(), data, settlement)) {
                helper.assertTrue(settlement.origins().places().size() <= human().places().size(), "At most one hit per biome type");
            }
            helper.assertTrue(settlement.origins().lookupDone(), "The lookup finished");
            for (PlaceHit hit : settlement.origins().places().values()) {
                helper.assertTrue(hit.blocks() <= OriginPlaces.SEARCH_RADIUS_BLOCKS * 3 / 2, "Inside the radius of the search (a square): " + hit);
            }
            helper.succeed();
        } finally {
            QuietOrigins.restore();
        }
    }
}
