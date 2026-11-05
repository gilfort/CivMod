package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments.AssignResult;
import com.gilfort.civmod.settlement.AutoAssignment;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "assignment_owner", AssignmentOwnerGameTests::owner);
            register(helper, "assignment_replace_one_place", AssignmentOwnerGameTests::replaceOnePlace);
            register(helper, "assignment_replace_several_places", AssignmentOwnerGameTests::replaceSeveralPlaces);
            register(helper, "assignment_replace_stale", AssignmentOwnerGameTests::replaceStale);
        });
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
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        TestSettlements.setBlock(helper, new BlockPos(4, 1, 1), ModBlocks.MINER_BLOCK.get());
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

    private static Settler addSettler(GameTestHelper helper, SettlementData data, Settlement settlement, int x, String name) {
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(x, 1, 3), data, settlement);
        settler.setCustomName(Component.literal(name));
        return settler;
    }

    /**
     * A one-place Job: the replaced settler loses only this Job (its Miner Job stays), the new settler gets the place,
     * also with automatic assignment on, which does not take the place back at the next morning.
     */
    private static void replaceOnePlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        TestSettlements.setBlock(helper, new BlockPos(4, 1, 1), ModBlocks.MINER_BLOCK.get());
        BlockPos lumber = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos miner = helper.absolutePos(new BlockPos(4, 1, 1));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(lumber).orElseThrow();
        Settler holder = addSettler(helper, data, settlement, 2, "Holder");
        Settler newcomer = addSettler(helper, data, settlement, 3, "Newcomer");
        Job lumberjack = ModJobs.LUMBERJACK.get();
        helper.assertTrue(SettlementWorld.assign(level, lumber, holder, lumberjack) == AssignResult.OK, "Holder takes the Lumberjack place");
        helper.assertTrue(SettlementWorld.assign(level, miner, holder, ModJobs.MINER.get()) == AssignResult.OK, "Holder is also a Miner");
        helper.assertTrue(SettlementWorld.assign(level, lumber, newcomer, lumberjack) == AssignResult.OCCUPIED, "The place is taken");
        AutoAssignment.setAuto(level, lumber, lumberjack, true);

        helper.assertTrue(SettlementWorld.replace(level, lumber, newcomer, lumberjack, holder.getUUID()) == AssignResult.OK, "Swap");
        List<UUID> assignees = StationTypeGameTests.block(level, lumber).assignees().stream().map(SettlementBlock.Assignee::id).toList();
        helper.assertTrue(assignees.equals(List.of(newcomer.getUUID())), "Only the newcomer works the block: " + assignees);
        helper.assertTrue(newcomer.getJobs().equals(List.of(new SettlerJob(lumberjack, lumber))), "Newcomer job list: " + newcomer.getJobs());
        helper.assertTrue(holder.getJobs().equals(List.of(new SettlerJob(ModJobs.MINER.get(), miner))),
                "The holder keeps its Miner Job: " + holder.getJobs());
        AutoAssignment.morning(level, settlement);
        helper.assertTrue(StationTypeGameTests.block(level, lumber).isAssignee(newcomer.getUUID()), "Automatic assignment leaves the new holder");

        SettlementWorld.unassignAll(level, holder);
        SettlementWorld.unassignAll(level, newcomer);
        holder.discard();
        newcomer.discard();
        helper.succeed();
    }

    /** A several-place Job: the chosen holder goes, the other holder stays. */
    private static void replaceSeveralPlaces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 1));
        SettlementWorld.setLevel(level, clerk, 2);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Settler first = addSettler(helper, data, settlement, 2, "First");
        Settler second = addSettler(helper, data, settlement, 3, "Second");
        Settler newcomer = addSettler(helper, data, settlement, 4, "Newcomer");
        Job deliveryman = ModJobs.DELIVERYMAN.get();
        helper.assertTrue(SettlementWorld.assign(level, clerk, first, deliveryman) == AssignResult.OK, "First Deliveryman");
        helper.assertTrue(SettlementWorld.assign(level, clerk, second, deliveryman) == AssignResult.OK, "Second Deliveryman");
        helper.assertTrue(SettlementWorld.assign(level, clerk, newcomer, deliveryman) == AssignResult.FULL, "Both places are taken");

        helper.assertTrue(SettlementWorld.replace(level, clerk, newcomer, deliveryman, second.getUUID()) == AssignResult.OK, "Swap");
        SettlementBlock block = StationTypeGameTests.block(level, clerk);
        helper.assertTrue(block.isAssignee(first.getUUID()) && block.isAssignee(newcomer.getUUID()) && !block.isAssignee(second.getUUID()),
                "First stays, newcomer replaces second: " + block.assignees());
        helper.assertTrue(second.getJobs().isEmpty() && first.getJobs().size() == 1 && newcomer.getJobs().size() == 1, "Job lists agree");

        for (Settler settler : List.of(first, second, newcomer)) {
            SettlementWorld.unassignAll(level, settler);
            settler.discard();
        }
        helper.succeed();
    }

    /** A stale click (the holder left meanwhile) changes nothing. */
    private static void replaceStale(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        BlockPos lumber = helper.absolutePos(new BlockPos(1, 1, 1));
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(lumber).orElseThrow();
        Settler holder = addSettler(helper, data, settlement, 2, "Holder");
        Settler other = addSettler(helper, data, settlement, 3, "Other");
        Settler newcomer = addSettler(helper, data, settlement, 4, "Newcomer");
        Job lumberjack = ModJobs.LUMBERJACK.get();
        SettlementWorld.assign(level, lumber, holder, lumberjack);

        helper.assertTrue(SettlementWorld.replace(level, lumber, newcomer, lumberjack, other.getUUID()) == AssignResult.NOT_HOLDER,
                "A settler without the Job cannot be replaced");
        helper.assertTrue(StationTypeGameTests.block(level, lumber).isAssignee(holder.getUUID()) && newcomer.getJobs().isEmpty(), "Nothing changed");
        SettlementWorld.unassign(level, lumber, holder);
        helper.assertTrue(SettlementWorld.replace(level, lumber, newcomer, lumberjack, holder.getUUID()) == AssignResult.NOT_HOLDER,
                "The holder left before the click");
        helper.assertTrue(StationTypeGameTests.block(level, lumber).assignees().isEmpty() && newcomer.getJobs().isEmpty(), "No assignment happened");

        for (Settler settler : List.of(holder, other, newcomer)) {
            settler.discard();
        }
        helper.succeed();
    }
}
