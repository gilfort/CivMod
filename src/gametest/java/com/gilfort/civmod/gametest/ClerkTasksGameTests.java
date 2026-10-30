package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.Pickups;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.menu.ClerkTabData.TaskRow;
import com.gilfort.civmod.menu.ClerkTabData.TaskStatus;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.Warehouses;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Clerk's Tasks tab (#142, vault {@code Jobs/Clerk.md}): one row per request and Delivery task, blocked
 * first, then in progress, then waiting; a Warehouse lists the tasks of every Clerk it serves, a Served Clerk only its own.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClerkTasksGameTests {

    private static final UUID CARL = UUID.fromString("00000000-0000-0000-0000-0000000001c3");
    private static final UUID DORA = UUID.fromString("00000000-0000-0000-0000-0000000001d0");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-0000000001c5");

    private ClerkTasksGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "clerk_tasks_order_and_status", ClerkTasksGameTests::orderAndStatus);
            register(helper, "clerk_tasks_warehouse", ClerkTasksGameTests::warehouse);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * Requests and pickups share one list: a request nobody can make and a pickup without Deliveryman are blocked and come
     * first, a claimed request and a taken pickup are in progress; icons are the requested item and the first waiting
     * item; the tooltip carries the details.
     */
    private static void orderAndStatus(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        StockRules.setRule(data, clerk, StockRule.minimum(0, Items.TORCH, 5, 3));
        StockRules.setRule(data, clerk, StockRule.minimum(1, Items.STONE_PICKAXE, 8, 0));
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        Request torches = Requests.forRule(settlement, clerk, 0).orElseThrow();
        helper.assertTrue(Requests.claim(data, settlement, torches.id(), CARL, "Carl", 5) == 5, "Carl claims the torches");

        List<TaskRow> tasks = ClerkTabData.of(level, clerk).tasks();
        helper.assertValueEqual(statuses(tasks), List.of(TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS), "Blocked before in progress (priority aside)");
        helper.assertValueEqual(icons(tasks), List.of(Items.STONE_PICKAXE, Items.TORCH), "Icon: the requested item");
        helper.assertValueEqual(key(tasks.get(0).text()), "civmod.gui.clerk.task.request.open", "Unclaimed: \"8 Stone Pickaxe\"");
        helper.assertValueEqual(key(tasks.get(1).text()), "civmod.gui.clerk.task.request", "Claimed: \"Carl: 5 Torch\"");
        helper.assertValueEqual(arg(tasks.get(1).text(), 0), "Carl", "Named after its producer");
        helper.assertTrue(keys(tasks.get(0).details()).contains("civmod.gui.clerk.request.no_producer"), "Reason in the tooltip");
        helper.assertTrue(keys(tasks.get(1).details()).contains("civmod.gui.clerk.request.crafting"), "Producer in the tooltip");

        // A Lumberjack drop-off with a stack of logs: a pickup to this Clerk, without Deliveryman blocked.
        helper.setBlock(new BlockPos(7, 1, 1), ModBlocks.LUMBERJACK_BLOCK.get());
        helper.setBlock(new BlockPos(9, 1, 1), Blocks.CHEST);
        BlockPos lumberjack = helper.absolutePos(new BlockPos(7, 1, 1));
        BlockPos dropOff = helper.absolutePos(new BlockPos(9, 1, 1));
        JobAssignments.cyclePoint(data, lumberjack, dropOff, false, true);
        helper.assertTrue(JobAssignments.cyclePoint(data, lumberjack, dropOff, false, true) == JobAssignments.PointResult.DROP_OFF, "Drop-off");
        ((Container) level.getBlockEntity(dropOff)).setItem(0, new ItemStack(Items.OAK_LOG, 64));
        Pickups.delivered(level, JobBlockSite.at(level, lumberjack).orElseThrow(), false);
        helper.assertTrue(Deliveries.get(settlement, dropOff).isPresent(), "Pickup posted");

        tasks = ClerkTabData.of(level, clerk).tasks();
        helper.assertValueEqual(statuses(tasks), List.of(TaskStatus.BLOCKED, TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS), "The pickup is blocked");
        helper.assertValueEqual(icons(tasks), List.of(Items.STONE_PICKAXE, Items.OAK_LOG, Items.TORCH), "Requests before deliveries; icon: first item");
        helper.assertValueEqual(key(tasks.get(1).text()), "civmod.gui.clerk.delivery.pickup", "\"Lumberjack → Clerk\"");
        helper.assertTrue(keys(tasks.get(1).details()).contains("civmod.gui.clerk.delivery.no_deliveryman"), "Why it is blocked");

        Deliveries.take(data, settlement, dropOff, DORA, "Dora", DayClock.day(level));
        tasks = ClerkTabData.of(level, clerk).tasks();
        helper.assertValueEqual(statuses(tasks), List.of(TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS, TaskStatus.IN_PROGRESS), "Taken: in progress");
        helper.assertValueEqual(icons(tasks), List.of(Items.STONE_PICKAXE, Items.TORCH, Items.OAK_LOG), "Requests before deliveries");
        helper.assertTrue(tasks.get(2).details().stream().anyMatch(line -> line.getString().equals("Dora")), "Deliveryman in the tooltip");
        helper.succeed();
    }

    /** A Warehouse lists its own tasks and those of its Served Clerks (naming the Clerk); a Served Clerk only its own. */
    private static void warehouse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos warehouse = StockRuleGameTests.workingClerk(helper);
        Settlement settlement = data.settlementAt(warehouse).orElseThrow();
        helper.setBlock(new BlockPos(1, 1, 6), ModBlocks.CLERK_BLOCK.get());
        BlockPos served = helper.absolutePos(new BlockPos(1, 1, 6));
        helper.assertTrue(TestAssignments.assign(data, served, SAM, "Sam", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Served Clerk assigned");
        helper.assertTrue(Warehouses.link(data, warehouse, served) == Warehouses.LinkResult.LINKED, "Linked as Served Clerk");
        StockRules.setRule(data, warehouse, StockRule.minimum(0, Items.TORCH, 5, 0));
        StockRules.setRule(data, served, StockRule.minimum(0, Items.LANTERN, 4, 0));
        StockRules.check(level, warehouse, ClerkGameTests.MORNING);
        StockRules.check(level, served, ClerkGameTests.MORNING);

        List<TaskRow> atWarehouse = ClerkTabData.of(level, warehouse).tasks();
        helper.assertValueEqual(icons(atWarehouse), List.of(Items.TORCH, Items.LANTERN), "The Warehouse lists both Clerks' requests");
        helper.assertFalse(keys(atWarehouse.get(0).details()).contains("civmod.gui.clerk.task.for"), "Its own request names no Clerk");
        helper.assertTrue(keys(atWarehouse.get(1).details()).contains("civmod.gui.clerk.task.for"), "A Served Clerk's request names it");
        helper.assertValueEqual(icons(ClerkTabData.of(level, served).tasks()), List.of(Items.LANTERN), "The Served Clerk lists only its own");
        helper.succeed();
    }

    private static List<TaskStatus> statuses(List<TaskRow> tasks) {
        return tasks.stream().map(TaskRow::status).toList();
    }

    private static List<Item> icons(List<TaskRow> tasks) {
        return tasks.stream().map(task -> task.icon().getItem()).toList();
    }

    private static List<String> keys(List<Component> lines) {
        return lines.stream().map(ClerkTasksGameTests::key).toList();
    }

    private static String key(Component text) {
        return text.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : text.getString();
    }

    private static String arg(Component text, int index) {
        Object arg = ((TranslatableContents) text.getContents()).getArgs()[index];
        return arg instanceof Component component ? component.getString() : String.valueOf(arg);
    }
}
