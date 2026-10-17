package com.gilfort.civmod.registry;

import java.util.Map;
import java.util.Set;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.item.ScrollOfAssignmentItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for all items (including block items) added by CivMod.
 * <p>
 * Every item needs a matching {@code item.civmod.<name>} entry in {@code en_us.json}.
 */
public final class ModItems {

    /** Deferred register for items in the {@code civmod} namespace. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CivMod.MOD_ID);

    /** Item of the {@link ModBlocks#LUMBERJACK_BLOCK}. */
    public static final DeferredItem<BlockItem> LUMBERJACK_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.LUMBERJACK_BLOCK);
    /** Item of the {@link ModBlocks#MINER_BLOCK}. */
    public static final DeferredItem<BlockItem> MINER_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.MINER_BLOCK);
    /** Item of the {@link ModBlocks#FISHERMAN_BLOCK}. */
    public static final DeferredItem<BlockItem> FISHERMAN_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.FISHERMAN_BLOCK);
    /** Item of the {@link ModBlocks#MISSION_BOARD}. */
    public static final DeferredItem<BlockItem> MISSION_BOARD = ITEMS.registerSimpleBlockItem(ModBlocks.MISSION_BOARD);
    /** Item of the {@link ModBlocks#CLERK_BLOCK}. */
    public static final DeferredItem<BlockItem> CLERK_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.CLERK_BLOCK);
    /** Item of the {@link ModBlocks#CRAFTSPERSON_BLOCK}. */
    public static final DeferredItem<BlockItem> CRAFTSPERSON_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.CRAFTSPERSON_BLOCK);

    /** The Scroll of Assignment. */
    public static final DeferredItem<ScrollOfAssignmentItem> SCROLL_OF_ASSIGNMENT = ITEMS.registerItem("scroll_of_assignment",
            ScrollOfAssignmentItem::new, properties -> properties.stacksTo(1));

    /** Render-only item for the red X above unconnected JobBlocks; not obtainable. */
    public static final DeferredItem<Item> UNCONNECTED_MARKER = ITEMS.registerSimpleItem("unconnected_marker");
    /** Render-only icon: work POI. */
    public static final DeferredItem<Item> WORK_MARKER = ITEMS.registerSimpleItem("work_marker");
    /** Render-only icon: primary work POI. */
    public static final DeferredItem<Item> PRIMARY_WORK_MARKER = ITEMS.registerSimpleItem("primary_work_marker");
    /** Render-only icon: collect inventory. */
    public static final DeferredItem<Item> COLLECT_MARKER = ITEMS.registerSimpleItem("collect_marker");
    /** Render-only icon: drop-off inventory. */
    public static final DeferredItem<Item> DROP_OFF_MARKER = ITEMS.registerSimpleItem("drop_off_marker");

    /** Render-only icon: bed. */
    public static final DeferredItem<Item> BED_MARKER = ITEMS.registerSimpleItem("bed_marker");

    /** Render-only icon: Clerk stand spot, numbered by the stack count (1–8). */
    public static final DeferredItem<Item> STAND_SPOT_MARKER = ITEMS.registerSimpleItem("stand_spot_marker");

    /** Items that are only used for rendering and never shown in the creative tab. */
    public static final Set<DeferredItem<Item>> RENDER_ONLY = Set.of(UNCONNECTED_MARKER, WORK_MARKER, PRIMARY_WORK_MARKER, COLLECT_MARKER,
            DROP_OFF_MARKER, BED_MARKER, STAND_SPOT_MARKER);

    /**
     * Starter tool every new JobBlock item of a job that needs a tool carries (vault {@code Systems/JobBlock.md}).
     * Clerk, Craftsperson and Mission Board have none.
     */
    private static final Map<DeferredItem<BlockItem>, Item> STARTER_TOOLS = Map.of(LUMBERJACK_BLOCK, Items.STONE_AXE,
            MINER_BLOCK, Items.STONE_PICKAXE, FISHERMAN_BLOCK, Items.FISHING_ROD);

    private ModItems() {
    }

    /**
     * Attaches the item register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(ModItems::addStarterTools);
    }

    /** Gives every new JobBlock item its starter tool as a default component (crafted, creative, {@code /give}, loot). */
    private static void addStarterTools(ModifyDefaultComponentsEvent event) {
        STARTER_TOOLS.forEach((jobBlock, tool) -> event.modify(jobBlock,
                (components, context, item) -> components.set(ModDataComponents.STARTER_TOOL.get(), BuiltInRegistries.ITEM.wrapAsHolder(tool))));
    }
}
