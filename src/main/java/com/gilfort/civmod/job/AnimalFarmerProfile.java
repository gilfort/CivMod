package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * The Animal Farmer (vault {@code Jobs/Animal Farmer.md}), a Specialization of the Gatherer: collects the loot of the
 * adult farm animals around its hay bale without killing them, and eggs from chickens.
 * <ul>
 * <li>Tool: an axe ({@code #minecraft:axes}); its Looting raises the loot.</li>
 * <li>Work place: a hay bale set with the Scroll of Assignment, else the nearest within 20 blocks.</li>
 * <li>Scan: every day one box query around the hay bale, 5 × JobBlock level wide, for the adults of
 * {@code #civmod:farm_animals} ({@link AnimalScan}).</li>
 * <li>Roll: an animal type weighted by its count, then the loot table of a live adult of that type near the hay bale
 * (one small, bounded query per output) with the settler as attacker; a chicken lays an egg instead 20 % of the time.
 * No animal is hurt, no XP drops.</li>
 * <li>Speed: the shared formula × the farm curve of the counted animals.</li>
 * </ul>
 */
public final class AnimalFarmerProfile implements ResourceCollectionProfile {

    /** The chance that a rolled chicken gives an egg instead of its loot. */
    public static final double EGG_CHANCE = 0.2;
    private static final int AUTO_SEARCH_HORIZONTAL = 20;
    private static final int AUTO_SEARCH_VERTICAL = 10;
    /** How far up and down from the hay bale animals count. */
    private static final int HALF_HEIGHT = 5;
    /** The scan radius per JobBlock level. */
    private static final int RADIUS_PER_LEVEL = 5;
    /** Where a roll looks for its sample animal: the scan radius at the highest JobBlock level. */
    private static final int SAMPLE_RADIUS = 5 * RADIUS_PER_LEVEL;
    /** How often {@link #dropped} rolls a throwaway animal's loot to find an item. */
    private static final int DROPPED_TRIES = 4;

    @Override
    public boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.AXES);
    }

    @Override
    public Item toolIcon() {
        return Items.STONE_AXE;
    }

    @Override
    public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.HAY_BLOCK);
    }

    @Override
    public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.HAY_BLOCK);
    }

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock) {
        return autoSearch(level, jobBlock, pos -> true);
    }

    @Override
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock, Predicate<BlockPos> free) {
        return BlockSearch.nearest(level, jobBlock, AUTO_SEARCH_HORIZONTAL, AUTO_SEARCH_VERTICAL, state -> state.is(Blocks.HAY_BLOCK),
                pos -> free.test(pos) && MinerProfile.isExposed(level, pos));
    }

    /** The hay bale of the day. */
    @Override
    public BlockPos scanCenter(BlockPos jobBlock, List<BlockPos> workPoints, BlockPos workPlace) {
        return workPlace;
    }

    @Override
    public int scanRadius(int jobBlockLevel) {
        return RADIUS_PER_LEVEL * jobBlockLevel;
    }

    @Override
    public boolean rescanDaily() {
        return true;
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        return new AnimalScan(center, radius, HALF_HEIGHT);
    }

    @Override
    public boolean needsResources() {
        return true;
    }

    /** Every counted animal. */
    @Override
    public int farmCount(@Nullable ScanResult scan) {
        return animals(scan).total();
    }

    private static AnimalScan.Result animals(@Nullable ScanResult scan) {
        return scan instanceof AnimalScan.Result animals ? animals : AnimalScan.Result.EMPTY;
    }

    @Override
    public Optional<Produced> produce(Context context) {
        AnimalScan.Result scan = animals(context.scan());
        List<Identifier> types = scan.foundBlocks();
        if (types.isEmpty()) {
            return Optional.empty();
        }
        Set<Identifier> leftOut = leftOut(context, types);
        List<Identifier> group = types.stream().filter(type -> !leftOut.contains(type)).toList();
        if (group.isEmpty()) {
            // Every type excluded or at Max: an empty roll still counts as work (EXP, tool wear).
            return Optional.of(new Produced(List.of(), context.tool()));
        }
        ServerLevel level = context.level();
        int pick = level.getRandom().nextInt(group.stream().mapToInt(type -> scan.counts().get(type)).sum());
        Identifier rolled = group.getLast();
        for (Identifier type : group) {
            pick -= scan.counts().get(type);
            if (pick < 0) {
                rolled = type;
                break;
            }
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(rolled);
        Optional<Animal> sample = AnimalScan.sample(level, AnimalScan.box(context.poi(), SAMPLE_RADIUS, HALF_HEIGHT), type, level.getRandom());
        // An animal that walked off since the scan: the work is done, nothing to collect this time.
        List<ItemStack> drops = sample.map(animal -> animal instanceof Chicken && level.getRandom().nextDouble() < EGG_CHANCE
                ? egg(level, animal) : loot(level, animal, context.settler())).orElse(List.of());
        return Optional.of(new Produced(drops, context.tool()));
    }

    /** The loot of an animal as if the settler had killed it (Looting from the settler's main hand). */
    private static List<ItemStack> loot(ServerLevel level, Animal animal, @Nullable LivingEntity settler) {
        Optional<ResourceKey<LootTable>> key = animal.getLootTable();
        if (key.isEmpty()) {
            return List.of();
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, animal)
                .withParameter(LootContextParams.ORIGIN, animal.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, settler == null ? level.damageSources().generic() : level.damageSources().mobAttack(settler))
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, settler)
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, settler)
                .create(LootContextParamSets.ENTITY);
        return roll(level, key.get(), params);
    }

    /** What the chicken lays (the egg of its variant). */
    private static List<ItemStack> egg(ServerLevel level, Animal chicken) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, chicken.position())
                .withParameter(LootContextParams.THIS_ENTITY, chicken)
                .create(LootContextParamSets.GIFT);
        return roll(level, BuiltInLootTables.CHICKEN_LAY, params);
    }

    private static List<ItemStack> roll(ServerLevel level, ResourceKey<LootTable> key, LootParams params) {
        List<ItemStack> drops = new ArrayList<>();
        level.getServer().reloadableRegistries().getLootTable(key).getRandomItems(params, drops::add);
        return drops;
    }

    /** The first item of the type's loot, rolled on a throwaway animal (never added to the world). */
    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        Entity entity = BuiltInRegistries.ENTITY_TYPE.getValue(type).create(level, EntitySpawnReason.LOAD);
        if (!(entity instanceof Animal animal)) {
            return Items.AIR;
        }
        animal.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        for (int i = 0; i < DROPPED_TRIES; i++) {
            Optional<ItemStack> first = loot(level, animal, null).stream().filter(stack -> !stack.isEmpty()).findFirst();
            if (first.isPresent()) {
                return first.get().getItem();
            }
        }
        return Items.AIR;
    }

    /** Each animal type as its spawn egg, named with its count. */
    @Override
    public List<Found> found(ScanResult scan, Optional<Worker> worker) {
        AnimalScan.Result animals = animals(scan);
        return animals.foundBlocks().stream().map(type -> {
            EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(type);
            ItemStack icon = SpawnEggItem.byId(entityType).map(ItemStack::new).orElseGet(() -> new ItemStack(Items.HAY_BLOCK));
            icon.set(DataComponents.ITEM_NAME, Component.translatable("civmod.gui.found.animal", entityType.getDescription(), animals.counts().get(type)));
            return new Found(type, icon, true);
        }).toList();
    }

    @Override
    public List<Component> foundLines(ScanResult scan, Optional<Worker> worker) {
        return List.of(Component.translatable("civmod.gui.found.animals", animals(scan).total()));
    }
}
