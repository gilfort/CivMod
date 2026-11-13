package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.item.DeliverySlipItem;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliverySlip;
import com.gilfort.civmod.logistics.DeliverySlip.Variant;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.SlipPlan;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.menu.DeliveryOrdersTab;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.DeliveryOrders;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.SlipOrders;
import com.gilfort.civmod.settlement.TestAssignments;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for Delivery Slips and the Delivery Orders tab (#240): writing a slip, what a visit moves for each variant,
 * the cooldown, the slip tasks in the shared pool, the tab's slots and a Pantry kept stocked by a slip.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class DeliverySlipGameTests {

    private static final long NOW = 1000L;

    private DeliverySlipGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "delivery_slip_writing", DeliverySlipGameTests::writing);
            register(helper, "delivery_slip_plan", DeliverySlipGameTests::plan);
            register(helper, "delivery_slip_variants", DeliverySlipGameTests::variants);
            register(helper, "delivery_slip_cooldown", DeliverySlipGameTests::cooldown);
            register(helper, "delivery_slip_tasks", DeliverySlipGameTests::tasks);
            register(helper, "delivery_slip_orders_tab", DeliverySlipGameTests::ordersTab);
            register(helper, "delivery_slip_pantry", DeliverySlipGameTests::pantry);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- helpers

    private static BlockPos chest(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.CHEST);
        return helper.absolutePos(new BlockPos(x, 1, z));
    }

    private static Container container(GameTestHelper helper, BlockPos pos) {
        return (Container) helper.getLevel().getBlockEntity(pos);
    }

    private static Stock stock(GameTestHelper helper, BlockPos pos) {
        return ClerkStorage.stock(helper.getLevel(), pos, true);
    }

    /** Registers a JobBlock without a block in the world (pure settlement rules). */
    private static BlockPos place(GameTestHelper helper, BlockPos relative, Job job) {
        BlockPos pos = helper.absolutePos(relative);
        TestSettlements.place(helper, pos, job, 1);
        return pos;
    }

    private static SettlementBlock block(GameTestHelper helper, BlockPos pos) {
        return JobBlockSite.blockAt(helper.getLevel(), pos).orElseThrow();
    }

    private static ItemStack slipItem(DeliverySlip slip) {
        ItemStack stack = new ItemStack(ModItems.DELIVERY_SLIP.get());
        stack.set(ModDataComponents.DELIVERY_SLIP.get(), slip);
        return stack;
    }

    private static DeliverySlip slip(BlockPos source, List<BlockPos> targets, List<Item> filter, Variant variant, int fillTo, int keep) {
        return new DeliverySlip(UUID.randomUUID(), Optional.of(source), targets, false, filter, variant, fillTo, keep, DeliverySlip.Status.OK);
    }

    private static SlipPlan plan(GameTestHelper helper, DeliverySlip slip, int next, int carryStacks) {
        return SlipPlan.of(slip, new DeliveryOrders.Clock(0L, 0, next), stock(helper, slip.source().orElseThrow()), pos -> stock(helper, pos), carryStacks,
                helper.getLevel().getRandom());
    }

    private static int count(Map<Item, Integer> items, Item item) {
        return items.getOrDefault(item, 0);
    }

    // ---------------------------------------------------------------- writing a slip

    /**
     * A plain slip is no order yet. Shift-click sets the one source, a click adds a target and the next click removes it,
     * the source is no target, a block without an inventory is refused. The order survives saving and the filled slip is
     * recognised.
     */
    private static void writing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos a = chest(helper, 3, 1);
        BlockPos b = chest(helper, 5, 1);
        helper.setBlock(new BlockPos(7, 1, 1), Blocks.STONE);
        BlockPos stone = helper.absolutePos(new BlockPos(7, 1, 1));

        ItemStack stack = new ItemStack(ModItems.DELIVERY_SLIP.get());
        helper.assertTrue(stack.get(ModDataComponents.DELIVERY_SLIP.get()) == null && DeliveryOrders.slip(stack).isEmpty(), "A plain slip is no order");
        helper.assertTrue(DeliverySlipItem.setSource(level, stack, a) == DeliverySlipItem.Result.SOURCE_SET, "The source is set");
        helper.assertTrue(DeliveryOrders.slip(stack).isEmpty(), "A source alone is not filled");
        helper.assertTrue(DeliverySlipItem.toggleTarget(level, stack, b) == DeliverySlipItem.Result.TARGET_ADDED, "A target is added");
        helper.assertTrue(DeliveryOrders.slip(stack).isPresent(), "A source and a target: filled");
        helper.assertTrue(DeliverySlipItem.toggleTarget(level, stack, b) == DeliverySlipItem.Result.TARGET_REMOVED, "The same click again removes it");
        helper.assertTrue(DeliverySlipItem.toggleTarget(level, stack, a) == DeliverySlipItem.Result.IS_SOURCE, "The source is no target");
        helper.assertTrue(DeliverySlipItem.toggleTarget(level, stack, stone) == DeliverySlipItem.Result.NOT_A_CONTAINER, "Stone has no inventory");
        helper.assertTrue(DeliverySlipItem.toggleTarget(level, stack, clerk) == DeliverySlipItem.Result.TARGET_ADDED, "A Clerk JobBlock is a target");
        helper.assertTrue(DeliverySlipItem.toggleTarget(level, stack, b) == DeliverySlipItem.Result.TARGET_ADDED, "Any number of targets");
        helper.assertTrue(DeliverySlipItem.setSource(level, stack, b) == DeliverySlipItem.Result.SOURCE_SET, "A new source replaces the old one");
        DeliverySlip written = stack.get(ModDataComponents.DELIVERY_SLIP.get());
        helper.assertTrue(written.source().equals(Optional.of(b)) && written.targets().equals(List.of(clerk)), "One source, the new source left the targets: " + written);

        DeliverySlip full = new DeliverySlip(UUID.randomUUID(), Optional.of(a), List.of(b, clerk), true, List.of(Items.BREAD, Items.STONE), Variant.ROUND_ROBIN, 64, 8,
                DeliverySlip.Status.TARGET_MISSING);
        DeliverySlip loaded = DeliverySlip.CODEC.parse(NbtOps.INSTANCE, DeliverySlip.CODEC.encodeStart(NbtOps.INSTANCE, full).getOrThrow()).getOrThrow();
        helper.assertTrue(loaded.equals(full), "The slip keeps its data on the item: " + loaded);
        helper.assertTrue(!full.accepts(new ItemStack(Items.BREAD)) && full.accepts(new ItemStack(Items.DIRT)), "A blacklist lets everything else through");
        helper.assertTrue(!full.withBlacklistToggled().accepts(new ItemStack(Items.DIRT)) && full.withBlacklistToggled().accepts(new ItemStack(Items.STONE)),
                "A whitelist lets only the listed items through");
        helper.succeed();
    }

    // ---------------------------------------------------------------- what a visit moves

    /**
     * The filter, "keep at the source", "fill target up to" and the carry capacity decide what is taken; a target that
     * takes less gets less and the rest goes to the next one.
     */
    private static void plan(GameTestHelper helper) {
        BlockPos source = chest(helper, 1, 3);
        BlockPos near = chest(helper, 3, 3);
        BlockPos mid = chest(helper, 5, 3);
        BlockPos far = chest(helper, 7, 3);
        Container sourceChest = container(helper, source);
        sourceChest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        sourceChest.setItem(1, new ItemStack(Items.COBBLESTONE, 36));
        sourceChest.setItem(2, new ItemStack(Items.STONE, 10));
        List<BlockPos> targets = List.of(far, near, mid);

        SlipPlan all = plan(helper, slip(source, targets, List.of(Items.COBBLESTONE), Variant.NEAREST, 0, 0), 0, 9);
        helper.assertTrue(all.legs().size() == 1 && all.legs().get(0).target().equals(near), "Nearest first: the nearest target takes it all");
        helper.assertTrue(count(all.take(), Items.COBBLESTONE) == 100 && count(all.take(), Items.STONE) == 0, "The whitelist takes only cobblestone: " + all.take());

        SlipPlan one = plan(helper, slip(source, targets, List.of(Items.COBBLESTONE), Variant.NEAREST, 0, 0), 0, 1);
        helper.assertTrue(count(one.take(), Items.COBBLESTONE) == 64, "One carry stack: 64 items, " + one.take());

        SlipPlan kept = plan(helper, slip(source, targets, List.of(Items.COBBLESTONE), Variant.NEAREST, 0, 30), 0, 9);
        helper.assertTrue(count(kept.take(), Items.COBBLESTONE) == 70, "Keep 30 at the source: " + kept.take());

        DeliverySlip blacklist = new DeliverySlip(UUID.randomUUID(), Optional.of(source), targets, true, List.of(Items.COBBLESTONE), Variant.NEAREST, 0, 0,
                DeliverySlip.Status.OK);
        SlipPlan others = plan(helper, blacklist, 0, 9);
        helper.assertTrue(count(others.take(), Items.STONE) == 10 && count(others.take(), Items.COBBLESTONE) == 0, "A blacklist takes the other items: " + others.take());

        SlipPlan furthest = plan(helper, slip(source, targets, List.of(Items.COBBLESTONE), Variant.FURTHEST, 0, 0), 0, 9);
        helper.assertTrue(furthest.legs().get(0).target().equals(far), "Furthest first");

        container(helper, near).setItem(0, new ItemStack(Items.COBBLESTONE, 60));
        SlipPlan fill = plan(helper, slip(source, targets, List.of(Items.COBBLESTONE), Variant.NEAREST, 64, 0), 0, 9);
        helper.assertTrue(fill.legs().size() == 3 && count(fill.legs().get(0).items(), Items.COBBLESTONE) == 4
                && fill.legs().get(1).target().equals(mid) && count(fill.legs().get(1).items(), Items.COBBLESTONE) == 64
                && fill.legs().get(2).target().equals(far) && count(fill.legs().get(2).items(), Items.COBBLESTONE) == 32,
                "Fill up to 64: the nearest target gets 4 of 100, the next 64, the last 32: " + fill.legs());

        container(helper, near).setItem(0, ItemStack.EMPTY);
        container(helper, mid).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        SlipPlan full = plan(helper, slip(source, targets, List.of(Items.COBBLESTONE), Variant.NEAREST, 64, 0), 0, 9);
        helper.assertTrue(full.legs().size() == 2 && count(full.take(), Items.COBBLESTONE) == 100, "A target at the limit gets nothing: " + full.legs());

        BlockPos lonely = chest(helper, 9, 3);
        SlipPlan none = plan(helper, slip(lonely, List.of(near), List.of(Items.COBBLESTONE), Variant.NEAREST, 0, 0), 0, 9);
        helper.assertTrue(none.empty() && none.take().isEmpty(), "An empty source: nothing to move");
        helper.succeed();
    }

    /**
     * Round robin goes on with the target after the last one and skips a full target; forced round robin waits for the
     * next target even when others have space; random and the rest fill the targets they reach.
     */
    private static void variants(GameTestHelper helper) {
        BlockPos source = chest(helper, 1, 3);
        BlockPos first = chest(helper, 3, 3);
        BlockPos second = chest(helper, 5, 3);
        BlockPos third = chest(helper, 7, 3);
        container(helper, source).setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        List<BlockPos> targets = List.of(first, second, third);
        List<Item> filter = List.of(Items.COBBLESTONE);

        SlipPlan start = plan(helper, slip(source, targets, filter, Variant.ROUND_ROBIN, 0, 0), 0, 9);
        helper.assertTrue(start.legs().size() == 1 && start.legs().get(0).target().equals(first) && start.next() == 1, "Round robin starts at the first target, one per visit");
        SlipPlan next = plan(helper, slip(source, targets, filter, Variant.ROUND_ROBIN, 0, 0), start.next(), 9);
        helper.assertTrue(next.legs().get(0).target().equals(second) && next.next() == 2, "The next visit serves the second target");
        SlipPlan wrap = plan(helper, slip(source, targets, filter, Variant.ROUND_ROBIN, 0, 0), 2, 9);
        helper.assertTrue(wrap.legs().get(0).target().equals(third) && wrap.next() == 0, "After the last target it starts over");

        Container secondChest = container(helper, second);
        for (int slot = 0; slot < secondChest.getContainerSize(); slot++) {
            secondChest.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        SlipPlan skipped = plan(helper, slip(source, targets, filter, Variant.ROUND_ROBIN, 0, 0), 1, 9);
        helper.assertTrue(skipped.legs().size() == 1 && skipped.legs().get(0).target().equals(third) && skipped.next() == 0,
                "Round robin skips the full target: " + skipped.legs());

        SlipPlan forced = plan(helper, slip(source, targets, filter, Variant.FORCED_ROUND_ROBIN, 0, 0), 1, 9);
        helper.assertTrue(forced.empty() && forced.next() == 1, "Forced round robin waits for the full target: " + forced.legs());
        SlipPlan forcedFree = plan(helper, slip(source, targets, filter, Variant.FORCED_ROUND_ROBIN, 0, 0), 2, 9);
        helper.assertTrue(forcedFree.legs().get(0).target().equals(third) && forcedFree.next() == 0, "Forced round robin serves the next target when it has room");

        SlipPlan random = plan(helper, slip(source, targets, filter, Variant.RANDOM, 0, 0), 0, 9);
        helper.assertTrue(random.legs().size() == 1 && count(random.take(), Items.COBBLESTONE) == 10, "Random: everything goes to a target with room: " + random.legs());
        helper.succeed();
    }

    // ---------------------------------------------------------------- cooldown

    /**
     * 30 s after each visit; three empty visits in a row raise it to 60 s, three more to 120 s (the cap); a visit that
     * moves items starts over.
     */
    private static void cooldown(GameTestHelper helper) {
        SettlementData data = SettlementData.get(helper.getLevel());
        BlockPos clerk = place(helper, new BlockPos(1, 1, 1), ModJobs.CLERK.get());
        SettlementBlock block = block(helper, clerk);
        UUID id = UUID.randomUUID();
        long[] seconds = {30, 30, 60, 60, 60, 120, 120, 120};
        for (int visit = 0; visit < seconds.length; visit++) {
            SlipOrders.visited(data, block, id, NOW, false, 30, 0);
            long rest = (block.orders().clock(id).readyAt() - NOW) / 20L;
            helper.assertTrue(rest == seconds[visit], "Empty visit " + (visit + 1) + ": " + seconds[visit] + " s, got " + rest);
        }
        SlipOrders.visited(data, block, id, NOW, true, 30, 2);
        DeliveryOrders.Clock reset = block.orders().clock(id);
        helper.assertTrue((reset.readyAt() - NOW) / 20L == 30 && reset.emptyVisits() == 0 && reset.next() == 2, "A visit that moves items starts over: " + reset);
        SlipOrders.visited(data, block, id, NOW, false, 10, 2);
        helper.assertTrue((block.orders().clock(id).readyAt() - NOW) / 20L == 10, "The base value is a config entry");
        helper.succeed();
    }

    // ---------------------------------------------------------------- tasks in the pool

    /**
     * A filled slip makes one task of the shared pool unless it rests or a container is gone; slip tasks rank above
     * automatic tasks and below urgent ones, the slot order decides among slips; with "Auto" off only slips are taken; a
     * slip trip is a trip of its own; a slip taken out loses its task and its clock.
     */
    private static void tasks(GameTestHelper helper) {
        SettlementData data = SettlementData.get(helper.getLevel());
        BlockPos warehouse = place(helper, new BlockPos(1, 1, 1), ModJobs.CLERK.get());
        BlockPos lumberjack = place(helper, new BlockPos(3, 1, 1), ModJobs.LUMBERJACK.get());
        int settlementId = data.settlementAt(warehouse).orElseThrow().id();
        helper.assertTrue(TestAssignments.assign(data, warehouse, UUID.randomUUID(), "Dora", settlementId, List.of(), ModJobs.DELIVERYMAN.get())
                == JobAssignments.AssignResult.OK, "Deliveryman assigned");
        Settlement settlement = data.settlementAt(warehouse).orElseThrow();
        SettlementBlock clerk = block(helper, warehouse);
        BlockPos sourceA = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos sourceB = helper.absolutePos(new BlockPos(7, 1, 1));
        BlockPos target = helper.absolutePos(new BlockPos(9, 1, 1));
        DeliverySlip late = slip(sourceA, List.of(target), List.of(Items.BREAD), Variant.NEAREST, 0, 0);
        DeliverySlip early = slip(sourceB, List.of(target), List.of(Items.BREAD), Variant.NEAREST, 0, 0);
        clerk.orders().setSlip(5, slipItem(late));
        clerk.orders().setSlip(2, slipItem(early));

        SlipOrders.sync(data, settlement, clerk, NOW, pos -> false);
        List<DeliveryTask> slipTasks = settlement.deliveries().stream().filter(task -> task.slip().isPresent()).toList();
        helper.assertTrue(slipTasks.size() == 2 && slipTasks.stream().allMatch(task -> task.jobBlock().equals(warehouse)), "One task per filled slip: " + slipTasks);
        SlipOrders.sync(data, settlement, clerk, NOW, pos -> false);
        helper.assertTrue(settlement.deliveries().size() == 2, "Syncing again creates no second task");

        SettlementBlock lumber = block(helper, lumberjack);
        helper.assertTrue(lumber.homeClerk().equals(Optional.of(warehouse)), "The Lumberjack belongs to the Warehouse");
        Deliveries.request(data, settlement, helper.absolutePos(new BlockPos(3, 1, 3)), lumberjack, 0L, false);
        Optional<DeliveryTask> picked = Deliveries.pick(settlement, clerk, warehouse, NOW + 20L * 3600, 0L, Set.of());
        helper.assertTrue(picked.flatMap(DeliveryTask::slip).map(DeliveryTask.SlipRef::slot).equals(Optional.of(2)),
                "A slip beats an automatic task that waited an hour, the lower slot first: " + picked);
        Deliveries.request(data, settlement, helper.absolutePos(new BlockPos(3, 1, 5)), lumberjack, NOW, true);
        Optional<DeliveryTask> urgent = Deliveries.pick(settlement, clerk, warehouse, NOW, 0L, Set.of());
        helper.assertTrue(urgent.filter(task -> task.slip().isEmpty() && task.urgent()).isPresent(), "An urgent task beats a slip: " + urgent);
        Optional<DeliveryTask> slipsOnly = Deliveries.pick(settlement, clerk, warehouse, NOW, 0L, Set.of(), Deliveries.Scope.SLIPS_ONLY);
        helper.assertTrue(slipsOnly.flatMap(DeliveryTask::slip).map(DeliveryTask.SlipRef::slot).equals(Optional.of(2)), "Auto off: slips only");
        Optional<DeliveryTask> automatic = Deliveries.pick(settlement, clerk, warehouse, NOW, 0L, Set.of(), Deliveries.Scope.AUTOMATIC_ONLY);
        helper.assertTrue(automatic.filter(task -> task.slip().isEmpty()).isPresent(), "Catch-up: automatic tasks only");
        helper.assertTrue(Deliveries.batch(settlement, clerk, slipsOnly.orElseThrow(), 9, task -> 1, 0L, Set.of()).size() == 1, "A slip is a trip of its own");
        helper.assertTrue(Deliveries.destination(settlement, slipsOnly.orElseThrow()).isEmpty(), "Its targets are worked out at the source");
        helper.assertTrue(Deliveries.get(settlement, sourceB).isEmpty(), "A slip task is no pickup of its source");

        SlipOrders.visited(data, clerk, early.id(), NOW, false, 30, 0);
        SlipOrders.sync(data, settlement, clerk, NOW + 1, pos -> false);
        helper.assertTrue(settlement.deliveries().stream().filter(task -> task.slip().isPresent()).count() == 1, "A resting slip has no task");
        SlipOrders.sync(data, settlement, clerk, NOW + 20L * 30, pos -> false);
        helper.assertTrue(settlement.deliveries().stream().filter(task -> task.slip().isPresent()).count() == 2, "After the cooldown the task is back");

        SlipOrders.sync(data, settlement, clerk, NOW + 20L * 30, pos -> pos.equals(sourceA));
        helper.assertTrue(DeliveryOrders.slip(clerk.orders().slip(5)).orElseThrow().status() == DeliverySlip.Status.SOURCE_MISSING, "Source missing");
        helper.assertTrue(settlement.deliveries().stream().filter(task -> task.slip().isPresent()).count() == 1, "A slip with a missing source is skipped");
        SlipOrders.sync(data, settlement, clerk, NOW + 20L * 30, pos -> pos.equals(target));
        helper.assertTrue(DeliveryOrders.slip(clerk.orders().slip(2)).orElseThrow().status() == DeliverySlip.Status.TARGET_MISSING, "Target missing");
        helper.assertTrue(settlement.deliveries().stream().noneMatch(task -> task.slip().isPresent()), "Both skipped, the slips stay in their slots");
        helper.assertTrue(DeliveryOrders.slip(clerk.orders().slip(5)).isPresent(), "The slip stays");
        SlipOrders.sync(data, settlement, clerk, NOW + 20L * 30, pos -> false);
        helper.assertTrue(DeliveryOrders.slip(clerk.orders().slip(5)).orElseThrow().status() == DeliverySlip.Status.OK
                && settlement.deliveries().stream().filter(task -> task.slip().isPresent()).count() == 2, "The container is back: the slip works again");

        clerk.orders().setSlip(5, ItemStack.EMPTY);
        SlipOrders.sync(data, settlement, clerk, NOW + 20L * 30, pos -> false);
        helper.assertTrue(settlement.deliveries().stream().filter(task -> task.slip().isPresent()).count() == 1, "A slip taken out loses its task");
        helper.assertTrue(clerk.orders().slipIds().equals(List.of(early.id())), "Only the slip in the slot is left");
        helper.succeed();
    }

    // ---------------------------------------------------------------- the tab

    /**
     * The Clerk's Delivery Orders tab has {@code (level + Ledgers) x 9} slots, takes filled slips only, keeps them in the
     * settlement data and posts their tasks; "Auto" is on by default.
     */
    private static void ordersTab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        BlockPos clerkPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos a = chest(helper, 3, 1);
        BlockPos b = chest(helper, 5, 1);
        SettlementBlock clerk = block(helper, clerkPos);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        JobBlockMenu menu = new JobBlockMenu(1, player.getInventory(), (JobBlockEntity) level.getBlockEntity(clerkPos),
                JobBlockInfo.of(ModJobs.CLERK.get(), 1));
        DeliveryOrdersTab tab = menu.tab(DeliveryOrdersTab.class).orElseThrow();
        menu.setActiveTab(tab.index());
        List<Slot> slots = menu.slots.stream().filter(slot -> slot.container.getContainerSize() == DeliveryOrders.MAX_SLOTS).toList();
        helper.assertTrue(slots.size() == DeliveryOrders.MAX_SLOTS && slots.stream().filter(Slot::isActive).count() == 9 && tab.slots() == 9,
                "Level 1: 9 slots in use, got " + slots.stream().filter(Slot::isActive).count());
        helper.assertTrue(tab.auto(), "Auto is on by default");

        ItemStack filled = slipItem(slip(a, List.of(b), List.of(Items.BREAD), Variant.NEAREST, 0, 0));
        helper.assertTrue(!slots.get(0).mayPlace(new ItemStack(ModItems.DELIVERY_SLIP.get())), "A plain slip does not go in");
        helper.assertTrue(!slots.get(0).mayPlace(new ItemStack(Items.PAPER)), "Neither does anything else");
        helper.assertTrue(slots.get(0).mayPlace(filled) && !slots.get(9).mayPlace(filled), "A filled slip goes into the slots in use only");
        slots.get(0).set(filled);
        helper.assertTrue(DeliveryOrders.slip(clerk.orders().slip(0)).isPresent(), "The slip is kept in the Clerk's settlement data");
        Settlement settlement = SettlementData.get(level).settlementAt(clerkPos).orElseThrow();
        helper.assertTrue(settlement.deliveries().stream().anyMatch(task -> task.slip().isPresent()), "Putting it in posts its task");
        helper.assertTrue(slots.get(0).getMaxStackSize() == 1, "One slip per slot");

        SettlementWorld.setLedgers(level, clerkPos, 2);
        menu.broadcastChanges();
        helper.assertTrue(tab.slots() == 27 && slots.stream().filter(Slot::isActive).count() == 27, "Level 1 and 2 Ledgers: 27 slots");
        slots.get(0).set(ItemStack.EMPTY);
        helper.assertTrue(settlement.deliveries().stream().noneMatch(task -> task.slip().isPresent()), "Taking it out takes the task away");
        helper.succeed();
    }

    // ---------------------------------------------------------------- the example

    /**
     * "Clerk to Pantry, filter food, fill up to 64": the Deliveryman carries food from the Clerk's storage into the Pantry
     * and the Tavern Host serves a hungry settler from it.
     */
    private static void pantry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<long[]> forced = LumberjackGameTests.forceChunks(helper);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos clerkPos = ClerkGameTests.clerkWithStorage(helper);
        Container storage = container(helper, helper.absolutePos(new BlockPos(4, 1, 1)));
        storage.setItem(0, new ItemStack(Items.COOKED_BEEF, 64));
        storage.setItem(1, new ItemStack(Items.COOKED_BEEF, 36));
        storage.setItem(2, new ItemStack(Items.COBBLESTONE, 64));
        TestSettlements.setBlock(helper, new BlockPos(1, 1, 7), ModBlocks.PANTRY_BLOCK.get());
        BlockPos pantryPos = helper.absolutePos(new BlockPos(1, 1, 7));
        Container pantry = container(helper, pantryPos);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerkPos).orElseThrow();
        // The Clerk is the source, food the filter: the cobblestone stays.
        block(helper, clerkPos).orders().setSlip(0, slipItem(slip(clerkPos, List.of(pantryPos), List.of(Items.COOKED_BEEF), Variant.NEAREST, 64, 0)));

        Settler dora = ClerkGameTests.settler(helper, new BlockPos(2, 1, 4), data, settlement);
        dora.setNoAi(false);
        TestAssignments.assign(data, clerkPos, dora.getUUID(), "Dora", settlement.id(), List.of(), ModJobs.DELIVERYMAN.get());
        dora.addJob(new SettlerJob(ModJobs.DELIVERYMAN.get(), clerkPos));
        Settler host = ClerkGameTests.settler(helper, new BlockPos(1, 1, 5), data, settlement);
        TestAssignments.assign(data, pantryPos, host.getUUID(), "Host", settlement.id(), List.of());
        host.addJob(new SettlerJob(ModJobs.TAVERN_HOST.get(), pantryPos));
        Settler customer = ClerkGameTests.settler(helper, new BlockPos(7, 1, 7), data, settlement);
        customer.setNoAi(false);
        customer.getFood().setLevel(2);
        customer.getFood().setSaturation(0.0F);
        helper.succeedWhen(() -> {
            int inStorage = 0;
            for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                inStorage += storage.getItem(slot).is(Items.COOKED_BEEF) ? storage.getItem(slot).getCount() : 0;
            }
            helper.assertTrue(inStorage < 100, "The Deliveryman has not been at the Clerk yet (" + dora.getWork().statusText(clerkPos).getString() + ")");
            helper.assertTrue(customer.getFood().level() >= 11, "The Tavern Host has not served the customer yet, food level " + customer.getFood().level()
                    + " (" + customer.statusText().getString() + ", pantry " + pantry.getItem(0) + ")");
            helper.assertTrue(storage.getItem(2).is(Items.COBBLESTONE), "The filter left the cobblestone alone");
            TestAssignments.unassign(data, clerkPos);
            TestAssignments.unassign(data, pantryPos);
            customer.discard();
            host.discard();
            dora.discard();
            forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
        });
    }
}
