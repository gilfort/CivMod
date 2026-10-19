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
    /** Render-only icon: the red bed of the "going to bed" thought bubble (the vanilla bed model shows its underside there). */
    public static final DeferredItem<Item> BED_ICON = ITEMS.registerSimpleItem("bed_icon");

    /** Render-only icon: Clerk stand spot, numbered by the stack count (1–8). */
    public static final DeferredItem<Item> STAND_SPOT_MARKER = ITEMS.registerSimpleItem("stand_spot_marker");

    /** Display item: the hammer a Craftsperson holds while crafting; only shown in its hand, not obtainable. */
    public static final DeferredItem<Item> HAMMER = ITEMS.registerItem("hammer", Item::new, properties -> properties.stacksTo(1));

    /** Render-only sprite: the thought bubble drawn above a settler. */
    public static final DeferredItem<Item> THOUGHT_BUBBLE = ITEMS.registerSimpleItem("thought_bubble");
    /** Render-only sprite: the red "!" of a problem thought bubble. */
    public static final DeferredItem<Item> PROBLEM_MARKER = ITEMS.registerSimpleItem("problem_marker");

    /** Items that are only used for rendering and never shown in the creative tab. */
    public static final Set<DeferredItem<Item>> RENDER_ONLY = Set.of(UNCONNECTED_MARKER, WORK_MARKER, PRIMARY_WORK_MARKER, COLLECT_MARKER,
            DROP_OFF_MARKER, BED_MARKER, BED_ICON, STAND_SPOT_MARKER, HAMMER, THOUGHT_BUBBLE, PROBLEM_MARKER);

    /**
     * Starter tool every new JobBlock item of a job that needs a tool carries (vault {@code Systems/JobBlock.md}).
     * Clerk, Craftsperson and Mission Board have none.
     * <p>
     * A new entry also needs its block loot table to drop the JobBlock without the tool (the {@code !civmod:starter_tool}
     * component removal in {@code data/civmod/loot_table/blocks/}), or every break and re-place adds another tool.
     */
    public static final Map<DeferredItem<BlockItem>, Item> STARTER_TOOLS = Map.of(LUMBERJACK_BLOCK, Items.STONE_AXE,
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
