package com.gilfort.civmod.settlement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.logistics.StockRule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The Clerk's stock rules (vault {@code Jobs/Clerk.md}), kept in its {@link SettlementBlock}: one rule per item with
 * Min / Max / Order ({@link StockRule}). While a Clerk settler is on shift, a rule whose stock (plus amount in delivery)
 * falls below Min opens one request; on every check it asks for {@code target stock − stock − amount being crafted −
 * amount in delivery} and is withdrawn at the target ({@link Requests}; the open request is the hysteresis, nothing else
 * is saved). Rules are checked every 30 s (staggered per Clerk, loaded Clerks only), right after a delivery into the
 * Clerk and once per simulated hour in catch-up. A check while the Clerk is not working, or of a rule inactive after a
 * downgrade, withdraws the rule's request (running claims are finished). Every check also checks the surplus above Max
 * ({@link #checkSurplus}), and so does every CivMod transaction that increased a Clerk's stock of an item with a Max
 * rule (write-through, at the end of the tick); collecting jobs ask which items are at Max at their Home Clerk
 * ({@link #atMax}).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class StockRules {

    /** Ticks between two checks of a Clerk's rules (30 s). */
    public static final int CHECK_INTERVAL = 600;
    /** Ticks between two passes over the Clerks of a level; each pass checks the Clerks due in it. */
    private static final int PASS_INTERVAL = 20;

    private StockRules() {
    }

    /**
     * Sets the rule in its slot (replacing the rule there). A changed priority moves the rule's open request at once;
     * a changed item withdraws it. Changed Min / Max / Order take effect on the next check.
     *
     * @param data  the dimension's settlement data
     * @param clerk the Clerk JobBlock position
     * @param rule  the rule
     * @return whether it was set (a Clerk JobBlock, a slot within the highest level, Min or Max set and no other rule
     *         of the Clerk for that item)
     */
    public static boolean setRule(SettlementData data, BlockPos clerk, StockRule rule) {
        Optional<ClerkAt> found = clerkAt(data, clerk);
        if (found.isEmpty() || !rule.valid() || rule.slot() < 0 || rule.slot() >= StockRule.slots(SettlementRules.MAX_LEVEL)) {
            return false;
        }
        Settlement settlement = found.get().settlement();
        SettlementBlock block = found.get().block();
        if (block.stockRules().stream().anyMatch(other -> other.item() == rule.item() && other.slot() != rule.slot())) {
            // One rule per item and Clerk.
            return false;
        }
        Optional<StockRule> previous = block.stockRule(rule.slot());
        List<StockRule> rules = block.mutableStockRules();
        rules.removeIf(existing -> existing.slot() == rule.slot());
        rules.add(rule);
        rules.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        Requests.forRule(settlement, clerk, rule.slot()).ifPresent(request -> {
            if (previous.filter(old -> old.item() == rule.item()).isEmpty()) {
                Requests.withdraw(data, settlement, request.id());
            } else {
                Requests.setPriority(data, settlement, request.id(), rule.priority());
            }
        });
        data.setDirty();
        return true;
    }

    /**
     * Deletes the rule in a slot and withdraws its open request at once (a running craft is finished and delivered).
     *
     * @param data  the dimension's settlement data
     * @param clerk the Clerk JobBlock position
     * @param slot  the rule slot
     * @return whether a rule was deleted
     */
    public static boolean removeRule(SettlementData data, BlockPos clerk, int slot) {
        Optional<ClerkAt> found = clerkAt(data, clerk);
        if (found.isEmpty() || !found.get().block().mutableStockRules().removeIf(rule -> rule.slot() == slot)) {
            return false;
        }
        Settlement settlement = found.get().settlement();
        Requests.forRule(settlement, clerk, slot).ifPresent(request -> Requests.withdraw(data, settlement, request.id()));
        data.setDirty();
        return true;
    }

    /**
     * Checks a Clerk's rules against its whole storage and opens, updates or withdraws their requests.
     *
     * @param level   the level
     * @param clerk   the Clerk JobBlock position
     * @param dayTime the day time (the rules are active during the Clerk's shift)
     */
    public static void check(ServerLevel level, BlockPos clerk, long dayTime) {
        SettlementData data = SettlementData.get(level);
        Optional<ClerkAt> found = clerkAt(data, clerk);
        if (found.isEmpty() || !level.isLoaded(clerk)) {
            return;
        }
        Settlement settlement = found.get().settlement();
        SettlementBlock block = found.get().block();
        boolean working = ClerkStorage.working(block, DayClock.day(level), dayTime);
        Map<Item, Integer> stock = null;
        for (StockRule rule : block.stockRules()) {
            Optional<Request> request = Requests.forRule(settlement, clerk, rule.slot());
            if (!working || !rule.activeAt(block.level())) {
                request.ifPresent(open -> Requests.withdraw(data, settlement, open.id()));
                continue;
            }
            if (stock == null) {
                stock = count(level, clerk);
            }
            int crafting = request.map(Request::crafting).orElse(0);
            // Results waiting for a Deliveryman count as there (no double production).
            int held = stock.getOrDefault(rule.item(), 0) + Deliveries.inDelivery(settlement, clerk, rule.item());
            int missing = rule.target() - held - crafting;
            if (request.isPresent()) {
                Requests.update(data, settlement, request.get().id(), open -> open.withAmount(missing).withPriority(rule.priority()));
            } else if (rule.hasMin() && held < rule.min() && missing > 0) {
                Requests.add(data, settlement, new Request(UUID.randomUUID(), rule.item(), missing, rule.priority(), Optional.of(clerk),
                        Optional.of(rule.slot()), Optional.empty(), List.of(), List.of()));
            }
        }
        // Requests whose rule is gone (should not happen; deleting a rule withdraws its request).
        for (Request request : Requests.ofClerk(settlement, clerk)) {
            if (request.rule().flatMap(block::stockRule).isEmpty()) {
                Requests.withdraw(data, settlement, request.id());
            }
        }
        checkSurplus(level, clerk);
    }

    /**
     * Checks the surplus of a Clerk (vault {@code Jobs/Clerk.md}): the stock above an active rule's Max goes to the
     * serving Warehouse's store as a surplus task for its Deliverymen, as far as the Warehouse is below its own Max for
     * the item. A Warehouse itself, a Clerk without Warehouse, or a Warehouse at its own Max keeps it ("Surplus: nowhere
     * to go"). Called with every rule check (after CivMod's own stock changes, every 30 s, hourly in catch-up) and in the
     * Deliveryman's end-of-work sweep; independent of the Clerk's shift (Deliverymen are staff there).
     *
     * @param level the level
     * @param clerk the Clerk JobBlock position
     */
    public static void checkSurplus(ServerLevel level, BlockPos clerk) {
        SettlementData data = SettlementData.get(level);
        Optional<ClerkAt> found = clerkAt(data, clerk);
        if (found.isEmpty() || !level.isLoaded(clerk)) {
            return;
        }
        Settlement settlement = found.get().settlement();
        SettlementBlock block = found.get().block();
        long day = DayClock.day(level);
        Optional<BlockPos> warehouse = surplusTarget(settlement, clerk);
        Map<Item, Integer> stock = null;
        Set<Item> capped = new HashSet<>();
        for (StockRule rule : block.stockRules()) {
            if (!rule.hasMax() || !rule.activeAt(block.level())) {
                continue;
            }
            capped.add(rule.item());
            if (warehouse.isEmpty()) {
                continue;
            }
            if (stock == null) {
                stock = count(level, clerk);
            }
            // A free surplus task is not reserved yet: its items are still counted as stock.
            int surplus = stock.getOrDefault(rule.item(), 0) - rule.max();
            Deliveries.setSurplus(data, settlement, clerk, warehouse.get(), rule.item(),
                    Math.min(surplus, room(level, settlement, clerk, warehouse.get(), rule.item(), day)), level.getGameTime(), day);
        }
        // Free surplus tasks of rules without Max (or without Warehouse) any more.
        for (DeliveryTask task : List.copyOf(settlement.deliveries())) {
            if (task.surplus() && task.source().equals(clerk) && !task.carried() && task.takerOn(day).isEmpty()) {
                for (Item item : task.input().orElseThrow().items().keySet()) {
                    if (warehouse.isEmpty() || !capped.contains(item)) {
                        Deliveries.setSurplus(data, settlement, clerk, task.input().get().to(), item, 0, level.getGameTime(), day);
                    }
                }
            }
        }
    }

    /**
     * @param level the level
     * @param clerk the Clerk JobBlock position
     * @return the slots of its active rules with stock above Max that cannot go anywhere: the Clerk is a Warehouse
     *         itself, has none, or its Warehouse is at its own Max for the item ("Surplus: nowhere to go")
     */
    public static List<Integer> surplusNowhere(ServerLevel level, BlockPos clerk) {
        Optional<ClerkAt> found = clerkAt(SettlementData.get(level), clerk);
        if (found.isEmpty() || !level.isLoaded(clerk) || found.get().block().stockRules().stream().noneMatch(StockRule::hasMax)) {
            return List.of();
        }
        Settlement settlement = found.get().settlement();
        SettlementBlock block = found.get().block();
        Optional<BlockPos> warehouse = surplusTarget(settlement, clerk);
        Map<Item, Integer> stock = count(level, clerk);
        long day = DayClock.day(level);
        List<Integer> slots = new ArrayList<>();
        for (StockRule rule : block.stockRules()) {
            if (rule.hasMax() && rule.activeAt(block.level()) && stock.getOrDefault(rule.item(), 0) > rule.max()
                    && (warehouse.isEmpty() || room(level, settlement, clerk, warehouse.get(), rule.item(), day) <= 0)) {
                slots.add(rule.slot());
            }
        }
        return slots;
    }

    /** The Warehouse store a Clerk's surplus goes to: that of the Warehouse serving it, never the Clerk itself. */
    private static Optional<BlockPos> surplusTarget(Settlement settlement, BlockPos clerk) {
        return Warehouses.warehouseOf(settlement, clerk).map(SettlementBlock::pos).filter(warehouse -> !warehouse.equals(clerk));
    }

    /**
     * How much more of an item a Warehouse takes as surplus: unlimited without an active Max rule for it, else its Max
     * minus its stock minus the surplus already on its way (except the Clerk's own free task, which is being resized).
     */
    private static int room(ServerLevel level, Settlement settlement, BlockPos clerk, BlockPos warehouse, Item item, long day) {
        Optional<SettlementBlock> block = settlement.block(warehouse);
        Optional<StockRule> rule = block.flatMap(found -> found.stockRules().stream()
                .filter(candidate -> candidate.item() == item && candidate.hasMax() && candidate.activeAt(found.level())).findFirst());
        if (rule.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        if (!level.isLoaded(warehouse)) {
            return 0;
        }
        int own = Deliveries.freeSurplus(settlement, clerk, item, day).map(task -> task.input().orElseThrow().items().getOrDefault(item, 0)).orElse(0);
        int coming = Deliveries.surplusTo(settlement, warehouse, item) - own;
        return rule.get().max() - ClerkStorage.stock(level, warehouse, true).count(item) - coming;
    }

    /**
     * The items a collecting JobBlock leaves out of its production (vault {@code Systems/Resource Collection Jobs.md}):
     * those whose active stock rule at its Home Clerk has a Max that the Home Clerk's store holds.
     *
     * @param level the level
     * @param site  the JobBlock
     * @return the items at Max, empty without a Home Clerk, without Max rules there or while it is unloaded
     */
    public static Set<Item> atMax(ServerLevel level, JobBlockSite site) {
        Optional<SettlementBlock> clerk = site.block().homeClerk().flatMap(site.settlement()::block).filter(ClerkStorage::isClerk);
        if (clerk.isEmpty() || clerk.get().stockRules().stream().noneMatch(StockRule::hasMax) || !level.isLoaded(clerk.get().pos())) {
            return Set.of();
        }
        Stock stock = ClerkStorage.stock(level, clerk.get().pos(), true);
        Set<Item> items = new HashSet<>();
        for (StockRule rule : clerk.get().stockRules()) {
            // A lookup in the Clerk's stock view per Max rule, no count of the whole store.
            if (rule.hasMax() && rule.activeAt(clerk.get().level()) && stock.count(rule.item()) >= rule.max()) {
                items.add(rule.item());
            }
        }
        return items;
    }

    /** A Clerk JobBlock and its settlement. */
    private record ClerkAt(Settlement settlement, SettlementBlock block) {
    }

    /** The Clerk JobBlock at a position, empty for any other block. */
    private static Optional<ClerkAt> clerkAt(SettlementData data, BlockPos clerk) {
        return data.settlementAt(clerk).flatMap(settlement -> settlement.block(clerk).filter(ClerkStorage::isClerk)
                .map(block -> new ClerkAt(settlement, block)));
    }

    /**
     * Write-through of the surplus (vault {@code Jobs/Clerk.md}): checks the surplus of the Clerks whose stock CivMod's
     * own transactions increased this tick, only where an item that went in has an active Max rule.
     */
    private static void checkGrownSurplus(ServerLevel level) {
        Map<BlockPos, Set<Item>> grown = ClerkStorage.takeGrown(level);
        if (grown.isEmpty()) {
            return;
        }
        SettlementData data = SettlementData.get(level);
        grown.forEach((clerk, items) -> {
            Optional<ClerkAt> found = clerkAt(data, clerk);
            if (found.isPresent() && found.get().block().stockRules().stream()
                    .anyMatch(rule -> rule.hasMax() && rule.activeAt(found.get().block().level()) && items.contains(rule.item()))) {
                checkSurplus(level, clerk);
            }
        });
    }

    /** Item counts over the Clerk's whole storage, counted once per check. */
    private static Map<Item, Integer> count(ServerLevel level, BlockPos clerk) {
        // Stock.contents() has one item per slot; the counts are the real amounts.
        return ClerkStorage.stock(level, clerk, true).counts();
    }

    /**
     * Checks the surplus of Clerks whose stock CivMod increased this tick, and the rules of every loaded Clerk every
     * {@value #CHECK_INTERVAL} ticks, staggered by position.
     */
    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        checkGrownSurplus(level);
        if (level.getGameTime() % PASS_INTERVAL != 0) {
            return;
        }
        long pass = level.getGameTime() / PASS_INTERVAL;
        int passes = CHECK_INTERVAL / PASS_INTERVAL;
        long dayTime = DayClock.dayTime(level);
        for (Settlement settlement : List.copyOf(SettlementData.get(level).settlements())) {
            for (SettlementBlock block : List.copyOf(settlement.blocks())) {
                if (Math.floorMod(pass + block.pos().hashCode(), passes) == 0 && ClerkStorage.isClerk(block)
                        && (!block.stockRules().isEmpty() || !Requests.ofClerk(settlement, block.pos()).isEmpty())) {
                    check(level, block.pos(), dayTime);
                }
            }
        }
    }
}
