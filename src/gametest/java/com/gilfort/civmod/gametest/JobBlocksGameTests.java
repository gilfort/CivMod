package com.gilfort.civmod.gametest;

import java.util.List;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobNeeds;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.StationTypesTab;
import com.gilfort.civmod.menu.StationTypesTabData;
import com.gilfort.civmod.registry.ModJobs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the job blocks of the Station Types tab (#290, vault {@code Systems/JobBlock.md}, decision of 2026-11-15):
 * one block per unlocked Job with its station, tool and extra station, each with a tick or a cross, read once when the
 * window opens.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class JobBlocksGameTests {

    private JobBlocksGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            TestFunctions.register(helper, "job_blocks_unlocked_only", JobBlocksGameTests::unlockedOnly);
            TestFunctions.register(helper, "job_blocks_baker_ticks", JobBlocksGameTests::bakerTicks);
            TestFunctions.register(helper, "job_blocks_tool_line", JobBlocksGameTests::toolLine);
            TestFunctions.register(helper, "job_blocks_read_once", JobBlocksGameTests::readOnce);
        });
    }

    private static List<Job> jobsOf(StationTypesTabData tab) {
        return tab.blocks().stream().map(JobNeeds::job).toList();
    }

    private static JobNeeds blockOf(StationTypesTabData tab, Job job) {
        return tab.blocks().stream().filter(block -> block.job() == job).findFirst().orElseThrow();
    }

    /**
     * A Craftsperson with only its Base Job unlocked shows one block, not the list of every Specialization it could have.
     * A hay bale unlocks the Baker: it gets a block behind the Base Job's.
     */
    private static void unlockedOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(jobsOf(StationTypesTabData.of(level, pos)).equals(List.of(ModJobs.CRAFTSPERSON.get())),
                "Only the Base Job: " + jobsOf(StationTypesTabData.of(level, pos)));
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 0, Items.HAY_BLOCK), "A hay bale unlocks the Baker");
        helper.assertTrue(jobsOf(StationTypesTabData.of(level, pos)).equals(List.of(ModJobs.CRAFTSPERSON.get(), BakerGameTests.baker())),
                "The Base Job first, then the Baker: " + jobsOf(StationTypesTabData.of(level, pos)));
        helper.succeed();
    }

    /**
     * The Baker: a station (crafting table) and an extra station (furnace), no tool. Each is crossed until a matching block
     * stands near the JobBlock (the extra: near the station).
     */
    private static void bakerTicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = BakerGameTests.bakery(helper, new BlockPos(1, 1, 1));
        JobNeeds baker = blockOf(StationTypesTabData.of(level, pos), BakerGameTests.baker());
        helper.assertTrue(baker.station().state() == JobNeeds.State.MISSING && baker.station().icon().is(Items.CRAFTING_TABLE),
                "No table yet: " + baker.station());
        helper.assertTrue(baker.extra().state() == JobNeeds.State.MISSING && baker.extra().icon().is(Items.FURNACE), "No furnace yet: " + baker.extra());
        helper.assertTrue(baker.tool().state() == JobNeeds.State.NOT_NEEDED, "The Baker has no tool: " + baker.tool());

        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CRAFTING_TABLE);
        baker = blockOf(StationTypesTabData.of(level, pos), BakerGameTests.baker());
        helper.assertTrue(baker.station().present() && !baker.extra().present(), "A table: station ticked, extra still crossed: " + baker);
        helper.setBlock(new BlockPos(4, 1, 1), Blocks.FURNACE);
        baker = blockOf(StationTypesTabData.of(level, pos), BakerGameTests.baker());
        helper.assertTrue(baker.station().present() && baker.extra().present(), "A furnace next to the table: both ticked: " + baker);
        helper.succeed();
    }

    /**
     * A Resource Collection Job with a tool shows its icon and the tier of the skill; without a worker at the block it is
     * the first tier and crossed. Such a Job works no station.
     */
    private static void toolLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = GathererGameTests.gathererBlock(helper);
        BlockPos pos = helper.absolutePos(relative);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 0, Items.COMPOSTER), "A composter unlocks the Veggy Farmer");
        JobNeeds veggy = blockOf(StationTypesTabData.of(level, pos), ModJobs.VEGGY_FARMER.get());
        helper.assertTrue(veggy.tool().state() == JobNeeds.State.MISSING && !veggy.tool().icon().isEmpty(), "A tool, crossed: " + veggy.tool());
        helper.assertTrue(veggy.tool().text().getString().equals("Tool: any – Stone"), "The first tier: " + veggy.tool().text().getString());
        helper.assertTrue(veggy.station().state() == JobNeeds.State.NOT_NEEDED && veggy.extra().state() == JobNeeds.State.NOT_NEEDED,
                "A collecting Job has no station: " + veggy);
        helper.succeed();
    }

    /** The window reads the needs once: a table placed after it opened does not tick the station. */
    private static void readOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = StationTypeGameTests.menu(helper, player, pos);
        StationTypesTab tab = menu.tab(StationTypesTab.class).orElseThrow();
        menu.resend();
        helper.assertTrue(!blockOf(tab.data().get(), ModJobs.CRAFTSPERSON.get()).station().present(), "No table when it opened");
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CRAFTING_TABLE);
        menu.resend();
        helper.assertTrue(!blockOf(tab.data().get(), ModJobs.CRAFTSPERSON.get()).station().present(), "No live update while it stays open");
        helper.assertTrue(blockOf(StationTypesTabData.of(level, pos), ModJobs.CRAFTSPERSON.get()).station().present(), "A new window sees the table");
        helper.succeed();
    }
}
