package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Stock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The collect inventory as the work step sees it: the tools of the drop pool entries and the food of the Animal Farmer. The
 * stock is read when something first asks and kept until {@link #refresh()}, so one view serves a whole roll.
 */
final class CollectTools implements JobDrops.Tools {

    private final ServerLevel level;
    private final @Nullable LivingEntity settler;
    private final Supplier<Stock> collect;
    private @Nullable Stock stock;

    private CollectTools(ServerLevel level, @Nullable LivingEntity settler, Supplier<Stock> collect) {
        this.level = level;
        this.settler = settler;
        this.collect = collect;
    }

    /**
     * @param level   the level
     * @param settler the settler using the tools (null: the tools are only looked at, never worn)
     * @param collect what the settler reaches at its collect inventory, read when something first asks
     * @return the view
     */
    static CollectTools of(ServerLevel level, @Nullable LivingEntity settler, Supplier<Stock> collect) {
        return new CollectTools(level, settler, collect);
    }

    /** Reads the collect inventory again at the next question: something was put into it or released there. */
    void refresh() {
        stock = null;
    }

    private Stock stock() {
        if (stock == null) {
            stock = collect.get();
        }
        return stock;
    }

    @Override
    public Optional<ItemStack> find(Predicate<ItemStack> kind) {
        return SettlerTools.find(stock(), kind).map(index -> stock().contents().get(index));
    }

    @Override
    public void wear(Predicate<ItemStack> kind) {
        Optional<Integer> index = SettlerTools.find(stock(), kind);
        if (settler == null || index.isEmpty()) {
            return;
        }
        ItemStack tool = stock().takeOne(index.get());
        if (tool.isEmpty()) {
            return;
        }
        tool.hurtAndBreak(1, level, settler, item -> { });
        if (!tool.isEmpty()) {
            ItemStack rest = stock().insert(tool);
            if (!rest.isEmpty()) {
                settler.spawnAtLocation(level, rest);
            }
        }
    }

    @Override
    public int count(Predicate<ItemStack> kind) {
        return stock().count(kind);
    }

    @Override
    public boolean take(Predicate<ItemStack> kind, int amount) {
        if (stock().count(kind) < amount) {
            return false;
        }
        // Registry order, so the same stock always pays the same way.
        List<Map.Entry<ItemKey, Integer>> entries = new ArrayList<>(stock().counts().entrySet());
        entries.sort(Comparator.comparingInt(entry -> BuiltInRegistries.ITEM.getId(entry.getKey().item())));
        int left = amount;
        for (Map.Entry<ItemKey, Integer> held : entries) {
            if (left > 0 && kind.test(held.getKey().toStack())) {
                left -= stock().take(held.getKey(), Math.min(left, held.getValue())).stream().mapToInt(ItemStack::getCount).sum();
            }
        }
        return left <= 0;
    }
}
