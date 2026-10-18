package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.Job;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for production via Deliverymen, result deliveries (#78): the Craftsperson's delivery toggle, result
 * delivery tasks and their routing across Warehouses, "in delivery" for stock rules, live and catch-up transport.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ResultDeliveryGameTests {

    private ResultDeliveryGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "result_delivery_toggle_default", ResultDeliveryGameTests::toggleDefault);
            register(helper, "result_delivery_routing", ResultDeliveryGameTests::routing);
            register(helper, "result_delivery_craftsperson_drop_off", ResultDeliveryGameTests::craftspersonDropOff);
            register(helper, "result_delivery_across_warehouses", ResultDeliveryGameTests::acrossWarehouses);
            register(helper, "result_delivery_catch_up", ResultDeliveryGameTests::catchUp);
        });
    }

    /** Two Warehouses with a Deliveryman each and a Craftsperson near the first one, its drop-off holding 4 results. */
    private record Layout(BlockPos home, BlockPos requesting, BlockPos craft, Settler dora, Settler eve, List<long[]> forced) {

        static Layout build(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            List<long[]> forced = LumberjackGameTests.forceChunks(helper, new BlockPos(11, 0, 9));
            for (int x = 0; x < 11; x++) {
                for (int z = 0; z < 9; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE);
                }
            }
            helper.setBlock(new BlockPos(1, 1, 1), com.gilfort.civmod.registry.ModBlocks.CLERK_BLOCK.get());
            helper.setBlock(new BlockPos(9, 1, 1), com.gilfort.civmod.registry.ModBlocks.CLERK_BLOCK.get());
            BlockPos home = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos requesting = helper.absolutePos(new BlockPos(9, 1, 1));
            BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(3, 1, 7));
            SettlementData data = SettlementData.get(level);
            Settlement settlement = data.settlementAt(home).orElseThrow();
            helper.assertTrue(data.settlementAt(requesting).filter(settlement::equals).isPresent()
                    && JobBlockSite.blockAt(level, craft).orElseThrow().homeClerk().equals(Optional.of(home)), "Layout");
            ((Container) level.getBlockEntity(craft)).setItem(0, new ItemStack(Items.STONE_PICKAXE, 1));
            ((Container) level.getBlockEntity(craft)).setItem(1, new ItemStack(Items.STONE_PICKAXE, 1));
            ((Container) level.getBlockEntity(craft)).setItem(2, new ItemStack(Items.STONE_PICKAXE, 1));
            ((Container) level.getBlockEntity(craft)).setItem(3, new ItemStack(Items.STONE_PICKAXE, 1));
            Deliveries.postResult(data, settlement, craft, craft, Items.STONE_PICKAXE, 4, requesting, level.getGameTime());
            Settler dora = courier(helper, home, new BlockPos(2, 1, 3), "Dora");
            Settler eve = courier(helper, requesting, new BlockPos(8, 1, 3), "Eve");
            return new Layout(home, requesting, craft, dora, eve, forced);
        }

        private static Settler courier(GameTestHelper helper, BlockPos warehouse, BlockPos relative, String name) {
            SettlementData data = SettlementData.get(helper.getLevel());
            Settler settler = ClerkGameTests.settler(helper, relative, data, data.settlementAt(warehouse).orElseThrow());
            settler.setCustomName(net.minecraft.network.chat.Component.literal(name));
            settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, 16));
            deliveryman(helper, warehouse, settler.getUUID());
            settler.addJob(new com.gilfort.civmod.npc.SettlerJob(ModJobs.DELIVERYMAN.get(), warehouse));
            return settler;
        }

        int arrived(ServerLevel level) {
            return ClerkStorage.stock(level, requesting, true).count(stack -> stack.is(Items.STONE_PICKAXE));
        }

        boolean open(Settlement settlement) {
            return settlement.deliveries().stream().anyMatch(task -> task.result().isPresent());
        }

        void cleanUp(GameTestHelper helper) {
            SettlementData data = SettlementData.get(helper.getLevel());
            JobAssignments.unassign(data, home);
            JobAssignments.unassign(data, requesting);
            dora.discard();
            eve.discard();
            forced.forEach(chunk -> helper.getLevel().setChunkForced((int) chunk[0], (int) chunk[1], false));
        }
    }

    /**
     * Live: the producer's Warehouse brings the results into the producer's Home Clerk; the requesting Clerk's
     * Warehouse fetches them there and unloads them at the requesting Clerk.
     */
    private static void acrossWarehouses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Layout layout = Layout.build(helper);
        Settlement settlement = SettlementData.get(level).settlementAt(layout.home()).orElseThrow();
        layout.dora().setNoAi(false);
        layout.eve().setNoAi(false);
        helper.succeedWhen(() -> {
            helper.assertTrue(layout.arrived(level) == 4, "Not arrived yet: " + layout.arrived(level) + " (" + settlement.deliveries() + ", Dora "
                    + layout.dora().getWork().statusText(layout.home()).getString() + ", Eve "
                    + layout.eve().getWork().statusText(layout.requesting()).getString() + ")");
            helper.assertTrue(!layout.open(settlement), "Its result delivery is done");
            helper.assertTrue(ClerkStorage.stock(level, layout.craft(), false).count(stack -> stack.is(Items.STONE_PICKAXE)) == 0, "Drop-off emptied");
            layout.cleanUp(helper);
        });
    }

    /** Catch-up: the same two legs, the first Warehouse's Deliveryman first, then the requesting Clerk's. */
    private static void catchUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Layout layout = Layout.build(helper);
        Settlement settlement = SettlementData.get(level).settlementAt(layout.home()).orElseThrow();
        long missed = Math.floorDiv(level.getOverworldClockTime(), 24000L) * 24000L - 24000L;
        layout.dora().getWork().setCatchUp(missed, missed + 2000L);
        com.gilfort.civmod.job.CatchUp.runNow(level, List.of(layout.dora()));
        helper.assertTrue(ClerkStorage.stock(level, layout.home(), true).count(stack -> stack.is(Items.STONE_PICKAXE)) == 4,
                "Relayed into the Home Clerk");
        helper.assertTrue(layout.arrived(level) == 0 && layout.open(settlement), "Still on its way: " + settlement.deliveries());
        layout.eve().getWork().setCatchUp(missed, missed + 2000L);
        com.gilfort.civmod.job.CatchUp.runNow(level, List.of(layout.eve()));
        helper.assertTrue(layout.arrived(level) == 4, "Arrived at the requesting Clerk: " + layout.arrived(level) + " " + settlement.deliveries());
        helper.assertTrue(!layout.open(settlement), "Done");
        layout.cleanUp(helper);
        helper.succeed();
    }

    /**
     * On "Deliveryman" the Craftsperson puts the result into its own drop-off (its JobBlock) with a result delivery for
     * the requesting Clerk; the request is done and the stock rule counts the result as in delivery (no new request).
     */
    private static void craftspersonDropOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 1));
        chest.setItem(1, new ItemStack(Items.COBBLESTONE, 3));
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, Items.STONE_PICKAXE, 1, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);

        BlockPos craft = CraftspersonGameTests.craftsperson(helper, new BlockPos(1, 1, 5));
        CraftspersonGameTests.placeTable(helper, new BlockPos(3, 1, 5));
        CraftspersonGameTests.define(helper, craft, 0, Items.STONE_PICKAXE);
        CraftspersonGameTests.define(helper, craft, 1, Items.STICK);
        CraftspersonGameTests.define(helper, craft, 2, Items.OAK_PLANKS);
        helper.assertTrue(JobAssignments.toggleDelivery(data, craft), "Delivery: Deliveryman");
        Settler settler = CraftspersonGameTests.craftsperson(helper, craft, new BlockPos(2, 1, 7), "Cora");
        CraftspersonGameTests.catchUp(level, settler, 4000L);

        helper.assertTrue(ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.STONE_PICKAXE)) == 0, "Not delivered by itself");
        helper.assertTrue(ClerkStorage.stock(level, craft, false).count(stack -> stack.is(Items.STONE_PICKAXE)) == 1, "In its drop-off");
        List<DeliveryTask> results = settlement.deliveries().stream().filter(task -> task.result().isPresent()).toList();
        helper.assertTrue(results.size() == 1 && results.getFirst().source().equals(craft)
                && results.getFirst().result().orElseThrow().clerk().equals(clerk), "A result delivery for the Clerk: " + results);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "Request done: " + Requests.byPriority(settlement));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(settlement).isEmpty(), "In delivery: no second request " + Requests.byPriority(settlement));
        JobAssignments.unassign(data, clerk);
        settler.discard();
        helper.succeed();
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Registers a JobBlock without a block in the world (pure settlement rules). */
    private static BlockPos place(GameTestHelper helper, BlockPos relative, Job job) {
        BlockPos pos = helper.absolutePos(relative);
        SettlementManager.place(SettlementData.get(helper.getLevel()), pos, job, 1, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        return pos;
    }

    private static void remove(GameTestHelper helper, BlockPos... positions) {
        for (BlockPos pos : positions) {
            SettlementManager.remove(SettlementData.get(helper.getLevel()), pos, SettlementManager.Listener.NONE);
        }
    }

    private static SettlementBlock block(GameTestHelper helper, BlockPos pos) {
        return JobBlockSite.blockAt(helper.getLevel(), pos).orElseThrow();
    }

    static void deliveryman(GameTestHelper helper, BlockPos clerk, UUID settler) {
        SettlementData data = SettlementData.get(helper.getLevel());
        int settlement = data.settlementAt(clerk).orElseThrow().id();
        helper.assertTrue(JobAssignments.assign(data, clerk, settler, "Dora", settlement, List.of(), ModJobs.DELIVERYMAN.get())
                == JobAssignments.AssignResult.OK, "Deliveryman assigned");
    }

    /** The Craftsperson delivers itself by default; the toggle switches to Deliveryman and back. */
    private static void toggleDefault(GameTestHelper helper) {
        SettlementData data = SettlementData.get(helper.getLevel());
        BlockPos craft = place(helper, new BlockPos(1, 1, 1), ModJobs.CRAFTSPERSON.get());
        helper.assertFalse(block(helper, craft).byDeliveryman(), "Default: Self");
        helper.assertTrue(JobAssignments.toggleDelivery(data, craft), "Toggled: Deliveryman");
        helper.assertTrue(block(helper, craft).byDeliveryman(), "Stored");
        helper.assertFalse(JobAssignments.toggleDelivery(data, craft), "Toggled back: Self");
        remove(helper, craft);
        helper.succeed();
    }

    /**
     * A result goes straight to the requesting Clerk when the producer's Warehouse serves it; otherwise into the
     * producer's Home Clerk, from where the requesting Clerk's Warehouse fetches it. It counts as in delivery until it
     * arrives.
     */
    private static void routing(GameTestHelper helper) {
        SettlementData data = SettlementData.get(helper.getLevel());
        BlockPos home = place(helper, new BlockPos(1, 1, 1), ModJobs.CLERK.get());
        BlockPos craft = place(helper, new BlockPos(3, 1, 1), ModJobs.CRAFTSPERSON.get());
        BlockPos served = place(helper, new BlockPos(1, 1, 5), ModJobs.CLERK.get());
        BlockPos other = place(helper, new BlockPos(12, 1, 1), ModJobs.CLERK.get());
        deliveryman(helper, home, UUID.randomUUID());
        deliveryman(helper, other, UUID.randomUUID());
        helper.assertTrue(Warehouses.link(data, home, served) == Warehouses.LinkResult.LINKED, "Served Clerk linked");
        Settlement settlement = data.settlementAt(home).orElseThrow();
        helper.assertTrue(block(helper, craft).homeClerk().equals(Optional.of(home)), "The Craftsperson's Home Clerk");
        long day = 2L;
        long now = 5000L;

        // Same Warehouse: straight to the requesting Clerk; two results of the same kind share one task.
        Deliveries.postResult(data, settlement, craft, craft, Items.STONE_PICKAXE, 2, served, now);
        Deliveries.postResult(data, settlement, craft, craft, Items.STONE_PICKAXE, 1, served, now);
        List<DeliveryTask> results = settlement.deliveries().stream().filter(task -> task.result().isPresent()).toList();
        helper.assertTrue(results.size() == 1 && results.getFirst().result().orElseThrow().count() == 3, "One task for 3: " + results);
        DeliveryTask direct = results.getFirst();
        helper.assertTrue(Deliveries.destination(settlement, direct).equals(Optional.of(served)), "Straight to the Served Clerk");
        helper.assertTrue(Deliveries.inDelivery(settlement, served, Items.STONE_PICKAXE) == 3, "In delivery for the Served Clerk");
        Deliveries.done(data, settlement, direct.id());

        // Other Warehouse: into the Home Clerk first.
        Deliveries.postResult(data, settlement, craft, craft, Items.STONE_PICKAXE, 4, other, now);
        DeliveryTask task = settlement.deliveries().stream().filter(found -> found.result().isPresent()).findFirst().orElseThrow();
        helper.assertTrue(Deliveries.destination(settlement, task).equals(Optional.of(home)), "Relayed through the Home Clerk");
        helper.assertTrue(Deliveries.warehouseOf(settlement, task).map(SettlementBlock::pos).equals(Optional.of(home)), "The producer's Warehouse");
        helper.assertTrue(Deliveries.pick(settlement, block(helper, other), other, now, day, Set.of()).isEmpty(), "Not yet for the other Warehouse");
        helper.assertTrue(Deliveries.pick(settlement, block(helper, home), home, now, day, Set.of()).isPresent(), "The producer's Warehouse picks it");

        UUID dora = UUID.randomUUID();
        Deliveries.take(data, settlement, task.id(), dora, "Dora", day);
        Deliveries.carry(data, settlement, task.id(), dora, 4);
        Deliveries.releaseAll(data, dora);
        helper.assertTrue(Deliveries.pick(settlement, block(helper, home), home, now, day, Set.of()).isEmpty(), "A carried task is nobody else's");
        helper.assertTrue(Deliveries.inDelivery(settlement, other, Items.STONE_PICKAXE) == 4, "Still in delivery while carried");
        Deliveries.arrived(data, settlement, dora, home);
        DeliveryTask relayed = Deliveries.get(settlement, task.id()).orElseThrow();
        helper.assertTrue(relayed.source().equals(home) && relayed.result().orElseThrow().relayed(), "Waits in the Home Clerk: " + relayed);
        helper.assertTrue(Deliveries.warehouseOf(settlement, relayed).map(SettlementBlock::pos).equals(Optional.of(other)),
                "Now the requesting Clerk's Warehouse fetches it");
        helper.assertTrue(Deliveries.destination(settlement, relayed).equals(Optional.of(other)), "Its destination is the requesting Clerk");
        helper.assertTrue(Deliveries.pick(settlement, block(helper, other), other, now, day, Set.of()).isPresent(), "Picked by the other Warehouse");

        UUID eve = UUID.randomUUID();
        Deliveries.take(data, settlement, relayed.id(), eve, "Eve", day);
        Deliveries.carry(data, settlement, relayed.id(), eve, 3);
        helper.assertTrue(Deliveries.inDelivery(settlement, other, Items.STONE_PICKAXE) == 3, "A shortfall at pickup shrinks the task");
        Deliveries.arrived(data, settlement, eve, other);
        helper.assertTrue(Deliveries.get(settlement, relayed.id()).isEmpty() && Deliveries.inDelivery(settlement, other, Items.STONE_PICKAXE) == 0,
                "Arrived: done");

        // A removed requesting Clerk drops its result deliveries.
        Deliveries.postResult(data, settlement, craft, craft, Items.STONE_PICKAXE, 1, other, now);
        remove(helper, other);
        helper.assertTrue(settlement.deliveries().stream().noneMatch(found -> found.result().isPresent()), "Gone with the requesting Clerk");
        remove(helper, home, craft, served);
        helper.succeed();
    }
}
