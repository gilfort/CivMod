package com.gilfort.civmod.gametest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.job.BlockCountScan;
import com.gilfort.civmod.job.JobDrops;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.VineBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the NPC drop pools of the farming Jobs (vault {@code Systems/Resource Collection Jobs.md}, decisions
 * 2026-10-29; story #137): CivMod's default entries and the entries of the test pack ({@code civmod_job_drops/test/}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobDropGameTests {

    static final int SAMPLES = 20000;
    private static final Identifier BUSH = Identifier.withDefaultNamespace("bush");
    private static final Identifier PITCHER_CROP = Identifier.withDefaultNamespace("pitcher_crop");
    private static final Identifier BIG_DRIPLEAF = Identifier.withDefaultNamespace("big_dripleaf");
    private static final Identifier SPORE_BLOSSOM = Identifier.withDefaultNamespace("spore_blossom");
    private static final Identifier HAY_BLOCK = Identifier.withDefaultNamespace("hay_block");
    private static final Identifier ARMADILLO = Identifier.withDefaultNamespace("armadillo");

    private JobDropGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "job_drops_plantation_vines", JobDropGameTests::plantationVines);
            register(helper, "job_drops_gatherer_bush_seeds", JobDropGameTests::gathererBushSeeds);
            register(helper, "job_drops_data_format", JobDropGameTests::dataFormat);
            register(helper, "job_drops_new_block", JobDropGameTests::newBlock);
            register(helper, "job_drops_add_keeps_loot", JobDropGameTests::addKeepsLoot);
            register(helper, "job_drops_entry_tool", JobDropGameTests::entryTool);
            register(helper, "job_drops_enchantments", JobDropGameTests::enchantments);
            register(helper, "job_drops_animal_entry", JobDropGameTests::animalEntry);
            register(helper, "job_drops_max_and_exclusion", JobDropGameTests::maxAndExclusion);
            register(helper, "job_drops_found_tab", JobDropGameTests::foundTab);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A Plantation Farmer with an axe delivers vines from vines (default entry: vine × 1, 100 %); the vines stay. */
    private static void plantationVines(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos relative = GathererGameTests.gathererBlock(helper);
        BlockPos jobBlock = helper.absolutePos(relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        TestAssignments.setStationType(SettlementData.get(level), jobBlock, 1, Items.BAMBOO);
        for (int x = 4; x < 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 6), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 6), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 5), Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true));
        }
        Settler settler = worker(helper, jobBlock, "Planter", ModJobs.PLANTATION_FARMER.get());
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_AXE));
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).counts().getOrDefault(Items.VINE, 0) > 0,
                    "No vines yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(helper.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.VINE), "The vines stay");
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** Bare-handed, a Gatherer gets each of melon, pumpkin and beetroot seeds from about 5 % of its bush rolls. */
    private static void gathererBushSeeds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceCollectionProfile profile = ModJobs.GATHERER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        Map<Item, Integer> drops = roll(level, profile, helper.absolutePos(BlockPos.ZERO), new BlockCountScan.Result(Map.of(BUSH, 5)),
                ItemStack.EMPTY, Set.of());
        for (Item seeds : List.of(Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.BEETROOT_SEEDS)) {
            double rate = drops.getOrDefault(seeds, 0) / (double) SAMPLES;
            helper.assertTrue(Math.abs(rate - 0.05) < 0.01, "About 5 % " + seeds + ": " + drops);
        }
        helper.succeed();
    }

    /**
     * The test pack's files: an entry for an unknown item skips its file, an entry for the Lumberjack is ignored for it
     * (kept for the Gatherer), {@code "job": []} turns an entry off.
     */
    private static void dataFormat(GameTestHelper helper) {
        Map<Identifier, JobDrops.Entry> loaded = JobDrops.all().stream().collect(Collectors.toMap(JobDrops.Drop::id, JobDrops.Drop::entry));
        helper.assertFalse(loaded.containsKey(CivMod.id("test/unknown_item")), "An unknown item skips the file");
        helper.assertValueEqual(loaded.get(CivMod.id("test/lumberjack_ignored")).jobs(), List.of(ModJobs.GATHERER.get().id()),
                "The Lumberjack is ignored, the Gatherer kept");
        helper.assertValueEqual(loaded.get(CivMod.id("test/disabled")).jobs(), List.of(), "An entry without Jobs is off");
        helper.assertTrue(JobDrops.pool(ModJobs.LUMBERJACK.get().id()).drops().isEmpty(), "The Lumberjack has no drop pool");
        helper.assertTrue(JobDrops.pool(ModJobs.GATHERER.get().id()).drops().stream().noneMatch(drop -> drop.id().equals(CivMod.id("test/disabled"))),
                "The turned-off entry is in no pool");
        JobDrops.Entry range = JobDrops.parse(JsonParser.parseString(
                "{\"job\":\"civmod:gatherer\",\"from\":\"#minecraft:flowers\",\"result\":{\"id\":\"minecraft:stick\",\"count\":{\"min\":1,\"max\":3}},\"chance\":0.5,\"mode\":\"replace\",\"tool\":\"#c:tools/shear\"}"))
                .getOrThrow();
        helper.assertValueEqual(range.result().count(), new JobDrops.Count(1, 3), "Count range");
        helper.assertTrue(range.mode() == JobDrops.Mode.REPLACE && range.tool().isPresent() && range.chance() == 0.5, "Mode, tool and chance: " + range);
        helper.assertTrue(JobDrops.parse(JsonParser.parseString("{\"job\":\"civmod:gatherer\",\"from\":\"minecraft:bush\",\"result\":\"minecraft:stick\",\"chance\":2}"))
                .isError(), "A chance above 1 is invalid");
        helper.succeed();
    }

    /**
     * A datapack entry for a new block (ripe pitcher crops): the Gatherer's scan counts the ripe ones only (Found tab,
     * farm curve) and its roll gives the entry's result in place of the crop's loot.
     */
    private static void newBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GathererGameTests.gathererBlock(helper);
        for (int x = 4; x < 8; x++) {
            helper.setBlock(new BlockPos(x, 0, 5), Blocks.FARMLAND);
            helper.setBlock(new BlockPos(x, 1, 5), Blocks.PITCHER_CROP.defaultBlockState().setValue(PitcherCropBlock.AGE, x < 7 ? 4 : 1));
        }
        ResourceCollectionProfile profile = ModJobs.GATHERER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        BlockPos center = helper.absolutePos(new BlockPos(5, 1, 5));
        ScanResult scan = profile.createScan(center, 4).runAll(level);
        helper.assertValueEqual(scan.foundBlocks(), List.of(PITCHER_CROP), "The ripe pitcher crops are found");
        helper.assertValueEqual(profile.farmCount(scan), 3, "Three ripe pitcher crops count for the farm curve, the unripe one not");
        Map<Item, Integer> drops = roll(level, profile, center, scan, ItemStack.EMPTY, Set.of());
        helper.assertValueEqual(drops, Map.of(Items.APPLE, 2 * SAMPLES), "Two apples per roll, no pitcher loot (replace)");
        helper.succeed();
    }

    /** {@code add} keeps the block's own loot (big dripleaf) and adds the entry's stick; {@code replace} removes it (above). */
    private static void addKeepsLoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceCollectionProfile profile = ModJobs.GATHERER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        Map<Item, Integer> drops = roll(level, profile, helper.absolutePos(BlockPos.ZERO), new BlockCountScan.Result(Map.of(BIG_DRIPLEAF, 3)),
                ItemStack.EMPTY, Set.of());
        helper.assertValueEqual(drops, Map.of(Items.BIG_DRIPLEAF, SAMPLES, Items.STICK, SAMPLES), "Own loot and the stick each roll");
        helper.succeed();
    }

    /**
     * An entry with its own tool (shears for spore blossoms) uses the shears of the collect inventory and wears them;
     * without shears the entry is skipped and the blossoms give nothing.
     */
    private static void entryTool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos relative = GathererGameTests.gathererBlock(helper);
        BlockPos jobBlock = helper.absolutePos(relative);
        // Blossoms at head height under a stone strip: the settler stands beside them.
        for (int x = 4; x < 8; x++) {
            helper.setBlock(new BlockPos(x, 2, 5), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 5), Blocks.SPORE_BLOSSOM);
        }
        ResourceCollectionProfile profile = ModJobs.GATHERER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        helper.assertValueEqual(roll(level, profile, jobBlock, new BlockCountScan.Result(Map.of(SPORE_BLOSSOM, 4)), ItemStack.EMPTY, Set.of()), Map.of(),
                "Without shears the blossoms give nothing");
        Container collect = (Container) level.getBlockEntity(jobBlock);
        collect.setItem(0, new ItemStack(Items.SHEARS));
        Settler settler = worker(helper, jobBlock, "Picker", ModJobs.GATHERER.get());
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getWork().buffer(jobBlock).counts().getOrDefault(Items.PINK_PETALS, 0) > 0,
                    "No petals yet (status " + settler.getWork().status(jobBlock) + ")");
            helper.assertTrue(settler.getWork().buffer(jobBlock).counts().getOrDefault(Items.SPORE_BLOSSOM, 0) == 0, "No blossom loot (replace)");
            ItemStack shears = collect.getItem(0);
            helper.assertTrue(shears.is(Items.SHEARS) && shears.getDamageValue() > 0, "The shears in the collect inventory wore: " + shears);
            for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
                helper.assertFalse(settler.getInventory().getItem(i).is(Items.SHEARS), "The shears never go onto the belt");
            }
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /**
     * Enchantments of the tool used: Fortune III adds 0–1 wheat per level to the hay entry (1 → 2.5 on average); Silk
     * Touch gives the hay bale itself, once per output, instead of the wheat.
     */
    private static void enchantments(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceCollectionProfile profile = ModJobs.PLANTATION_FARMER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        BlockPos poi = helper.absolutePos(BlockPos.ZERO);
        BlockCountScan.Result hay = new BlockCountScan.Result(Map.of(HAY_BLOCK, 4));
        Map<Item, Integer> plain = roll(level, profile, poi, hay, new ItemStack(Items.STONE_AXE), Set.of());
        helper.assertValueEqual(plain, Map.of(Items.WHEAT, SAMPLES), "One wheat per roll with a plain axe, no hay bale loot (replace)");
        ItemStack fortune = new ItemStack(Items.IRON_AXE);
        fortune.enchant(enchantment(level, Enchantments.FORTUNE), 3);
        double perRoll = roll(level, profile, poi, hay, fortune, Set.of()).getOrDefault(Items.WHEAT, 0) / (double) SAMPLES;
        helper.assertTrue(Math.abs(perRoll - 2.5) < 0.1, "Fortune III: about 2.5 wheat per roll: " + perRoll);
        ItemStack silk = new ItemStack(Items.IRON_AXE);
        silk.enchant(enchantment(level, Enchantments.SILK_TOUCH), 1);
        helper.assertValueEqual(roll(level, profile, poi, hay, silk, Set.of()), Map.of(Items.HAY_BLOCK, SAMPLES), "Silk Touch: the hay bale, once per roll");
        helper.succeed();
    }

    private static Holder<Enchantment> enchantment(ServerLevel level, ResourceKey<Enchantment> key) {
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    /**
     * An Animal Farmer entity entry: armadillos (not farm animals) are counted around the hay bale and give scutes in
     * place of their loot.
     */
    private static void animalEntry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
            }
        }
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.HAY_BLOCK);
        List<Armadillo> armadillos = List.of(helper.spawn(EntityTypes.ARMADILLO, new BlockPos(2, 1, 2)), helper.spawn(EntityTypes.ARMADILLO, new BlockPos(6, 1, 6)));
        armadillos.forEach(armadillo -> armadillo.setNoAi(true));
        ResourceCollectionProfile profile = ModJobs.ANIMAL_FARMER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        BlockPos hay = helper.absolutePos(new BlockPos(4, 1, 4));
        ScanResult scan = profile.createScan(hay, 5).runAll(level);
        helper.assertValueEqual(scan.foundBlocks(), List.of(ARMADILLO), "Armadillos are counted");
        helper.assertValueEqual(profile.farmCount(scan), 2, "Both count for the farm curve");
        Map<Item, Integer> drops = new HashMap<>();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        for (int i = 0; i < 200; i++) {
            profile.produce(new ResourceCollectionProfile.Context(level, settler, scan, hay, new ItemStack(Items.STONE_AXE), Set.of(), 1, Set.of(),
                    type -> profile.droppedItems(level, hay, type, ItemStack.EMPTY))).ifPresent(produced -> produced.stacks()
                    .forEach(stack -> drops.merge(stack.getItem(), stack.getCount(), Integer::sum)));
        }
        settler.discard();
        armadillos.forEach(Armadillo::discard);
        helper.assertValueEqual(drops, Map.of(Items.ARMADILLO_SCUTE, 200), "A scute per roll, no armadillo loot");
        helper.succeed();
    }

    /**
     * Max per result item: melon seeds at Max leave the bush's roll while the other seeds go on; with all three at Max
     * the bush is at its Max and the rolls are empty; an excluded bush gives nothing. A type whose loot is replaced and
     * whose only entry misses its tool (spore blossoms without shears) leaves the roll.
     */
    private static void maxAndExclusion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceCollectionProfile profile = ModJobs.GATHERER.get().profile(ResourceCollectionProfile.class).orElseThrow();
        BlockPos poi = helper.absolutePos(BlockPos.ZERO);
        BlockCountScan.Result bushes = new BlockCountScan.Result(Map.of(BUSH, 5));
        Map<Item, Integer> drops = roll(level, profile, poi, bushes, ItemStack.EMPTY, Set.of(Items.MELON_SEEDS));
        helper.assertTrue(!drops.containsKey(Items.MELON_SEEDS) && drops.getOrDefault(Items.PUMPKIN_SEEDS, 0) > 0, "Melon seeds at Max skipped: " + drops);
        Set<Item> allSeeds = Set.of(Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.BEETROOT_SEEDS);
        helper.assertTrue(ResourceCollectionProfile.atMax(profile.droppedItems(level, poi, BUSH, ItemStack.EMPTY), allSeeds),
                "The bush is at its Max when all its seeds are");
        helper.assertValueEqual(roll(level, profile, poi, bushes, ItemStack.EMPTY, allSeeds), Map.of(), "Nothing at Max is given");
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Optional<ResourceCollectionProfile.Produced> excluded = profile.produce(new ResourceCollectionProfile.Context(level, settler, bushes, poi,
                ItemStack.EMPTY, Set.of(BUSH), 1, Set.of(), type -> profile.droppedItems(level, poi, type, ItemStack.EMPTY)));
        settler.discard();
        helper.assertTrue(excluded.isPresent() && excluded.get().stacks().isEmpty(), "An excluded bush gives nothing: " + excluded);
        BlockCountScan.Result mixed = new BlockCountScan.Result(Map.of(SPORE_BLOSSOM, 50, BIG_DRIPLEAF, 1));
        helper.assertValueEqual(roll(level, profile, poi, mixed, ItemStack.EMPTY, Set.of()), Map.of(Items.BIG_DRIPLEAF, SAMPLES, Items.STICK, SAMPLES),
                "Spore blossoms without shears leave the roll; every roll is the dripleaf's");
        helper.succeed();
    }

    /**
     * The Found tab lists a new block of an entry, and a type's tooltip names its extra drops ("Also: Melon Seeds 5 %");
     * an extra drop at Max or missing its tool is greyed out, the latter with "Needs: Shears".
     */
    private static void foundTab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        BlockPos relative = GathererGameTests.gathererBlock(helper);
        BlockPos jobBlock = helper.absolutePos(relative);
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.BUSH);
        helper.setBlock(new BlockPos(6, 3, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(6, 2, 5), Blocks.SPORE_BLOSSOM);
        Settler settler = worker(helper, jobBlock, "Picker", ModJobs.GATHERER.get());
        helper.succeedWhen(() -> {
            FoundTabData tab = FoundTabData.of(level, jobBlock);
            FoundTabData.Entry bush = tab.entry(BUSH).orElseThrow(() -> helper.assertionException("No bush entry yet: " + tab));
            FoundTabData.Entry blossom = tab.entry(SPORE_BLOSSOM).orElseThrow(() -> helper.assertionException("No blossom entry: " + tab));
            List<String> bushLines = bush.dropLines().stream().map(Component::getString).toList();
            helper.assertTrue(bushLines.contains("Also: Melon Seeds 5 %") && bushLines.size() == 3, "Bush lines: " + bushLines);
            List<String> blossomLines = blossom.dropLines().stream().map(Component::getString).toList();
            helper.assertTrue(blossomLines.size() == 1 && blossomLines.get(0).contains("Pink Petals") && blossomLines.get(0).contains("Needs: Shears")
                    && TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY).equals(blossom.dropLines().get(0).getStyle().getColor()),
                    "Blossom line greyed with its tool: " + blossomLines);
            settler.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }

    /** A settler working the JobBlock in a Job. */
    static Settler worker(GameTestHelper helper, BlockPos jobBlock, String name, com.gilfort.civmod.api.Job job) {
        SettlementData data = SettlementData.get(helper.getLevel());
        Settler settler = GathererGameTests.settler(helper, jobBlock, new BlockPos(3, 1, 3), name);
        TestAssignments.assign(data, jobBlock, settler.getUUID(), name, data.settlementAt(jobBlock).orElseThrow().id(), List.of(), job);
        settler.addJob(new SettlerJob(job, jobBlock));
        return settler;
    }

    /** Rolls {@value #SAMPLES} times with a tool; counts the items produced. */
    static Map<Item, Integer> roll(ServerLevel level, ResourceCollectionProfile profile, BlockPos poi, ScanResult scan, ItemStack tool, Set<Item> atMax) {
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            Optional<ResourceCollectionProfile.Produced> produced = profile.produce(new ResourceCollectionProfile.Context(level, settler, scan, poi,
                    tool, Set.of(), 1, atMax, type -> profile.droppedItems(level, poi, type, tool)));
            produced.ifPresent(found -> found.stacks().forEach(stack -> counts.merge(stack.getItem(), stack.getCount(), Integer::sum)));
        }
        settler.discard();
        return counts;
    }
}
