package com.gilfort.civmod.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSpawner;
import com.gilfort.civmod.origin.Compass;
import com.gilfort.civmod.origin.OriginData;
import com.gilfort.civmod.origin.OriginEntry;
import com.gilfort.civmod.origin.OriginText;
import com.gilfort.civmod.origin.Origins;
import com.gilfort.civmod.origin.Place;
import com.gilfort.civmod.origin.PlaceHit;
import com.gilfort.civmod.origin.RaceDefinition;
import com.gilfort.civmod.origin.Rescuer;
import com.gilfort.civmod.origin.SettlerName;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.origin.SurnameType;
import com.gilfort.civmod.registry.ModAttachments;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementOrigins;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestSettlements;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the origin of a settler through each way it comes in: starting settlers, the Priest's pilgrims, a converted Villager
 * (with and without a name or a mission place) and the spawn egg (vault {@code Systems/Settler Origin.md}). They check what a player
 * can see on the settler: the name, the gender, the place with its direction, the reason and who took it in.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class OriginArrivalGameTests {

    private static final Rescuer FOUNDER = new Rescuer(UUID.fromString("00000000-0000-0000-0000-0000000000f0"), "Founder", 3L);
    private static final Identifier PLAINS = CivMod.id("plains");
    private static final Identifier STEPPE = CivMod.id("steppe");
    private static final Identifier DESERT = CivMod.id("desert");
    private static final Identifier DESERT_VILLAGE = CivMod.id("desert_village");
    private static final BlockPos CENTER = new BlockPos(4, 1, 4);
    private static final BlockPos PILGRIM_BED = new BlockPos(1, 1, 1);

    private OriginArrivalGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "origin_starting_settlers", OriginArrivalGameTests::startingSettlers);
            register(helper, "origin_starting_settlers_far_away", OriginArrivalGameTests::startingSettlersFarAway);
            register(helper, "origin_starters_wait_for_lookup", OriginArrivalGameTests::startersWaitForLookup);
            register(helper, "origin_pilgrim", OriginArrivalGameTests::pilgrim);
            register(helper, "origin_converted_villager", OriginArrivalGameTests::convertedVillager);
            register(helper, "origin_converted_villager_keeps_name", OriginArrivalGameTests::convertedVillagerKeepsName);
            register(helper, "origin_converted_mission_villager", OriginArrivalGameTests::convertedMissionVillager);
            register(helper, "origin_egg_settler", OriginArrivalGameTests::eggSettler);
            register(helper, "origin_homeless_keeps_origin", OriginArrivalGameTests::homelessKeepsOrigin);
            register(helper, "origin_founder_recorded", OriginArrivalGameTests::founderRecorded);
            register(helper, "origin_hand_built_settler", OriginArrivalGameTests::handBuiltSettler);
            register(helper, "origin_save_round_trip", OriginArrivalGameTests::saveRoundTrip);
            register(helper, "origin_human_definition", OriginArrivalGameTests::humanDefinition);
            register(helper, "origin_texts_exist", OriginArrivalGameTests::textsExist);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    private static Map<Identifier, PlaceHit> hits() {
        return Map.of(PLAINS, new PlaceHit(Compass.SOUTH, 1200), STEPPE, new PlaceHit(Compass.EAST, 3000), DESERT, new PlaceHit(Compass.WEST, 4500));
    }

    /** The founder and a finished lookup: what a settlement of a founded world knows when its settlers arrive. */
    private static void knowWorld(SettlementData data, Settlement settlement, Map<Identifier, PlaceHit> hits) {
        data.updateOrigins(settlement, origins -> new SettlementOrigins(Optional.of(FOUNDER), hits, Optional.of(List.of())));
    }

    private static Settlement standalone(GameTestHelper helper, SettlementData data, BlockPos anchor) {
        return SettlementManager.placeCenter(data, anchor, com.gilfort.civmod.registry.ModJobs.ADMINISTRATOR.get(), 1, Races.HUMAN,
                helper.getLevel().getGameTime(), RandomSource.create(0L), SettlementManager.Listener.NONE).settlement().orElseThrow();
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE);
            }
        }
    }

    /** The common part of every origin: a name that shows in the name tag, a gender, an upbringing and a turning point. */
    private static void checkRolled(GameTestHelper helper, Settler settler) {
        SettlerOrigin origin = settler.getPast().origin().orElseThrow(() -> helper.assertionException("A settler without an origin"));
        SettlerName name = origin.name().orElseThrow(() -> helper.assertionException("A settler without a rolled name"));
        helper.assertTrue(settler.getName().equals(OriginText.name(name)), "The name tag is built from the name parts: " + settler.getName());
        helper.assertTrue(name.surname().type() != SurnameType.NONE && settler.getName().getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals("origin.name") && translatable.getArgs()[0].equals(name.first()),
                "A translatable name with the first name as argument: " + settler.getName());
        helper.assertTrue(origin.upbringing().isPresent() && origin.turningPoint().isPresent() && origin.reason().isPresent(),
                "Upbringing, turning point and reason: " + origin);
    }

    // ---------------------------------------------------------------- tests

    /** Starting settlers come from a nearby real region as survivors, taken in by the founder, each with its own first name. */
    private static void startingSettlers(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = standalone(helper, data, anchor);
        knowWorld(data, settlement, hits());
        List<Settler> settlers = SettlerSpawner.arrive(helper.getLevel(), data, settlement, anchor);
        try {
            helper.assertTrue(!settlers.isEmpty(), "Starting settlers arrived");
            Set<String> firstNames = new HashSet<>();
            for (Settler settler : settlers) {
                checkRolled(helper, settler);
                SettlerOrigin origin = settler.getPast().origin().orElseThrow();
                firstNames.add(origin.name().orElseThrow().first());
                Place place = origin.place();
                helper.assertTrue(place.kind() == Place.Kind.BIOME && (place.id().equals(PLAINS) || place.id().equals(STEPPE)),
                        "From one of the two nearest regions: " + place);
                helper.assertTrue(place.direction().equals(Optional.of(place.id().equals(PLAINS) ? Compass.SOUTH : Compass.EAST))
                        && place.days() == (place.id().equals(PLAINS) ? 1 : 3), "Direction and days of walking: " + place);
                helper.assertTrue(origin.reason().equals(Optional.of(CivMod.id("survivors"))), "Survivors: " + origin.reason());
                helper.assertTrue(origin.rescuer().equals(Optional.of(FOUNDER)), "Taken in by the founder: " + origin.rescuer());
            }
            helper.assertTrue(firstNames.size() == settlers.size(), "First names are not repeated within the settlement: " + firstNames);
            helper.succeed();
        } finally {
            settlers.forEach(Settler::discard);
        }
    }

    /** Without any hit the starters come from far away, without a direction. */
    private static void startingSettlersFarAway(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = standalone(helper, data, anchor);
        List<Settler> settlers = SettlerSpawner.arrive(helper.getLevel(), data, settlement, anchor);
        try {
            for (Settler settler : settlers) {
                checkRolled(helper, settler);
                SettlerOrigin origin = settler.getPast().origin().orElseThrow();
                helper.assertTrue(origin.place().equals(Place.farAway()), "From far away: " + origin.place());
                helper.assertTrue(origin.rescuer().isEmpty(), "No founder is recorded for a settlement that was not founded by a player: " + origin.rescuer());
            }
            helper.succeed();
        } finally {
            settlers.forEach(Settler::discard);
        }
    }

    /** The starters wait until the places are looked up, but never longer than the wait; settlers that have arrived are not due again. */
    private static void startersWaitForLookup(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        Settlement settlement = standalone(helper, data, helper.absolutePos(new BlockPos(2, 1, 2)));
        long due = settlement.settlersDue();
        long giveUp = due + SettlerSpawner.LOOKUP_WAIT_TICKS;
        helper.assertFalse(SettlerSpawner.startersDue(settlement, due - 1), "Not before the timer");
        helper.assertFalse(SettlerSpawner.startersDue(settlement, due), "The timer ran out, the lookup has not started");
        helper.assertFalse(SettlerSpawner.startersDue(settlement, giveUp - 1), "Still waiting for the lookup");
        helper.assertTrue(SettlerSpawner.startersDue(settlement, giveUp), "No settler waits for ever for a lookup that does not come");
        data.updateOrigins(settlement, origins -> origins.withLookup(List.of(PLAINS, STEPPE)));
        helper.assertFalse(SettlerSpawner.startersDue(settlement, due), "A lookup that is half done is not enough");
        data.updateOrigins(settlement, origins -> origins.searched(Optional.empty()).searched(Optional.empty()));
        helper.assertFalse(SettlerSpawner.startersDue(settlement, due - 1), "A finished lookup does not bring them before the timer");
        helper.assertTrue(SettlerSpawner.startersDue(settlement, due), "The timer ran out and the lookup is done");
        data.settlersArrived(settlement);
        helper.assertFalse(SettlerSpawner.startersDue(settlement, giveUp), "Settlers that have arrived are not due again");
        helper.succeed();
    }

    /** A pilgrim of the Priest's prayer comes from a biome type with a hit and was taken in by the founder. */
    private static void pilgrim(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        TestSettlements.centerAt(level, helper.absolutePos(CENTER));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(helper.absolutePos(CENTER)).orElseThrow();
        data.settlersArrived(settlement);
        knowWorld(data, settlement, hits());
        VillagerConversionGameTests.bed(helper, PILGRIM_BED);
        Optional<Settler> recruit = SettlerSpawner.recruit(level, data, settlement, helper.absolutePos(CENTER));
        helper.assertTrue(recruit.isPresent(), "The Priest's prayer brings a pilgrim");
        Settler settler = recruit.get();
        try {
            checkRolled(helper, settler);
            SettlerOrigin origin = settler.getPast().origin().orElseThrow();
            helper.assertTrue(hits().containsKey(origin.place().id()) && origin.place().direction().isPresent() && origin.place().days() >= 1,
                    "From a biome type with a hit: " + origin.place());
            helper.assertTrue(origin.rescuer().equals(Optional.of(FOUNDER)), "Taken in by the founder: " + origin.rescuer());
            Set<Identifier> pilgrimReasons = Set.of(CivMod.id("followed_prayer"), CivMod.id("seeking_shelter"), CivMod.id("saw_the_shrine"));
            helper.assertTrue(pilgrimReasons.contains(origin.reason().orElseThrow()), "A reason of the pilgrims: " + origin.reason());
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /**
     * Runs a conversion to its end and checks the new settler.
     *
     * @param prepare what to do to the Villager before it is fed
     * @param check   what the new settler must look like
     */
    private static void converted(GameTestHelper helper, Consumer<Villager> prepare, Consumer<Settler> check) {
        List<long[]> forced = VillagerConversionGameTests.force(helper, 9);
        Settlement settlement = VillagerConversionGameTests.settlement(helper);
        knowWorld(SettlementData.get(helper.getLevel()), settlement, hits());
        VillagerConversionGameTests.bed(helper, VillagerConversionGameTests.BED);
        Villager villager = VillagerConversionGameTests.villager(helper, VillagerConversionGameTests.VILLAGER);
        prepare.accept(villager);
        VillagerConversionGameTests.convert(helper, villager);
        Vec3 pos = villager.position();
        VillagerConversionGameTests.hurry(helper, villager);
        helper.succeedWhen(() -> {
            helper.assertTrue(villager.isRemoved(), "The Villager is gone");
            Settler settler = VillagerConversionGameTests.settlerNear(helper, settlement, pos).orElseThrow(() -> helper.assertionException("No Settler"));
            check.accept(settler);
            settler.discard();
            VillagerConversionGameTests.unforce(helper, forced);
        });
    }

    /** A converted Villager comes from its village; it gets a rolled name and a reason of the Villagers, and the founder took it in. */
    private static void convertedVillager(GameTestHelper helper) {
        converted(helper, villager -> { }, settler -> {
            checkRolled(helper, settler);
            SettlerOrigin origin = settler.getPast().origin().orElseThrow();
            helper.assertTrue(origin.place().equals(Place.village()), "From its village: " + origin.place());
            helper.assertTrue(origin.rescuer().equals(Optional.of(FOUNDER)), "Taken in by the founder: " + origin.rescuer());
            Set<Identifier> reasons = Set.of(CivMod.id("village_too_poor"), CivMod.id("offered_bread"), CivMod.id("tired_of_waiting"));
            helper.assertTrue(reasons.contains(origin.reason().orElseThrow()), "A reason of the Villagers: " + origin.reason());
        });
    }

    /** A Villager with its own custom name keeps it, without a surname, and still gets an origin. */
    private static void convertedVillagerKeepsName(GameTestHelper helper) {
        converted(helper, villager -> villager.setCustomName(Component.literal("Hilde")), settler -> {
            helper.assertTrue(settler.getName().getString().equals("Hilde"), "The Villager's name is kept: " + settler.getName());
            SettlerOrigin origin = settler.getPast().origin().orElseThrow(() -> helper.assertionException("No origin"));
            helper.assertTrue(origin.name().isEmpty(), "No first name and no surname are rolled: " + origin.name());
            helper.assertTrue(origin.place().equals(Place.village()) && origin.upbringing().isPresent(), "The rest of the origin is rolled: " + origin);
        });
    }

    /** A Villager a mission brought takes the place of the mission and a reason of the missions. */
    private static void convertedMissionVillager(GameTestHelper helper) {
        converted(helper, villager -> villager.setData(ModAttachments.MISSION_PLACE, Optional.of(DESERT_VILLAGE)), settler -> {
            SettlerOrigin origin = settler.getPast().origin().orElseThrow(() -> helper.assertionException("No origin"));
            helper.assertTrue(origin.place().equals(Place.mission(DESERT_VILLAGE)), "From the place of the mission: " + origin.place());
            Set<Identifier> reasons = Set.of(CivMod.id("brought_out"), CivMod.id("escorted_here"));
            helper.assertTrue(reasons.contains(origin.reason().orElseThrow()), "A reason of the missions: " + origin.reason());
            helper.assertTrue(origin.rescuer().equals(Optional.of(FOUNDER)), "Taken in by the founder: " + origin.rescuer());
        });
    }

    /** A settler from the spawn egg comes from a biome type of its race without a direction, and nobody took it in. */
    private static void eggSettler(GameTestHelper helper) {
        Settler settler = SettlerSpawnEggGameTests.spawn(helper, ModItems.SETTLER_SPAWN_EGG.get().getDefaultInstance(), new BlockPos(1, 1, 1));
        try {
            checkRolled(helper, settler);
            SettlerOrigin origin = settler.getPast().origin().orElseThrow();
            RaceDefinition race = OriginData.race(settler.getRace()).orElseThrow();
            helper.assertTrue(race.places().stream().anyMatch(entry -> entry.id().equals(origin.place().id())) && origin.place().direction().isEmpty(),
                    "A biome type of its race, no direction: " + origin.place());
            helper.assertTrue(origin.rescuer().isEmpty(), "Nobody took it in: " + origin.rescuer());
            helper.assertTrue(settler.isHomeless(), "The egg settler stays homeless");
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /** A settler who loses its home keeps its origin untouched. */
    private static void homelessKeepsOrigin(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = standalone(helper, data, anchor);
        knowWorld(data, settlement, hits());
        List<Settler> settlers = SettlerSpawner.arrive(helper.getLevel(), data, settlement, anchor);
        try {
            Settler settler = settlers.get(0);
            SettlerOrigin before = settler.getPast().origin().orElseThrow();
            Component nameBefore = settler.getName();
            settler.becomeHomeless();
            helper.assertTrue(settler.isHomeless(), "The settler is homeless");
            helper.assertTrue(settler.getPast().origin().equals(Optional.of(before)) && settler.getName().equals(nameBefore), "Origin and name are kept");
            helper.succeed();
        } finally {
            settlers.forEach(Settler::discard);
        }
    }

    /** A player who places a Center Block that founds a settlement is recorded as its founder, with name and day. */
    private static void founderRecorded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(CENTER);
        ServerPlayer player = TestPlayers.add(helper, Vec3.atBottomCenterOf(center.north(2)));
        try {
            level.setBlock(center, ModBlocks.HUMAN_CENTER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            SettlementWorld.placedByPlayer(level, center, player, 1);
            Settlement settlement = SettlementData.get(level).settlementAt(center).orElseThrow(() -> helper.assertionException("No settlement founded"));
            Rescuer founder = settlement.origins().founder().orElseThrow(() -> helper.assertionException("No founder recorded"));
            helper.assertTrue(founder.player().equals(player.getUUID()) && founder.name().equals(player.getName().getString()),
                    "The placing player: " + founder);
            helper.assertTrue(founder.day() == com.gilfort.civmod.settlement.DayClock.day(level), "The day of the founding: " + founder.day());
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
        }
    }

    /** A settler built without an origin (an old save, a test) has none, keeps its name and still counts as a used first name. */
    private static void handBuiltSettler(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = standalone(helper, data, anchor);
        Settler settler = ModEntityTypes.SETTLER.get().create(helper.getLevel(), EntitySpawnReason.EVENT);
        settler.setCustomName(Component.literal("Hilde"));
        settler.snapTo(anchor.getX(), anchor.getY(), anchor.getZ(), 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        try {
            helper.assertTrue(settler.getPast().origin().isEmpty(), "No origin: " + settler.getPast().origin());
            helper.assertTrue(Origins.usedFirstNames(helper.getLevel(), settlement).equals(Set.of("Hilde")), "Counts with its name");
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /** The origin of a settler and the founder and places of a settlement survive a save and a load; a save without them loads empty. */
    private static void saveRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerOrigin origin = new SettlerOrigin(com.gilfort.civmod.origin.Gender.FEMALE,
                Optional.of(new SettlerName("Mira", new SettlerName.Surname(SurnameType.TRADE, CivMod.id("smith").toString(),
                        Optional.of(com.gilfort.civmod.origin.Ancestor.GRANDFATHER)))),
                Place.biome(STEPPE, new PlaceHit(Compass.NORTH_EAST, 2500)), Optional.of(CivMod.id("farmers")), Optional.empty(),
                Optional.of(CivMod.id("survivors")), Optional.of(FOUNDER));
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.getPast().setOrigin(origin);
        settler.setCustomName(OriginText.name(origin.name().orElseThrow()));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        Settler loaded = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.LOAD);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
        helper.assertTrue(loaded.getPast().origin().equals(Optional.of(origin)), "Origin after the round trip: " + loaded.getPast().origin());
        helper.assertTrue(loaded.getName().equals(settler.getName()), "The name tag after the round trip: " + loaded.getName());

        Settler old = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        TagValueOutput oldOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        old.saveWithoutId(oldOutput);
        helper.assertTrue(!oldOutput.buildResult().contains("origin"), "A settler without an origin saves none");

        SettlementData data = new SettlementData();
        Settlement settlement = standalone(helper, data, helper.absolutePos(new BlockPos(2, 1, 2)));
        data.updateOrigins(settlement, origins -> new SettlementOrigins(Optional.of(FOUNDER), hits(), Optional.of(List.of(DESERT, PLAINS))));
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        SettlementData copy = SettlementData.CODEC.parse(ops, SettlementData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        SettlementOrigins saved = copy.settlements().iterator().next().origins();
        helper.assertTrue(saved.equals(settlement.origins()), "Settlement origins after the round trip: " + saved);
        helper.succeed();
    }

    /** Human has what the story asks for: five biome types with biomes behind their tags, 15 upbringings, 12 turning points, reasons per channel. */
    private static void humanDefinition(GameTestHelper helper) {
        RaceDefinition human = OriginData.race(Races.HUMAN).orElseThrow(() -> helper.assertionException("Human has no origin definition"));
        helper.assertTrue(human.places().size() == 5, "Five biome types: " + human.places().size());
        helper.assertTrue(human.upbringings().size() == 15 && human.turningPoints().size() == 12, "15 upbringings and 12 turning points");
        helper.assertTrue(human.upbringings().stream().allMatch(entry -> entry.jobs().size() >= 1 && entry.jobs().size() <= 2),
                "An upbringing favours one or two Base Jobs");
        Map<SurnameType, Integer> weights = new java.util.EnumMap<>(SurnameType.class);
        human.surnames().forEach(rule -> weights.put(rule.type(), rule.weight()));
        helper.assertTrue(weights.get(SurnameType.PATRONYMIC) == 40 && weights.get(SurnameType.TRADE) == 30 && weights.get(SurnameType.PLACE) == 10
                && weights.get(SurnameType.TRAIT) == 20 && weights.get(SurnameType.MATRONYMIC) == 0, "Human surname weights: " + weights);
        for (String channel : List.of("starter", "pilgrim", "villager", "mission", "egg")) {
            long reasons = human.reasons().stream().filter(entry -> entry.channels().contains(channel)).count();
            helper.assertTrue(reasons >= 1 && reasons <= 3, "Reasons for " + channel + ": " + reasons);
        }
        HolderGetter<Biome> biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        for (OriginEntry place : human.places()) {
            for (ExtraCodecs.TagOrElementLocation location : place.biomes()) {
                boolean found = location.tag()
                        ? biomes.get(TagKey.create(Registries.BIOME, location.id())).map(set -> set.size() > 0).orElse(false)
                        : biomes.get(ResourceKey.create(Registries.BIOME, location.id())).isPresent();
                helper.assertTrue(found, "Biomes behind " + location + " of " + place.id());
            }
        }
        helper.succeed();
    }

    /** Every entry a race ships has its text in the language file, and so has every place a mission names. */
    private static void textsExist(GameTestHelper helper) {
        JsonObject lang = GuiTextGameTests.lang(helper);
        RaceDefinition human = OriginData.race(Races.HUMAN).orElseThrow();
        List<String> keys = new java.util.ArrayList<>();
        human.places().forEach(entry -> {
            keys.add(OriginText.key("place", entry.id()));
            keys.add(OriginText.key("surname.place", entry.id()));
        });
        human.upbringings().forEach(entry -> keys.add(OriginText.key("upbringing", entry.id())));
        human.turningPoints().forEach(entry -> keys.add(OriginText.key("turning_point", entry.id())));
        human.reasons().forEach(entry -> keys.add(OriginText.key("reason", entry.id())));
        human.surnameEntries().get(SurnameType.TRADE).forEach(entry -> keys.add(OriginText.key("surname.trade", entry.id())));
        human.surnameEntries().get(SurnameType.TRAIT).forEach(entry -> keys.add(OriginText.key("surname.trait", entry.id())));
        keys.add(OriginText.key("place", Place.VILLAGE_ID));
        keys.add(OriginText.key("place", Place.FAR_AWAY_ID));
        for (Mission mission : Missions.all(helper.getLevel().registryAccess()).values()) {
            mission.place().ifPresent(place -> keys.add(OriginText.key("place", place)));
        }
        for (String key : keys) {
            helper.assertTrue(lang.has(key), "Language key " + key);
        }
        for (String key : List.of("origin.name", "origin.surname.patronymic", "origin.surname.matronymic", "origin.journey", "origin.journey.unknown",
                "origin.days.one", "origin.days.many")) {
            helper.assertTrue(lang.has(key), "Language key " + key);
        }
        for (Compass direction : Compass.values()) {
            helper.assertTrue(lang.has(direction.langKey()), "Language key " + direction.langKey());
        }
        for (com.gilfort.civmod.origin.Ancestor ancestor : com.gilfort.civmod.origin.Ancestor.values()) {
            helper.assertTrue(lang.has(ancestor.langKey()), "Language key " + ancestor.langKey());
        }
        helper.succeed();
    }
}
