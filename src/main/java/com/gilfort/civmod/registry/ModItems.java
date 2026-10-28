package com.gilfort.civmod.registry;

import java.util.Set;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.CivModApi;
import com.gilfort.civmod.block.JobBlock;
import com.gilfort.civmod.block.StarterTools;
import com.gilfort.civmod.item.ScrollOfAssignmentItem;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
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
    public static final DeferredItem<BlockItem> LUMBERJACK_BLOCK = jobBlockItem(ModBlocks.LUMBERJACK_BLOCK, Items.STONE_AXE);
    /** Item of the {@link ModBlocks#MINER_BLOCK}. */
    public static final DeferredItem<BlockItem> MINER_BLOCK = jobBlockItem(ModBlocks.MINER_BLOCK, Items.STONE_PICKAXE);
    /** Item of the {@link ModBlocks#FISHERMAN_BLOCK}. */
    public static final DeferredItem<BlockItem> FISHERMAN_BLOCK = jobBlockItem(ModBlocks.FISHERMAN_BLOCK, Items.FISHING_ROD);
    /** Item of the {@link ModBlocks#GATHERER_BLOCK}; no starter tool (the Gatherer works bare-handed). */
    public static final DeferredItem<BlockItem> GATHERER_BLOCK = jobBlockItem(ModBlocks.GATHERER_BLOCK);
    /** Item of the {@link ModBlocks#MISSION_BOARD}. */
    public static final DeferredItem<BlockItem> MISSION_BOARD = jobBlockItem(ModBlocks.MISSION_BOARD);
    /** Item of the {@link ModBlocks#CLERK_BLOCK}. */
    public static final DeferredItem<BlockItem> CLERK_BLOCK = jobBlockItem(ModBlocks.CLERK_BLOCK);
    /** Item of the {@link ModBlocks#CRAFTSPERSON_BLOCK}. */
    public static final DeferredItem<BlockItem> CRAFTSPERSON_BLOCK = jobBlockItem(ModBlocks.CRAFTSPERSON_BLOCK);
    /** Item of the {@link ModBlocks#SMELTER_BLOCK}. */
    public static final DeferredItem<BlockItem> SMELTER_BLOCK = jobBlockItem(ModBlocks.SMELTER_BLOCK);
    /** Item of the {@link ModBlocks#SCARECROW}. */
    public static final DeferredItem<BlockItem> SCARECROW = ITEMS.registerSimpleBlockItem(ModBlocks.SCARECROW);

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

    private ModItems() {
    }

    /** The item of a JobBlock without a starter tool (Clerk, Craftsperson, Mission Board), from the JobBlock factory. */
    private static DeferredItem<BlockItem> jobBlockItem(DeferredBlock<JobBlock> block) {
        return ITEMS.registerItem(block.getId().getPath(), properties -> CivModApi.jobBlockItem(block.get(), properties));
    }

    /**
     * The item of a JobBlock whose job needs a tool, from the JobBlock factory: a new item carries the starter tool (vault
     * {@code Systems/JobBlock.md}). Its block loot table drops the JobBlock without the tool (the {@code !civmod:starter_tool}
     * component removal in {@code data/civmod/loot_table/blocks/}), or every break and re-place would add another tool.
     */
    private static DeferredItem<BlockItem> jobBlockItem(DeferredBlock<JobBlock> block, Item starterTool) {
        return ITEMS.registerItem(block.getId().getPath(), properties -> CivModApi.jobBlockItem(block.get(), () -> starterTool, properties));
    }

    /**
     * Attaches the item register to the mod event bus.
     *
     * @param modEventBus the mod-specific event bus
     */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(StarterTools::addDefaults);
    }
}
