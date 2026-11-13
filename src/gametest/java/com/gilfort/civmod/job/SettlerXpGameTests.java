package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;
import com.gilfort.civmod.api.XpGain;
import com.gilfort.civmod.mission.Mission;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestSettlements;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for settler XP and Mending (vault {@code Systems/Settler XP.md}): the sources per Job, the datapack list, the
 * repair from new XP only, mission XP and the expected XP of catch-up.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlerXpGameTests {

    private static final double EPSILON = 1.0E-4;

    private SettlerXpGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "xp_gain_rolls_and_expects", SettlerXpGameTests::gainRollsAndExpects);
            register(helper, "xp_miner_defaults", SettlerXpGameTests::minerDefaults);
            register(helper, "xp_producing_defaults", SettlerXpGameTests::producingDefaults);
            register(helper, "xp_datapack_replaces_defaults", SettlerXpGameTests::datapackReplacesDefaults);
            register(helper, "xp_mending_new_xp_only", SettlerXpGameTests::mendingNewXpOnly);
            register(helper, "xp_mending_order", SettlerXpGameTests::mendingOrder);
            register(helper, "xp_counter_crossings", SettlerXpGameTests::counterCrossings);
            register(helper, "xp_mission_success_only", SettlerXpGameTests::missionSuccessOnly);
            register(helper, "xp_catch_up_expected", SettlerXpGameTests::catchUpExpected);
            register(helper, "xp_work_rules", SettlerXpGameTests::workRules);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    private static Settler settler(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        settler.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        return settler;
    }

    private static ItemStack tool(ServerLevel level, Item item, int damage, boolean mending) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(damage);
        if (mending) {
            stack.enchant(enchantment(level, Enchantments.MENDING), 1);
        }
        return stack;
    }

    private static Holder<Enchantment> enchantment(ServerLevel level, ResourceKey<Enchantment> key) {
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    /** The fraction is a chance for one more point; a failed chance gives nothing. */
    private static void gainRollsAndExpects(GameTestHelper helper) {
        RandomSource random = RandomSource.create(1L);
        helper.assertTrue(near(XpGain.of(1, 6).expected(), 3.5), "1–6 expects 3.5");
        helper.assertTrue(near(new XpGain(2, 4, 0.5, 0.0).expected(), 1.5), "The chance scales the amount");
        helper.assertTrue(near(XpGain.fractional(2.35).expected(), 2.35), "A fraction expects itself");
        int rounds = 2000;
        int ones = 0;
        for (int i = 0; i < rounds; i++) {
            int rolled = XpGain.fractional(0.25).roll(random);
            helper.assertTrue(rolled == 0 || rolled == 1, "Fraction 0.25 rolls 0 or 1: " + rolled);
            ones += rolled;
            int range = XpGain.of(1, 6).roll(random);
            helper.assertTrue(range >= 1 && range <= 6, "Inside the range: " + range);
        }
        helper.assertTrue(ones > rounds / 8 && ones < rounds / 2, "About a quarter: " + ones);
        helper.assertTrue(XpGain.NONE.roll(random) == 0 && XpGain.NONE.expected() == 0.0, "None gives nothing");
        helper.succeed();
    }

    /** The block XP range of an ore; stone and Silk Touch give none. */
    private static void minerDefaults(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        helper.assertTrue(MinerProfile.blockXp(level, Blocks.DIAMOND_ORE.defaultBlockState(), pickaxe).equals(XpGain.of(3, 7)), "Diamond ore 3–7");
        helper.assertTrue(MinerProfile.blockXp(level, Blocks.COAL_ORE.defaultBlockState(), pickaxe).equals(XpGain.of(0, 2)), "Coal ore 0–2");
        helper.assertTrue(MinerProfile.blockXp(level, Blocks.REDSTONE_ORE.defaultBlockState(), pickaxe).equals(XpGain.of(1, 5)), "Redstone ore 1–5");
        helper.assertTrue(MinerProfile.blockXp(level, Blocks.STONE.defaultBlockState(), pickaxe).equals(XpGain.NONE), "Stone gives none");
        ItemStack silk = new ItemStack(Items.IRON_PICKAXE);
        silk.enchant(enchantment(level, Enchantments.SILK_TOUCH), 1);
        helper.assertTrue(MinerProfile.blockXp(level, Blocks.DIAMOND_ORE.defaultBlockState(), silk).equals(XpGain.NONE), "Silk Touch gives none");
        helper.succeed();
    }

    /** Smelter, Cook and Founder earn the recipe's experience; everyone else nothing. */
    private static void producingDefaults(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RecipeHolder<?> smelted = recipe(level, "iron_ingot_from_smelting_raw_iron");
        RecipeHolder<?> smoked = recipe(level, "cooked_beef_from_smoking");
        helper.assertTrue(near(SmelterProfile.smelter().xp(level, smelted).expected(), 0.7), "Smelter: the recipe's experience");
        helper.assertTrue(near(SmelterProfile.cook().xp(level, smoked).expected(), 0.35), "Cook: the recipe's experience");
        helper.assertTrue(near(SmelterProfile.founder().xp(level, recipe(level, "iron_ingot_from_blasting_raw_iron")).expected(), 0.7), "Founder: the recipe's experience");
        helper.assertTrue(new CraftspersonProfile().xp(level, smelted).equals(XpGain.NONE), "Craftsperson: none");
        helper.assertTrue(FishermanProfile.CATCH_XP.equals(XpGain.of(1, 6)), "Fisherman: 1–6 per catch");
        helper.assertTrue(new ResourceCollectionProfile.Produced(List.of(), ItemStack.EMPTY).xp().equals(XpGain.NONE), "Other collecting Jobs: none");
        helper.succeed();
    }

    private static RecipeHolder<?> recipe(ServerLevel level, String name) {
        return level.getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(name))).orElseThrow();
    }

    /** A pack's list replaces the default and matching entries add up; a later file replaces the list as a whole. */
    private static void datapackReplacesDefaults(GameTestHelper helper) {
        JsonElement first = JsonParser.parseString("""
                { "xp": [ { "amount": 9 } ] }""");
        JsonElement second = JsonParser.parseString("""
                { "xp": [
                    { "amount": 2 },
                    { "items": "minecraft:oak_log", "amount": [1, 3], "chance": 0.5 },
                    { "items": "#minecraft:planks", "amount": 4 },
                    { "items": ["minecraft:stick", "minecraft:bone"], "amount": 7 }
                ] }""");
        JobBalancing merged = JobBalancing.merge(List.of(first, second));
        List<XpEntry> entries = merged.xp().orElseThrow();
        helper.assertTrue(entries.size() == 4, "The later list replaces the earlier one: " + entries);
        helper.assertTrue(JobBalancing.merge(List.of(first, JsonParser.parseString("{ \"base_exp\": 3 }"))).xp().orElseThrow().size() == 1,
                "A file without xp keeps the list");

        XpGain byDefault = XpGain.of(5);
        helper.assertTrue(SettlerXp.gains(Optional.empty(), byDefault, List.of(new ItemStack(Items.OAK_LOG))).equals(List.of(byDefault)), "No list: the default");
        helper.assertTrue(near(total(SettlerXp.gains(merged.xp(), byDefault, List.of(new ItemStack(Items.OAK_LOG)))), 2.0 + 1.0), "Per action plus the item");
        helper.assertTrue(near(total(SettlerXp.gains(merged.xp(), byDefault, List.of(new ItemStack(Items.OAK_PLANKS)))), 2.0 + 4.0), "By tag");
        helper.assertTrue(near(total(SettlerXp.gains(merged.xp(), byDefault, List.of(new ItemStack(Items.BONE)))), 2.0 + 7.0), "By list");
        helper.assertTrue(near(total(SettlerXp.gains(merged.xp(), byDefault, List.of())), 2.0), "No item: the entry per action alone");
        helper.assertTrue(near(total(SettlerXp.gains(merged.xp(), byDefault, List.of(new ItemStack(Items.OAK_LOG), new ItemStack(Items.OAK_LOG, 3)))), 2.0 + 1.0),
                "An entry counts once per action");
        helper.assertTrue(near(total(SettlerXp.gains(Optional.of(List.of()), byDefault, List.of(new ItemStack(Items.OAK_LOG)))), 0.0), "An empty list means no XP");
        helper.succeed();
    }

    private static double total(List<XpGain> gains) {
        return gains.stream().mapToDouble(XpGain::expected).sum();
    }

    /** New XP repairs at 1 XP = 2 durability, the rest is stored; stored XP never repairs. */
    private static void mendingNewXpOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = settler(helper);
        ItemStack pickaxe = tool(level, Items.IRON_PICKAXE, 5, true);
        settler.getInventory().setItem(Settler.BELT_START, pickaxe);
        settler.getXp().restore(40);

        settler.getWork().collectXp(level, List.of(XpGain.of(10)), pickaxe);
        helper.assertTrue(pickaxe.getDamageValue() == 0, "5 durability repaired, left " + pickaxe.getDamageValue());
        helper.assertTrue(settler.getXp().points() == 40 + 7, "3 XP spent, 7 stored: " + settler.getXp().points());

        pickaxe.setDamageValue(20);
        settler.getWork().collectXp(level, List.of(XpGain.NONE), pickaxe);
        helper.assertTrue(pickaxe.getDamageValue() == 20 && settler.getXp().points() == 47, "Stored XP never repairs");

        settler.getWork().collectXp(level, List.of(XpGain.of(1)), pickaxe);
        helper.assertTrue(pickaxe.getDamageValue() == 18 && settler.getXp().points() == 47, "1 XP repairs 2 and is used up");

        ItemStack plain = tool(level, Items.IRON_PICKAXE, 20, false);
        settler.getInventory().setItem(Settler.BELT_START, plain);
        settler.getWork().collectXp(level, List.of(XpGain.of(4)), plain);
        helper.assertTrue(plain.getDamageValue() == 20 && settler.getXp().points() == 51, "Without Mending all of it is stored");
        settler.discard();
        helper.succeed();
    }

    /** First the tool used, then the belt; pockets, armour and the offhand stay as they are. */
    private static void mendingOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = settler(helper);
        ItemStack first = tool(level, Items.IRON_AXE, 4, true);
        ItemStack used = tool(level, Items.IRON_PICKAXE, 4, true);
        ItemStack pocket = tool(level, Items.IRON_SHOVEL, 4, true);
        ItemStack armour = tool(level, Items.IRON_CHESTPLATE, 4, true);
        ItemStack offhand = tool(level, Items.IRON_HOE, 4, true);
        settler.getInventory().setItem(Settler.BELT_START, first);
        settler.getInventory().setItem(Settler.BELT_START + 1, used);
        settler.getInventory().setItem(Settler.POCKETS_START, pocket);
        settler.getInventory().setItem(Settler.ARMOR_START, armour);
        settler.getInventory().setItem(Settler.OFFHAND, offhand);

        settler.getWork().collectXp(level, List.of(XpGain.of(1)), used);
        helper.assertTrue(used.getDamageValue() == 2 && first.getDamageValue() == 4, "The used tool comes first");
        settler.getWork().collectXp(level, List.of(XpGain.of(2)), used);
        helper.assertTrue(used.getDamageValue() == 0 && first.getDamageValue() == 2, "Then the other tools of the belt");
        settler.getWork().collectXp(level, List.of(XpGain.of(4)), ItemStack.EMPTY);
        helper.assertTrue(first.getDamageValue() == 0 && settler.getXp().points() == 3, "No tool used: the belt; the rest is stored");
        helper.assertTrue(pocket.getDamageValue() == 4 && armour.getDamageValue() == 4 && offhand.getDamageValue() == 4, "Pockets, armour and offhand stay");
        settler.discard();
        helper.succeed();
    }

    /** One crossing per action, however many hundreds it passes. */
    private static void counterCrossings(GameTestHelper helper) {
        SettlerXp xp = new SettlerXp();
        helper.assertTrue(!xp.store(99) && xp.store(1) && xp.points() == 100, "Reaching 100 crosses");
        helper.assertTrue(!xp.store(0) && !xp.store(99), "Below the next hundred");
        helper.assertTrue(xp.store(350) && xp.points() == 549, "Several hundreds cross once");
        xp.restore(Integer.MAX_VALUE - 1);
        xp.store(5);
        helper.assertTrue(xp.points() == Integer.MAX_VALUE, "The counter stops at the int limit");
        helper.succeed();
    }

    /** A mission's XP counts on success only; missing XP is none. */
    private static void missionSuccessOnly(GameTestHelper helper) {
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        JsonElement file = JsonParser.parseString("""
                { "title": "T", "days": 1, "recommended_level": 1, "jobs": ["civmod:adventurer"], "loot_tables": ["minecraft:chests/simple_dungeon"],
                  "xp": { "amount": [10, 20], "chance": 0.5 } }""");
        Mission mission = Mission.CODEC.parse(ops, file).getOrThrow();
        helper.assertTrue(mission.xp().equals(new XpGain(10, 20, 0.5, 0.0)), "Parsed: " + mission.xp());
        helper.assertTrue(Mission.CODEC.parse(ops, Mission.CODEC.encodeStart(ops, mission).getOrThrow()).getOrThrow().xp().equals(mission.xp()), "Round trip");
        helper.assertTrue(MissionRules.xp(MissionRules.Outcome.SUCCESS, mission).equals(mission.xp()), "Success gives it");
        for (MissionRules.Outcome outcome : MissionRules.Outcome.values()) {
            if (outcome != MissionRules.Outcome.SUCCESS) {
                helper.assertTrue(MissionRules.xp(outcome, mission).equals(XpGain.NONE), outcome + " gives none");
            }
        }
        file.getAsJsonObject().remove("xp");
        helper.assertTrue(Mission.CODEC.parse(ops, file).getOrThrow().xp().equals(XpGain.NONE), "Missing = none");
        Settler settler = settler(helper);
        ItemStack damaged = tool(helper.getLevel(), Items.IRON_PICKAXE, 20, true);
        settler.getInventory().setItem(Settler.BELT_START, damaged);
        settler.getWork().storeXp(helper.getLevel(), XpGain.of(5));
        helper.assertTrue(settler.getXp().points() == 5 && damaged.getDamageValue() == 20, "Mission XP goes to the counter without Mending");
        settler.discard();
        helper.succeed();
    }

    /** Catch-up adds the expected XP without rolling, keeps the fractions and mends with the whole points. */
    private static void catchUpExpected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = settler(helper);
        ItemStack pickaxe = tool(level, Items.IRON_PICKAXE, 2, true);
        settler.getInventory().setItem(Settler.BELT_START, pickaxe);
        settler.getWork().startCatchUp();
        settler.getWork().collectXp(level, List.of(XpGain.of(1, 6)), pickaxe);
        helper.assertTrue(pickaxe.getDamageValue() == 0 && settler.getXp().points() == 2, "3 of 3.5 points: 1 mends, 2 stored: " + settler.getXp().points());
        settler.getWork().collectXp(level, List.of(XpGain.of(1, 6)), pickaxe);
        helper.assertTrue(settler.getXp().points() == 6, "The half point carries over: " + settler.getXp().points());
        // Eleven: ten times 0.1 stays just below 1 as a double; the rest is kept, not lost.
        for (int i = 0; i < 11; i++) {
            settler.getWork().collectXp(level, List.of(XpGain.fractional(0.1)), ItemStack.EMPTY);
        }
        helper.assertTrue(settler.getXp().points() == 7, "Small fractions add up: " + settler.getXp().points());
        settler.discard();
        helper.succeed();
    }

    /** One production step of the Work rules: earns the XP of the roll, after the tool's wear. */
    private static final class XpProfile implements ResourceCollectionProfile {

        private final XpGain gain;
        private final List<ItemStack> drops;

        XpProfile(XpGain gain, List<ItemStack> drops) {
            this.gain = gain;
            this.drops = drops;
        }

        @Override
        public boolean isTool(ItemStack stack) {
            return stack.is(ItemTags.PICKAXES);
        }

        @Override
        public Item toolIcon() {
            return Items.STICK;
        }

        @Override
        public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
            return true;
        }

        @Override
        public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
            return true;
        }

        @Override
        public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock) {
            return null;
        }

        @Override
        public boolean rescanDaily() {
            return false;
        }

        @Override
        public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
            throw new UnsupportedOperationException("The test does not scan");
        }

        @Override
        public boolean needsResources() {
            return false;
        }

        @Override
        public double speed(int skillLevel, ItemStack tool) {
            return 20.0;
        }

        @Override
        public Optional<Produced> produce(Context context) {
            return Optional.of(new Produced(drops, context.tool(), gain));
        }
    }

    /** Output that takes everything. */
    private static final WorkRules.Output NOWHERE = new WorkRules.Output() {
        @Override
        public boolean hasRoom() {
            return true;
        }

        @Override
        public boolean accept(List<ItemStack> stacks) {
            return true;
        }
    };

    /** Three rolls with the used tool: wear and Mending both count, what is left over is stored. */
    private static void workRules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos jobBlock = helper.absolutePos(new BlockPos(1, 1, 1));
        TestSettlements.place(helper, jobBlock, ModJobs.MINER.get(), 1);
        Settler settler = settler(helper);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        SettlerJob job = new SettlerJob(ModJobs.MINER.get(), jobBlock);
        settler.addJob(job);
        ItemStack pickaxe = tool(level, Items.IRON_PICKAXE, 10, true);
        settler.getInventory().setItem(Settler.BELT_START, pickaxe);
        WorkRules.work(level, settler, job, new XpProfile(XpGain.of(3), List.of(new ItemStack(Items.COBBLESTONE))), JobBlockSite.at(level, jobBlock).orElseThrow(), settler.getWork().workplace(jobBlock),
                jobBlock, 3, SimulatedSpan.LIVE, WorkRules.ToolSupply.BELT, NOWHERE);
        helper.assertTrue(pickaxe.getDamageValue() == 0, "Each roll wears 1 and mends up to 6: " + pickaxe.getDamageValue());
        helper.assertTrue(settler.getXp().points() == 2, "The third roll needs 1 of its 3 XP, the rest is stored: " + settler.getXp().points());
        WorkRules.work(level, settler, job, new XpProfile(XpGain.of(3), List.of()), JobBlockSite.at(level, jobBlock).orElseThrow(), settler.getWork().workplace(jobBlock),
                jobBlock, 3, SimulatedSpan.LIVE, WorkRules.ToolSupply.BELT, NOWHERE);
        helper.assertTrue(settler.getXp().points() == 2, "A roll that yields nothing earns no profile XP: " + settler.getXp().points());
        settler.discard();
        SettlementManager.remove(data, jobBlock, 0L, SettlementManager.Listener.NONE);
        helper.succeed();
    }
}
