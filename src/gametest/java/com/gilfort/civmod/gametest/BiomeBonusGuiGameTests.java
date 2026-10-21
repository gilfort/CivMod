package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.item.ScrollOfAssignmentItem;
import com.gilfort.civmod.menu.BiomeBonusText;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for showing biome bonuses (#106, vault {@code Systems/JobBlock.md}): the "?" overview per Job, the action bar
 * text of a new work point, the JobBlock window's line and the Found tab's stars. Test files:
 * {@code civmod_biome_bonuses/test_gui_*.json} (Miner and Lumberjack in the desert).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class BiomeBonusGuiGameTests {

    private BiomeBonusGuiGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "biome_bonus_gui_overview", BiomeBonusGuiGameTests::overview);
            register(helper, "biome_bonus_gui_action_bar", BiomeBonusGuiGameTests::actionBar);
            register(helper, "biome_bonus_gui_info_line", BiomeBonusGuiGameTests::infoLine);
            register(helper, "biome_bonus_gui_found_stars", BiomeBonusGuiGameTests::foundStars);
        });
    }

    /**
     * A Miner working in the desert: the Found tab marks iron ore with two stars (an ore: rare chance; raw iron: double
     * drop of two files), stone with one (it drops cobblestone) and granite with none; each tooltip line names an
     * effect and its file. The JobBlock window shows today's biome with all effects (raw iron's chances added).
     */
    private static void foundStars(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(16, 0, 16));
        fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(16, 8, 16), Biomes.DESERT);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        BlockPos jobBlockRelative = new BlockPos(1, 1, 1);
        helper.setBlock(jobBlockRelative, ModBlocks.MINER_BLOCK.get());
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.GRANITE);
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.IRON_ORE);
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(3, 1, 3), data, settlement);
        settler.setNoAi(false);
        settler.setCustomName(Component.literal("Digger"));
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_PICKAXE));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(5, 1, 5)), true, false);
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Digger", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), jobBlock));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(FoundTabData.of(level, jobBlock).scanned()
                        && CivModApi.biomeBonus(settler, jobBlock).biome().isPresent(), "No scan or no work place yet"))
                .thenExecute(() -> {
                    FoundTabData tab = FoundTabData.of(level, jobBlock);
                    FoundTabData.Entry iron = tab.entry(Identifier.withDefaultNamespace("iron_ore")).orElseThrow();
                    helper.assertTrue(iron.rareBonus() && iron.doubleBonus(), "Iron ore: two stars: " + iron);
                    helper.assertTrue(strings(iron.bonusLines()).equals(List.of("Ore chance ×1.5 (biome bonus civmod:test_gui_miner_a)",
                            "Raw Iron drops +50 % (biome bonus civmod:test_gui_miner_a)", "Raw Iron drops +25 % (biome bonus civmod:test_gui_miner_b)")),
                            "Iron ore tooltip: " + strings(iron.bonusLines()));
                    FoundTabData.Entry stone = tab.entry(Identifier.withDefaultNamespace("stone")).orElseThrow();
                    helper.assertTrue(!stone.rareBonus() && stone.doubleBonus(), "Stone: one star: " + stone);
                    helper.assertTrue(strings(stone.bonusLines()).equals(List.of("Cobblestone drops +25 % (biome bonus civmod:test_gui_miner_a)")),
                            "Stone tooltip: " + strings(stone.bonusLines()));
                    FoundTabData.Entry granite = tab.entry(Identifier.withDefaultNamespace("granite")).orElseThrow();
                    helper.assertTrue(!granite.rareBonus() && !granite.doubleBonus() && granite.bonusLines().isEmpty(), "Granite: no star: " + granite);
                    Optional<String> line = today(JobBlockInfo.of(level, jobBlock, ModJobs.MINER.get(), 1));
                    helper.assertTrue(line.equals(Optional.of("Desert · Ore chance ×1.5 · Raw Iron drops +75 % · Cobblestone drops +25 %")),
                            "JobBlock line: " + line);
                    settler.discard();
                    fillBiome(helper, new BlockPos(0, -4, 0), new BlockPos(16, 8, 16), Biomes.PLAINS);
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                })
                .thenSucceed();
    }

    private static Optional<String> today(JobBlockInfo info) {
        return info.biome().today().map(Component::getString);
    }

    /**
     * The JobBlock window's line: today's biome and its effects (a Clerk reads its JobBlock's biome, so it has one
     * without a settler), the biome alone without an effect, nothing for a Miner that has no work place of the day yet.
     * The window carries the Job's "?" overview too.
     */
    private static void infoLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(16, 0, 16));
        fillBiome(helper,new BlockPos(0, -4, 0), new BlockPos(16, 8, 16), Biomes.MUSHROOM_FIELDS);
        helper.setBlock(new BlockPos(6, 1, 6), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerk = helper.absolutePos(new BlockPos(6, 1, 6));
        JobBlockInfo mushroom = JobBlockInfo.of(level, clerk, ModJobs.CLERK.get(), 1);
        helper.assertTrue(today(mushroom).equals(Optional.of("Mushroom Fields · Speed ×1.3")), "With an effect: " + today(mushroom));
        helper.assertTrue(strings(mushroom.biome().overview()).equals(List.of("Biome bonuses:", "Mushroom Fields: Speed ×1.3", "All other biomes: no effect")),
                "The Clerk's overview: " + strings(mushroom.biome().overview()));
        fillBiome(helper,new BlockPos(0, -4, 0), new BlockPos(16, 8, 16), Biomes.SAVANNA);
        JobBlockInfo savanna = JobBlockInfo.of(level, clerk, ModJobs.CLERK.get(), 1);
        helper.assertTrue(today(savanna).equals(Optional.of("Savanna")), "The biome alone: " + today(savanna));
        helper.setBlock(new BlockPos(10, 1, 10), ModBlocks.MINER_BLOCK.get());
        BlockPos miner = helper.absolutePos(new BlockPos(10, 1, 10));
        helper.assertTrue(today(JobBlockInfo.of(level, miner, ModJobs.MINER.get(), 1)).isEmpty(), "No work place of the day, no biome");
        fillBiome(helper,new BlockPos(0, -4, 0), new BlockPos(16, 8, 16), Biomes.PLAINS);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * Sets the biome of a box like {@link BiomeBonusGameTests#fillBiome}, but accepts a box that already has it (the
     * test position lies in a generated world, whose biome there may be the one wanted).
     */
    private static void fillBiome(GameTestHelper helper, BlockPos from, BlockPos to, ResourceKey<Biome> key) {
        var result = FillBiomeCommand.fill(helper.getLevel(), helper.absolutePos(from), helper.absolutePos(to), BiomeBonusGameTests.biome(helper, key));
        BlockPos center = helper.absolutePos(new BlockPos((from.getX() + to.getX()) / 2, 1, (from.getZ() + to.getZ()) / 2));
        helper.assertTrue(result.left().isPresent() || helper.getLevel().getBiome(center).is(key),
                "Biome not set: " + result.right().map(Exception::getMessage).orElse(""));
    }

    private static List<String> strings(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    /**
     * The "?" overview lists one line per bonus file with an effect for the Job (its biomes, then its effects), framed by
     * a title and the note that all other biomes have no effect. A file whose only effect does not apply to the Job
     * (rare chance for the Lumberjack) and Jobs without a biome position (Mission Jobs) list nothing.
     */
    private static void overview(GameTestHelper helper) {
        List<String> miner = strings(BiomeBonusText.overview(ModJobs.MINER.get()));
        helper.assertTrue(miner.size() > 2 && miner.get(0).equals("Biome bonuses:") && miner.get(miner.size() - 1).equals("All other biomes: no effect"),
                "Title and note: " + miner);
        helper.assertTrue(miner.contains("Desert: Ore chance ×1.5 · Raw Iron drops +50 % · Cobblestone drops +25 %"), "Both effects of a file: " + miner);
        helper.assertTrue(miner.contains("Desert: Raw Iron drops +25 %"), "One line per file: " + miner);
        helper.assertTrue(miner.contains("Mushroom Fields: Ore chance ×2 · Cobblestone, Granite drops +100 %"), "An item list: " + miner);
        helper.assertTrue(miner.contains("Mountains: Ore chance ×1.1"), "A biome tag by its name: " + miner);
        List<String> lumberjack = strings(BiomeBonusText.overview(ModJobs.LUMBERJACK.get()));
        helper.assertTrue(lumberjack.stream().noneMatch(line -> line.contains("Desert")), "Rare chance does nothing for the Lumberjack: " + lumberjack);
        helper.assertTrue(lumberjack.contains("Forests: Speed ×1.1"), "The forest speed: " + lumberjack);
        helper.assertTrue(BiomeBonusText.overview(ModJobs.ADVENTURER.get()).isEmpty(), "A Mission Job has no biome bonuses");
        helper.succeed();
    }

    /**
     * Setting a work point names the point's biome and its effects for the Job in the action bar; a biome without an
     * effect is named alone.
     */
    private static void actionBar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(32, 0, 32));
        fillBiome(helper,new BlockPos(0, -4, 0), new BlockPos(12, 8, 12), Biomes.DESERT);
        fillBiome(helper,new BlockPos(20, -4, 20), new BlockPos(32, 8, 32), Biomes.SAVANNA);
        String desert = ScrollOfAssignmentItem.workPointMessage(level, ModJobs.MINER.get(), helper.absolutePos(new BlockPos(6, 1, 6))).getString();
        helper.assertTrue(desert.equals("Work place for Miner added · Desert · Ore chance ×1.5 · Raw Iron drops +75 % · Cobblestone drops +25 %"),
                "Desert: " + desert);
        String savanna = ScrollOfAssignmentItem.workPointMessage(level, ModJobs.MINER.get(), helper.absolutePos(new BlockPos(26, 1, 26))).getString();
        helper.assertTrue(savanna.equals("Work place for Miner added · Savanna"), "Savanna: " + savanna);
        fillBiome(helper,new BlockPos(0, -4, 0), new BlockPos(12, 8, 12), Biomes.PLAINS);
        fillBiome(helper,new BlockPos(20, -4, 20), new BlockPos(32, 8, 32), Biomes.PLAINS);
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        helper.succeed();
    }
}
