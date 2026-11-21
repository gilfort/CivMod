package com.gilfort.civmod.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.api.DiaryEntryType;
import com.gilfort.civmod.chronicle.Chronicle;
import com.gilfort.civmod.chronicle.DiaryEntry;
import com.gilfort.civmod.job.PlayerTalk;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSpawner;
import com.gilfort.civmod.origin.Ancestor;
import com.gilfort.civmod.origin.Gender;
import com.gilfort.civmod.origin.OriginData;
import com.gilfort.civmod.origin.OriginLine;
import com.gilfort.civmod.origin.OriginText;
import com.gilfort.civmod.origin.Origins;
import com.gilfort.civmod.origin.Place;
import com.gilfort.civmod.origin.RaceDefinition;
import com.gilfort.civmod.origin.Rescuer;
import com.gilfort.civmod.origin.SettlerName;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.origin.SurnameType;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementOrigins;
import com.google.gson.JsonObject;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the diary and the talk (vault {@code Systems/Chronicle.md}, {@code Systems/Settler Origin.md}): the arrival entry, the
 * entry when a settler loses its home, the 20-entry cap, the addon API, a player's Shift + right-click on a settler, what it tells,
 * the small talk and the Entertainment of the first talk per day. The chat line cannot be read back from a test player, so the checks
 * read the line that the talk returns.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ChronicleGameTests {

    private static final Rescuer FOUNDER = new Rescuer(UUID.fromString("00000000-0000-0000-0000-0000000000f1"), "Founder", 3L);
    private static final Identifier TEST_TYPE = CivMod.id("test_entry");
    private static final Identifier PLAINS = CivMod.id("plains");
    private static final Identifier CRAFTSFOLK = CivMod.id("craftsfolk");
    private static final Identifier SMITH = CivMod.id("smith");
    private static final Identifier MINER_TRADE = CivMod.id("miner");
    private static final Identifier RAIDERS = CivMod.id("raiders");
    /** The Entertainment of the first talk per day, as the spec states it (not read from the code under test). */
    private static final double TALK_POINTS = 0.5;
    private static final Vec3 SETTLER_AT =new Vec3(2.5, 1.0, 2.5);
    private static final Vec3 PLAYER_AT = new Vec3(3.5, 1.0, 2.5);
    private static final BlockPos ANCHOR = new BlockPos(2, 1, 2);

    private ChronicleGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(CivModApi.DIARY_ENTRY_TYPE_REGISTRY, helper -> helper.register(TEST_TYPE, DiaryEntryType.of("diary.test.entry")));
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "chronicle_arrival_entry", ChronicleGameTests::arrivalEntry);
            register(helper, "chronicle_arrival_entry_without_rescuer", ChronicleGameTests::arrivalEntryWithoutRescuer);
            register(helper, "chronicle_homeless_entry", ChronicleGameTests::homelessEntry);
            register(helper, "chronicle_egg_settler_has_no_homeless_entry", ChronicleGameTests::eggSettlerHasNoHomelessEntry);
            register(helper, "chronicle_diary_cap_keeps_arrival", ChronicleGameTests::diaryCapKeepsArrival);
            register(helper, "chronicle_addon_api", ChronicleGameTests::addonApi);
            register(helper, "chronicle_save_round_trip", ChronicleGameTests::saveRoundTrip);
            register(helper, "chronicle_window_info", ChronicleGameTests::windowInfo);
            register(helper, "chronicle_texts_exist", ChronicleGameTests::textsExist);
            register(helper, "chronicle_talk_shift_empty_hand", ChronicleGameTests::talkShiftEmptyHand);
            register(helper, "chronicle_talk_other_clicks_open_window", ChronicleGameTests::otherClicksOpenWindow);
            register(helper, "chronicle_talk_three_origin_lines", ChronicleGameTests::threeOriginLines);
            register(helper, "chronicle_talk_upbringing_variants", ChronicleGameTests::upbringingVariants);
            register(helper, "chronicle_talk_small_talk", ChronicleGameTests::smallTalk);
            register(helper, "chronicle_talk_entertainment_once_per_day", ChronicleGameTests::entertainmentOncePerDay);
            register(helper, "chronicle_talk_multiplayer", ChronicleGameTests::multiplayer);
            register(helper, "chronicle_talk_settler_without_origin", ChronicleGameTests::settlerWithoutOrigin);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    /** A hand-built Human settler that does not think: no origin, no diary. */
    private static Settler handBuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 at = helper.absoluteVec(SETTLER_AT);
        settler.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        settler.setRace(Races.HUMAN);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        return settler;
    }

    private static SettlerOrigin origin(Optional<SettlerName> name, Optional<Identifier> upbringing, Optional<Identifier> turningPoint) {
        return new SettlerOrigin(Gender.MALE, name, Place.somewhere(PLAINS), upbringing, turningPoint, Optional.of(CivMod.id("wandered_in")),
                Optional.of(FOUNDER));
    }

    private static SettlerName tradeName(Identifier trade, Ancestor ancestor) {
        return new SettlerName("Brann", new SettlerName.Surname(SurnameType.TRADE, trade.toString(), Optional.of(ancestor)));
    }

    /** A settler with a hand-set origin: the family, the trade surname and the turning point are the ones given. */
    private static Settler withOrigin(GameTestHelper helper, Optional<SettlerName> name) {
        Settler settler = handBuilt(helper);
        settler.setOrigin(origin(name, Optional.of(CRAFTSFOLK), Optional.of(RAIDERS)));
        return settler;
    }

    private static ServerPlayer player(GameTestHelper helper, boolean sneaking) {
        ServerPlayer player = TestPlayers.add(helper, helper.absoluteVec(PLAYER_AT));
        player.setShiftKeyDown(sneaking);
        return player;
    }

    private static void interact(ServerPlayer player, Settler settler, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.interactOn(settler, InteractionHand.MAIN_HAND, settler.position());
    }

    /**
     * Clicks the settler and tells whether the window opened. The test player's connection has no payload registered, so the open
     * screen packet fails; that failure is the proof that the window was opened.
     */
    private static boolean opensWindow(ServerPlayer player, Settler settler, ItemStack held) {
        try {
            interact(player, settler, held);
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()).contains("advanced_open_screen");
        }
        return player.containerMenu instanceof SettlerMenu;
    }

    private static PlayerTalk.Result talk(GameTestHelper helper, Settler settler, ServerPlayer player) {
        return PlayerTalk.talk(settler, player).orElseThrow(() -> helper.assertionException("The settler did not talk"));
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "not translatable: " + text;
    }

    private static List<Identifier> types(Settler settler) {
        return settler.getChronicle().entries().stream().map(DiaryEntry::type).toList();
    }

    private static double entertainment(Settler settler) {
        return settler.getWork().entertainment().value();
    }

    private static Settlement standalone(GameTestHelper helper, SettlementData data, BlockPos anchor, Optional<Rescuer> founder) {
        Settlement settlement = SettlementManager.placeCenter(data, anchor, ModJobs.ADMINISTRATOR.get(), 1, Races.HUMAN, helper.getLevel().getGameTime(),
                RandomSource.create(0L), SettlementManager.Listener.NONE).settlement().orElseThrow();
        data.updateOrigins(settlement, origins -> new SettlementOrigins(founder, java.util.Map.of(), Optional.of(List.of())));
        return settlement;
    }

    // ---------------------------------------------------------------- the diary

    /** A settler that arrives starts with the arrival entry: the day, the reason and the player who took it in. */
    private static void arrivalEntry(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(ANCHOR);
        Settlement settlement = standalone(helper, data, anchor, Optional.of(FOUNDER));
        List<Settler> settlers = SettlerSpawner.arrive(helper.getLevel(), data, settlement, anchor);
        try {
            helper.assertTrue(!settlers.isEmpty(), "Starting settlers arrived");
            for (Settler settler : settlers) {
                List<DiaryEntry> diary = settler.getChronicle().entries();
                helper.assertTrue(diary.size() == 1 && diary.get(0).type().equals(CivMod.id("arrival")), "One arrival entry: " + diary);
                DiaryEntry arrival = diary.get(0);
                helper.assertTrue(arrival.day() == DayClock.day(helper.getLevel()), "Written on the day of the arrival: " + arrival.day());
                helper.assertTrue(arrival.params().equals(List.of(CivMod.id("survivors").toString(), "Founder")), "The reason and the rescuer: " + arrival.params());
                Component text = ModDiaryEntryTypes.ARRIVAL.get().text().apply(arrival.params());
                helper.assertTrue(key(text).equals("diary.civmod.arrival.rescued") && ((TranslatableContents) text.getContents()).getArgs()[1].equals("Founder"),
                        "First person text with the rescuer: " + text);
            }
            helper.succeed();
        } finally {
            settlers.forEach(Settler::discard);
        }
    }

    /** Without a founder the arrival entry has only the reason. */
    private static void arrivalEntryWithoutRescuer(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(ANCHOR);
        Settlement settlement = standalone(helper, data, anchor, Optional.empty());
        List<Settler> settlers = SettlerSpawner.arrive(helper.getLevel(), data, settlement, anchor);
        try {
            DiaryEntry arrival = settlers.get(0).getChronicle().entries().get(0);
            helper.assertTrue(arrival.params().get(1).isEmpty(), "Nobody took it in: " + arrival.params());
            Component text = ModDiaryEntryTypes.ARRIVAL.get().text().apply(arrival.params());
            helper.assertTrue(key(text).equals(OriginText.key("reason", CivMod.id("survivors"))), "Only the reason: " + key(text));
            helper.succeed();
        } finally {
            settlers.forEach(Settler::discard);
        }
    }

    /** A settler that loses its settlement writes an entry about it, once. */
    private static void homelessEntry(GameTestHelper helper) {
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(ANCHOR);
        Settlement settlement = standalone(helper, data, anchor, Optional.of(FOUNDER));
        List<Settler> settlers = SettlerSpawner.arrive(helper.getLevel(), data, settlement, anchor);
        try {
            Settler settler = settlers.get(0);
            settler.becomeHomeless();
            helper.assertTrue(types(settler).equals(List.of(CivMod.id("arrival"), CivMod.id("homeless"))), "Arrival, then the loss of the home: " + types(settler));
            settler.becomeHomeless();
            helper.assertTrue(settler.getChronicle().entries().size() == 2, "A settler that is homeless already writes nothing: " + types(settler));
            helper.succeed();
        } finally {
            settlers.forEach(Settler::discard);
        }
    }

    /** A settler from the spawn egg starts homeless: no entry about a home it never had. */
    private static void eggSettlerHasNoHomelessEntry(GameTestHelper helper) {
        Settler settler = SettlerSpawnEggGameTests.spawn(helper, ModItems.SETTLER_SPAWN_EGG.get().getDefaultInstance(), new BlockPos(1, 1, 1));
        try {
            helper.assertTrue(types(settler).equals(List.of(CivMod.id("arrival"))), "Only the arrival: " + types(settler));
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /** A full diary drops its oldest entry that is not the arrival entry. */
    private static void diaryCapKeepsArrival(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        try {
            Origins.assignEgg(settler, RandomSource.create(1L));
            int extra = Chronicle.CAP + 5;
            for (int i = 0; i < extra; i++) {
                helper.assertTrue(CivModApi.addDiaryEntry(settler, ModDiaryEntryTypes.ORIGIN_LINE, List.of("turning_point", "civmod:entry_" + i)), "Entry " + i + " written");
            }
            List<DiaryEntry> diary = settler.getChronicle().entries();
            helper.assertTrue(diary.size() == Chronicle.CAP, "At most " + Chronicle.CAP + " entries: " + diary.size());
            helper.assertTrue(diary.get(0).type().equals(CivMod.id("arrival")), "The arrival entry stays at the top: " + types(settler));
            helper.assertTrue(diary.get(1).params().get(1).equals("civmod:entry_" + (extra - Chronicle.CAP + 1)), "The oldest others dropped first: " + diary.get(1));
            helper.assertTrue(diary.get(Chronicle.CAP - 1).params().get(1).equals("civmod:entry_" + (extra - 1)), "The newest is last: " + diary.get(Chronicle.CAP - 1));
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /** An addon registers a type and writes an entry through the public API; unknown things do not write. */
    private static void addonApi(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        try {
            DiaryEntryType type = ModDiaryEntryTypes.REGISTRY.getValue(TEST_TYPE);
            helper.assertTrue(type != null && !type.permanent() && !type.beforeArrival(), "The addon type is in the registry: " + type);
            helper.assertTrue(CivModApi.addDiaryEntry(settler, () -> type, List.of("a", "b")), "The entry is written");
            DiaryEntry entry = settler.getChronicle().entries().get(0);
            helper.assertTrue(entry.type().equals(TEST_TYPE) && entry.params().equals(List.of("a", "b")) && entry.day() == DayClock.day(helper.getLevel()),
                    "Type, parameters and day: " + entry);
            helper.assertTrue(key(type.text().apply(entry.params())).equals("diary.test.entry"), "The words come from the type");
            helper.assertFalse(CivModApi.addDiaryEntry(new ItemEntity(helper.getLevel(), 0, 0, 0, new ItemStack(Items.STICK)), () -> type, List.of()),
                    "Only a settler has a diary");
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /** The diary, the told lines and the talent flag are saved with the settler; a save without a chronicle loads an empty one. */
    private static void saveRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = handBuilt(helper);
        try {
            Origins.assignEgg(settler, RandomSource.create(2L));
            settler.setChronicle(settler.getChronicle().withTold(OriginLine.Slot.UPBRINGING).withTold(OriginLine.Slot.PLACE));
            CivModApi.addDiaryEntry(settler, ModDiaryEntryTypes.HOMELESS, List.of());
            Chronicle before = settler.getChronicle();
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            settler.saveWithoutId(output);
            CompoundTag tag = output.buildResult();
            Settler loaded = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
            loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
            helper.assertTrue(loaded.getChronicle().equals(before), "Saved with the settler: " + loaded.getChronicle());
            helper.assertTrue(loaded.getChronicle().told().equals(List.of(OriginLine.Slot.UPBRINGING, OriginLine.Slot.PLACE)), "The told lines: " + loaded.getChronicle().told());
            tag.remove("chronicle");
            Settler old = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
            old.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
            helper.assertTrue(old.getChronicle().equals(Chronicle.EMPTY), "A save without a chronicle loads an empty one: " + old.getChronicle());
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /** The window gets the diary and whether there is an origin; settlers without an origin show an empty diary. */
    private static void windowInfo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = handBuilt(helper);
        try {
            SettlerInfo.Person bare = SettlerInfo.of(settler).person();
            helper.assertTrue(bare.diary().isEmpty() && !bare.hasOrigin(), "A settler without an origin: an empty diary and a note: " + bare);
            Origins.assignEgg(settler, RandomSource.create(3L));
            CivModApi.addDiaryEntry(settler, ModDiaryEntryTypes.HOMELESS, List.of());
            SettlerInfo info = SettlerInfo.of(settler);
            helper.assertTrue(info.person().hasOrigin() && info.person().diary().equals(settler.getChronicle().entries()), "The diary of the settler: " + info.person());
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            SettlerInfo.STREAM_CODEC.encode(buffer, info);
            SettlerInfo received = SettlerInfo.STREAM_CODEC.decode(buffer);
            helper.assertTrue(received.person().equals(info.person()), "The diary survives the trip to the client: " + received.person());
            helper.assertTrue(SettlerMenu.TAB_CHRONICLE < SettlerMenu.FIRST_JOB_TAB, "The Chronicle is a top tab");
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    /** Every language key the talk, the diary and the tab need exists, for every entry of the Human tables. */
    private static void textsExist(GameTestHelper helper) {
        JsonObject lang = GuiTextGameTests.lang(helper);
        RaceDefinition human = OriginData.race(Races.HUMAN).orElseThrow();
        Set<Identifier> jobs = new HashSet<>();
        human.upbringings().forEach(entry -> jobs.addAll(entry.jobs()));
        human.surnameEntries().get(SurnameType.TRADE).forEach(entry -> jobs.addAll(entry.jobs()));
        List<String> keys = new java.util.ArrayList<>();
        jobs.forEach(job -> keys.add(OriginText.key("job", job)));
        human.surnameEntries().get(SurnameType.TRADE).forEach(entry -> keys.add(OriginText.key("trade", entry.id())));
        for (String topic : List.of("weather.good", "weather.bad", "settlement.bed", "settlement.fire", "settlement.food", "settlement.fine")) {
            for (int variant = 0; variant < 3; variant++) {
                keys.add("civmod.talk.small." + topic + "." + variant);
            }
        }
        keys.addAll(List.of("origin.told.place", "origin.told.jobs.two", "origin.told.upbringing.plain", "origin.told.upbringing.same",
                "origin.told.upbringing.different", "diary.civmod.arrival.no_reason", "diary.civmod.arrival.rescued", "diary.civmod.homeless",
                "civmod.talk.line", "civmod.gui.settler.tab.chronicle", "civmod.gui.settler.help.chronicle", "civmod.gui.chronicle.no_origin",
                "civmod.gui.chronicle.before_day", "civmod.gui.chronicle.before_arrival", "civmod.gui.chronicle.entry", "diary.civmod.talent",
                "civmod.gui.chronicle.talent"));
        for (String key : keys) {
            helper.assertTrue(lang.has(key), "Language key " + key);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- the talk

    /** Shift + right-click with an empty hand: the settler tells a line, writes it into its diary, and the first talk of the day gives 0.5. */
    private static void talkShiftEmptyHand(GameTestHelper helper) {
        Settler settler = withOrigin(helper, Optional.empty());
        ServerPlayer player = player(helper, true);
        try {
            double before = entertainment(settler);
            interact(player, settler, ItemStack.EMPTY);
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "The window stays closed");
            Chronicle chronicle = settler.getChronicle();
            helper.assertTrue(chronicle.told().size() == 1 && chronicle.entries().size() == 1, "One line told and written: " + chronicle);
            helper.assertTrue(chronicle.entries().get(0).type().equals(CivMod.id("origin_line")), "An origin line entry: " + chronicle.entries());
            helper.assertTrue(entertainment(settler) - before == TALK_POINTS, "Entertainment rose by 0.5: " + entertainment(settler));
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** A plain click, Shift with an item in hand and a sleeping settler all open the window and tell nothing. */
    private static void otherClicksOpenWindow(GameTestHelper helper) {
        Settler settler = withOrigin(helper, Optional.empty());
        ServerPlayer player = player(helper, false);
        try {
            helper.assertTrue(opensWindow(player, settler, ItemStack.EMPTY), "A plain right-click opens the window");
            player.setShiftKeyDown(true);
            helper.assertTrue(opensWindow(player, settler, new ItemStack(Items.STICK)), "Shift with an item in hand opens the window");
            settler.setSleepingPos(helper.absolutePos(ANCHOR));
            helper.assertTrue(opensWindow(player, settler, ItemStack.EMPTY), "A sleeping settler does not talk: the window opens");
            helper.assertTrue(settler.getChronicle().equals(Chronicle.EMPTY) && entertainment(settler) == 0.0, "Nothing was told: " + settler.getChronicle());
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** The first three talks tell place, upbringing and turning point, each once; the fourth is small talk and writes nothing. */
    private static void threeOriginLines(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        ServerPlayer player = player(helper, true);
        try {
            Origins.assignEgg(settler, RandomSource.create(4L));
            Set<OriginLine.Slot> slots = new HashSet<>();
            Set<String> lines = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                PlayerTalk.Result result = talk(helper, settler, player);
                helper.assertTrue(result.told().isPresent(), "Talk " + (i + 1) + " tells a part of the origin");
                slots.add(result.told().get());
                lines.add(key(result.line()) + result.line());
            }
            helper.assertTrue(slots.equals(Set.of(OriginLine.Slot.values())), "All three parts, none twice: " + slots);
            helper.assertTrue(lines.size() == 3, "Three different lines: " + lines);
            helper.assertTrue(types(settler).equals(List.of(CivMod.id("arrival"), CivMod.id("origin_line"), CivMod.id("origin_line"), CivMod.id("origin_line"))),
                    "The arrival and the three lines in the diary: " + types(settler));
            PlayerTalk.Result fourth = talk(helper, settler, player);
            helper.assertTrue(fourth.told().isEmpty() && key(fourth.line()).startsWith("civmod.talk.small."), "The fourth talk is small talk: " + key(fourth.line()));
            helper.assertTrue(settler.getChronicle().entries().size() == 4, "Small talk writes nothing: " + types(settler));
            for (int i = 0; i < 5; i++) {
                helper.assertTrue(talk(helper, settler, player).told().isEmpty(), "Never an origin line again");
            }
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** The upbringing line has a "same" variant when the trade surname fits the favoured Jobs, a "different" one otherwise and a plain one. */
    private static void upbringingVariants(GameTestHelper helper) {
        ServerPlayer player = player(helper, true);
        List<Settler> settlers = new java.util.ArrayList<>();
        try {
            // The talk draws the order of the lines: pick 1 of the three is the upbringing.
            PlayerTalk.script(OriginRolls.picks(1));
            Settler same = withOrigin(helper, Optional.of(tradeName(SMITH, Ancestor.GRANDFATHER)));
            Settler different = withOrigin(helper, Optional.of(tradeName(MINER_TRADE, Ancestor.GREAT_GRANDFATHER)));
            Settler plain = withOrigin(helper, Optional.of(new SettlerName("Brann", new SettlerName.Surname(SurnameType.PATRONYMIC, "Olaf", Optional.empty()))));
            settlers.addAll(List.of(same, different, plain));
            PlayerTalk.Result sameLine = talk(helper, same, player);
            helper.assertTrue(key(sameLine.line()).equals("origin.told.upbringing.same"), "A smith's name and a craftspeople family: " + key(sameLine.line()));
            Object[] args = ((TranslatableContents) sameLine.line().getContents()).getArgs();
            helper.assertTrue(key((Component) args[0]).equals(OriginText.key("upbringing", CRAFTSFOLK)) && key((Component) args[1]).equals(OriginText.key("surname.trade", SMITH))
                    && key((Component) args[2]).equals(Ancestor.GRANDFATHER.langKey()) && key((Component) args[3]).equals(OriginText.key("trade", SMITH))
                    && key((Component) args[4]).equals(OriginText.key("job", CivMod.id("craftsperson"))), "Family, name, ancestor, trade and the talent hint: " + sameLine.line());
            PlayerTalk.Result differentLine = talk(helper, different, player);
            helper.assertTrue(key(differentLine.line()).equals("origin.told.upbringing.different"), "A miner's name and a craftspeople family: " + key(differentLine.line()));
            PlayerTalk.Result plainLine = talk(helper, plain, player);
            helper.assertTrue(key(plainLine.line()).equals("origin.told.upbringing.plain"), "No trade surname: " + key(plainLine.line()));
            helper.assertTrue(plainLine.told().equals(Optional.of(OriginLine.Slot.UPBRINGING)), "It is the upbringing: " + plainLine.told());
            DiaryEntry written = plain.getChronicle().entries().get(0);
            helper.assertTrue(OriginLine.text(written.params()).equals(plainLine.line()), "The diary entry reads like the line: " + written);
            helper.succeed();
        } finally {
            PlayerTalk.script(null);
            TestPlayers.remove(player);
            settlers.forEach(Settler::discard);
        }
    }

    /** After the origin the settler talks about the weather or, from its own needs, about the settlement. */
    private static void smallTalk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = new SettlementData();
        BlockPos anchor = helper.absolutePos(ANCHOR);
        Settlement settlement = standalone(helper, data, anchor, Optional.of(FOUNDER));
        Settler settler = handBuilt(helper);
        settler.setOrigin(origin(Optional.empty(), Optional.empty(), Optional.empty()));
        settler.setChronicle(settler.getChronicle().withTold(OriginLine.Slot.PLACE));
        settler.joinSettlement(data, settlement);
        ServerPlayer player = player(helper, true);
        try {
            // Picks: 0 = the weather, then its variant.
            PlayerTalk.script(OriginRolls.picks(0, 1));
            PlayerTalk.Result weather = talk(helper, settler, player);
            String mood = level.isRaining() || level.isThundering() ? "bad" : "good";
            helper.assertTrue(key(weather.line()).equals("civmod.talk.small.weather." + mood + ".1") && weather.told().isEmpty(), "The weather, in the mood of the weather: " + key(weather.line()));
            // Picks: 1 = the settlement, which of the unmet needs, its variant. A settler without a bed or a hearth complains about one of them.
            PlayerTalk.script(OriginRolls.picks(1, 0, 2));
            PlayerTalk.Result settlementTalk = talk(helper, settler, player);
            String key = key(settlementTalk.line());
            helper.assertTrue(key.matches("civmod\\.talk\\.small\\.settlement\\.(bed|fire)\\.2"), "A need it lacks: " + key);
            helper.assertTrue(settler.getChronicle().entries().isEmpty(), "Small talk writes nothing");
            // A homeless settler has no settlement to talk about: it talks about the weather whatever the pick.
            settler.becomeHomeless();
            PlayerTalk.script(OriginRolls.picks(1, 0, 0));
            helper.assertTrue(key(talk(helper, settler, player).line()).startsWith("civmod.talk.small.weather."), "A homeless settler talks about the weather");
            helper.succeed();
        } finally {
            PlayerTalk.script(null);
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** The first talk per day and settler gives 0.5 Entertainment; more talks that day give nothing; the next day gives again. */
    private static void entertainmentOncePerDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long clock = level.getOverworldClockTime();
        Settler settler = withOrigin(helper, Optional.empty());
        ServerPlayer player = player(helper, true);
        try {
            double start = entertainment(settler);
            talk(helper, settler, player);
            helper.assertTrue(entertainment(settler) == start + TALK_POINTS, "The first talk of the day: " + entertainment(settler));
            talk(helper, settler, player);
            talk(helper, settler, player);
            helper.assertTrue(entertainment(settler) == start + TALK_POINTS, "More talks that day give nothing: " + entertainment(settler));
            level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), clock + DayClock.DAY_TICKS);
            talk(helper, settler, player);
            helper.assertTrue(entertainment(settler) == start + 2 * TALK_POINTS, "The next day gives again: " + entertainment(settler));
            helper.succeed();
        } finally {
            level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), clock);
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** The told lines belong to the settler: two players together hear each line once, then small talk. */
    private static void multiplayer(GameTestHelper helper) {
        Settler settler = withOrigin(helper, Optional.empty());
        ServerPlayer first = player(helper, true);
        ServerPlayer second = player(helper, true);
        try {
            Set<OriginLine.Slot> slots = new HashSet<>();
            slots.add(talk(helper, settler, first).told().orElseThrow());
            slots.add(talk(helper, settler, second).told().orElseThrow());
            slots.add(talk(helper, settler, first).told().orElseThrow());
            helper.assertTrue(slots.size() == 3, "Each part once, whoever asks: " + slots);
            helper.assertTrue(talk(helper, settler, second).told().isEmpty(), "Small talk for every player after that");
            helper.assertTrue(entertainment(settler) == TALK_POINTS, "One day, one gift of Entertainment per settler: " + entertainment(settler));
            helper.succeed();
        } finally {
            TestPlayers.remove(first);
            TestPlayers.remove(second);
            settler.discard();
        }
    }

    /** A settler without an origin (an old save, a hand-built one) makes small talk and writes no diary. */
    private static void settlerWithoutOrigin(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        ServerPlayer player = player(helper, true);
        try {
            PlayerTalk.Result result = talk(helper, settler, player);
            helper.assertTrue(result.told().isEmpty() && key(result.line()).startsWith("civmod.talk.small."), "Small talk: " + key(result.line()));
            helper.assertTrue(settler.getChronicle().equals(Chronicle.EMPTY), "No diary entry: " + settler.getChronicle());
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }
}
