package com.gilfort.civmod.menu;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.mission.MissionRules;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.registry.ModMenuTypes;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.StockRules;

import com.gilfort.civmod.settlement.AutoAssignment;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;
import com.gilfort.civmod.settlement.StationTypes;
import com.gilfort.civmod.settlement.DayClock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Menu of a JobBlock: tab "Storage" (27 slots + player inventory), tab "Info" and, on the Mission Board, tab
 * "Missions"; the Clerk adds the tabs "Stock" (stock rules, with the player inventory to pick items from), "Requests"
 * and "Deliveries" ({@link ClerkTab}); Resource Collection JobBlocks add the tab "Found" ({@link FoundTab}). Producing
 * JobBlocks add the Base Job's "Patterns" tab ({@link PatternTab}), the "Station Types" tab ({@link StationTypeTab},
 * shown when the Base Job has Specializations) and one "Patterns" tab per Specialization (shown while it is unlocked or
 * its library holds a pattern).
 */
public class JobBlockMenu extends CivMenu {

    /** Storage tab. */
    public static final int TAB_STORAGE = 0;
    /** Info tab. */
    public static final int TAB_INFO = 1;
    /** Missions tab (Mission Board only). */
    public static final int TAB_MISSIONS = 2;
    /** Stock tab (Clerk only): the stock rules. */
    public static final int TAB_STOCK = 2;
    /** Requests tab (Clerk only): the requests of the stock rules. */
    public static final int TAB_REQUESTS = 3;
    /** Deliveries tab (Clerk only): the open Delivery tasks. */
    public static final int TAB_DELIVERIES = 4;
    /** Patterns tab of the Base Job (Producing Jobs only): its pattern library, with the player inventory to pick items from. */
    public static final int TAB_PATTERNS = 2;
    /** Station Types tab (Producing Jobs only, shown if the Base Job has Specializations). */
    public static final int TAB_STATION_TYPES = 3;
    /** Patterns tab of the first Specialization; the others follow in registry order. */
    private static final int TAB_SPECIALIZATION_PATTERNS = 4;
    /** Found tab (Resource Collection Jobs only): the found resources and their exclusions. */
    public static final int TAB_FOUND = 2;
    /**
     * Panel height of the Clerk's and a Producing Job's menu (a grid of 45 / 54 slots, the Clerk's three-row stock
     * rule editor and the player inventory).
     */
    public static final int CLERK_HEIGHT = 262;
    /** Ticks between two checks whether the Clerk, Patterns or Found tab changed. */
    private static final int TAB_SYNC_INTERVAL = 20;

    /** Button id: free the JobBlock (remove the job from its settler). */
    public static final int BUTTON_UNASSIGN = 50;
    /** Button id: toggle leaf collection (Lumberjack). */
    public static final int BUTTON_TOGGLE_LEAVES = 51;
    /** Button id: upgrade to the next level. */
    public static final int BUTTON_UPGRADE = 52;
    /** Button id: toggle who carries the results, Self / Deliveryman (Producing Jobs). */
    public static final int BUTTON_TOGGLE_DELIVERY = 53;
    /** Button ids from here: put the carried item into a Station Types slot ({@link #stationTypeButtonId}). */
    private static final int BUTTON_STATION_TYPE = 54;
    /** Button ids from here: set the mark of a mission offer entry ({@link #markButtonId}). */
    private static final int BUTTON_MARK_MISSION = 100;
    /** Upper bound (exclusive) of the mark button ids. */
    private static final int BUTTON_MARK_MISSION_END = BUTTON_MARK_MISSION + 2 * MissionRules.MAX_OFFER_SIZE;
    /** Assign buttons per offer entry: unlock, then one per board settler. */
    private static final int ASSIGN_SLOTS = 1 + SettlementRules.MAX_SETTLERS;
    /** Button ids from here: assign or unlock a mission offer entry ({@link #assignButtonId}). */
    private static final int BUTTON_ASSIGN_MISSION = BUTTON_MARK_MISSION_END;
    /** Upper bound (exclusive) of the assign button ids. */
    private static final int BUTTON_ASSIGN_MISSION_END = BUTTON_ASSIGN_MISSION + ASSIGN_SLOTS * MissionRules.MAX_OFFER_SIZE;
    /** Most Job lines of a JobBlock with an automatic assignment toggle. */
    private static final int MAX_JOB_LINES = 16;
    /** Button ids from here: set the automatic assignment toggle of a Job line ({@link #autoButtonId}). */
    private static final int BUTTON_AUTO_ASSIGN = BUTTON_ASSIGN_MISSION_END;
    /** Upper bound (exclusive) of the automatic assignment button ids. */
    private static final int BUTTON_AUTO_ASSIGN_END = BUTTON_AUTO_ASSIGN + 2 * MAX_JOB_LINES;

    private final Container container;
    private final Player player;
    private JobBlockInfo info;
    private ClerkTab clerkTab = ClerkTab.EMPTY;
    private int tabSyncIn;
    private PatternTab patternTab = PatternTab.EMPTY;
    private StationTypeTab stationTypeTab = StationTypeTab.EMPTY;
    private FoundTab foundTab = FoundTab.EMPTY;
    /** The Producing Jobs with a Patterns tab: the Base Job, then its Specializations (Station Types). */
    private final List<Job> patternJobs;

    /**
     * Client constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param buffer      extra data written by the server
     */
    public JobBlockMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, new SimpleContainer(JobBlockEntity.SLOTS), JobBlockInfo.STREAM_CODEC.decode(buffer));
    }

    /**
     * Server constructor.
     *
     * @param containerId the container id
     * @param inventory   the player inventory
     * @param container   the JobBlock storage
     * @param info        the info snapshot
     */
    public JobBlockMenu(int containerId, Inventory inventory, Container container, JobBlockInfo info) {
        super(ModMenuTypes.JOB_BLOCK.get(), containerId, tabCount(info.jobType()));
        checkContainerSize(container, JobBlockEntity.SLOTS);
        this.container = container;
        this.player = inventory.player;
        this.info = info;
        this.patternJobs = patternJobs(info.jobType());
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addTabSlot(container, column + row * 9, 8 + column * 18, 18 + row * 18, TAB_STORAGE);
            }
        }
        if (isClerk(info)) {
            // Stock rule items are picked from the player inventory, so it is shown on the Stock tab as well.
            addPlayerInventory(inventory, 8, CLERK_HEIGHT - 82, TAB_STORAGE, TAB_STOCK);
        } else if (hasPatternsTab(info)) {
            // Pattern and unlock items are picked from the player inventory, so it is shown on those tabs as well.
            int[] tabs = new int[patternJobs.size() + 2];
            tabs[0] = TAB_STORAGE;
            tabs[1] = TAB_STATION_TYPES;
            for (int i = 0; i < patternJobs.size(); i++) {
                tabs[i + 2] = patternsTab(i);
            }
            addPlayerInventory(inventory, 8, CLERK_HEIGHT - 82, tabs);
        } else {
            addPlayerInventory(inventory, 8, 84, TAB_STORAGE);
        }
    }

    private static int tabCount(Job blockJob) {
        JobBlockTabs tabs = JobBlockTabs.of(blockJob);
        return tabs == JobBlockTabs.PATTERNS ? TAB_SPECIALIZATION_PATTERNS + patternJobs(blockJob).size() - 1 : tabs.count();
    }

    /** The Producing Jobs with a Patterns tab at a JobBlock: its Job, then the Specializations of its Station Types. */
    private static List<Job> patternJobs(Job blockJob) {
        if (JobBlockTabs.of(blockJob) != JobBlockTabs.PATTERNS) {
            return List.of();
        }
        List<Job> jobs = new java.util.ArrayList<>(List.of(blockJob));
        if (StationTypes.hasStationTypes(blockJob)) {
            StationTypes.specializations(blockJob).stream().filter(job -> job.category() == com.gilfort.civmod.api.JobCategory.PRODUCING)
                    .forEach(jobs::add);
        }
        return List.copyOf(jobs);
    }

    /** The tab index of the Patterns tab of the {@code index}-th pattern Job. */
    private static int patternsTab(int index) {
        return index == 0 ? TAB_PATTERNS : TAB_SPECIALIZATION_PATTERNS + index - 1;
    }

    /**
     * @return the Producing Jobs with a Patterns tab, in tab order (the Base Job first; empty without Patterns tabs)
     */
    public List<Job> patternJobs() {
        return patternJobs;
    }

    /**
     * @param job a Producing Job of the JobBlock
     * @return the index of its Patterns tab, {@code -1} if it has none
     */
    public int patternsTabOf(Job job) {
        int index = patternJobs.indexOf(job);
        return index < 0 ? -1 : patternsTab(index);
    }

    /**
     * @param tab a tab index
     * @return the Job whose Patterns tab it is, if it is one
     */
    public Optional<Job> patternJob(int tab) {
        for (int i = 0; i < patternJobs.size(); i++) {
            if (patternsTab(i) == tab) {
                return Optional.of(patternJobs.get(i));
            }
        }
        return Optional.empty();
    }

    /**
     * @param slot a Station Types slot
     * @return the menu button id that puts the carried item into it
     */
    public static int stationTypeButtonId(int slot) {
        return BUTTON_STATION_TYPE + slot;
    }

    /**
     * @return the Station Types tab as last synced (Producing Jobs only)
     */
    public StationTypeTab getStationTypeTab() {
        return stationTypeTab;
    }

    /**
     * Client: the server sent a new Station Types tab.
     *
     * @param tab the tab
     */
    public void setStationTypeTab(StationTypeTab tab) {
        stationTypeTab = tab;
    }

    /**
     * @param info a JobBlock info
     * @return whether the JobBlock is a Clerk JobBlock (Stock, Requests and Deliveries tabs)
     */
    public static boolean isClerk(JobBlockInfo info) {
        return JobBlockTabs.of(info.jobType()) == JobBlockTabs.CLERK;
    }

    /**
     * @param info a JobBlock info
     * @return whether the JobBlock offers a Producing Job (Patterns tab)
     */
    public static boolean hasPatternsTab(JobBlockInfo info) {
        return JobBlockTabs.of(info.jobType()) == JobBlockTabs.PATTERNS;
    }

    /**
     * @param info a JobBlock info
     * @return whether the JobBlock offers a Resource Collection Job (Found tab)
     */
    public static boolean hasFoundTab(JobBlockInfo info) {
        return FoundTab.hasTab(info.jobType());
    }

    /**
     * @return the found resources as last synced (Resource Collection Jobs only)
     */
    public FoundTab getFoundTab() {
        return foundTab;
    }

    /**
     * Client: the server sent a new Found tab.
     *
     * @param tab the tab
     */
    public void setFoundTab(FoundTab tab) {
        foundTab = tab;
    }

    /**
     * Server: excludes or includes a found resource and sends the tab at once. Only a type the tab lists is taken.
     *
     * @param type the resource type
     * @return whether it was toggled
     */
    public boolean toggleExcluded(Identifier type) {
        if (!hasFoundTab(info) || !(container instanceof JobBlockEntity jobBlock) || !(jobBlock.getLevel() instanceof ServerLevel level)
                || !stillValid(player)) {
            return false;
        }
        BlockPos pos = jobBlock.getBlockPos();
        if (FoundTab.of(level, pos).entry(type).isEmpty()) {
            return false;
        }
        SettlementWorld.toggleExcluded(level, pos, type);
        tabSyncIn = 0;
        return true;
    }

    /**
     * @return whether the menu has a tall panel (Clerk, Producing Jobs)
     */
    public boolean isTall() {
        return JobBlockTabs.of(info.jobType()).tall();
    }

    /**
     * @return the pattern library of the Patterns tab last looked at, as last synced (Producing Jobs only)
     */
    public PatternTab getPatternTab() {
        return patternTab;
    }

    /**
     * Client: the server sent a new Patterns tab.
     *
     * @param tab the tab
     */
    public void setPatternTab(PatternTab tab) {
        patternTab = tab;
    }

    /**
     * Server: changes a pattern of one library and sends the tab at once.
     *
     * @param edit the edit
     */
    public void editPattern(PatternTab.Edit edit) {
        if (!patternJobs.contains(edit.job()) || !(container instanceof JobBlockEntity jobBlock) || !(jobBlock.getLevel() instanceof ServerLevel level)
                || !stillValid(player)) {
            return;
        }
        BlockPos pos = jobBlock.getBlockPos();
        SettlementData data = SettlementData.get(level);
        Job job = edit.job();
        Optional<?> ignored = switch (edit.op()) {
            case SET_OUTPUT -> Patterns.setOutput(level, pos, job, edit.slot(), edit.item());
            case SET_INPUT -> Patterns.setInput(level, pos, job, edit.slot(), edit.index(), edit.item());
            case CYCLE -> Patterns.cycle(level, pos, job, edit.slot(), edit.index());
            case TOGGLE_CHECK -> Optional.of(Patterns.toggleCheck(level, pos, job, edit.slot()));
            case CLEAR -> {
                Patterns.clear(data, pos, job, edit.slot());
                yield Optional.empty();
            }
            case TRANSFER -> Patterns.transfer(level, pos, job, edit.slot(), edit.recipe(), edit.grid());
        };
        tabSyncIn = 0;
    }

    /**
     * Server: puts one of the carried items into a Station Types slot ({@link StationTypes#put}) and sends the tabs at once.
     *
     * @param slot the slot
     * @return whether the item was taken
     */
    private boolean putStationType(int slot) {
        if (!(container instanceof JobBlockEntity jobBlock) || !(jobBlock.getLevel() instanceof ServerLevel level)) {
            // Client: the server decides; the carried stack follows from it.
            return hasPatternsTab(info);
        }
        if (!hasPatternsTab(info) || !stillValid(player)) {
            return false;
        }
        StationTypes.PutResult result = StationTypes.put(level, jobBlock.getBlockPos(), slot, getCarried());
        if (result != StationTypes.PutResult.UNLOCKED && result != StationTypes.PutResult.REPLACED) {
            return false;
        }
        info = JobBlockInfo.of(level, jobBlock.getBlockPos(), info.jobType(), info.level());
        if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new JobBlockInfo.Sync(containerId, info));
        }
        tabSyncIn = 0;
        return true;
    }

    /**
     * @return the Clerk's rules and requests as last synced (Clerk only)
     */
    public ClerkTab getClerkTab() {
        return clerkTab;
    }

    /**
     * Client: the server sent a new info snapshot.
     *
     * @param info the info
     */
    public void setInfo(JobBlockInfo info) {
        this.info = info;
    }

    /**
     * Client: the server sent a new Clerk tab.
     *
     * @param tab the tab
     */
    public void setClerkTab(ClerkTab tab) {
        clerkTab = tab;
    }

    /**
     * Server: sets or deletes the stock rule in a slot (only active slots take a new rule) and checks the rules at once.
     *
     * @param slot the rule slot
     * @param rule the new rule, empty to delete it
     */
    public void editStockRule(int slot, Optional<StockRule> rule) {
        if (!isClerk(info) || !(container instanceof JobBlockEntity jobBlock) || !(jobBlock.getLevel() instanceof ServerLevel level)
                || !stillValid(player)) {
            return;
        }
        SettlementData data = SettlementData.get(level);
        BlockPos pos = jobBlock.getBlockPos();
        boolean valid = rule.map(found -> found.slot() == slot && found.item() != Items.AIR && found.activeAt(jobBlock.getJobLevel())).orElse(true);
        if (!valid) {
            return;
        }
        boolean changed = rule.isPresent() ? StockRules.setRule(data, pos, rule.get()) : StockRules.removeRule(data, pos, slot);
        if (changed) {
            StockRules.check(level, pos, DayClock.dayTime(level));
            tabSyncIn = 0;
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(isTall() || hasFoundTab(info)) || !(player instanceof ServerPlayer serverPlayer) || !(container instanceof JobBlockEntity jobBlock)
                || !(jobBlock.getLevel() instanceof ServerLevel level) || --tabSyncIn > 0) {
            return;
        }
        tabSyncIn = TAB_SYNC_INTERVAL;
        if (hasFoundTab(info)) {
            FoundTab found = FoundTab.of(level, jobBlock.getBlockPos());
            if (!found.equals(foundTab)) {
                foundTab = found;
                PacketDistributor.sendToPlayer(serverPlayer, new FoundTab.Sync(containerId, found));
            }
            return;
        }
        if (hasPatternsTab(info)) {
            // The library of the Patterns tab looked at last (the Base Job's before any).
            Job job = patternJob(getActiveTab()).orElseGet(() -> patternTab.job().orElse(info.jobType()));
            // A pattern its Job filters out loses its checkmark as soon as the tab shows it; foreign ones keep theirs.
            Patterns.uncheckFiltered(level, jobBlock.getBlockPos(), job);
            PatternTab patterns = PatternTab.of(level, jobBlock.getBlockPos(), job);
            if (!patterns.equals(patternTab)) {
                patternTab = patterns;
                PacketDistributor.sendToPlayer(serverPlayer, new PatternTab.Sync(containerId, patterns));
            }
            StationTypeTab stations = StationTypeTab.of(level, jobBlock.getBlockPos());
            if (!stations.equals(stationTypeTab)) {
                stationTypeTab = stations;
                PacketDistributor.sendToPlayer(serverPlayer, new StationTypeTab.Sync(containerId, stations));
            }
            return;
        }
        ClerkTab now = ClerkTab.of(level, jobBlock.getBlockPos());
        if (!now.equals(clerkTab)) {
            clerkTab = now;
            PacketDistributor.sendToPlayer(serverPlayer, new ClerkTab.Sync(containerId, now));
        }
    }

    /**
     * @param info a JobBlock info
     * @return whether the JobBlock is a Mission Board (offers a Mission Job)
     */
    public static boolean isMissionBoard(JobBlockInfo info) {
        return JobBlockTabs.of(info.jobType()) == JobBlockTabs.MISSIONS;
    }

    /**
     * The mark buttons carry the wanted state instead of a toggle, so two players clicking the same entry agree with
     * what they see.
     *
     * @param index  a mission offer index
     * @param marked the wanted mark
     * @return the menu button id that sets it
     */
    public static int markButtonId(int index, boolean marked) {
        return BUTTON_MARK_MISSION + 2 * index + (marked ? 1 : 0);
    }

    /**
     * Assign buttons name the settler by its index in the snapshot the player sees
     * ({@link JobBlockInfo.MissionTab#settlers()}); the server assigns the settler of its own snapshot at that index.
     *
     * @param index   a mission offer index
     * @param settler the settler index in the board's settler list, {@code -1} to unlock the mission
     * @return the menu button id that assigns or unlocks it
     */
    public static int assignButtonId(int index, int settler) {
        return BUTTON_ASSIGN_MISSION + ASSIGN_SLOTS * index + settler + 1;
    }

    /**
     * The toggle buttons carry the wanted state, like the mark buttons.
     *
     * @param line the index of the Job line ({@link JobBlockInfo#places()})
     * @param on   the wanted state of its automatic assignment
     * @return the menu button id that sets it
     */
    public static int autoButtonId(int line, boolean on) {
        return BUTTON_AUTO_ASSIGN + 2 * line + (on ? 1 : 0);
    }

    /**
     * @return the info snapshot
     */
    public JobBlockInfo getInfo() {
        return info;
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId == BUTTON_UNASSIGN) {
            info = info.withoutAssignees();
            if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
                SettlementWorld.unassign(level, jobBlock.getBlockPos());
            }
            return true;
        }
        if (buttonId == BUTTON_UPGRADE) {
            return upgrade(player);
        }
        if (buttonId >= BUTTON_MARK_MISSION && buttonId < BUTTON_MARK_MISSION_END) {
            int index = (buttonId - BUTTON_MARK_MISSION) / 2;
            boolean marked = (buttonId - BUTTON_MARK_MISSION) % 2 == 1;
            if (index >= info.missions().size()) {
                return false;
            }
            if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level
                    && !MissionOffers.setMarked(SettlementData.get(level), jobBlock.getBlockPos(), index, info.missions().get(index).mission(), marked)) {
                return false;
            }
            info = info.withMarked(index, marked);
            return true;
        }
        if (buttonId >= BUTTON_ASSIGN_MISSION && buttonId < BUTTON_ASSIGN_MISSION_END) {
            return assign((buttonId - BUTTON_ASSIGN_MISSION) / ASSIGN_SLOTS, (buttonId - BUTTON_ASSIGN_MISSION) % ASSIGN_SLOTS - 1);
        }
        if (buttonId >= BUTTON_AUTO_ASSIGN && buttonId < BUTTON_AUTO_ASSIGN_END) {
            return setAuto((buttonId - BUTTON_AUTO_ASSIGN) / 2, (buttonId - BUTTON_AUTO_ASSIGN) % 2 == 1);
        }
        if (buttonId == BUTTON_TOGGLE_LEAVES) {
            info = info.withLeavesToggled();
            if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
                JobAssignments.toggleLeaves(SettlementData.get(level), jobBlock.getBlockPos());
            }
            return true;
        }
        if (buttonId >= BUTTON_STATION_TYPE && buttonId < BUTTON_STATION_TYPE + StationTypes.SLOTS) {
            return putStationType(buttonId - BUTTON_STATION_TYPE);
        }
        if (buttonId >= tabButtonId(0) && buttonId < tabButtonId(getTabCount())) {
            // A new tab: its Patterns or Station Types content is sent at once.
            tabSyncIn = 0;
        }
        if (buttonId == BUTTON_TOGGLE_DELIVERY && hasPatternsTab(info)) {
            info = info.withDeliveryToggled();
            if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
                JobAssignments.toggleDelivery(SettlementData.get(level), jobBlock.getBlockPos());
            }
            return true;
        }
        return super.clickMenuButton(player, buttonId);
    }

    /** Sets the automatic assignment toggle of a Job line; switching it on may assign settlers at once (fresh snapshot). */
    private boolean setAuto(int line, boolean on) {
        if (line >= info.places().size() || !info.inSettlement()) {
            return false;
        }
        Job job = info.places().get(line).job();
        if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
            if (!AutoAssignment.setAuto(level, jobBlock.getBlockPos(), job, on)) {
                return false;
            }
            info = JobBlockInfo.of(level, jobBlock.getBlockPos(), info.jobType(), info.level());
            if (player instanceof ServerPlayer serverPlayer) {
                PacketDistributor.sendToPlayer(serverPlayer, new JobBlockInfo.Sync(containerId, info));
            }
        } else {
            info = info.withAuto(job, on);
        }
        return true;
    }

    /** Assigns an offer entry to a settler of the board ({@code settler} = index in the snapshot) or unlocks it ({@code -1}). */
    private boolean assign(int index, int settler) {
        List<JobBlockInfo.MissionSettler> settlers = info.missionTab().settlers();
        if (index >= info.missions().size() || settler >= settlers.size()) {
            return false;
        }
        Identifier mission = info.missions().get(index).mission();
        Optional<JobBlockInfo.MissionSettler> chosen = settler < 0 ? Optional.empty() : Optional.of(settlers.get(settler));
        if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
            SettlementData data = SettlementData.get(level);
            BlockPos board = jobBlock.getBlockPos();
            boolean changed = chosen.isPresent()
                    ? MissionOffers.assign(data, board, index, mission, chosen.get().id(), chosen.get().job(), Missions.all(level.registryAccess()))
                    : MissionOffers.unlock(data, board, index, mission);
            if (!changed) {
                return false;
            }
        }
        info = info.withAssignee(index, chosen.map(JobBlockInfo.MissionSettler::id));
        return true;
    }

    private boolean upgrade(Player player) {
        int next = info.level() + 1;
        if (next > com.gilfort.civmod.settlement.SettlementRules.MAX_LEVEL || (!player.isCreative() && !info.canAffordNext(player.getInventory()))) {
            return false;
        }
        if (container instanceof JobBlockEntity jobBlock && jobBlock.getLevel() instanceof ServerLevel level) {
            java.util.List<com.gilfort.civmod.block.UpgradeCosts.Cost> costs = com.gilfort.civmod.block.UpgradeCosts.get(info.jobType(), next);
            if (!player.isCreative()) {
                if (!com.gilfort.civmod.block.UpgradeCosts.canAfford(player.getInventory(), costs)) {
                    return false;
                }
                com.gilfort.civmod.block.UpgradeCosts.pay(player.getInventory(), costs);
            }
            jobBlock.setJobLevel(next);
            SettlementWorld.setLevel(level, jobBlock.getBlockPos(), next);
            level.playSound(null, jobBlock.getBlockPos(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.BLOCKS,
                    0.6F, 1.0F);
            info = JobBlockInfo.of(level, jobBlock.getBlockPos(), info.jobType(), next);
        } else {
            info = info.withLevel(next);
        }
        return true;
    }

    /**
     * Shift-click moves between the player inventory and the JobBlock storage on the Storage tab only; on the other
     * tabs (Stock, Patterns, Station Types: the player inventory is there to pick items from) it does nothing.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (getActiveTab() != TAB_STORAGE || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = slotIndex < JobBlockEntity.SLOTS
                ? moveItemStackTo(stack, JobBlockEntity.SLOTS, slots.size(), true)
                : moveItemStackTo(stack, 0, JobBlockEntity.SLOTS, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }
}
