package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.food.FoodVariety;
import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.NeedsReport;
import com.gilfort.civmod.job.SettlerWork.RestPlace;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
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
 * GameTests for the tier 2 needs (vault {@code Systems/Needs.md}): an own real bed and food variety.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class NeedTier2GameTests {

    private static final BlockPos CENTER = new BlockPos(8, 1, 8);
    private static final int DAYS = 3;
    private static final long DAY = 24000L;

    private NeedTier2GameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "variety_window", NeedTier2GameTests::window);
            register(helper, "variety_covers_last_items", NeedTier2GameTests::coversLastItems);
            register(helper, "variety_bounded", NeedTier2GameTests::bounded);
            register(helper, "variety_choice", NeedTier2GameTests::choice);
            register(helper, "variety_saved", NeedTier2GameTests::saved);
            register(helper, "variety_old_save", NeedTier2GameTests::oldSave);
            register(helper, "variety_fed_row", NeedTier2GameTests::fedRow);
            register(helper, "variety_catch_up", NeedTier2GameTests::catchUp);
            register(helper, "variety_clerk_prefers_new_food", NeedTier2GameTests::clerkPrefersNewFood);
            register(helper, "bed_need_straw", NeedTier2GameTests::strawBedFails);
            register(helper, "bed_need_real", NeedTier2GameTests::realBedPasses);
            register(helper, "bed_need_far_bed", NeedTier2GameTests::farBedFails);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static Identifier id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static FoodVariety eaten(long day, Item... items) {
        FoodVariety variety = new FoodVariety();
        for (Item item : items) {
            variety.record(id(item), day);
        }
        return variety;
    }

    /** Three distinct foods of the last three days are enough, repeats do not count, older days fall out. */
    private static void window(GameTestHelper helper) {
        helper.assertTrue(eaten(10L, Items.BREAD, Items.CARROT, Items.APPLE).distinct(10L, DAYS) == 3, "3 distinct foods");
        helper.assertFalse(eaten(10L, Items.BREAD, Items.CARROT, Items.APPLE).isShort(10L, DAYS), "3 distinct foods are enough");
        helper.assertTrue(eaten(10L, Items.BREAD, Items.BREAD, Items.CARROT).isShort(10L, DAYS), "A repeat is no new food");

        FoodVariety days = eaten(8L, Items.BREAD);
        days.record(id(Items.CARROT), 9L);
        days.record(id(Items.APPLE), 10L);
        helper.assertFalse(days.isShort(10L, DAYS), "Days 8, 9 and 10 are the last three days");

        FoodVariety older = eaten(5L, Items.BREAD);
        for (int i = 0; i < 6; i++) {
            older.record(id(Items.CARROT), 9L);
            older.record(id(Items.APPLE), 10L);
        }
        helper.assertTrue(older.window(10L, DAYS).equals(Set.of(id(Items.CARROT), id(Items.APPLE))),
                "Older than three days and not among the last 6 items: " + older.window(10L, DAYS));
        helper.succeed();
    }

    /** A light eater: 2 large meals in 3 days, the window goes back to the last 6 items, but not further. */
    private static void coversLastItems(GameTestHelper helper) {
        FoodVariety light = eaten(1L, Items.BREAD);
        light.record(id(Items.CARROT), 3L);
        light.record(id(Items.APPLE), 9L);
        light.record(id(Items.COOKED_BEEF), 10L);
        helper.assertTrue(light.distinct(10L, DAYS) == 4, "All of the last 6 items count: " + light.distinct(10L, DAYS));
        helper.assertFalse(light.isShort(10L, DAYS), "Not short with 2 large meals in 3 days");

        FoodVariety seventh = eaten(1L, Items.BREAD);
        for (int i = 0; i < 3; i++) {
            seventh.record(id(Items.CARROT), 2L);
            seventh.record(id(Items.APPLE), 2L);
        }
        helper.assertTrue(seventh.window(10L, DAYS).equals(Set.of(id(Items.CARROT), id(Items.APPLE))),
                "The 7th last item is out: " + seventh.window(10L, DAYS));

        FoodVariety many = new FoodVariety();
        List<Item> foods = List.of(Items.BREAD, Items.CARROT, Items.APPLE, Items.COOKED_BEEF, Items.COOKIE, Items.BAKED_POTATO, Items.MELON_SLICE);
        foods.forEach(food -> many.record(id(food), 10L));
        helper.assertTrue(many.distinct(10L, DAYS) == 7, "Everything inside the days counts, more than 6 too: " + many.distinct(10L, DAYS));
        helper.succeed();
    }

    /** The list keeps the newest meals only. */
    private static void bounded(GameTestHelper helper) {
        FoodVariety variety = eaten(1L, Items.BREAD);
        for (int i = 0; i < 3 * FoodVariety.MAX_MEALS; i++) {
            variety.record(id(Items.CARROT), 2L);
        }
        helper.assertTrue(variety.meals().size() == FoodVariety.MAX_MEALS, "Bounded: " + variety.meals().size());
        helper.assertTrue(variety.meals().stream().noneMatch(meal -> meal.item().equals(id(Items.BREAD))), "The oldest went first");
        helper.succeed();
    }

    /** Short of variety: a food outside the window, even a small one; otherwise the one closest to 20. */
    private static void choice(GameTestHelper helper) {
        List<ItemStack> stock = List.of(new ItemStack(Items.COOKED_BEEF), new ItemStack(Items.CARROT), new ItemStack(Items.STONE));
        helper.assertTrue(eaten(10L, Items.COOKED_BEEF).choose(stock, 12, 10L, DAYS).equals(Optional.of(1)), "The carrot is new, the beef fits better");
        helper.assertTrue(eaten(10L, Items.COOKED_BEEF, Items.CARROT).choose(stock, 12, 10L, DAYS).equals(Optional.of(0)),
                "Nothing new: the existing choice");
        helper.assertTrue(eaten(10L, Items.BREAD, Items.APPLE, Items.COOKIE).choose(stock, 12, 10L, DAYS).equals(Optional.of(0)),
                "Enough variety: the existing choice");
        helper.assertTrue(eaten(10L, Items.BREAD).choose(List.of(new ItemStack(Items.COOKED_BEEF)), 15, 10L, DAYS).isEmpty(),
                "A new food that would overeat stays where it is");
        helper.succeed();
    }

    private static CompoundTag save(Settler settler, ServerLevel level) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        return output.buildResult();
    }

    private static Settler load(CompoundTag tag, ServerLevel level) {
        Settler loaded = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        return loaded;
    }

    /** The meals are saved with the settler, days and order included. */
    private static void saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.getFood().variety().record(id(Items.BREAD), 7L);
        settler.getFood().variety().record(id(Items.CARROT), 8L);
        Settler loaded = load(save(settler, level), level);
        helper.assertTrue(loaded.getFood().variety().meals().equals(settler.getFood().variety().meals()), "Saved: " + loaded.getFood().variety().meals());
        helper.assertTrue(loaded.getFood().variety().meals().size() == 2, "Both meals: " + loaded.getFood().variety().meals());
        settler.discard();
        loaded.discard();
        helper.succeed();
    }

    /** A save from before food variety loads with no meals and keeps its food level. */
    private static void oldSave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.getFood().setLevel(7);
        settler.getFood().variety().record(id(Items.BREAD), 7L);
        CompoundTag tag = save(settler, level);
        CompoundTag food = tag.getCompoundOrEmpty("food");
        helper.assertTrue(food.contains("meals"), "Saved under this key: " + food);
        food.remove("meals");
        Settler loaded = load(tag, level);
        helper.assertTrue(loaded.getFood().variety().meals().isEmpty(), "No meals: " + loaded.getFood().variety().meals());
        helper.assertTrue(loaded.getFood().level() == 7, "The rest still loads: " + loaded.getFood().level());
        settler.discard();
        loaded.discard();
        helper.succeed();
    }

    /** Registers a JobBlock without a block in the world, in a settlement of its own. */
    private static BlockPos jobBlock(GameTestHelper helper, BlockPos relative, int blockLevel) {
        BlockPos pos = helper.absolutePos(relative);
        SettlementManager.place(SettlementData.get(helper.getLevel()), pos, ModJobs.LUMBERJACK.get(), blockLevel, 0L, RandomSource.create(0L),
                SettlementManager.Listener.NONE);
        return pos;
    }

    private static Settlement settlement(GameTestHelper helper, BlockPos jobBlock) {
        return SettlementData.get(helper.getLevel()).settlementAt(jobBlock).orElseThrow();
    }

    /** A settler that does not move, with a job at the JobBlock. */
    private static Settler settler(GameTestHelper helper, BlockPos jobBlock) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        settler.addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
        return settler;
    }

    private static void end(GameTestHelper helper, BlockPos jobBlock, Settler settler) {
        settler.discard();
        SettlementManager.remove(SettlementData.get(helper.getLevel()), jobBlock, SettlementManager.Listener.NONE);
        helper.succeed();
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents contents ? contents.getKey() : text.getString();
    }

    private static NeedsReport.Row row(Settler settler, String need) {
        return SettlerInfo.of(settler).needs().rows(2).stream().filter(row -> key(row.name()).equals(need)).findFirst().orElseThrow();
    }

    /** Eating through a player's hand counts; the row shows the count of the window with the foods as icons. */
    private static void fedRow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER, 1);
        Settler settler = settler(helper, jobBlock);
        settler.getFood().setLevel(5);
        settler.getWork().feed(level, new ItemStack(Items.BREAD));
        settler.getWork().feed(level, new ItemStack(Items.CARROT));

        NeedsReport.Row two = row(settler, "civmod.need.food_variety");
        helper.assertFalse(two.met(), "2 of 3 foods");
        helper.assertTrue(key(two.detail()).equals("civmod.need.food_variety.detail"), "Detail: " + two.detail());
        Object[] args = ((TranslatableContents) two.detail().getContents()).getArgs();
        helper.assertTrue(List.of(args).equals(List.of(2, 3, 3)), "2 / 3 foods in 3 days: " + List.of(args));
        helper.assertTrue(two.icons().stream().map(ItemStack::getItem).toList().equals(List.of(Items.BREAD, Items.CARROT)), "Icons: " + two.icons());

        settler.getWork().feed(level, new ItemStack(Items.APPLE));
        helper.assertTrue(row(settler, "civmod.need.food_variety").met(), "3 foods");
        end(helper, jobBlock, settler);
    }

    /** Meals of the missed days are recorded with their simulated day. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER, 1);
        Settler settler = settler(helper, jobBlock);
        settler.getFood().setSaturation(0.0F);
        settler.getFood().setLevel(4);
        settler.getFood().setAppetite(1.0F);
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 4));
        settler.getInventory().setItem(Settler.POCKETS_START + 1, new ItemStack(Items.CARROT, 4));
        long dayStart = Math.floorDiv(level.getOverworldClockTime(), DAY) * DAY;
        long today = DayClock.day(level.getOverworldClockTime());
        settler.getWork().setCatchUp(dayStart - 2 * DAY, dayStart);
        CatchUp.runNow(level, List.of(settler));
        List<FoodVariety.Meal> meals = settler.getFood().variety().meals();
        helper.assertFalse(meals.isEmpty(), "Simulated meals are recorded");
        helper.assertTrue(meals.stream().allMatch(meal -> meal.day() < today && meal.day() >= today - 3), "On the simulated days: " + meals + ", today " + today);
        end(helper, jobBlock, settler);
    }

    /** At the Clerk a settler short of variety eats the new foods first, the carrot too, which the old choice leaves. */
    private static void clerkPrefersNewFood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos clerkPos = helper.absolutePos(new BlockPos(6, 1, 6));
        helper.setBlock(new BlockPos(6, 1, 6), ModBlocks.CLERK_BLOCK.get());
        Container clerk = (Container) level.getBlockEntity(clerkPos);
        clerk.setItem(0, new ItemStack(Items.COOKED_BEEF, 16));
        clerk.setItem(1, new ItemStack(Items.BREAD, 4));
        clerk.setItem(2, new ItemStack(Items.CARROT, 4));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getFood().variety().record(id(Items.COOKED_BEEF), DayClock.day(level.getOverworldClockTime()));
        settler.getFood().setLevel(5);
        settler.getFood().setSaturation(0.0F);
        helper.succeedWhen(() -> {
            List<Identifier> eaten = settler.getFood().variety().meals().stream().map(FoodVariety.Meal::item).toList();
            helper.assertTrue(eaten.size() >= 3, "Not eaten yet: " + eaten + " (" + settler.statusText().getString() + ")");
            helper.assertTrue(eaten.subList(1, 3).equals(List.of(id(Items.BREAD), id(Items.CARROT))), "New foods first: " + eaten);
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** Places a bed (both halves) with its head at the given relative position, facing north. */
    private static BlockPos bed(GameTestHelper helper, BlockPos relativeHead) {
        BlockPos head = helper.absolutePos(relativeHead);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        int flags = Block.UPDATE_ALL;
        helper.getLevel().setBlock(head.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        helper.getLevel().setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
        return head;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 2 * CENTER.getX(); x++) {
            for (int z = 0; z <= 2 * CENTER.getZ(); z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static RestPlace goToBed(Settler settler) {
        settler.getWork().goToBedNow((ServerLevel) settler.level());
        return settler.getWork().restPlace().orElseThrow();
    }

    /** Sleeping at the camp on straw does not meet the need. */
    private static void strawBedFails(GameTestHelper helper) {
        floor(helper);
        BlockPos jobBlock = jobBlock(helper, CENTER, 1);
        helper.setBlock(CENTER.east(4), Blocks.CAMPFIRE);
        Settler settler = settler(helper, jobBlock);
        RestPlace place = goToBed(settler);
        helper.assertTrue(place.straw() != null, "On a straw bed: " + place);
        NeedsReport.Row row = row(settler, "civmod.need.bed");
        helper.assertFalse(row.met(), "Straw does not count");
        helper.assertTrue(key(row.detail()).equals("civmod.need.bed.straw"), "Detail: " + row.detail());
        end(helper, jobBlock, settler);
    }

    /** An assigned real bed near a hearth meets the need. */
    private static void realBedPasses(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER, 1);
        helper.setBlock(CENTER.east(4), Blocks.CAMPFIRE);
        BlockPos bed = bed(helper, CENTER.west(2));
        Settler settler = settler(helper, jobBlock);
        SettlerBeds.assign(level, SettlementData.get(level), Optional.of(settlement(helper, jobBlock)), settler.getUUID(), bed);
        NeedsReport.Row row = row(settler, "civmod.need.bed");
        helper.assertTrue(row.met(), "Own real bed");
        helper.assertTrue(key(row.detail()).equals("civmod.need.bed.own"), "Detail: " + row.detail());
        end(helper, jobBlock, settler);
    }

    /** A far bed is kept, but the settler sleeps on straw at the camp: the need fails. */
    private static void farBedFails(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos jobBlock = jobBlock(helper, CENTER, 4);
        helper.setBlock(CENTER.east(4), Blocks.CAMPFIRE);
        BlockPos far = bed(helper, CENTER.west(30));
        Settler settler = settler(helper, jobBlock);
        SettlerBeds.assign(level, SettlementData.get(level), Optional.of(settlement(helper, jobBlock)), settler.getUUID(), far);
        helper.assertTrue(settlement(helper, jobBlock).bedOf(settler.getUUID()).isPresent(), "It keeps its bed");
        helper.assertFalse(row(settler, "civmod.need.bed").met(), "Sleeps at the camp");
        end(helper, jobBlock, settler);
    }
}
