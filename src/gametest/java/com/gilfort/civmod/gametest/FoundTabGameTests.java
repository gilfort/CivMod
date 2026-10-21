package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.menu.FoundTab;
import com.gilfort.civmod.menu.FoundTabData;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestAssignments;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Found tab of Resource Collection JobBlocks and excluded resources (#72).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FoundTabGameTests {

    private static final long DAY = 24000L;
    private static final Identifier OAK_LOG = Identifier.withDefaultNamespace("oak_log");
    private static final Identifier BIRCH_LOG = Identifier.withDefaultNamespace("birch_log");
    private static final Identifier STONE = Identifier.withDefaultNamespace("stone");
    private static final Identifier GRANITE = Identifier.withDefaultNamespace("granite");
    private static final Identifier DIAMOND_ORE = Identifier.withDefaultNamespace("diamond_ore");

    private FoundTabGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "found_tab_toggle_and_pause", FoundTabGameTests::toggleAndPause);
            register(helper, "found_tab_survives_settler_change", FoundTabGameTests::survivesSettlerChange);
            register(helper, "found_tab_exclusions_in_catch_up", FoundTabGameTests::exclusionsInCatchUp);
            register(helper, "found_tab_miner_empty_rolls", FoundTabGameTests::minerEmptyRolls);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static JobBlockMenu menu(GameTestHelper helper, BlockPos jobBlock) {
        ServerLevel level = helper.getLevel();
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.snapTo(jobBlock.getX() + 0.5, jobBlock.getY() + 1, jobBlock.getZ() + 0.5);
        JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(jobBlock);
        SettlementBlock block = JobBlockSite.blockAt(level, jobBlock).orElseThrow();
        return new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(level, jobBlock, block.jobType(), block.level()));
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                total += container.getItem(i).getCount();
            }
        }
        return total;
    }

    private static int total(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            total += container.getItem(i).getCount();
        }
        return total;
    }

    private static void cleanUp(ServerLevel level, Settler settler, List<long[]> forced) {
        settler.discard();
        forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
    }

    /**
     * "Not scanned yet" before the first scan, then the found logs; right-click through the menu excludes a listed type
     * (an unlisted one is refused); with every log excluded the job pauses with "All resources excluded", and
     * including the log again brings it back at once (well before the 30 s retry).
     */
    private static void toggleAndPause(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        helper.assertFalse(FoundTabData.of(level, jobBlock).scanned(), "Not scanned yet before the first scan");
        JobBlockMenu menu = menu(helper, jobBlock);
        helper.assertTrue(menu.getTabCount() == 3, "Storage, Info, Found");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(FoundTabData.of(level, jobBlock).scanned(), "Never scanned"))
                .thenExecute(() -> {
                    FoundTabData tab = FoundTabData.of(level, jobBlock);
                    helper.assertTrue(tab.entry(OAK_LOG).filter(entry -> !entry.excluded() && !entry.needsTool()).isPresent(), "Oak log listed: " + tab);
                    helper.assertFalse(menu.tab(FoundTab.class).orElseThrow().toggleExcluded(DIAMOND_ORE), "A type the tab does not list is refused");
                    helper.assertTrue(JobBlockSite.blockAt(level, jobBlock).orElseThrow().excluded().isEmpty(), "Nothing excluded by the refused toggle");
                    helper.assertTrue(menu.tab(FoundTab.class).orElseThrow().toggleExcluded(OAK_LOG), "Oak log toggled");
                    helper.assertTrue(FoundTabData.of(level, jobBlock).entry(OAK_LOG).filter(FoundTabData.Entry::excluded).isPresent(), "Oak log excluded");
                })
                .thenWaitUntil(() -> helper.assertTrue(settler.getWork().status(jobBlock) == JobStatus.ALL_EXCLUDED,
                        "Not paused: " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> helper.assertTrue(menu.tab(FoundTab.class).orElseThrow().toggleExcluded(OAK_LOG), "Oak log included again"))
                .thenExecuteAfter(2 * 100 + 20, () -> helper.assertTrue(settler.getWork().status(jobBlock) != JobStatus.ALL_EXCLUDED,
                        "Still paused after including the log: " + settler.getWork().status(jobBlock)))
                .thenExecute(() -> cleanUp(level, settler, forced))
                .thenSucceed();
    }

    /**
     * Exclusions belong to the JobBlock: they survive saving, the settler leaving (the tab keeps the scan and drops the
     * tool marks) and a new settler taking the place, who does not produce the excluded type either.
     */
    private static void survivesSettlerChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler first = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = first.getJobs().get(0).jobBlock();
        // A 2×2 column: the sampled scan sees every other block.
        for (int x = 6; x <= 7; x++) {
            for (int z = 6; z <= 7; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.BIRCH_LOG);
                }
            }
        }
        SettlementData data = SettlementData.get(level);
        Settler[] second = new Settler[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(FoundTabData.of(level, jobBlock).scanned(), "Never scanned"))
                .thenExecute(() -> {
                    SettlementWorld.toggleExcluded(level, jobBlock, OAK_LOG);
                    var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                    SettlementBlock block = JobBlockSite.blockAt(level, jobBlock).orElseThrow();
                    SettlementBlock loaded = SettlementBlock.CODEC.parse(ops, SettlementBlock.CODEC.encodeStart(ops, block).getOrThrow()).getOrThrow();
                    helper.assertTrue(loaded.excluded().equals(java.util.Set.of(OAK_LOG)), "Exclusion not saved: " + loaded.excluded());
                    helper.assertTrue(loaded.foundScan().isPresent(), "Scan of the JobBlock not saved");
                    SettlementWorld.unassign(level, jobBlock);
                    FoundTabData tab = FoundTabData.of(level, jobBlock);
                    helper.assertTrue(tab.scanned() && tab.entry(OAK_LOG).filter(FoundTabData.Entry::excluded).isPresent(),
                            "Without a settler the tab keeps the scan and the exclusion: " + tab);
                    Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
                    second[0] = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
                    Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 3)));
                    second[0].snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
                    second[0].setCustomName(net.minecraft.network.chat.Component.literal("Second"));
                    level.addFreshEntity(second[0]);
                    second[0].joinSettlement(data, settlement);
                    second[0].getInventory().setItem(Settler.BELT_START, new ItemStack(Items.NETHERITE_AXE));
                    second[0].getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
                    helper.assertTrue(TestAssignments.assign(data, jobBlock, second[0].getUUID(), "Second", settlement.id(), List.of())
                            == JobAssignments.AssignResult.OK, "Second assignment failed");
                    second[0].addJob(new SettlerJob(ModJobs.LUMBERJACK.get(), jobBlock));
                })
                .thenWaitUntil(() -> helper.assertTrue(second[0].getWork().buffer(jobBlock).stacks().stream().anyMatch(stack -> stack.is(Items.BIRCH_LOG)),
                        "The new settler cuts no birch yet (status " + second[0].getWork().status(jobBlock) + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(second[0].getWork().buffer(jobBlock).stacks().stream().noneMatch(stack -> stack.is(Items.OAK_LOG)),
                            "The new settler cut excluded oak");
                    helper.assertTrue(JobBlockSite.blockAt(level, jobBlock).orElseThrow().excluded().contains(OAK_LOG), "Exclusion lost");
                    first.discard();
                    cleanUp(level, second[0], forced);
                })
                .thenSucceed();
    }

    /** Catch-up uses the JobBlock's exclusions: a day of oak and birch with oak excluded yields only birch logs. */
    private static void exclusionsInCatchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        Settler settler = CatchUpGameTests.lumberjack(helper, forced);
        BlockPos jobBlock = settler.getJobs().get(0).jobBlock();
        for (int x = 4; x < 8; x++) {
            for (int z = 6; z <= 7; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.BIRCH_LOG);
                }
            }
        }
        SettlementWorld.toggleExcluded(level, jobBlock, OAK_LOG);
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || !FoundTabData.of(level, jobBlock).scanned()) {
                return;
            }
            done[0] = true;
            helper.assertTrue(FoundTabData.of(level, jobBlock).entry(BIRCH_LOG).isPresent(), "Birch not found: " + FoundTabData.of(level, jobBlock));
            Container container = (Container) level.getBlockEntity(jobBlock);
            long clock = level.getOverworldClockTime();
            settler.getWork().setCatchUp(clock - DAY, clock);
            CatchUp.runNow(level, List.of(settler));
            int oak = count(container, Items.OAK_LOG) + settler.getWork().buffer(jobBlock).stacks().stream()
                    .filter(stack -> stack.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum();
            int birch = count(container, Items.BIRCH_LOG);
            helper.assertTrue(oak == 0, "Excluded oak produced in catch-up: " + oak);
            helper.assertTrue(birch >= 20, "Birch logs expected from a caught-up day: " + birch);
            cleanUp(level, settler, forced);
            helper.succeed();
        });
    }

    /**
     * A Miner with everything excluded but the diamond ore its stone pickaxe cannot mine: the tab marks that ore (no
     * mark without a settler); the job keeps working, and a caught-up day gives nothing, but EXP and tool wear for the
     * empty stone rolls.
     */
    private static void minerEmptyRolls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos jobBlockRelative = new BlockPos(1, 1, 1);
        helper.setBlock(jobBlockRelative, ModBlocks.MINER_BLOCK.get());
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.GRANITE);
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.DIAMOND_ORE);
        BlockPos jobBlock = helper.absolutePos(jobBlockRelative);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(jobBlock).orElseThrow();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(net.minecraft.network.chat.Component.literal("Digger"));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.STONE_PICKAXE));
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.COOKED_BEEF, 64));
        JobAssignments.cyclePoint(data, jobBlock, helper.absolutePos(new BlockPos(5, 1, 5)), true, false);
        helper.assertTrue(TestAssignments.assign(data, jobBlock, settler.getUUID(), "Digger", settlement.id(), List.of())
                == JobAssignments.AssignResult.OK, "Assignment failed");
        settler.addJob(new SettlerJob(ModJobs.MINER.get(), jobBlock));
        boolean[] done = new boolean[1];
        helper.onEachTick(() -> {
            if (done[0] || !FoundTabData.of(level, jobBlock).scanned()) {
                return;
            }
            done[0] = true;
            FoundTabData tab = FoundTabData.of(level, jobBlock);
            helper.assertTrue(tab.entry(DIAMOND_ORE).filter(FoundTabData.Entry::needsTool).isPresent()
                    && tab.entry(GRANITE).filter(entry -> !entry.needsTool()).isPresent(), "Diamond ore needs a better tool: " + tab);
            // Every stone (and any other ore the area may hold) is excluded: only the unminable diamond ore stays.
            tab.entries().stream().map(FoundTabData.Entry::type).filter(type -> !type.equals(DIAMOND_ORE))
                    .forEach(type -> SettlementWorld.toggleExcluded(level, jobBlock, type));
            helper.assertTrue(tab.entry(STONE).isPresent(), "Stone found: " + tab);
            Container container = (Container) level.getBlockEntity(jobBlock);
            int damageBefore = settler.getInventory().getItem(Settler.BELT_START).getDamageValue();
            int expBefore = settler.getSkills().get(ModJobs.MINER.get()).exp() + 1000 * settler.getSkills().get(ModJobs.MINER.get()).level();
            int before = total(container) + settler.getWork().buffer(jobBlock).count();
            long clock = level.getOverworldClockTime();
            settler.getWork().setCatchUp(clock - DAY, clock);
            CatchUp.runNow(level, List.of(settler));
            int produced = total(container) + settler.getWork().buffer(jobBlock).count() - before;
            helper.assertTrue(produced == 0, "Excluded stones produced: " + produced);
            helper.assertTrue(settler.getWork().status(jobBlock) != JobStatus.ALL_EXCLUDED, "The diamond ore is a main resource left");
            int expAfter = settler.getSkills().get(ModJobs.MINER.get()).exp() + 1000 * settler.getSkills().get(ModJobs.MINER.get()).level();
            helper.assertTrue(expAfter > expBefore, "EXP for the empty rolls");
            helper.assertTrue(settler.getInventory().getItem(Settler.BELT_START).isEmpty()
                    || settler.getInventory().getItem(Settler.BELT_START).getDamageValue() > damageBefore, "The pickaxe wears on empty rolls");
            SettlementWorld.unassign(level, jobBlock);
            helper.assertTrue(FoundTabData.of(level, jobBlock).entries().stream().noneMatch(FoundTabData.Entry::needsTool), "No tool marks without a settler");
            cleanUp(level, settler, forced);
            helper.succeed();
        });
    }
}
