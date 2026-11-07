package com.gilfort.civmod.gametest;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.NeedState;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Bonuses;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Entertainment value (vault {@code Systems/Entertainment.md}): the rule table, the need tier, the work
 * speed, the Needs tab row and the catch-up.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class EntertainmentGameTests {

    private static final double EPSILON = 1.0E-9;
    private static final BlockPos JOB_BLOCK = new BlockPos(1, 1, 1);

    private EntertainmentGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "entertainment_rules_table", EntertainmentGameTests::rulesTable);
            register(helper, "entertainment_need_tier", EntertainmentGameTests::needTier);
            register(helper, "entertainment_tier_1_and_homeless", EntertainmentGameTests::tierOneAndHomeless);
            register(helper, "entertainment_decay", EntertainmentGameTests::decay);
            register(helper, "entertainment_saved", EntertainmentGameTests::saved);
            register(helper, "entertainment_speed", EntertainmentGameTests::speed);
            register(helper, "entertainment_needs_row", EntertainmentGameTests::needsRow);
            register(helper, "entertainment_catch_up", EntertainmentGameTests::catchUp);
        });
    }

    /**
     * A Lumberjack of the given skill whose other needs are all met: food, a hearth next to its own bed and a varied diet.
     * Only Entertainment decides its tier.
     */
    private static Settler content(GameTestHelper helper, BlockPos jobBlock, int skill) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.CAMPFIRE);
        Settler settler = NeedTierGameTests.hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), skill);
        long today = DayClock.day(level.getOverworldClockTime());
        BlockPos head = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(head.south(), bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        SettlementData data = SettlementData.get(level);
        SettlerBeds.assign(level, data, data.settlementAt(jobBlock), settler.getUUID(), head);
        for (Item food : List.of(Items.BREAD, Items.CARROT, Items.APPLE)) {
            settler.getFood().variety().record(BuiltInRegistries.ITEM.getKey(food), today);
        }
        settler.getFood().setLevel(15);
        // Provisions: hunger must not limit a settler through a catch-up.
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        return settler;
    }

    /** Sets the value without a decay mark: the next check only marks the day. */
    private static void set(Settler settler, double value) {
        settler.getWork().setEntertainment(Entertainment.of(value));
    }

    /** More than 5 for tier 2, more than 8 for tier 3: a grace day, one tier lower, a complaint, and back at once. */
    private static void needTier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, JOB_BLOCK, ModJobs.LUMBERJACK.get(), 3);
        Settler settler = content(helper, jobBlock, 45);
        SettlerJob job = settler.getJobs().get(0);
        long today = DayClock.day(level.getOverworldClockTime());

        helper.assertTrue(settler.getWork().entertainment().value() == 0.0, "A new settler starts at 0");
        set(settler, 9);
        settler.getWork().checkNeedsNow(level, today);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 45 && !settler.getWork().needState().inGrace(), "More than 8: tier 3 is met: " + settler.getWork().needState());

        set(settler, 8);
        settler.getWork().checkNeedsNow(level, today + 1);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 45 && settler.getWork().needState().inGrace(), "8 is not more than 8: grace day at the full level: " + settler.getWork().needState());
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().recentThoughts().contains(new ThoughtBubble.Show(settler.getId(), ThoughtBubble.NEED_UNMET, Items.JUKEBOX, true)),
                "Complains with the icon of Entertainment: " + settler.getWork().recentThoughts());

        set(settler, 6);
        settler.getWork().checkNeedsNow(level, today + 2);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 40 && !settler.getWork().needState().inGrace(), "Next morning one tier lower, tier 2 met with 6: " + settler.getWork().needState());

        set(settler, 5);
        settler.getWork().checkNeedsNow(level, today + 3);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 40 && settler.getWork().needState().inGrace(), "5 is not more than 5: grace day at tier 2: " + settler.getWork().needState());
        settler.getWork().checkNeedsNow(level, today + 4);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 20, "Then tier 1: " + settler.getWork().effectiveLevel(job));

        set(settler, 9);
        settler.getWork().decideNow(level);
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 45 && !settler.getWork().needState().inGrace(), "Met again: back at once: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Tier 1 never loses a level for Entertainment; a settler without a settlement skips the check. */
    private static void tierOneAndHomeless(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, JOB_BLOCK, ModJobs.LUMBERJACK.get(), 1);
        Settler settler = content(helper, jobBlock, 10);
        SettlerJob job = settler.getJobs().get(0);
        long today = DayClock.day(level.getOverworldClockTime());
        for (int day = 0; day < 4; day++) {
            set(settler, 0);
            settler.getWork().checkNeedsNow(level, today + day);
        }
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 10 && !settler.getWork().needState().inGrace(), "Tier 1 with 0 Entertainment: " + settler.getWork().needState());

        Settler homeless = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        homeless.snapTo(helper.absoluteVec(new Vec3(8.5, 1.0, 8.5)));
        homeless.setNoAi(true);
        level.addFreshEntity(homeless);
        homeless.getWork().setEntertainment(Entertainment.of(7));
        homeless.getWork().checkNeedsNow(level, today);
        homeless.getWork().checkNeedsNow(level, today + 3);
        helper.assertTrue(homeless.getWork().needState().equals(NeedState.START) && homeless.getWork().entertainment().value() == 7.0, "No settlement: no check, no decay: " + homeless.getWork().entertainment());
        homeless.discard();
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** One point per in-game day, also for missed days; the day of a check only counts once; never below 0. */
    private static void decay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, JOB_BLOCK, ModJobs.LUMBERJACK.get(), 3);
        Settler settler = content(helper, jobBlock, 10);
        long today = DayClock.day(level.getOverworldClockTime());
        set(settler, 8);
        settler.getWork().checkNeedsNow(level, today);
        helper.assertTrue(settler.getWork().entertainment().value() == 8.0, "The first check only marks the day: " + settler.getWork().entertainment());
        settler.getWork().checkNeedsNow(level, today + 1);
        helper.assertTrue(settler.getWork().entertainment().value() == 7.0, "A day later: 7: " + settler.getWork().entertainment());
        settler.getWork().checkNeedsNow(level, today + 1);
        helper.assertTrue(settler.getWork().entertainment().value() == 7.0, "The same day again: still 7: " + settler.getWork().entertainment());
        settler.getWork().checkNeedsNow(level, today + 4);
        helper.assertTrue(settler.getWork().entertainment().value() == 4.0, "Three missed days: 4: " + settler.getWork().entertainment());
        settler.getWork().checkNeedsNow(level, today + 40);
        helper.assertTrue(settler.getWork().entertainment().value() == 0.0, "Never below 0: " + settler.getWork().entertainment());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** 2 % faster per point at every tier, added to the blessing's share: full Entertainment and a blessing make 1.4. */
    private static void speed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, JOB_BLOCK, ModJobs.LUMBERJACK.get(), 3);
        Settler settler = NeedTierGameTests.hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), 10);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        long now = level.getOverworldClockTime();

        // value, expected factor without a blessing, expected factor with the blessing
        double[][] table = {{0, 1.0, 1.2}, {5, 1.1, 1.3}, {10, 1.2, 1.4}};
        for (double[] row : table) {
            set(settler, row[0]);
            data.setBonuses(settlement, Bonuses.NONE);
            double plain = settler.getWork().speedFactor(level);
            helper.assertTrue(near(plain, row[1]), "Entertainment " + row[0] + " without a blessing: " + row[1] + ", got " + plain);
            // The test clock stands still: the blessing runs around now.
            data.setBonuses(settlement, Bonuses.NONE.start(Bonuses.Kind.WORK_SPEED, now - 10L, now + 1L));
            double blessed = settler.getWork().speedFactor(level);
            helper.assertTrue(near(blessed, row[2]), "Entertainment " + row[0] + " with a blessing: " + row[2] + ", got " + blessed);
        }
        helper.assertTrue(near(settler.getWork().blessingFactor(level), 1.2), "The blessing alone stays 1.2");
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** The tiers 2 and 3 show an Entertainment row with the value; its tooltip lists today's points and the speed bonus. */
    private static void needsRow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, JOB_BLOCK, ModJobs.LUMBERJACK.get(), 3);
        Settler settler = content(helper, jobBlock, 45);
        long today = DayClock.day(level.getOverworldClockTime());
        settler.getWork().setEntertainment(Entertainment.of(3).add(3, today));

        NeedsReport report = SettlerInfo.of(settler).needs();
        for (int tier = 2; tier <= 3; tier++) {
            List<NeedsReport.Row> rows = report.rows(tier).stream().filter(row -> isEntertainment(row)).toList();
            helper.assertTrue(rows.size() == 1, "One Entertainment row in tier " + tier + ": " + report);
            NeedsReport.Row row = rows.get(0);
            helper.assertTrue(row.met() == (tier == 2), "6 is more than 5 but not more than 8, tier " + tier + ": " + row.met());
            helper.assertTrue(key(row.detail()).equals("civmod.need.entertainment.detail") && args(row.detail()).equals(List.of("6", "10")),
                    "The row reads 6/10: " + row.detail());
            helper.assertTrue(key(row.tooltip()).equals("civmod.need.entertainment.tooltip") && args(row.tooltip()).equals(List.of("3", "12")),
                    "The tooltip lists today's 3 points and 12 % speed: " + row.tooltip());
        }
        helper.assertTrue(report.rows(1).stream().noneMatch(row -> isEntertainment(row)) && report.rows(4).isEmpty(),
                "No Entertainment row in the other tiers");

        // Another day: today's points are gone.
        settler.getWork().setEntertainment(Entertainment.of(3).add(3, today - 1));
        NeedsReport tomorrow = SettlerInfo.of(settler).needs();
        helper.assertTrue(args(tomorrow.rows(2).stream().filter(row -> isEntertainment(row)).findFirst().orElseThrow().tooltip()).get(0).equals("0"),
                "Points of another day do not count as today's");
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    private static boolean isEntertainment(NeedsReport.Row row) {
        return key(row.name()).equals("civmod.need.entertainment");
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }

    private static List<String> args(Component component) {
        return component.getContents() instanceof TranslatableContents translatable
                ? Arrays.stream(translatable.getArgs()).map(String::valueOf).toList() : List.of();
    }

    /** Catch-up takes the decay of every missed day and judges the needs of each simulated day with that day's value. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = NeedTierGameTests.place(helper, JOB_BLOCK, ModJobs.LUMBERJACK.get(), 3);
        Settler settler = content(helper, jobBlock, 45);
        SettlerJob job = settler.getJobs().get(0);
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L;
        long today = DayClock.day(level.getOverworldClockTime());

        set(settler, 8);
        settler.getWork().checkNeedsNow(level, today - 3);
        helper.assertTrue(settler.getWork().entertainment().value() == 8.0 && settler.getWork().effectiveLevel(job) == 45, "Last seen three days ago with 8");
        settler.getWork().setCatchUp(dayStart - 2 * 24000L, dayStart);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(settler.getWork().entertainment().value() == 6.0, "Two missed days take 2 points: " + settler.getWork().entertainment());
        helper.assertTrue(settler.getWork().effectiveLevel(job) == 40,
                "7 on the first day is no tier 3: a grace day, then one tier lower with 6: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** The value, its decay mark and today's points are saved with the settler; an old save without them starts at 0. */
    private static void saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Entertainment entertainment = new Entertainment(7.5, 12L, 3.0, 12L, 12L, 12L);
        settler.getWork().setEntertainment(entertainment);

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        Settler loaded = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        helper.assertTrue(loaded.getWork().entertainment().equals(entertainment), "Saved with the settler: " + loaded.getWork().entertainment());

        tag.remove("entertainment");
        Settler old = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        old.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        helper.assertTrue(old.getWork().entertainment().equals(Entertainment.START), "An old save starts at 0: " + old.getWork().entertainment());
        settler.discard();
        loaded.discard();
        old.discard();
        helper.succeed();
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    /** Decay, range, thresholds, speed bonus and the datapack merge of the rule module with its defaults. */
    private static void rulesTable(GameTestHelper helper) {
        EntertainmentRules rules = EntertainmentRules.FALLBACK;

        // value, days, expected after decay
        double[][] decay = {{10, 1, 9}, {10, 0, 10}, {5, 3, 2}, {1, 1, 0}, {3, 5, 0}, {0, 1, 0}, {4.5, 1, 3.5}, {10, 30, 0}};
        for (double[] row : decay) {
            double actual = rules.decayed(row[0], (long) row[1]);
            helper.assertTrue(near(actual, row[2]), "Value " + row[0] + " after " + row[1] + " days is " + row[2] + ": " + actual);
        }

        // value, expected after clamping to 0..10
        double[][] range = {{-2, 0}, {0, 0}, {7.5, 7.5}, {10, 10}, {14, 10}};
        for (double[] row : range) {
            helper.assertTrue(near(EntertainmentRules.clamp(row[0]), row[1]), "Clamped " + row[0] + " is " + row[1] + ": " + EntertainmentRules.clamp(row[0]));
        }

        // value, tier 2 met, tier 3 met: more than 5 for tier 2, more than 8 for tier 3
        double[][] thresholds = {{0, 0, 0}, {5, 0, 0}, {5.5, 1, 0}, {6, 1, 0}, {8, 1, 0}, {8.5, 1, 1}, {9, 1, 1}, {10, 1, 1}};
        for (double[] row : thresholds) {
            helper.assertTrue(rules.met(row[0], 2) == (row[1] == 1), "Value " + row[0] + ", tier 2 met " + (row[1] == 1));
            helper.assertTrue(rules.met(row[0], 3) == (row[2] == 1), "Value " + row[0] + ", tier 3 met " + (row[2] == 1));
        }
        helper.assertTrue(rules.met(0, 1) && rules.met(0, 4) && rules.met(0, 5), "Only the tiers 2 and 3 ask for Entertainment");

        // value, blessing factor, expected combined factor: 2 % per point, added to the blessing's share
        double[][] speed = {{0, 1.0, 1.0}, {5, 1.0, 1.1}, {10, 1.0, 1.2}, {0, 1.2, 1.2}, {5, 1.2, 1.3}, {10, 1.2, 1.4}, {15, 1.0, 1.2}, {-1, 1.0, 1.0}};
        for (double[] row : speed) {
            double actual = rules.speedFactor(row[0], row[1]);
            helper.assertTrue(near(actual, row[2]), "Value " + row[0] + ", blessing " + row[1] + ": factor " + row[2] + ", got " + actual);
        }
        helper.assertTrue(near(rules.speedBonus(10), 0.2) && near(rules.speedBonus(3), 0.06), "The bonus is 2 % per point");

        // datapack files: later values override, missing values keep the defaults
        helper.assertTrue(EntertainmentRules.merge(List.of()).equals(rules), "No file: the defaults");
        EntertainmentRules merged = EntertainmentRules.merge(List.of(json("{\"decay\": 2, \"tier_2_threshold\": 4}"), json("{\"decay\": 3, \"speed_per_point\": 0.05}")));
        helper.assertTrue(merged.equals(new EntertainmentRules(3.0, 4.0, 8.0, 0.05, 3.0, 1.5, 4, 12, 8)), "Merged in order: " + merged);
        EntertainmentRules tavern = EntertainmentRules.merge(List.of(json("{\"tavern_points\": 2, \"crowd_size\": 3, \"jukebox_radius\": 5}"),
                json("{\"tavern_factor\": 2, \"tavern_radius\": 10}")));
        helper.assertTrue(tavern.equals(new EntertainmentRules(1.0, 5.0, 8.0, 0.02, 2.0, 2.0, 3, 10, 5)), "The Tavern values merge: " + tavern);

        // present settlers, jukebox with a disc, expected points per guest: A or B gives the base, both multiply it
        double[][] evening = {{0, 0, 0}, {3, 0, 0}, {4, 0, 3}, {9, 0, 3}, {0, 1, 3}, {1, 1, 3}, {3, 1, 3}, {4, 1, 4.5}};
        for (double[] row : evening) {
            helper.assertTrue(near(rules.tavernPoints((int) row[0], row[1] == 1), row[2]),
                    row[0] + " present, jukebox " + (row[1] == 1) + ": " + row[2] + ", got " + rules.tavernPoints((int) row[0], row[1] == 1));
        }
        helper.succeed();
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }
}
