package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.npc.Races;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.origin.Gender;
import com.gilfort.civmod.origin.Place;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDiaryEntryTypes;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the talent apart from the EXP funnels ({@link com.gilfort.civmod.job.TalentExpGameTests}): the tie-break of the
 * automatic assignment, what the Chronicle tab gets to show, the saved fraction of a skill and the words of the entry.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class TalentGameTests {

    private static final Identifier WOODCUTTERS = CivMod.id("woodcutters");
    private static final Identifier STONE_AND_SEA = CivMod.id("stone_and_sea");
    private static final Identifier MINERS = CivMod.id("miners");
    private static final Identifier PLAINS = CivMod.id("plains");
    private static final BlockPos LUMBERJACK = new BlockPos(1, 1, 1);
    private static final Vec3 SETTLER_AT = new Vec3(2.5, 1.0, 2.5);

    private TalentGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "talent_assign_prefers_talent_on_equal_level", TalentGameTests::assignPrefersTalent);
            register(helper, "talent_assign_keeps_higher_level", TalentGameTests::assignKeepsHigherLevel);
            register(helper, "talent_assign_other_talent_is_no_tie_break", TalentGameTests::assignOtherTalent);
            register(helper, "talent_chronicle_shows_after_confirmation", TalentGameTests::chronicleShows);
            register(helper, "talent_skill_fraction_is_saved", TalentGameTests::skillFractionSaved);
            register(helper, "talent_confirmation_text", TalentGameTests::confirmationText);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    private static SettlerOrigin origin(Identifier upbringing) {
        return new SettlerOrigin(Gender.FEMALE, Optional.empty(), Place.somewhere(PLAINS), Optional.of(upbringing), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** A joined settler with the given upbringing and its Lumberjack skill at the given level. */
    private static Settler candidate(GameTestHelper helper, Settlement settlement, String name, BlockPos at, Optional<Identifier> upbringing, int level) {
        Settler settler = AutoAssignmentGameTests.settler(helper, settlement, name, at);
        upbringing.ifPresent(id -> settler.getPast().setOrigin(origin(id)));
        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(level, 0));
        return settler;
    }

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

    // ---------------------------------------------------------------- assignment

    /**
     * On equal skill level the settler whose upbringing favours the Job wins over a nearer one, before the talent is confirmed: the
     * order uses the real talent, only the display waits.
     */
    private static void assignPrefersTalent(GameTestHelper helper) {
        Settlement settlement = AutoAssignmentGameTests.jobBlock(helper, LUMBERJACK, ModBlocks.LUMBERJACK_BLOCK.get());
        Settler nearPlain = candidate(helper, settlement, "NearPlain", new BlockPos(2, 1, 1), Optional.empty(), 2);
        Settler farTalented = candidate(helper, settlement, "FarTalented", new BlockPos(8, 1, 8), Optional.of(WOODCUTTERS), 2);
        helper.assertFalse(farTalented.getPast().chronicle().talentConfirmed(), "The talent is not confirmed yet");
        AutoAssignmentGameTests.toggle(helper, LUMBERJACK, ModJobs.LUMBERJACK.get(), true);
        helper.assertTrue(AutoAssignmentGameTests.holds(helper, farTalented, LUMBERJACK, ModJobs.LUMBERJACK.get()) && nearPlain.getJobs().isEmpty(),
                "The talented settler takes the place: " + AutoAssignmentGameTests.block(helper, LUMBERJACK).assignees());
        AutoAssignmentGameTests.discard(nearPlain, farTalented);
        helper.succeed();
    }

    /** A talented settler never takes the place of one with a higher level. */
    private static void assignKeepsHigherLevel(GameTestHelper helper) {
        Settlement settlement = AutoAssignmentGameTests.jobBlock(helper, LUMBERJACK, ModBlocks.LUMBERJACK_BLOCK.get());
        Settler talentedNear = candidate(helper, settlement, "TalentedNear", new BlockPos(2, 1, 1), Optional.of(WOODCUTTERS), 2);
        Settler plainFar = candidate(helper, settlement, "PlainFar", new BlockPos(8, 1, 8), Optional.empty(), 3);
        AutoAssignmentGameTests.toggle(helper, LUMBERJACK, ModJobs.LUMBERJACK.get(), true);
        helper.assertTrue(AutoAssignmentGameTests.holds(helper, plainFar, LUMBERJACK, ModJobs.LUMBERJACK.get()) && talentedNear.getJobs().isEmpty(),
                "The higher level keeps the place: " + AutoAssignmentGameTests.block(helper, LUMBERJACK).assignees());
        AutoAssignmentGameTests.discard(talentedNear, plainFar);
        helper.succeed();
    }

    /** A talent for another Job changes nothing: equal levels go to the nearest settler. */
    private static void assignOtherTalent(GameTestHelper helper) {
        Settlement settlement = AutoAssignmentGameTests.jobBlock(helper, LUMBERJACK, ModBlocks.LUMBERJACK_BLOCK.get());
        Settler nearPlain = candidate(helper, settlement, "NearPlain", new BlockPos(2, 1, 1), Optional.empty(), 2);
        Settler farMiner = candidate(helper, settlement, "FarMiner", new BlockPos(8, 1, 8), Optional.of(MINERS), 2);
        AutoAssignmentGameTests.toggle(helper, LUMBERJACK, ModJobs.LUMBERJACK.get(), true);
        helper.assertTrue(AutoAssignmentGameTests.holds(helper, nearPlain, LUMBERJACK, ModJobs.LUMBERJACK.get()) && farMiner.getJobs().isEmpty(),
                "The nearest settler wins: " + AutoAssignmentGameTests.block(helper, LUMBERJACK).assignees());
        AutoAssignmentGameTests.discard(nearPlain, farMiner);
        helper.succeed();
    }

    // ---------------------------------------------------------------- Chronicle tab

    /** The window gets the talent only after the confirmation, as the upbringing's Base Jobs. */
    private static void chronicleShows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = handBuilt(helper);
        try {
            settler.getPast().setOrigin(origin(STONE_AND_SEA));
            helper.assertTrue(SettlerInfo.of(settler).person().talent().isEmpty(), "Hidden before the confirmation");
            settler.getPast().setChronicle(settler.getPast().chronicle().withTalentConfirmed());
            SettlerInfo info = SettlerInfo.of(settler);
            List<Job> expected = List.of(ModJobs.MINER.get(), ModJobs.FISHERMAN.get());
            helper.assertTrue(info.person().talent().equals(expected), "Shown after the confirmation: " + info.person().talent());
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            SettlerInfo.STREAM_CODEC.encode(buffer, info);
            helper.assertTrue(SettlerInfo.STREAM_CODEC.decode(buffer).person().talent().equals(expected), "The talent survives the trip to the client");
            Settler withoutOrigin = handBuilt(helper);
            withoutOrigin.getPast().setChronicle(withoutOrigin.getPast().chronicle().withTalentConfirmed());
            helper.assertTrue(SettlerInfo.of(withoutOrigin).person().talent().isEmpty(), "No origin, no talent to show");
            withoutOrigin.discard();
            helper.succeed();
        } finally {
            settler.discard();
        }
    }

    // ---------------------------------------------------------------- save and text

    /** The fraction a skill carries is saved; a skill saved before it existed loads with none. */
    private static void skillFractionSaved(GameTestHelper helper) {
        SettlerSkills.Skill skill = new SettlerSkills.Skill(4, 7, 75);
        JsonObject saved = SettlerSkills.Skill.CODEC.encodeStart(JsonOps.INSTANCE, skill).getOrThrow().getAsJsonObject();
        helper.assertTrue(SettlerSkills.Skill.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().equals(skill), "Round trip: " + saved);
        SettlerSkills.Skill old = SettlerSkills.Skill.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"level\":4,\"exp\":7}")).getOrThrow();
        helper.assertTrue(old.equals(new SettlerSkills.Skill(4, 7)) && old.carry() == 0, "An old save has no fraction: " + old);
        helper.succeed();
    }

    /** The entry reads "I think working as <Job> really suits me", and every key exists. */
    private static void confirmationText(GameTestHelper helper) {
        Component text = ModDiaryEntryTypes.TALENT.get().text().apply(List.of(ModJobs.LUMBERJACK.get().id().toString()));
        helper.assertTrue(text.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals("diary.civmod.talent")
                && translatable.getArgs().length == 1, "The entry text: " + text);
        helper.assertFalse(ModDiaryEntryTypes.TALENT.get().beforeArrival() || ModDiaryEntryTypes.TALENT.get().permanent(),
                "A talent entry dates from the time here and may drop out of a full diary");
        JsonObject lang = GuiTextGameTests.lang(helper);
        for (String key : List.of("diary.civmod.talent", "civmod.gui.chronicle.talent", "job.civmod.lumberjack.indefinite")) {
            helper.assertTrue(lang.has(key), "Language key " + key);
        }
        helper.succeed();
    }
}
