package com.gilfort.civmod.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.chronicle.DiaryEntry;
import com.gilfort.civmod.job.PlayerTalk;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.origin.Origins;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.registry.ModEntityTypes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the favourite meal (vault {@code Systems/Needs.md}): the roll from the tag of the race, the reveal by a talk and by the
 * first meal, the Needs tab, the tier 5 need and the preference at the counter.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FavouriteMealGameTests {

    private static final Vec3 SETTLER_AT = new Vec3(2.5, 1.0, 2.5);
    /** The prepared foods of the Human tag, as the design names them; cake is in the tag too but has no food component, so it is never rolled. */
    private static final Set<Item> HUMAN_FAVOURITES = Set.of(Items.BREAD, Items.BAKED_POTATO, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
            Items.COOKED_MUTTON, Items.COOKED_CHICKEN, Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.PUMPKIN_PIE,
            Items.MUSHROOM_STEW, Items.RABBIT_STEW, Items.BEETROOT_SOUP, Items.COOKIE);

    private FavouriteMealGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "favourite_meal_arrival_rolls_from_race_tag", FavouriteMealGameTests::arrivalRolls);
            register(helper, "favourite_meal_race_without_tag_has_none", FavouriteMealGameTests::raceWithoutTag);
            register(helper, "favourite_meal_old_save_rolls_on_load", FavouriteMealGameTests::oldSaveRolls);
            register(helper, "favourite_meal_told_after_origin_lines", FavouriteMealGameTests::toldAfterOrigin);
            register(helper, "favourite_meal_told_first_without_origin", FavouriteMealGameTests::toldFirstWithoutOrigin);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    private static Identifier id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    /** A hand-built Human settler that does not think: no origin, no favourite. */
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

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.add(helper, helper.absoluteVec(new Vec3(3.5, 1.0, 2.5)));
        player.setShiftKeyDown(true);
        return player;
    }

    private static PlayerTalk.Result talk(GameTestHelper helper, Settler settler, ServerPlayer player) {
        return PlayerTalk.talk(settler, player).orElseThrow(() -> helper.assertionException("The settler did not talk"));
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "not translatable: " + text;
    }

    private static List<DiaryEntry> favouriteEntries(Settler settler) {
        return settler.getPast().chronicle().entries().stream().filter(entry -> entry.type().equals(ModDiaryEntryTypes.FAVOURITE_MEAL.getId())).toList();
    }

    // ---------------------------------------------------------------- the roll

    /** A settler that arrives rolls its favourite from the tag of its race: Human only prepared foods, and not always the same one. */
    private static void arrivalRolls(GameTestHelper helper) {
        Set<Identifier> allowed = new HashSet<>();
        HUMAN_FAVOURITES.forEach(item -> allowed.add(id(item)));
        Set<Identifier> seen = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            Settler settler = handBuilt(helper);
            Origins.assignEgg(settler, RandomSource.create(seed));
            Identifier favourite = settler.getFood().favourite().orElse(null);
            settler.discard();
            helper.assertTrue(favourite != null, "A settler that arrives has a favourite meal");
            helper.assertTrue(allowed.contains(favourite), "A prepared food of the Human tag: " + favourite);
            seen.add(favourite);
        }
        helper.assertTrue(seen.size() >= 4, "The roll spreads over the tag: " + seen);
        helper.succeed();
    }

    /** A race whose tag nobody defined has no favourite meal: the tier 5 need then asks for none. */
    private static void raceWithoutTag(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        settler.getFood().rollFavourite(Identifier.parse("civmod:nobody"), RandomSource.create(1L));
        helper.assertTrue(settler.getFood().favourite().isEmpty(), "No tag, no favourite: " + settler.getFood().favourite());
        settler.discard();
        helper.succeed();
    }

    /** A settler from an old save has no favourite saved: it rolls one on its first load and keeps it through every save after that. */
    private static void oldSaveRolls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler old = handBuilt(helper);
        CompoundTag saved = save(level, old);
        old.discard();
        helper.assertTrue(!saved.getCompoundOrEmpty("food").contains("favourite"), "An old save holds no favourite: " + saved.getCompoundOrEmpty("food"));
        Settler loaded = reload(level, saved);
        Identifier rolled = loaded.getFood().favourite().orElse(null);
        helper.assertTrue(rolled != null && HUMAN_FAVOURITES.stream().map(FavouriteMealGameTests::id).toList().contains(rolled),
                "Rolled on the first load from the Human tag: " + rolled);
        Settler again = reload(level, save(level, loaded));
        helper.assertTrue(again.getFood().favourite().equals(loaded.getFood().favourite()), "Kept: " + again.getFood().favourite() + " vs " + rolled);
        helper.succeed();
    }

    // ---------------------------------------------------------------- the reveal by a talk

    /**
     * After the three parts of the origin the next talk tells the favourite meal, once, and writes it into the diary with the same words;
     * after that it is small talk again, and the settler keeps the favourite hidden until then.
     */
    private static void toldAfterOrigin(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        ServerPlayer player = player(helper);
        try {
            Origins.assignEgg(settler, RandomSource.create(4L));
            Identifier favourite = settler.getFood().favourite().orElseThrow();
            helper.assertFalse(settler.getFood().favouriteKnown(), "The favourite is hidden at first");
            for (int i = 0; i < 3; i++) {
                helper.assertTrue(talk(helper, settler, player).told().isPresent(), "Talk " + (i + 1) + " tells the origin");
                helper.assertFalse(settler.getFood().favouriteKnown(), "Still hidden after " + (i + 1) + " talks");
            }
            helper.assertTrue(favouriteEntries(settler).isEmpty(), "Nothing about the favourite in the diary yet");

            PlayerTalk.Result fourth = talk(helper, settler, player);
            helper.assertTrue(fourth.told().isEmpty() && key(fourth.line()).equals("diary.civmod.favourite_meal.told"), "The fourth talk tells the favourite: " + key(fourth.line()));
            helper.assertTrue(((TranslatableContents) fourth.line().getContents()).getArgs()[0].equals(new ItemStack(BuiltInRegistries.ITEM.getValue(favourite)).getHoverName()),
                    "It names the favourite meal: " + fourth.line());
            helper.assertTrue(settler.getFood().favouriteKnown(), "The favourite is known now");
            List<DiaryEntry> entries = favouriteEntries(settler);
            helper.assertTrue(entries.size() == 1, "One diary entry: " + entries);
            helper.assertTrue(ModDiaryEntryTypes.FAVOURITE_MEAL.get().text().apply(entries.get(0).params()).equals(fourth.line()), "The entry reads like the line: " + entries.get(0));

            for (int i = 0; i < 4; i++) {
                PlayerTalk.Result later = talk(helper, settler, player);
                helper.assertTrue(later.told().isEmpty() && key(later.line()).startsWith("civmod.talk.small."), "Small talk after that: " + key(later.line()));
            }
            helper.assertTrue(favouriteEntries(settler).size() == 1, "Told once: " + favouriteEntries(settler));
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    /** A settler with a favourite but no origin (an old save) has no origin lines to tell first: its first talk tells the favourite. */
    private static void toldFirstWithoutOrigin(GameTestHelper helper) {
        Settler settler = handBuilt(helper);
        ServerPlayer player = player(helper);
        try {
            settler.getFood().rollFavourite(Races.HUMAN, RandomSource.create(2L));
            PlayerTalk.Result first = talk(helper, settler, player);
            helper.assertTrue(key(first.line()).equals("diary.civmod.favourite_meal.told") && settler.getFood().favouriteKnown(), "The first talk tells the favourite: " + key(first.line()));
            helper.assertTrue(key(talk(helper, settler, player).line()).startsWith("civmod.talk.small."), "Small talk after that");
            helper.succeed();
        } finally {
            TestPlayers.remove(player);
            settler.discard();
        }
    }

    private static CompoundTag save(ServerLevel level, Settler settler) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        return output.buildResult();
    }

    private static Settler reload(ServerLevel level, CompoundTag tag) {
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.LOAD);
        settler.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        return settler;
    }
}
