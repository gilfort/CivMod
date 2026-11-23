package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.clothing.ClothingRules;
import com.gilfort.civmod.clothing.SettlerClothing.State;
import com.gilfort.civmod.clothing.SettlerClothing.Want;
import com.gilfort.civmod.entertainment.Entertainment;
import com.gilfort.civmod.entertainment.EntertainmentRules;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.NeedTally;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModClothing;
import com.gilfort.civmod.registry.ModClothing.ClothSet;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.ArmorType;
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
 * GameTests for the clothing need and wearing (vault {@code Systems/Needs.md}, decisions of 2026-11-21): the tier rules, the Needs tab
 * rows, putting pieces on from the collect inventory and by hand, the wear per day and per mission day, catch-up, pieces below 20 % and
 * pieces that break. The seam is the morning need check of a settler: the pieces it wears, the tier it works at and the rows it shows.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClothingNeedGameTests {

    private static final long DAY = 24000L;
    /** A JobBlock of level 3: skill cap 60. */
    private static final int BLOCK_LEVEL = 3;
    /** The durability of the test pieces, so that 2 % are 2 points and 5 % are 5. */
    private static final int MAX_DAMAGE = 100;
    private static final List<ArmorType> TYPES = List.of(ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS);

    private ClothingNeedGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "clothing_rank_rules", ClothingNeedGameTests::rankRules);
            register(helper, "clothing_row_per_tier", ClothingNeedGameTests::rowPerTier);
            register(helper, "clothing_higher_set_satisfies_lower", ClothingNeedGameTests::higherSetSatisfiesLower);
            register(helper, "clothing_untagged_armour_does_not_count", ClothingNeedGameTests::untaggedArmour);
            register(helper, "clothing_tier_one_asks_nothing", ClothingNeedGameTests::tierOneAsksNothing);
            register(helper, "clothing_tier_drops_and_climbs", ClothingNeedGameTests::tierDropsAndClimbs);
            register(helper, "clothing_catch_up_downgrade", ClothingNeedGameTests::catchUpDowngrade);
            register(helper, "clothing_put_on_from_collect", ClothingNeedGameTests::putOnFromCollect);
            register(helper, "clothing_replacement_rules", ClothingNeedGameTests::replacementRules);
            register(helper, "clothing_search_hourly", ClothingNeedGameTests::searchHourly);
            register(helper, "clothing_right_click_equips", ClothingNeedGameTests::rightClickEquips);
            register(helper, "clothing_right_click_feeds_first", ClothingNeedGameTests::rightClickFeeds);
            register(helper, "clothing_wear_per_day", ClothingNeedGameTests::wearPerDay);
            register(helper, "clothing_wear_catch_up", ClothingNeedGameTests::wearCatchUp);
            register(helper, "clothing_wear_mission_day", ClothingNeedGameTests::wearMissionDay);
            register(helper, "clothing_worn_out_still_counts", ClothingNeedGameTests::wornOutStillCounts);
            register(helper, "clothing_broken_piece_is_gone", ClothingNeedGameTests::brokenPieceIsGone);
            register(helper, "clothing_saved", ClothingNeedGameTests::saved);
            register(helper, "clothing_reputation", ClothingNeedGameTests::reputation);
            register(helper, "clothing_bubble_shows_missing_piece", ClothingNeedGameTests::bubbleShowsMissingPiece);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- pieces and settlers

    /** A cloth piece with the test durability, {@code damage} points used. */
    private static ItemStack piece(ClothSet set, ArmorType type, int damage) {
        ItemStack stack = new ItemStack(ModClothing.piece(set, type).get());
        stack.set(DataComponents.MAX_DAMAGE, MAX_DAMAGE);
        stack.setDamageValue(damage);
        return stack;
    }

    /** The four pieces of a set in the order head, chest, legs, feet. */
    private static ItemStack[] set(ClothSet set, int damage) {
        return TYPES.stream().map(type -> piece(set, type, damage)).toArray(ItemStack[]::new);
    }

    /** Puts the pieces on, in the order head, chest, legs, feet; a missing one leaves its slot empty. */
    private static void wear(Settler settler, ItemStack... pieces) {
        for (int i = 0; i < ClothingRules.SLOTS.size(); i++) {
            settler.getInventory().setItem(Settler.ARMOR_START + i, i < pieces.length ? pieces[i] : ItemStack.EMPTY);
        }
    }

    /** Dresses a settler in a full set of highest clothing, which meets the clothing need of every tier. */
    static void dressInHighest(Settler settler) {
        wear(settler, set(ClothSet.HIGH, 0));
    }

    private static ItemStack worn(Settler settler, EquipmentSlot slot) {
        return settler.getClothing().worn(slot);
    }

    private static long today(ServerLevel level) {
        return DayClock.day(level.getOverworldClockTime());
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents contents ? contents.getKey() : text.getString();
    }

    private static Object[] args(Component text) {
        return ((TranslatableContents) text.getContents()).getArgs();
    }

    private static List<NeedsReport.Row> clothingRows(Settler settler, ServerLevel level) {
        return settler.getWork().needsReport(level).rows().stream().filter(row -> key(row.name()).equals("civmod.need.clothing")).toList();
    }

    private static NeedsReport.Row clothingRow(Settler settler, ServerLevel level, int tier) {
        return clothingRows(settler, level).stream().filter(row -> row.tier() == tier).findFirst().orElseThrow();
    }

    /** A settler of a tier that does not move, with its job at a JobBlock that is only registered, not placed. */
    private static Settler plain(GameTestHelper helper, BlockPos jobBlock, int skill) {
        return NeedTierGameTests.hungry(helper, jobBlock, ModJobs.LUMBERJACK.get(), skill);
    }

    private static BlockPos block(GameTestHelper helper) {
        return NeedTierGameTests.place(helper, new BlockPos(1, 1, 1), ModJobs.LUMBERJACK.get(), BLOCK_LEVEL);
    }

    /** Every need but clothing is met: a hearth, an own bed, three foods, full Entertainment and no hunger. */
    static Settler comfortable(GameTestHelper helper, BlockPos jobBlock, int skill) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.CAMPFIRE);
        Settler settler = plain(helper, jobBlock, skill);
        BlockPos head = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(head.south(), bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        SettlementData data = SettlementData.get(level);
        SettlerBeds.assign(level, data, data.settlementAt(jobBlock), settler.getUUID(), head);
        for (Item food : List.of(Items.BREAD, Items.CARROT, Items.APPLE)) {
            settler.getFood().variety().record(BuiltInRegistries.ITEM.getKey(food), today(level));
        }
        settler.getFood().setLevel(15);
        return settler;
    }

    /** The need check of a day with full Entertainment, so that clothing is the only need that can fail. */
    private static void check(Settler settler, ServerLevel level, long day) {
        settler.getWork().setEntertainment(Entertainment.of(EntertainmentRules.MAX));
        settler.getWork().checkNeedsNow(level, day);
    }

    private static int level(Settler settler) {
        return settler.getWork().effectiveLevel(settler.getJobs().get(0));
    }

    // ---------------------------------------------------------------- the rules

    /** The tag of a tier, the rank of a piece, its slot, the worn-out line and when a piece replaces another. */
    private static void rankRules(GameTestHelper helper) {
        int[] requiredByTier = {0, 0, 1, 2, 2, 3};
        for (int tier = 1; tier <= 5; tier++) {
            helper.assertTrue(ClothingRules.requiredRank(tier) == requiredByTier[tier], "Tier " + tier + " asks for rank " + requiredByTier[tier]);
        }
        helper.assertTrue(ClothingRules.tagOfRank(1).equals(ClothSet.SIMPLE.tag()) && ClothingRules.tagOfRank(2).equals(ClothSet.MEDIUM.tag())
                && ClothingRules.tagOfRank(3).equals(ClothSet.HIGH.tag()), "Rank 1 is simple, 2 better, 3 highest clothing");
        helper.assertTrue(ClothingRules.rank(piece(ClothSet.SIMPLE, ArmorType.HELMET, 0)) == 1, "Simple Cloth is rank 1");
        helper.assertTrue(ClothingRules.rank(piece(ClothSet.MEDIUM, ArmorType.CHESTPLATE, 0)) == 2, "Medium Cloth is rank 2");
        helper.assertTrue(ClothingRules.rank(piece(ClothSet.HIGH, ArmorType.BOOTS, 0)) == 3, "High Cloth is rank 3");
        helper.assertTrue(ClothingRules.rank(new ItemStack(Items.IRON_CHESTPLATE)) == ClothingRules.NO_RANK
                && ClothingRules.rank(new ItemStack(Items.LEATHER_BOOTS)) == ClothingRules.NO_RANK, "Armour without a clothing tag has no rank");
        helper.assertTrue(ClothingRules.slotOf(piece(ClothSet.SIMPLE, ArmorType.HELMET, 0)).equals(Optional.of(EquipmentSlot.HEAD))
                && ClothingRules.slotOf(new ItemStack(Items.IRON_BOOTS)).equals(Optional.of(EquipmentSlot.FEET))
                && ClothingRules.slotOf(new ItemStack(Items.STICK)).isEmpty(), "A piece fits the slot of its armour type");

        helper.assertFalse(ClothingRules.isWornOut(piece(ClothSet.SIMPLE, ArmorType.HELMET, 80)), "20 % left is not worn out yet");
        helper.assertTrue(ClothingRules.isWornOut(piece(ClothSet.SIMPLE, ArmorType.HELMET, 81)), "19 % left is worn out");

        ItemStack fresh = piece(ClothSet.SIMPLE, ArmorType.HELMET, 0);
        ItemStack tired = piece(ClothSet.SIMPLE, ArmorType.HELMET, 90);
        helper.assertTrue(ClothingRules.replaces(piece(ClothSet.MEDIUM, ArmorType.HELMET, 0), fresh), "A higher rank replaces");
        helper.assertTrue(ClothingRules.replaces(fresh, ItemStack.EMPTY), "Anything replaces an empty slot");
        helper.assertTrue(ClothingRules.replaces(fresh, new ItemStack(Items.IRON_HELMET)), "Clothing replaces armour that is no clothing");
        helper.assertFalse(ClothingRules.replaces(fresh, piece(ClothSet.MEDIUM, ArmorType.HELMET, 95)), "A lower rank never replaces, not even a worn-out piece");
        helper.assertFalse(ClothingRules.replaces(piece(ClothSet.SIMPLE, ArmorType.HELMET, 5), fresh), "The same rank does not replace a healthy piece");
        helper.assertTrue(ClothingRules.replaces(fresh, tired), "The same rank replaces a worn-out piece");
        helper.assertFalse(ClothingRules.replaces(piece(ClothSet.SIMPLE, ArmorType.HELMET, 85), tired), "A worn-out piece does not replace a worn-out one");
        helper.assertFalse(ClothingRules.replaces(new ItemStack(Items.IRON_HELMET), ItemStack.EMPTY), "Armour that is no clothing never goes on");
        helper.succeed();
    }

    // ---------------------------------------------------------------- the need

    /** One row per tier from 2, four slot icons each; nothing at tier 1. */
    private static void rowPerTier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 15);
        helper.assertTrue(clothingRows(settler, level).stream().map(NeedsReport.Row::tier).toList().equals(List.of(2, 3, 4, 5)),
                "Rows for tiers 2 to 5, none for tier 1: " + clothingRows(settler, level));
        helper.assertTrue(settler.getWork().needsReport(level).rows(1).stream().noneMatch(row -> key(row.name()).equals("civmod.need.clothing")),
                "Tier 1 asks for nothing");
        for (int tier = 2; tier <= 5; tier++) {
            NeedsReport.Row row = clothingRow(settler, level, tier);
            helper.assertFalse(row.met(), "A settler arrives without clothes: tier " + tier);
            helper.assertTrue(key(row.detail()).equals("civmod.need.clothing.missing") && args(row.detail()).length == 1, "Detail: " + row.detail());
            helper.assertTrue(row.icons().size() == 4, "Four slot icons: " + row.icons());
        }
        Item asked = clothingRow(settler, level, 2).icons().get(0).getItem();
        helper.assertTrue(asked == ModClothing.piece(ClothSet.SIMPLE, ArmorType.HELMET).get(), "The icons show the pieces that are missing: " + asked);
        helper.assertTrue(clothingRow(settler, level, 3).icons().get(1).getItem() == ModClothing.piece(ClothSet.MEDIUM, ArmorType.CHESTPLATE).get()
                && clothingRow(settler, level, 5).icons().get(3).getItem() == ModClothing.piece(ClothSet.HIGH, ArmorType.BOOTS).get(),
                "Tiers 3 and 4 show better clothing, tier 5 highest clothing");
        for (EquipmentSlot slot : ClothingRules.SLOTS) {
            helper.assertTrue(worn(settler, slot).isEmpty(), "No clothes on arrival: " + slot);
        }
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** A higher set satisfies a lower tier: simple tier 2, better tiers 2 to 4, highest every tier. */
    private static void higherSetSatisfiesLower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 15);
        int[][] metTiers = {{2}, {2, 3, 4}, {2, 3, 4, 5}};
        for (ClothSet cloth : ClothSet.values()) {
            wear(settler, set(cloth, 0));
            List<Integer> met = clothingRows(settler, level).stream().filter(NeedsReport.Row::met).map(NeedsReport.Row::tier).toList();
            List<Integer> expected = java.util.Arrays.stream(metTiers[cloth.ordinal()]).boxed().toList();
            helper.assertTrue(met.equals(expected), cloth + " Cloth meets tiers " + expected + ": " + met);
        }
        NeedsReport.Row full = clothingRow(settler, level, 5);
        helper.assertTrue(key(full.detail()).equals("civmod.need.clothing.met"), "Detail of a met row: " + full.detail());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Armour without a clothing tag does not count, one such piece breaks the set, and the row names the slot. */
    private static void untaggedArmour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 15);
        wear(settler, new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.IRON_LEGGINGS), new ItemStack(Items.IRON_BOOTS));
        helper.assertTrue(clothingRows(settler, level).stream().noneMatch(NeedsReport.Row::met), "Iron armour is no clothing");
        wear(settler, piece(ClothSet.HIGH, ArmorType.HELMET, 0), piece(ClothSet.HIGH, ArmorType.CHESTPLATE, 0), piece(ClothSet.HIGH, ArmorType.LEGGINGS, 0),
                new ItemStack(Items.LEATHER_BOOTS));
        NeedsReport.Row row = clothingRow(settler, level, 2);
        helper.assertFalse(row.met(), "Three pieces and leather boots");
        Component missing = (Component) args(row.detail())[0];
        helper.assertTrue(key(missing).equals("civmod.need.clothing.slot.feet"), "The boots are missing: " + missing);
        helper.assertTrue(row.icons().get(3).getItem() == ModClothing.piece(ClothSet.SIMPLE, ArmorType.BOOTS).get(), "The icon is the piece to wear: " + row.icons());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** No settler of tier 1 asks for anything: nothing is wanted, and its work level is not limited by clothing. */
    private static void tierOneAsksNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = comfortable(helper, jobBlock, 15);
        long day = today(level);
        for (int i = 0; i < 3; i++) {
            check(settler, level, day + i);
        }
        helper.assertTrue(settler.getClothing().wants(1).isEmpty(), "Tier 1 wants nothing: " + settler.getClothing().wants(1));
        helper.assertTrue(level(settler) == 15 && !settler.getWork().needState().inGrace(), "Working at full level without clothes: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Without clothes: grace day, then one tier lower each morning; a simple set climbs back to tier 2 at once, a better set to the full level. */
    private static void tierDropsAndClimbs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = comfortable(helper, jobBlock, 45);
        long day = today(level);
        check(settler, level, day);
        helper.assertTrue(level(settler) == 45 && settler.getWork().needState().inGrace(), "Grace day at the full level: " + level(settler));
        check(settler, level, day + 1);
        helper.assertTrue(level(settler) == 40, "Next morning at the top of tier 2: " + level(settler));
        check(settler, level, day + 2);
        helper.assertTrue(level(settler) == 20, "Tier 2 clothing is missing too: " + level(settler));

        wear(settler, set(ClothSet.SIMPLE, 0));
        check(settler, level, day + 2);
        helper.assertTrue(level(settler) == 40, "A simple set climbs back at once, to the top of tier 2: " + level(settler));

        wear(settler, set(ClothSet.MEDIUM, 0));
        check(settler, level, day + 2);
        helper.assertTrue(level(settler) == 45 && !settler.getWork().needState().inGrace(), "A better set: the full level, no grace day: " + level(settler));
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Catch-up goes through the same step: two missed days without clothes cost a tier. */
    private static void catchUpDowngrade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = comfortable(helper, jobBlock, 45);
        settler.getWork().setEntertainment(Entertainment.of(EntertainmentRules.MAX));
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), DAY) * DAY;
        settler.getWork().setCatchUp(dayStart - 2 * DAY, dayStart);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(level(settler) < 45, "Grace day, then a lower tier without clothes: " + settler.getWork().needState());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    // ---------------------------------------------------------------- putting on

    /** A settler at its JobBlock whose collect inventory is the block's own container, forced chunks included. */
    private record World(Settler settler, SettlerJob job, BlockPos jobBlock, List<long[]> forced) {

        Container storage(ServerLevel level) {
            return (Container) level.getBlockEntity(jobBlock);
        }

        void finish(ServerLevel level) {
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        }
    }

    private static World world(GameTestHelper helper, int skill) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        SettlerJob job = settler.getJobs().get(0);
        // The block entity keeps the level too and syncs it to the settlement: set both.
        ((com.gilfort.civmod.block.JobBlockEntity) level.getBlockEntity(job.jobBlock())).setJobLevel(BLOCK_LEVEL);
        SettlementManager.setLevel(SettlementData.get(level), job.jobBlock(), BLOCK_LEVEL, SettlementManager.Listener.NONE);
        settler.getSkills().set(ModJobs.LUMBERJACK.get(), new SettlerSkills.Skill(skill, 0));
        wear(settler);
        World world = new World(settler, job, job.jobBlock(), forced);
        world.storage(level).clearContent();
        return world;
    }

    private static void store(World world, ServerLevel level, ItemStack... stacks) {
        Container storage = world.storage(level);
        for (int i = 0; i < stacks.length; i++) {
            storage.setItem(i, stacks[i]);
        }
    }

    private static boolean has(Container container, Item item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static Item item(ClothSet set, ArmorType type) {
        return ModClothing.piece(set, type).get();
    }

    /** The settler takes a piece from its collect inventory by itself, clothing only. */
    private static void putOnFromCollect(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 30);
        ItemStack[] simple = set(ClothSet.SIMPLE, 0);
        store(world, level, simple[0], new ItemStack(Items.IRON_CHESTPLATE), simple[1], simple[2], simple[3], new ItemStack(Items.DIRT, 5));
        world.settler().getWork().checkNeedsNow(level, today(level));
        for (int i = 0; i < 4; i++) {
            helper.assertTrue(worn(world.settler(), ClothingRules.SLOTS.get(i)).getItem() == item(ClothSet.SIMPLE, TYPES.get(i)), "Put on: " + ClothingRules.SLOTS.get(i));
        }
        helper.assertTrue(has(world.storage(level), Items.IRON_CHESTPLATE) && has(world.storage(level), Items.DIRT), "Everything else stays in the collect inventory");
        helper.assertFalse(has(world.storage(level), item(ClothSet.SIMPLE, ArmorType.HELMET)), "The helmet left the collect inventory");
        helper.assertTrue(clothingRow(world.settler(), level, 2).met(), "Tier 2 is met now");
        world.finish(level);
        helper.succeed();
    }

    /**
     * A tier 3 settler: a higher rank replaces a simple piece and the old one goes into the collect inventory; the same rank does not
     * replace a healthy piece; a lower rank never replaces a higher one; the same rank replaces a worn-out piece.
     */
    private static void replacementRules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 45);
        wear(world.settler(), piece(ClothSet.SIMPLE, ArmorType.HELMET, 10), piece(ClothSet.SIMPLE, ArmorType.CHESTPLATE, 10),
                piece(ClothSet.MEDIUM, ArmorType.LEGGINGS, 90), piece(ClothSet.SIMPLE, ArmorType.BOOTS, 90));
        store(world, level, piece(ClothSet.MEDIUM, ArmorType.HELMET, 0), piece(ClothSet.SIMPLE, ArmorType.CHESTPLATE, 0),
                piece(ClothSet.SIMPLE, ArmorType.LEGGINGS, 0), piece(ClothSet.SIMPLE, ArmorType.BOOTS, 0));
        world.settler().getWork().checkNeedsNow(level, today(level));
        Settler settler = world.settler();
        helper.assertTrue(worn(settler, EquipmentSlot.HEAD).getItem() == item(ClothSet.MEDIUM, ArmorType.HELMET), "A higher rank replaces");
        helper.assertTrue(has(world.storage(level), item(ClothSet.SIMPLE, ArmorType.HELMET)), "The old helmet went back to the collect inventory");
        helper.assertTrue(worn(settler, EquipmentSlot.CHEST).getDamageValue() == 10, "The same rank does not replace a healthy piece");
        helper.assertTrue(worn(settler, EquipmentSlot.LEGS).getItem() == item(ClothSet.MEDIUM, ArmorType.LEGGINGS) && worn(settler, EquipmentSlot.LEGS).getDamageValue() == 90,
                "A lower rank never replaces a higher one, worn out or not");
        helper.assertTrue(worn(settler, EquipmentSlot.FEET).getDamageValue() == 0, "The same rank replaces a worn-out piece");
        helper.assertFalse(has(world.storage(level), item(ClothSet.MEDIUM, ArmorType.LEGGINGS)), "The leggings stayed on");
        world.finish(level);
        helper.succeed();
    }

    /** The collect inventory is searched once an hour: a fruitless search waits, the next day looks again. */
    private static void searchHourly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper, 30);
        long day = today(level);
        world.settler().getWork().checkNeedsNow(level, day);
        helper.assertTrue(worn(world.settler(), EquipmentSlot.HEAD).isEmpty(), "Nothing to put on");
        store(world, level, piece(ClothSet.SIMPLE, ArmorType.HELMET, 0));
        world.settler().getWork().checkNeedsNow(level, day);
        helper.assertTrue(worn(world.settler(), EquipmentSlot.HEAD).isEmpty(), "The next search waits an hour");
        world.settler().getWork().checkNeedsNow(level, day + 1);
        helper.assertTrue(!worn(world.settler(), EquipmentSlot.HEAD).isEmpty(), "A day later it looks again");
        world.finish(level);
        helper.succeed();
    }

    // ---------------------------------------------------------------- right-click

    private static ServerPlayer player(GameTestHelper helper) {
        return TestPlayers.add(helper, helper.absoluteVec(new Vec3(3.5, 1.0, 3.5)));
    }

    /** Clicks the settler; opening the window fails on the test player's connection, which is no error here. */
    private static void click(ServerPlayer player, Settler settler, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        try {
            player.interactOn(settler, InteractionHand.MAIN_HAND, settler.position());
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("advanced_open_screen")) {
                throw e;
            }
        }
    }

    private static boolean has(ServerPlayer player, Item item) {
        return player.getInventory().contains(stack -> stack.is(item));
    }

    /** A piece in the hand goes into its slot and the worn one comes back to the player; other armour opens the window. */
    private static void rightClickEquips(GameTestHelper helper) {
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 15);
        ServerPlayer player = player(helper);
        ItemStack simple = piece(ClothSet.SIMPLE, ArmorType.HELMET, 0);
        // A test player starts in creative mode, which keeps what it holds.
        player.getAbilities().instabuild = false;
        click(player, settler, simple);
        helper.assertTrue(worn(settler, EquipmentSlot.HEAD).getItem() == item(ClothSet.SIMPLE, ArmorType.HELMET), "The piece is on");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "It left the player's hand: " + player.getMainHandItem());
        helper.assertFalse(player.containerMenu instanceof com.gilfort.civmod.menu.SettlerMenu, "The click did not open the window");

        click(player, settler, piece(ClothSet.MEDIUM, ArmorType.HELMET, 0));
        helper.assertTrue(worn(settler, EquipmentSlot.HEAD).getItem() == item(ClothSet.MEDIUM, ArmorType.HELMET), "The new piece replaces the old one");
        helper.assertTrue(has(player, item(ClothSet.SIMPLE, ArmorType.HELMET)), "The old piece came back to the player");

        click(player, settler, new ItemStack(Items.IRON_HELMET));
        helper.assertTrue(worn(settler, EquipmentSlot.HEAD).getItem() == item(ClothSet.MEDIUM, ArmorType.HELMET), "Armour that is no clothing is not put on");
        TestPlayers.remove(player);
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Edible items still feed, clothing or not. */
    private static void rightClickFeeds(GameTestHelper helper) {
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 15);
        ServerPlayer player = player(helper);
        int before = settler.getFood().level();
        click(player, settler, new ItemStack(Items.BREAD));
        helper.assertTrue(settler.getFood().level() > before, "The bread fed it: " + settler.getFood().level());
        helper.assertTrue(ClothingRules.SLOTS.stream().allMatch(slot -> worn(settler, slot).isEmpty()), "Nothing was put on");
        TestPlayers.remove(player);
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    // ---------------------------------------------------------------- wear

    private static int damage(Settler settler, EquipmentSlot slot) {
        return worn(settler, slot).getDamageValue();
    }

    /** 2 % per day and piece, one step per day however often the check runs. */
    private static void wearPerDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 15);
        wear(settler, set(ClothSet.SIMPLE, 0));
        long day = today(level);
        settler.getWork().checkNeedsNow(level, day);
        helper.assertTrue(damage(settler, EquipmentSlot.HEAD) == 0, "The first check only marks the day");
        settler.getWork().checkNeedsNow(level, day + 1);
        settler.getWork().checkNeedsNow(level, day + 1);
        for (EquipmentSlot slot : ClothingRules.SLOTS) {
            helper.assertTrue(damage(settler, slot) == 2, "2 % of 100 after one day, once: " + slot + " " + damage(settler, slot));
        }
        settler.getWork().checkNeedsNow(level, day + 3);
        helper.assertTrue(damage(settler, EquipmentSlot.CHEST) == 6, "Two more days: " + damage(settler, EquipmentSlot.CHEST));
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** Catch-up applies the wear per simulated day. */
    private static void wearCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 15);
        wear(settler, set(ClothSet.SIMPLE, 0));
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), DAY) * DAY;
        long today = today(level);
        settler.getWork().checkNeedsNow(level, today - 4);
        settler.getWork().setCatchUp(dayStart - 3 * DAY, dayStart);
        CatchUp.runNow(level, List.of(settler));
        helper.assertTrue(damage(settler, EquipmentSlot.FEET) == 6, "Three simulated days, 2 each: " + damage(settler, EquipmentSlot.FEET));
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** A day on a mission costs 5 %: the days of the trip, from the day it leaves to the day it is back. */
    private static void wearMissionDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = MissionRunGameTests.adventurer(helper);
        settler.getWork().forceMissionOutcome(MissionRules.Outcome.SUCCESS);
        wear(settler, set(ClothSet.SIMPLE, 0));
        long leavesOn = today(level);
        settler.getWork().checkNeedsNow(level, leavesOn);
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || !settler.getWork().isAway()) {
                return;
            }
            done[0] = true;
            long returnDay = DayClock.day(settler.getWork().missionTrip().orElseThrow().returnTime());
            long missionDays = returnDay - leavesOn + 1;
            helper.assertTrue(settler.getWork().returnFromMission(level, settler.getWork().missionTrip().orElseThrow().returnTime()), "Back from the mission");
            settler.getWork().checkNeedsNow(level, returnDay + 1);
            helper.assertTrue(damage(settler, EquipmentSlot.CHEST) == 5 * missionDays,
                    missionDays + " mission days at 5 each: " + damage(settler, EquipmentSlot.CHEST));
            settler.getWork().checkNeedsNow(level, returnDay + 2);
            helper.assertTrue(damage(settler, EquipmentSlot.CHEST) == 5 * missionDays + 2, "An ordinary day after it: " + damage(settler, EquipmentSlot.CHEST));
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
            helper.succeed();
        });
    }

    /** Below 20 % the settler wants a replacement of the same or a higher rank, but the piece still counts for the need. */
    private static void wornOutStillCounts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 45);
        wear(settler, set(ClothSet.MEDIUM, 80));
        helper.assertTrue(settler.getClothing().wants(3).isEmpty(), "20 % left: nothing is wanted");
        wear(settler, set(ClothSet.MEDIUM, 81));
        helper.assertTrue(clothingRow(settler, level, 3).met() && clothingRow(settler, level, 4).met(), "Worn out pieces still meet tiers 3 and 4");
        helper.assertTrue(key(clothingRow(settler, level, 3).detail()).equals("civmod.need.clothing.worn"), "The row says worn: " + clothingRow(settler, level, 3).detail());
        helper.assertFalse(clothingRow(settler, level, 5).met(), "Medium cloth does not meet tier 5, worn out or not");
        List<Want> wants = settler.getClothing().wants(3);
        helper.assertTrue(wants.size() == 4 && wants.stream().allMatch(want -> want.minRank() == 2 && want.tag().equals(ClothSet.MEDIUM.tag())),
                "A replacement of the same rank is wanted for each: " + wants);

        wear(settler, set(ClothSet.HIGH, 81));
        helper.assertTrue(settler.getClothing().wants(3).stream().allMatch(want -> want.minRank() == 3), "A worn-out highest piece is replaced by a highest one");
        helper.assertTrue(settler.getClothing().pieces(3).stream().allMatch(piece -> piece.state() == State.WORN_OUT), "All four are worn out");
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** A piece that breaks is gone: the slot is empty and the need is unmet. */
    private static void brokenPieceIsGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 30);
        wear(settler, set(ClothSet.SIMPLE, 0));
        settler.getInventory().setItem(Settler.ARMOR_START + 3, piece(ClothSet.SIMPLE, ArmorType.BOOTS, 99));
        long day = today(level);
        settler.getWork().checkNeedsNow(level, day);
        helper.assertTrue(clothingRow(settler, level, 2).met(), "Met with a nearly broken boot");
        settler.getWork().checkNeedsNow(level, day + 1);
        helper.assertTrue(worn(settler, EquipmentSlot.FEET).isEmpty(), "The boots broke and are gone");
        helper.assertFalse(clothingRow(settler, level, 2).met(), "The need is unmet");
        helper.assertTrue(worn(settler, EquipmentSlot.HEAD).getDamageValue() == 2, "The other pieces are still worn");
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    /** The pieces and the day wear was counted to are saved: a settler loaded the next day wears once. */
    private static void saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = plain(helper, jobBlock, 15);
        wear(settler, set(ClothSet.SIMPLE, 0));
        long day = today(level);
        settler.getWork().checkNeedsNow(level, day);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        Settler loaded = com.gilfort.civmod.registry.ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        helper.assertTrue(worn(loaded, EquipmentSlot.HEAD).getItem() == item(ClothSet.SIMPLE, ArmorType.HELMET), "The pieces are saved");
        loaded.getWork().checkNeedsNow(level, day + 1);
        helper.assertTrue(damage(loaded, EquipmentSlot.HEAD) == 2, "One day of wear after loading: " + damage(loaded, EquipmentSlot.HEAD));

        CompoundTag old = tag.copy();
        old.remove("clothing");
        Settler fromOldSave = com.gilfort.civmod.registry.ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        fromOldSave.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), old));
        fromOldSave.getWork().checkNeedsNow(level, day + 1);
        helper.assertTrue(damage(fromOldSave, EquipmentSlot.HEAD) == 0, "A save without wear days starts counting: " + damage(fromOldSave, EquipmentSlot.HEAD));
        loaded.discard();
        fromOldSave.discard();
        NeedTierGameTests.end(helper, settler, jobBlock);
    }

    // ---------------------------------------------------------------- reputation and bubble

    /** Clothing counts for a settler of tier 2 and up, and not for one of tier 1. */
    private static void reputation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler tierTwo = plain(helper, jobBlock, 30);
        Settler tierOne = plain(helper, jobBlock, 15);
        NeedTally twoBare = tierTwo.getWork().needTally(level);
        NeedTally oneBare = tierOne.getWork().needTally(level);
        wear(tierTwo, set(ClothSet.SIMPLE, 0));
        wear(tierOne, set(ClothSet.SIMPLE, 0));
        helper.assertTrue(tierTwo.getWork().needTally(level).met() == twoBare.met() + 1 && tierTwo.getWork().needTally(level).total() == twoBare.total(),
                "Clothing is one more need met for tier 2: " + twoBare + " then " + tierTwo.getWork().needTally(level));
        helper.assertTrue(tierOne.getWork().needTally(level).equals(oneBare), "Tier 1 does not count clothing: " + oneBare + " then " + tierOne.getWork().needTally(level));
        tierTwo.discard();
        NeedTierGameTests.end(helper, tierOne, jobBlock);
    }

    /** The bubble of a missing piece shows that piece: the first one in the order head, chest, legs, feet. */
    private static void bubbleShowsMissingPiece(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = block(helper);
        Settler settler = comfortable(helper, jobBlock, 30);
        wear(settler, piece(ClothSet.SIMPLE, ArmorType.HELMET, 0));
        check(settler, level, today(level));
        helper.assertTrue(settler.getWork().needState().inGrace(), "On its grace day");
        settler.getWork().decideNow(level);
        Item expected = item(ClothSet.SIMPLE, ArmorType.CHESTPLATE);
        helper.assertTrue(settler.getWork().recentThoughts().contains(new ThoughtBubble.Show(settler.getId(), ThoughtBubble.NEED_UNMET, expected, true)),
                "The bubble shows the missing chestplate: " + settler.getWork().recentThoughts());
        NeedTierGameTests.end(helper, settler, jobBlock);
    }
}
