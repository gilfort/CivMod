package com.gilfort.civmod.block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.gilfort.civmod.registry.ModDataComponents;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;

/**
 * Starter tools of JobBlock items (vault {@code Systems/JobBlock.md}), registered by the JobBlock factory
 * ({@link com.gilfort.civmod.api.CivModApi#jobBlockItem(net.minecraft.world.level.block.Block, Supplier, Item.Properties)})
 * for CivMod's and addons' JobBlocks alike.
 */
public final class StarterTools {

    /** JobBlock item → its starter tool; filled while items register, read once all registries are frozen. */
    private static final Map<Item, Supplier<? extends ItemLike>> TOOLS = new LinkedHashMap<>();

    private StarterTools() {
    }

    /**
     * @param jobBlockItem a JobBlock item
     * @param tool         its starter tool
     */
    public static synchronized void register(Item jobBlockItem, Supplier<? extends ItemLike> tool) {
        TOOLS.put(jobBlockItem, tool);
    }

    /**
     * @return every JobBlock item with a starter tool, in registration order
     */
    public static synchronized List<Item> jobBlockItems() {
        return List.copyOf(TOOLS.keySet());
    }

    /**
     * Gives every registered JobBlock item its starter tool as a default component (crafted, creative, {@code /give},
     * loot).
     *
     * @param event the event
     */
    public static synchronized void addDefaults(ModifyDefaultComponentsEvent event) {
        TOOLS.forEach((jobBlockItem, tool) -> event.modify(jobBlockItem, (components, context, item) -> components.set(
                ModDataComponents.STARTER_TOOL.get(), BuiltInRegistries.ITEM.wrapAsHolder(tool.get().asItem()))));
    }
}
