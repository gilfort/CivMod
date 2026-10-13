package com.gilfort.civmod.npc;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.food.SettlerFood;
import com.gilfort.civmod.menu.SettlerInfo;
import com.gilfort.civmod.menu.SettlerMenu;
import com.gilfort.civmod.item.ScrollOfAssignmentItem;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A settler NPC (vault {@code Systems/NPC Population.md}).
 * <p>
 * Stores race, skin, settlement membership, job priority list and equipment. Invulnerable, ignored by hostile mobs
 * (they only target players, villagers and golems) and never despawns. Membership is re-validated every
 * {@value #CHECK_INTERVAL} ticks (staggered per entity): settlers of dissolved settlements become homeless, and
 * homeless settlers join a settlement within {@value #HOMELESS_JOIN_DISTANCE} blocks.
 */
public class Settler extends PathfinderMob {

    /** Inventory slots: tool belt. */
    public static final int BELT_START = 0;
    /** Number of tool belt slots. */
    public static final int BELT_SIZE = 4;
    /** Inventory slot: offhand. */
    public static final int OFFHAND = BELT_START + BELT_SIZE;
    /** Inventory slots: pockets. */
    public static final int POCKETS_START = OFFHAND + 1;
    /** Number of pocket slots. */
    public static final int POCKETS_SIZE = 9;
    /** Inventory slots: armor / clothing (not shown in the PoC). */
    public static final int ARMOR_START = POCKETS_START + POCKETS_SIZE;
    /** Number of armor slots. */
    public static final int ARMOR_SIZE = 4;
    /** Total inventory size. */
    public static final int INVENTORY_SIZE = ARMOR_START + ARMOR_SIZE;

    /** Ticks between membership checks. */
    private static final int CHECK_INTERVAL = 200;
    /** Homeless settlers join settlements with a connected JobBlock within this distance. */
    public static final double HOMELESS_JOIN_DISTANCE = 32.0;
    /** Stroll radius around the last position while homeless. */
    private static final int HOMELESS_RADIUS = 8;

    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(Settler.class, EntityDataSerializers.INT);
    /** Sleeping without bed: -1 = no, 0–3 = straw bed facing (2D value), 4–7 = bare ground facing. */
    private static final EntityDataAccessor<Integer> STRAW = SynchedEntityData.defineId(Settler.class, EntityDataSerializers.INT);
    /** Height of the vanilla straw bed base (4 px). */
    private static final double STRAW_BED_HEIGHT = 4.0 / 16.0;

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private Identifier race = Races.HUMAN;
    private int settlementId = -1;
    private List<SettlerJob> jobs = List.of();
    private final SettlerWork work = new SettlerWork(this);
    private final SettlerFood food = new SettlerFood();
    private final com.gilfort.civmod.job.SettlerSkills skills = new com.gilfort.civmod.job.SettlerSkills();

    /**
     * @param type  the entity type
     * @param level the level
     */
    public Settler(EntityType<? extends Settler> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        getNavigation().setCanOpenDoors(true);
        // Registered here, not in registerGoals(): that runs in the super constructor before fields exist.
        goalSelector.addGoal(2, work.goal());
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    /**
     * @return the attributes of settlers
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN, 0);
        builder.define(STRAW, -1);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    /**
     * @return the skin index (see {@link SettlerSkins})
     */
    public int getSkin() {
        return entityData.get(SKIN);
    }

    /**
     * @param skin the skin index
     */
    public void setSkin(int skin) {
        entityData.set(SKIN, Math.floorMod(skin, SettlerSkins.COUNT));
    }

    /**
     * @return the race id
     */
    public Identifier getRace() {
        return race;
    }

    /**
     * @param race the race id
     */
    public void setRace(Identifier race) {
        this.race = race;
    }

    /**
     * @return the settlement id, {@code -1} if homeless
     */
    public int getSettlementId() {
        return settlementId;
    }

    /**
     * @return whether the settler belongs to no settlement
     */
    public boolean isHomeless() {
        return settlementId < 0;
    }

    /**
     * @return the assigned jobs in priority order (Laborer excluded, it is always the last fallback)
     */
    public List<SettlerJob> getJobs() {
        return jobs;
    }

    /**
     * @param jobs the assigned jobs in priority order
     */
    public void setJobs(List<SettlerJob> jobs) {
        List<SettlerJob> removed = this.jobs.stream().filter(job -> !jobs.contains(job)).toList();
        this.jobs = List.copyOf(jobs);
        removed.forEach(job -> work.forget(job.jobBlock()));
    }

    /**
     * Adds a job with the lowest priority (directly above the Laborer fallback).
     *
     * @param job the job
     */
    public void addJob(SettlerJob job) {
        List<SettlerJob> updated = new java.util.ArrayList<>(jobs);
        updated.add(job);
        jobs = List.copyOf(updated);
    }

    /**
     * Removes the job of a JobBlock.
     *
     * @param jobBlock the JobBlock position
     */
    public void removeJob(BlockPos jobBlock) {
        jobs = jobs.stream().filter(job -> !job.jobBlock().equals(jobBlock)).toList();
        work.forget(jobBlock);
    }

    /**
     * @return the work behaviour (job status, buffers)
     */
    public SettlerWork getWork() {
        return work;
    }

    /**
     * @return the skill levels and EXP per job type
     */
    public com.gilfort.civmod.job.SettlerSkills getSkills() {
        return skills;
    }

    /**
     * @return the food state (food level, saturation, appetite, meals of the day)
     */
    public SettlerFood getFood() {
        return food;
    }

    /**
     * @return the equipment (tool belt, offhand, pockets, armor; see the slot constants)
     */
    public SimpleContainer getInventory() {
        return inventory;
    }

    /**
     * Makes the settler a member of a settlement and restricts it to the settlement area.
     *
     * @param data       the dimension's settlement data
     * @param settlement the settlement
     */
    public void joinSettlement(SettlementData data, Settlement settlement) {
        settlementId = settlement.id();
        data.addMember(settlement, getUUID());
        updateHome(settlement);
    }

    /**
     * Makes the settler homeless: keeps name, inventory and skills, drops all jobs except Laborer.
     */
    public void becomeHomeless() {
        settlementId = -1;
        jobs = List.of();
        setHomeTo(blockPosition(), HOMELESS_RADIUS);
    }

    /**
     * Lies down without a bed: on a straw bed (rendered client-side) or on the bare ground.
     *
     * @param spot  the spot and lying direction
     * @param straw whether a straw bed is shown
     */
    public void sleepOnStraw(SettlerBeds.StrawSpot spot, boolean straw) {
        Vec3 pos = Vec3.atBottomCenterOf(spot.pos());
        // Like vanilla beds: lying height on top of the (4 px) straw bed plus 0.125, otherwise the body is hidden in it.
        setPos(pos.x, pos.y + (straw ? STRAW_BED_HEIGHT : 0.0) + 0.125, pos.z);
        setSleepingPos(spot.pos());
        setPose(Pose.SLEEPING);
        setDeltaMovement(Vec3.ZERO);
        entityData.set(STRAW, spot.facing().get2DDataValue() + (straw ? 0 : 4));
    }

    /**
     * Gets up from a bed, straw bed or the ground.
     */
    public void wakeUp() {
        if (isSleeping()) {
            stopSleeping();
        }
        entityData.set(STRAW, -1);
    }

    /**
     * @return whether the settler sleeps without a real bed
     */
    public boolean isSleepingWithoutBed() {
        return entityData.get(STRAW) >= 0;
    }

    /**
     * @return the facing of the straw bed the settler lies on, empty if none is shown
     */
    public Optional<net.minecraft.core.Direction> strawBedFacing() {
        int value = entityData.get(STRAW);
        return value >= 0 && value < 4 ? Optional.of(net.minecraft.core.Direction.from2DDataValue(value)) : Optional.empty();
    }

    @Override
    public void travel(Vec3 input) {
        if (isSleeping() || food.isAway()) {
            // Lying still: leftover movement (move control, pushes) would slide the settler out of its bed.
            setDeltaMovement(Vec3.ZERO);
            return;
        }
        super.travel(input);
    }

    @Override
    public boolean isPushable() {
        return !isSleeping() && !isInvisible() && super.isPushable();
    }

    @Override
    public boolean isPickable() {
        // Invisible only while away foraging: not interactable.
        return !isInvisible() && super.isPickable();
    }

    @Override
    public boolean isNoGravity() {
        // A straw bed is never placed, nothing holds the settler at its lying height.
        return super.isNoGravity() || isSleepingWithoutBed() || food.isAway();
    }

    @Override
    public net.minecraft.core.@org.jspecify.annotations.Nullable Direction getBedOrientation() {
        int value = entityData.get(STRAW);
        return value >= 0 ? net.minecraft.core.Direction.from2DDataValue(value % 4) : super.getBedOrientation();
    }

    private void updateHome(Settlement settlement) {
        Optional<BlockPos> rest = work.restArea();
        if (rest.isPresent()) {
            setHomeTo(rest.get(), SettlerWork.restRadius());
            return;
        }
        SettlementManager.nearestBlock(settlement, blockPosition())
                .ifPresent(block -> setHomeTo(block.pos(), SettlementRules.joinRadius(block.level())));
    }

    @Override
    public void tick() {
        long profile = level().isClientSide() ? 0L : com.gilfort.civmod.debug.CivProfiler.start();
        super.tick();
        com.gilfort.civmod.debug.CivProfiler.stop(com.gilfort.civmod.debug.CivProfiler.Section.SETTLERS, profile);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        work.aiStep(level);
        if ((tickCount + getId()) % CHECK_INTERVAL == 0) {
            long profile = com.gilfort.civmod.debug.CivProfiler.start();
            checkMembership(level);
            com.gilfort.civmod.debug.CivProfiler.stop(com.gilfort.civmod.debug.CivProfiler.Section.DECISIONS, profile);
        }
    }

    private void checkMembership(ServerLevel level) {
        SettlementData data = SettlementData.get(level);
        if (!isHomeless()) {
            Optional<Settlement> settlement = data.settlement(settlementId);
            if (settlement.isEmpty()) {
                becomeHomeless();
            } else {
                updateHome(settlement.get());
                // Drop jobs whose JobBlock vanished or was given to someone else while this settler was unloaded.
                List<SettlerJob> valid = jobs.stream().filter(job -> JobAssignments.isAssignee(data, job.jobBlock(), getUUID())).toList();
                if (valid.size() != jobs.size()) {
                    setJobs(valid);
                }
            }
            return;
        }
        SettlementManager.settlementNear(data, blockPosition(), HOMELESS_JOIN_DISTANCE).ifPresent(settlement -> joinSettlement(data, settlement));
    }

    /**
     * @return the status shown in the GUI
     */
    public Component statusText() {
        if (isHomeless()) {
            return Component.translatable("civmod.settler.status.homeless");
        }
        if (isSleeping()) {
            return Component.translatable("civmod.settler.status.sleeping");
        }
        Optional<Component> foodStatus = work.foodStatus();
        if (foodStatus.isPresent()) {
            return foodStatus.get();
        }
        return work.activeJob()
                .map(job -> Component.translatable("civmod.settler.status.job", work.status(job.jobBlock()).displayName(), job.jobType().displayName()))
                .orElse(Component.translatable("civmod.settler.status.idle"));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.getItemInHand(hand).getItem() instanceof ScrollOfAssignmentItem || isInvisible()) {
            // Let the scroll handle the interaction (assign / bind); foraging settlers are away.
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (hand == InteractionHand.MAIN_HAND && SettlerFood.isEdible(held)) {
            if (player instanceof ServerPlayer serverPlayer) {
                feedBy(serverPlayer, held);
            }
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer && hand == InteractionHand.MAIN_HAND) {
            SettlerInfo info = SettlerInfo.of(this);
            serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, p) -> new SettlerMenu(id, inventory, this, info), getName()),
                    buffer -> SettlerInfo.STREAM_CODEC.encode(buffer, info));
        }
        return InteractionResult.SUCCESS;
    }

    private void feedBy(ServerPlayer player, ItemStack held) {
        ItemStack one = held.copyWithCount(1);
        ItemStack rest = work.feed(player.level(), one);
        if (ItemStack.matches(rest, one)) {
            player.sendOverlayMessage(Component.translatable("civmod.settler.full", getName()));
            return;
        }
        held.consume(1, player);
        if (!rest.isEmpty() && !ItemStack.isSameItemSameComponents(rest, one) && !player.getInventory().add(rest)) {
            player.spawnAtLocation(player.level(), rest);
        }
    }

    /**
     * Dismisses a homeless settler: drops its inventory and removes it.
     */
    public void dismiss() {
        if (level() instanceof ServerLevel && isHomeless()) {
            Containers.dropContents(level(), this, inventory);
            discard();
        }
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    @Override
    public boolean removeWhenFarAway(double distanceSquared) {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        // The held tool is only a display copy of a tool belt item; never save it.
        ItemStack held = getItemBySlot(EquipmentSlot.MAINHAND);
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        super.addAdditionalSaveData(output);
        setItemSlot(EquipmentSlot.MAINHAND, held);
        output.store("buffers", SettlerWork.BuffersSnapshot.CODEC, work.snapshot());
        output.putString("race", race.toString());
        output.putInt("skin", getSkin());
        output.putInt("settlement", settlementId);
        output.store("jobs", SettlerJob.CODEC.listOf(), jobs);
        ContainerHelper.saveAllItems(output, inventory.getItems());
        food.save(output);
        output.store("skills", com.gilfort.civmod.job.SettlerSkills.CODEC, skills.snapshot());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        race = Optional.ofNullable(Identifier.tryParse(input.getStringOr("race", Races.HUMAN.toString()))).orElse(Races.HUMAN);
        setSkin(input.getIntOr("skin", 0));
        settlementId = input.getIntOr("settlement", -1);
        jobs = input.read("jobs", SettlerJob.CODEC.listOf()).map(List::copyOf).orElse(List.of());
        input.read("buffers", SettlerWork.BuffersSnapshot.CODEC).ifPresent(work::restore);
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, inventory.getItems());
        food.load(input);
        input.read("skills", com.gilfort.civmod.job.SettlerSkills.CODEC).ifPresent(skills::restore);
        setInvisible(food.isAway());
    }
}
