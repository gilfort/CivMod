package com.gilfort.civmod.settlement;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The Clerk's stock rules (vault {@code Jobs/Clerk.md}), kept in its {@link SettlementBlock}. While a Clerk settler is
 * on shift, a rule below its amount keeps one request open for {@code amount − stock − amount being crafted − amount in
 * delivery} ({@link Requests}). Rules are checked every 30 s (staggered per Clerk, loaded Clerks only), right after a
 * delivery into the Clerk and once per simulated hour in catch-up. A check while the Clerk is not working, or of a rule
 * inactive after a downgrade, withdraws the rule's request (running claims are finished).
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
     * a changed item withdraws it. The amount takes effect on the next check.
     *
     * @param data  the dimension's settlement data
     * @param clerk the Clerk JobBlock position
     * @param rule  the rule
     * @return whether it was set (a Clerk JobBlock and a slot within the highest level)
     */
    public static boolean setRule(SettlementData data, BlockPos clerk, StockRule rule) {
        Optional<Settlement> settlement = data.settlementAt(clerk);
        Optional<SettlementBlock> block = settlement.flatMap(found -> found.block(clerk)).filter(ClerkStorage::isClerk);
        if (block.isEmpty() || rule.slot() < 0 || rule.slot() >= StockRule.slots(SettlementRules.MAX_LEVEL)) {
            return false;
        }
        Optional<StockRule> previous = block.get().stockRule(rule.slot());
        List<StockRule> rules = block.get().mutableStockRules();
        rules.removeIf(existing -> existing.slot() == rule.slot());
        rules.add(rule);
        rules.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        Requests.forRule(settlement.get(), clerk, rule.slot()).ifPresent(request -> {
            if (previous.filter(old -> old.item() == rule.item()).isEmpty()) {
                Requests.withdraw(data, settlement.get(), request.id());
            } else {
                Requests.setPriority(data, settlement.get(), request.id(), rule.priority());
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
        Optional<Settlement> settlement = data.settlementAt(clerk);
        Optional<SettlementBlock> block = settlement.flatMap(found -> found.block(clerk)).filter(ClerkStorage::isClerk);
        if (block.isEmpty() || !block.get().mutableStockRules().removeIf(rule -> rule.slot() == slot)) {
            return false;
        }
        Requests.forRule(settlement.get(), clerk, slot).ifPresent(request -> Requests.withdraw(data, settlement.get(), request.id()));
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
        Optional<Settlement> found = data.settlementAt(clerk);
        Optional<SettlementBlock> foundBlock = found.flatMap(settlement -> settlement.block(clerk)).filter(ClerkStorage::isClerk);
        if (foundBlock.isEmpty() || !level.isLoaded(clerk)) {
            return;
        }
        Settlement settlement = found.get();
        SettlementBlock block = foundBlock.get();
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
            int missing = rule.amount() - stock.getOrDefault(rule.item(), 0) - crafting - Deliveries.inDelivery(settlement, clerk, rule.item());
            if (request.isPresent()) {
                Requests.update(data, settlement, request.get().id(), open -> open.withAmount(missing).withPriority(rule.priority()));
            } else if (missing > 0) {
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
    }

    /** Item counts over the Clerk's whole storage, counted once per check. */
    private static Map<Item, Integer> count(ServerLevel level, BlockPos clerk) {
        // Stock.contents() has one item per slot; the counts are the real amounts.
        return ClerkStorage.stock(level, clerk, true).counts();
    }

    /**
     * Checks the rules of every loaded Clerk every {@value #CHECK_INTERVAL} ticks, staggered by position.
     */
    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % PASS_INTERVAL != 0) {
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
