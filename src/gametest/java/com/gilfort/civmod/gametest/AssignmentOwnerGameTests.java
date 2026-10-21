package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the settlement world as the one owner of assignment changes (#102, vault {@code Systems/Jobs.md},
 * {@code Systems/JobBlock.md}): every operation keeps the JobBlock's assignee list and the settler's job list in sync.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class AssignmentOwnerGameTests {

    private AssignmentOwnerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> register(helper, "assignment_owner", AssignmentOwnerGameTests::owner));
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static boolean free(ServerLevel level, BlockPos jobBlock) {
        return StationTypeGameTests.block(level, jobBlock).assignees().isEmpty();
    }

    /**
     * Removing one settler from one JobBlock, freeing all jobs of a settler and editing the job list in the settler
     * window each change the JobBlock's assignee list and the settler's job list together.
     */
    private static void owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(new BlockPos(4, 1, 1), ModBlocks.MINER_BLOCK.get());
        BlockPos lumber = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos miner = helper.absolutePos(new BlockPos(4, 1, 1));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(lumber).orElseThrow();
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
        SettlerJob lumberJob = new SettlerJob(ModJobs.LUMBERJACK.get(), lumber);
        SettlerJob minerJob = new SettlerJob(ModJobs.MINER.get(), miner);

        helper.assertTrue(SettlementWorld.assign(level, lumber, settler, ModJobs.LUMBERJACK.get()) == AssignResult.OK, "Lumberjack");
        helper.assertTrue(SettlementWorld.assign(level, miner, settler, ModJobs.MINER.get()) == AssignResult.OK, "Miner");
        helper.assertTrue(SettlementWorld.unassign(level, lumber, settler), "The settler leaves the Lumberjack Block");
        helper.assertTrue(free(level, lumber) && settler.getJobs().equals(List.of(minerJob)),
                "Block and job list agree: " + settler.getJobs());
        helper.assertFalse(SettlementWorld.unassign(level, lumber, settler), "Not working there any more");

        helper.assertTrue(SettlementWorld.assign(level, lumber, settler, ModJobs.LUMBERJACK.get()) == AssignResult.OK, "Lumberjack again");
        SettlementWorld.unassignAll(level, settler);
        helper.assertTrue(free(level, lumber) && free(level, miner) && settler.getJobs().isEmpty(), "All jobs freed: " + settler.getJobs());

        SettlementWorld.assign(level, lumber, settler, ModJobs.LUMBERJACK.get());
        SettlementWorld.assign(level, miner, settler, ModJobs.MINER.get());
        SettlementWorld.setJobs(level, settler, List.of(minerJob));
        helper.assertTrue(free(level, lumber) && !free(level, miner) && settler.getJobs().equals(List.of(minerJob)),
                "A job removed from the list frees its JobBlock: " + settler.getJobs());
        SettlementWorld.setJobs(level, settler, List.of(minerJob));
        helper.assertTrue(!free(level, miner), "Kept jobs stay assigned");
        helper.assertFalse(settler.getJobs().contains(lumberJob), "No stale job");

        SettlementWorld.unassignAll(level, settler);
        settler.discard();
        helper.succeed();
    }
}
