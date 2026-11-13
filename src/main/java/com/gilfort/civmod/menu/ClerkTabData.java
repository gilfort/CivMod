package com.gilfort.civmod.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.client.gui.CivGui;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.DeliveryTask;
import com.gilfort.civmod.logistics.Producers;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.logistics.Pickups;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Deliveries;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Warehouses;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * What the Clerk's "Stock" and "Tasks" tabs show (vault {@code Jobs/Clerk.md}, {@code Jobs/Deliveryman.md}): its stock
 * rules, its tasks (the requests of its rules and the open Delivery tasks to it; a Warehouse: those of every Clerk it
 * serves) and, for the Workers tab, its Served Clerks or the Warehouse serving it. The server sends it while the menu is
 * open whenever it changed ({@link Sync}); the client edits rules with {@link Edit}.
 *
 * @param level    the Clerk JobBlock level (9 rules per level are active)
 * @param ledgers  the Ledgers used on it (9 more active rules each)
 * @param rules    the stock rules
 * @param tasks    the tasks: blocked first, then in progress, then waiting; requests (highest priority first) before
 *                 Delivery tasks (in creation order) within each
 * @param served   a Warehouse's Served Clerks (inactive ones marked), empty for any other Clerk
 * @param servedBy the Warehouse serving this Clerk, if any
 * @param nowhere  the slots of the rules whose surplus has nowhere to go ("Surplus: nowhere to go")
 */
public record ClerkTabData(int level, int ledgers, List<StockRule> rules, List<TaskRow> tasks, List<Component> served, Optional<Component> servedBy,
        List<Integer> nowhere) {

    /** Nothing (any other JobBlock, or before the first sync). */
    public static final ClerkTabData EMPTY = new ClerkTabData(1, 0, List.of(), List.of(), List.of(), Optional.empty(), List.of());

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ClerkTabData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClerkTabData::level,
            ByteBufCodecs.VAR_INT, ClerkTabData::ledgers,
            StockRule.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTabData::rules,
            TaskRow.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTabData::tasks,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), ClerkTabData::served,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs::optional), ClerkTabData::servedBy,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), ClerkTabData::nowhere,
            ClerkTabData::new);

    /**
     * @param level    the Clerk JobBlock level
     * @param ledgers  the Ledgers used on it
     * @param rules    the stock rules
     * @param tasks    the tasks
     * @param served   the Served Clerks
     * @param servedBy the serving Warehouse
     * @param nowhere  the rule slots whose surplus has nowhere to go
     */
    public ClerkTabData {
        rules = List.copyOf(rules);
        tasks = List.copyOf(tasks);
        served = List.copyOf(served);
        nowhere = List.copyOf(nowhere);
    }

    /** The status of a task: its icon and colour in the Tasks tab, and its place in the list (blocked first, waiting last). */
    public enum TaskStatus {
        /** Nobody can work on it: no producer, inputs missing or no Deliveryman (a red ✕). */
        BLOCKED("✕", CivGui.ERROR_COLOR, 0),
        /** A producer crafts it or a Deliveryman is on it. */
        IN_PROGRESS("▶", CivGui.LABEL_COLOR, 1),
        /** Waiting for a producer or a Deliveryman to take it. */
        WAITING("⌛", CivGui.HINT_COLOR, 2);

        /** Network codec. */
        public static final StreamCodec<ByteBuf, TaskStatus> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], TaskStatus::ordinal);

        private final String icon;
        private final int color;
        private final int order;

        TaskStatus(String icon, int color, int order) {
            this.icon = icon;
            this.color = color;
            this.order = order;
        }

        /**
         * @return the status icon ("✕", "▶", "⌛")
         */
        public Component icon() {
            return Component.literal(icon);
        }

        /**
         * @return the icon's colour (ARGB; the {@link CivGui} constants are compile-time constants, so the server does
         *         not load the client class)
         */
        public int color() {
            return color;
        }

        /**
         * @return its place in the list, lowest first
         */
        public int order() {
            return order;
        }
    }

    /**
     * One row of the Tasks tab: a request of a stock rule or an open Delivery task.
     *
     * @param icon    a request's item, a delivery's first carried or waiting item (its source block if unknown)
     * @param text    the short text ("Smith: 8 Iron Pickaxe", "Lumberjack → Clerk")
     * @param status  its status
     * @param details the tooltip lines: the text, then amount, reason, Deliveryman or producer
     * @param depth   how deep the row sits under its main request: 0 for a main request or a delivery, 1 and more for the
     *                sub-requests below it (drawn indented and smaller)
     */
    public record TaskRow(ItemStack icon, Component text, TaskStatus status, List<Component> details, int depth) {

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, TaskRow> STREAM_CODEC = StreamCodec.composite(
                ItemStack.OPTIONAL_STREAM_CODEC, TaskRow::icon,
                ComponentSerialization.STREAM_CODEC, TaskRow::text,
                TaskStatus.STREAM_CODEC, TaskRow::status,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), TaskRow::details,
                ByteBufCodecs.VAR_INT, TaskRow::depth,
                TaskRow::new);

        /**
         * @param icon    the icon
         * @param text    the short text
         * @param status  the status
         * @param details the tooltip lines
         * @param depth   the depth under the main request
         */
        public TaskRow {
            details = List.copyOf(details);
        }

        /**
         * A row that sits at the top level.
         *
         * @param icon    the icon
         * @param text    the short text
         * @param status  the status
         * @param details the tooltip lines
         */
        public TaskRow(ItemStack icon, Component text, TaskStatus status, List<Component> details) {
            this(icon, text, status, details, 0);
        }

        // Item stacks have no value equality; the tab is only resent when a row really changed.
        @Override
        public boolean equals(Object other) {
            return other instanceof TaskRow row && ItemStack.matches(icon, row.icon) && text.equals(row.text) && status == row.status
                    && details.equals(row.details) && depth == row.depth;
        }

        @Override
        public int hashCode() {
            return Objects.hash(icon.getItem(), text, status, details, depth);
        }
    }

    /**
     * @return the active rule slots: (level + Ledgers) × 9
     */
    public int slots() {
        return StockRule.slots(level, ledgers);
    }

    /**
     * @param slot a rule slot
     * @return the rule in that slot
     */
    public Optional<StockRule> rule(int slot) {
        return rules.stream().filter(rule -> rule.slot() == slot).findFirst();
    }

    /**
     * The rule slot Shift + right-click on an inventory item fills (vault {@code Systems/GUI.md}).
     *
     * @return the first active rule slot without a rule, {@code -1} if every one holds a rule
     */
    public int nextEmptyGhostSlot() {
        for (int slot = 0; slot < slots(); slot++) {
            if (rule(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * @param level the level
     * @param clerk the Clerk JobBlock position
     * @return the current tab of that Clerk ({@link #EMPTY} if it is none)
     */
    public static ClerkTabData of(ServerLevel level, BlockPos clerk) {
        Optional<Settlement> settlement = SettlementData.get(level).settlementAt(clerk);
        Optional<SettlementBlock> block = settlement.flatMap(found -> found.block(clerk)).filter(ClerkStorage::isClerk);
        if (block.isEmpty()) {
            return EMPTY;
        }
        // A Warehouse shows the tasks of every Clerk it serves, any other Clerk only its own.
        boolean warehouse = Warehouses.warehouseOf(settlement.get(), clerk).filter(block.get()::equals).isPresent();
        List<BlockPos> clerks = warehouse ? Warehouses.handled(settlement.get(), block.get()) : List.of(clerk);
        List<TaskRow> tasks = new ArrayList<>(requests(level, settlement.get(), clerk, clerks));
        tasks.addAll(deliveries(level, settlement.get(), clerks));
        return new ClerkTabData(block.get().level(), block.get().ledgers(), block.get().stockRules(), byStatus(tasks), served(settlement.get(), block.get()),
                Warehouses.servedBy(settlement.get(), clerk).map(ClerkStorage::name), StockRules.surplusNowhere(level, clerk));
    }

    /**
     * Orders the rows by status, a stable sort: requests stay before deliveries, each in its own order. A main request
     * moves together with the sub-requests under it.
     */
    private static List<TaskRow> byStatus(List<TaskRow> rows) {
        List<List<TaskRow>> groups = new ArrayList<>();
        for (TaskRow row : rows) {
            if (row.depth() == 0 || groups.isEmpty()) {
                groups.add(new ArrayList<>());
            }
            groups.getLast().add(row);
        }
        groups.sort(Comparator.comparingInt(group -> group.getFirst().status().order()));
        return groups.stream().flatMap(List::stream).toList();
    }

    /**
     * The requests of the Clerks' rules, highest priority first: "Smith: 8 Iron Pickaxe" while a producer crafts it,
     * else "8 Iron Pickaxe"; another Clerk's request names it in the tooltip ("For <Clerk>"). Below each one sit its open
     * sub-requests.
     */
    private static List<TaskRow> requests(ServerLevel level, Settlement settlement, BlockPos clerk, List<BlockPos> clerks) {
        List<TaskRow> rows = new ArrayList<>();
        Requests.byPriority(settlement).stream()
                .filter(request -> request.rule().isPresent() && request.target().filter(clerks::contains).isPresent())
                .forEach(request -> {
                    rows.add(requestRow(level, settlement, clerk, request, 0));
                    addSubRequests(rows, level, settlement, clerk, request, 1);
                });
        return rows;
    }

    /** The open sub-requests of a request, each followed by its own, one level deeper. */
    private static void addSubRequests(List<TaskRow> rows, ServerLevel level, Settlement settlement, BlockPos clerk, Request parent, int depth) {
        for (Request child : Requests.children(settlement, parent.id())) {
            if (child.amount() + child.crafting() > 0) {
                rows.add(requestRow(level, settlement, clerk, child, depth));
                addSubRequests(rows, level, settlement, clerk, child, depth + 1);
            }
        }
    }

    private static TaskRow requestRow(ServerLevel level, Settlement settlement, BlockPos clerk, Request request, int depth) {
        int amount = request.amount() + request.crafting();
        Component item = new ItemStack(request.item()).getHoverName();
        Component text = request.claims().isEmpty() ? Component.translatable("civmod.gui.clerk.task.request.open", amount, item)
                : Component.translatable("civmod.gui.clerk.task.request", producer(settlement, request.claims().get(0)), amount, item);
        TaskStatus status;
        Component reason;
        if (!request.claims().isEmpty()) {
            status = TaskStatus.IN_PROGRESS;
            reason = Component.translatable("civmod.gui.clerk.request.crafting", request.claims().get(0).name());
        } else if (!request.missing().isEmpty()) {
            status = TaskStatus.BLOCKED;
            reason = missing(request);
        } else if (!Producers.canMake(level, settlement, request.item())) {
            status = TaskStatus.BLOCKED;
            reason = Component.translatable("civmod.gui.clerk.request.no_producer");
        } else {
            status = TaskStatus.WAITING;
            reason = Component.translatable("civmod.gui.clerk.request.waiting");
        }
        List<Component> details = new ArrayList<>(List.of(text, gray(reason),
                gray(Component.translatable("civmod.gui.clerk.request.priority", request.priority()))));
        BlockPos target = request.target().get();
        if (depth == 0 && !target.equals(clerk)) {
            details.add(gray(Component.translatable("civmod.gui.clerk.task.for", name(settlement, target))));
        }
        return new TaskRow(new ItemStack(request.item(), amount), text, status, details, depth);
    }

    /** The Job of the producer crafting a claim ("Smith"), its name if it holds none. */
    private static Component producer(Settlement settlement, Request.Claim claim) {
        return settlement.blocks().stream().flatMap(block -> block.assignee(claim.settler()).stream()).findFirst()
                .<Component>map(assignee -> assignee.job().displayName()).orElseGet(() -> Component.literal(claim.name()));
    }

    /** "Missing: 6× Cobblestone, 4× Stick". */
    private static Component missing(Request request) {
        MutableComponent inputs = Component.empty();
        for (Request.Missing missing : request.missing()) {
            if (!inputs.getSiblings().isEmpty()) {
                inputs.append(", ");
            }
            inputs.append(missing.text());
        }
        return Component.translatable("civmod.gui.clerk.request.missing", inputs);
    }

    /**
     * The open Delivery tasks to the Clerks, in creation order: "<source> → <Clerk>" for a pickup, "<store> → <producer>"
     * for inputs, "<producer> → <requesting Clerk>" for a result, "Surplus: <Clerk> → <Warehouse>".
     */
    private static List<TaskRow> deliveries(ServerLevel level, Settlement settlement, List<BlockPos> clerks) {
        long day = DayClock.day(level);
        return Deliveries.toClerks(settlement, clerks).stream().map(task -> {
            Optional<JobBlockSite> site = JobBlockSite.at(level, task.jobBlock());
            Component source = site.map(found -> ClerkStorage.isClerk(found.block()) ? ClerkStorage.name(found.block())
                    : found.block().jobType().displayName()).orElse(Component.literal(task.source().toShortString()));
            int items;
            ItemStack icon;
            Component text;
            if (task.input().isPresent()) {
                DeliveryTask.Input input = task.input().get();
                items = input.count();
                icon = input.items().keySet().stream().findFirst().map(ItemStack::new).orElse(ItemStack.EMPTY);
                Component storeName = name(settlement, task.source());
                text = task.surplus() ? Component.translatable("civmod.gui.clerk.delivery.surplus", storeName, name(settlement, input.to()))
                        : Component.translatable("civmod.gui.clerk.delivery.input", storeName, source);
            } else if (task.result().isPresent()) {
                DeliveryTask.Result result = task.result().get();
                items = result.count();
                icon = new ItemStack(result.item());
                text = Component.translatable("civmod.gui.clerk.delivery.result", source, name(settlement, result.clerk()));
            } else {
                // A pickup: the first item waiting at the drop-off; while it is unloaded or carried away the first item
                // its Deliveryman carries, else the source block.
                boolean loaded = level.isLoaded(task.source());
                items = site.filter(found -> loaded).map(found -> Pickups.contents(level, found, task.source()).items()).orElse(0);
                icon = site.filter(found -> loaded).map(found -> Pickups.firstItem(level, found, task.source())).orElse(ItemStack.EMPTY);
                if (icon.isEmpty()) {
                    icon = task.takerOn(day).map(taker -> firstCarried(level, taker)).orElse(ItemStack.EMPTY);
                }
                if (icon.isEmpty() && loaded) {
                    icon = new ItemStack(level.getBlockState(task.source()).getBlock().asItem());
                }
                Component to = Deliveries.homeClerk(settlement, task).map(pos -> name(settlement, pos)).orElse(Component.literal("?"));
                text = Component.translatable("civmod.gui.clerk.delivery.pickup", source, to);
            }
            TaskStatus status;
            Component state;
            Optional<DeliveryTask.Taker> taker = task.takerOn(day);
            if (taker.isPresent()) {
                status = TaskStatus.IN_PROGRESS;
                state = Component.literal(taker.get().name());
            } else if (task.carried()) {
                status = TaskStatus.IN_PROGRESS;
                state = Component.translatable("civmod.gui.clerk.delivery.carried");
            } else if (Deliveries.warehouseOf(settlement, task).filter(Warehouses::hasDeliverymen).isPresent()) {
                status = TaskStatus.WAITING;
                state = Component.translatable("civmod.gui.clerk.delivery.waiting");
            } else {
                status = TaskStatus.BLOCKED;
                state = Component.translatable("civmod.gui.clerk.delivery.no_deliveryman");
            }
            List<Component> details = List.of(text, gray(Component.translatable("civmod.gui.clerk.delivery.items", items)), gray(state));
            return new TaskRow(icon, text, status, details);
        }).toList();
    }

    /** The first item in a Deliveryman's load (a copy), empty if it is not loaded or carries nothing. */
    private static ItemStack firstCarried(ServerLevel level, DeliveryTask.Taker taker) {
        if (!(level.getEntity(taker.settler()) instanceof Settler settler)) {
            return ItemStack.EMPTY;
        }
        return settler.getJobs().stream().filter(job -> job.jobType() == ModJobs.DELIVERYMAN.get())
                .flatMap(job -> settler.getWork().buffer(job.jobBlock()).stacks().stream()).findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    /** The name of a Clerk JobBlock (its position if it is gone). */
    private static Component name(Settlement settlement, BlockPos pos) {
        return settlement.block(pos).map(ClerkStorage::name).orElse(Component.literal(pos.toShortString()));
    }

    private static Component gray(Component line) {
        return line.copy().withStyle(ChatFormatting.GRAY);
    }

    /** A Warehouse's Served Clerks; those beyond its level are marked inactive. */
    private static List<Component> served(Settlement settlement, SettlementBlock warehouse) {
        List<BlockPos> active = Warehouses.activeServed(warehouse);
        return warehouse.served().stream().<Component>map(pos -> {
            Component name = name(settlement, pos);
            return active.contains(pos) ? name : Component.translatable("civmod.gui.clerk.served.inactive", name).withStyle(ChatFormatting.GRAY);
        }).toList();
    }

    /**
     * Server → client: the Clerk tab of the open menu changed.
     *
     * @param containerId the menu's container id
     * @param tab         the tab
     */
    public record Sync(int containerId, ClerkTabData tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("clerk_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                ClerkTabData.STREAM_CODEC, Sync::tab,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: set the rule in a slot of the open Clerk menu, or delete it (empty rule).
     *
     * @param containerId the menu's container id
     * @param slot        the rule slot
     * @param rule        the new rule, empty to delete it
     */
    public record Edit(int containerId, int slot, Optional<StockRule> rule) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Edit> TYPE = new Type<>(CivMod.id("edit_stock_rule"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Edit::containerId,
                ByteBufCodecs.VAR_INT, Edit::slot,
                ByteBufCodecs.optional(StockRule.STREAM_CODEC), Edit::rule,
                Edit::new);

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }
}
