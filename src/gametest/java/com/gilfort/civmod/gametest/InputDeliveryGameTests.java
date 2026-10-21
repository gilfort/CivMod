package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Reservations;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for production via Deliverymen, input deliveries (#80): a Craftsperson on "Delivery: Deliveryman" asks for
 * the inputs of a round from its Home Clerk and other stores; a Deliveryman reserves them at the store and brings them
 * into its collect inventory, live and in catch-up; a shortfall at pickup requeues the task and gives the round up.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class InputDeliveryGameTests {

    private InputDeliveryGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "input_delivery_second_store", InputDeliveryGameTests::secondStore);
            register(helper, "input_delivery_reserved", InputDeliveryGameTests::reserved);
            register(helper, "input_delivery_shortfall", InputDeliveryGameTests::shortfall);
            register(helper, "input_delivery_catch_up", InputDeliveryGameTests::catchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * The Craftsperson's Home Clerk (a Warehouse with Deliveryman Dora) holds a log, a second Clerk without a Clerk
     * settler (block slots only, not served) holds 3 cobblestone; the Craftsperson on "Deliveryman" with patterns for a
     * stone pickaxe and its intermediates has a request for one.
     */
    private record Layout(BlockPos home, BlockPos second, BlockPos craft, Settler cora, Settler dora, UUID request, List<long[]> forced) {

        static Layout build(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(11, 0, 9));
            for (int x = 0; x < 11; x++) {
                for (int z = 0; z < 9; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                    for (int y = 1; y < 4; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                    }
                }
            }
            helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
            helper.setBlock(new BlockPos(9, 1, 1), ModBlocks.CLERK_BLOCK.get());
            BlockPos home = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos second = helper.absolutePos(new BlockPos(9, 1, 1));
            ((Container) level.getBlockEntity(home)).setItem(0, new ItemStack(Items.OAK_LOG, 1));
            ((Container) level.getBlockEntity(second)).setItem(0, new ItemStack(Items.COBBLESTONE, 3));
            BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(3, 1, 7));
            CraftspersonGameTests.placeTable(helper, new BlockPos(5, 1, 7));
            CraftspersonGameTests.define(helper, craft, 0, Items.STONE_PICKAXE);
            CraftspersonGameTests.define(helper, craft, 1, Items.STICK);
            CraftspersonGameTests.define(helper, craft, 2, Items.OAK_PLANKS);
            SettlementData data = SettlementData.get(level);
            Settlement settlement = data.settlementAt(home).orElseThrow();
            helper.assertTrue(data.settlementAt(second).filter(settlement::equals).isPresent()
                    && JobBlockSite.blockAt(level, craft).orElseThrow().homeClerk().equals(Optional.of(home)), "Layout");
            helper.assertTrue(JobAssignments.toggleDelivery(data, craft), "Delivery: Deliveryman");
            Request request = Requests.post(data, settlement, Items.STONE_PICKAXE, 1, Optional.of(home), Optional.empty());
            Settler cora = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(3, 1, 5), "Cora");
            Settler dora = ClerkGameTests.settler(helper, new BlockPos(2, 1, 3), data, settlement);
            dora.setCustomName(Component.literal("Dora"));
            dora.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 16));
            ResultDeliveryGameTests.deliveryman(helper, home, dora.getUUID());
            dora.addJob(new SettlerJob(ModJobs.DELIVERYMAN.get(), home));
            return new Layout(home, second, craft, cora, dora, request.id(), forced);
        }

        Settlement settlement(ServerLevel level) {
            return SettlementData.get(level).settlementAt(home).orElseThrow();
        }

        List<DeliveryTask> inputs(ServerLevel level) {
            return settlement(level).deliveries().stream().filter(task -> task.input().isPresent()).toList();
        }

        DeliveryTask inputAt(ServerLevel level, BlockPos store) {
            return inputs(level).stream().filter(task -> task.source().equals(store)).findFirst().orElseThrow();
        }

        int cobblestoneFor(ServerLevel level, boolean linked, UUID task) {
            return ClerkStorage.stock(level, second, linked, task).count(stack -> stack.is(Items.COBBLESTONE));
        }

        /** Pickaxes made: in the Craftsperson's drop-off (its JobBlock), carried or unloaded into the Home Clerk. */
        int pickaxes(ServerLevel level) {
            return ClerkStorage.stock(level, craft, false).count(stack -> stack.is(Items.STONE_PICKAXE))
                    + ClerkStorage.stock(level, home, true).count(stack -> stack.is(Items.STONE_PICKAXE))
                    + dora.getWork().buffer(home).stacks().stream().filter(stack -> stack.is(Items.STONE_PICKAXE)).mapToInt(ItemStack::getCount).sum();
        }

        void cleanUp(GameTestHelper helper) {
            SettlementData data = SettlementData.get(helper.getLevel());
            TestAssignments.unassign(data, home);
            TestAssignments.unassign(data, craft);
            cora.discard();
            dora.discard();
            forced.forEach(chunk -> helper.getLevel().setChunkForced((int) chunk[0], (int) chunk[1], false));
        }
    }

    /**
     * Live: the Craftsperson asks for its inputs – the log from its Home Clerk, the cobblestone from the second Clerk –
     * and waits; Dora brings both into its JobBlock, it crafts the pickaxe.
     */
    private static void secondStore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Layout layout = Layout.build(helper);
        layout.cora().getWork().decideNow(level);
        helper.assertTrue(layout.cora().getWork().status(layout.craft()) == JobStatus.WAITING_FOR_INPUTS,
                "Waiting for inputs: " + layout.cora().getWork().status(layout.craft()));
        List<DeliveryTask> inputs = layout.inputs(level);
        helper.assertTrue(inputs.size() == 2, "Two input deliveries: " + inputs);
        helper.assertTrue(layout.inputAt(level, layout.home()).input().orElseThrow().items().equals(java.util.Map.of(Items.OAK_LOG, 1))
                && layout.inputAt(level, layout.second()).input().orElseThrow().items().equals(java.util.Map.of(Items.COBBLESTONE, 3)),
                "The log from the Home Clerk, the cobblestone from the second store: " + inputs);
        helper.assertTrue(inputs.stream().allMatch(task -> Deliveries.destination(layout.settlement(level), task).equals(Optional.of(layout.craft()))),
                "Into its collect inventory (the JobBlock)");
        // Skilled up to the cap of a level 1 JobBlock: three crafting operations take a while.
        layout.cora().getSkills().set(ModJobs.CRAFTSPERSON.get(), new com.gilfort.civmod.job.SettlerSkills.Skill(100, 0));
        layout.cora().setNoAi(false);
        layout.dora().setNoAi(false);
        helper.succeedWhen(() -> {
            helper.assertTrue(layout.pickaxes(level) == 1, "Pickaxe not crafted yet: " + layout.settlement(level).deliveries() + ", Cora "
                    + layout.cora().getWork().statusText(layout.craft()).getString() + ", Dora "
                    + layout.dora().getWork().statusText(layout.home()).getString());
            helper.assertTrue(layout.cobblestoneFor(level, false, null) == 0, "The second store gave its cobblestone");
            helper.assertTrue(layout.inputs(level).isEmpty(), "Input deliveries done");
            layout.cleanUp(helper);
        });
    }

    /** Taking an input delivery reserves its inputs: customers no longer see them, the task does. */
    private static void reserved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Layout layout = Layout.build(helper);
        layout.cora().getWork().decideNow(level);
        DeliveryTask task = layout.inputAt(level, layout.second());
        helper.assertTrue(layout.cobblestoneFor(level, false, null) == 3, "Not reserved before a Deliveryman takes it");
        long day = DayClock.day(level);
        Settlement settlement = layout.settlement(level);
        Deliveries.take(SettlementData.get(level), settlement, task.id(), layout.dora().getUUID(), "Dora", day);
        helper.assertTrue(Reservations.reserved(settlement, layout.second(), day, null).equals(java.util.Map.of(Items.COBBLESTONE, 3)),
                "Reserved at the store: " + Reservations.reserved(settlement, layout.second(), day, null));
        helper.assertTrue(layout.cobblestoneFor(level, false, null) == 0, "Invisible to customers");
        helper.assertTrue(layout.cobblestoneFor(level, false, task.id()) == 3, "The task sees its inputs");
        layout.cleanUp(helper);
        helper.succeed();
    }

    /**
     * Someone took the reserved cobblestone: the pickup fails and the task is free again (requeued); the Craftsperson's
     * next decision sees the store short and gives the round up with "Missing".
     */
    private static void shortfall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Layout layout = Layout.build(helper);
        layout.cora().getWork().decideNow(level);
        DeliveryTask task = layout.inputAt(level, layout.second());
        long day = DayClock.day(level);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = layout.settlement(level);
        Deliveries.take(data, settlement, task.id(), layout.dora().getUUID(), "Dora", day);
        ((Container) level.getBlockEntity(layout.second())).setItem(0, new ItemStack(Items.COBBLESTONE, 1));

        Reservations.Pickup pickup = Deliveries.pickUpInput(data, settlement, task.id(), layout.dora().getUUID(),
                ClerkStorage.stock(level, layout.second(), false, task.id()), day);
        helper.assertTrue(pickup.failed() && pickup.taken().isEmpty(), "The pickup fails: " + pickup);
        DeliveryTask requeued = Deliveries.get(settlement, task.id()).orElseThrow();
        helper.assertTrue(requeued.takerOn(day).isEmpty() && !requeued.carried(), "Requeued: free for another try " + requeued);
        helper.assertTrue(layout.cobblestoneFor(level, false, null) == 1, "Nothing taken, nothing reserved");

        layout.cora().getWork().decideNow(level);
        Request request = Requests.get(settlement, layout.request()).orElseThrow();
        helper.assertTrue(request.missing().stream().anyMatch(missing -> missing.item() == Items.COBBLESTONE && missing.count() == 2),
                "Missing: 2× Cobblestone " + request.missing());
        helper.assertTrue(layout.inputs(level).isEmpty(), "The round's input deliveries are gone: " + layout.inputs(level));
        helper.assertTrue(request.crafting() == 0, "The claim is released");
        layout.cleanUp(helper);
        helper.succeed();
    }

    /** Catch-up: the Craftsperson asks, Dora brings the inputs in a later hour, the Craftsperson crafts. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Layout layout = Layout.build(helper);
        long missed = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L - 24000L;
        layout.cora().getWork().setCatchUp(missed, missed + 6000L);
        layout.dora().getWork().setCatchUp(missed, missed + 6000L);
        CatchUp.runNow(level, List.of(layout.cora(), layout.dora()));
        helper.assertTrue(layout.pickaxes(level) == 1, "Crafted from delivered inputs: " + layout.settlement(level).deliveries() + ", "
                + Requests.get(layout.settlement(level), layout.request()));
        helper.assertTrue(layout.cobblestoneFor(level, false, null) == 0
                && ClerkStorage.stock(level, layout.home(), true).count(stack -> stack.is(Items.OAK_LOG)) == 0, "Both stores gave their inputs");
        helper.assertTrue(layout.inputs(level).isEmpty(), "Input deliveries done: " + layout.inputs(level));
        layout.cleanUp(helper);
        helper.succeed();
    }
}
