package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.JobCraftingRecipe;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
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
 * <li>Roll: an animal type weighted by its count, then the loot table of a live adult of that type in the box of the
 * scan (one small, bounded query per output) with the settler as attacker; a chicken lays an egg instead 20 % of the time.
 * No animal is hurt, no XP drops.</li>
 * <li>Food: every roll takes {@link AnimalFood#PER_ROLL} of the breeding food of the rolled type from the collect inventory; a type
 * without food leaves the roll, and without food for every type the Job stops with "No food" ({@link FoodCostProfile},
 * {@link FoodOrders}).</li>
 * <li>Shearing: while a sheep is counted the roll has one more entry, {@link #SHEAR_SHEEP}, weighted like the sheep. With
 * shears in the collect inventory it gives 1 to 3 wool for no food and wears the shears, not the axe; without shears, or
 * switched off, it leaves the roll. {@link FoodOrders} asks for missing shears.</li>
 * <li>Speed: the shared formula × the farm curve of the counted animals.</li>
 * <li>NPC drop pool ({@link JobDrops}): the scan also counts the adult animals of the Job's entries; their entries add to
 * the loot of a rolled type (or replace it).</li>
 * </ul>
 */
public final class AnimalFarmerProfile extends FarmProfile implements FoodCostProfile {

    private static final Identifier JOB = CivMod.id("animal_farmer");

    /**
     * The shear entry of the sheep ("Wool (shear)" in the Found tab): the key of its exclusion at the JobBlock. It is
     * no entity type; it is listed while the scan counts a sheep.
     */
    public static final Identifier SHEAR_SHEEP = CivMod.id("animal_farmer/shear_sheep");
    private static final Predicate<ItemStack> SHEARS = stack -> stack.is(Items.SHEARS);
    private static final Identifier SHEEP = Identifier.withDefaultNamespace("sheep");
    /** How many wool a shearing gives, like vanilla. */
    private static final int SHEAR_MIN = 1;
    private static final int SHEAR_MAX = 3;

    /** The chance that a rolled chicken gives an egg instead of its loot. */
    public static final double EGG_CHANCE = 0.2;
    private static final int AUTO_SEARCH_VERTICAL = 10;
    /** How far up and down from the hay bale animals count. */
    private static final int HALF_HEIGHT = 5;

    /** The items each loot table can give ({@link #droppedItems}); a reload brings new tables, the old ones drop out. */
    private final Map<LootTable, Set<Item>> possibleItems = new WeakHashMap<>();

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
    public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock, Predicate<BlockPos> free) {
        return BlockSearch.nearest(level, jobBlock, AUTO_SEARCH_HORIZONTAL, AUTO_SEARCH_VERTICAL, state -> state.is(Blocks.HAY_BLOCK),
                pos -> free.test(pos) && BlockSearch.isExposed(level, pos));
    }

    @Override
    public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
        JobDrops.Pool pool = pool();
        return new AnimalScan(center, radius, HALF_HEIGHT, type -> !pool.of(type).isEmpty());
    }

    @Override
    public Optional<Identifier> dropPool() {
        return Optional.of(JOB);
    }

    @Override
    public boolean entityDrops() {
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

    /** The counted adults of an innate recipe's required types (the cows for milk). */
    @Override
    public int count(ScanResult scan, JobCraftingRecipe.Requirement requires) {
        AnimalScan.Result animals = animals(scan);
        return animals.counts().entrySet().stream().filter(entry -> requires.matches(BuiltInRegistries.ENTITY_TYPE.getValue(entry.getKey())))
                .mapToInt(Map.Entry::getValue).sum();
    }

    /** An animal type weighs as much as its count, like in {@link #produce}. */
    @Override
    public int weight(ScanResult scan, Identifier type) {
        return animals(scan).counts().getOrDefault(type, 0);
    }

    /** Then the drop pool entries of the rolled type (Looting of the tool used applies, never Silk Touch). */
    @Override
    public Optional<Produced> produce(Context context, JobDrops.Tools tools) {
        AnimalScan.Result scan = animals(context.scan());
        List<Identifier> types = scan.foundBlocks();
        if (types.isEmpty()) {
            return Optional.empty();
        }
        List<Identifier> group = rollable(context, scan, tools);
        boolean shear = shearing(context, scan, tools);
        if (group.isEmpty() && !shear) {
            // Every type excluded or at Max: an empty roll still counts as work (EXP, tool wear).
            return Optional.of(new Produced(List.of(), context.tool()));
        }
        List<Identifier> fed = group.stream().filter(type -> foodOf(context, type).filter(food -> hasFood(food, tools)).isPresent()).toList();
        if (fed.isEmpty() && !shear) {
            // Nothing can be rolled without food: no loot, no work (the loop shows "No food", see noFood).
            return Optional.empty();
        }
        ServerLevel level = context.level();
        // The shear entry weighs as much as the sheep it shears, like the sheep's slaughter entry.
        int shearWeight = shear ? scan.counts().get(SHEEP) : 0;
        int pick = level.getRandom().nextInt(fed.stream().mapToInt(type -> scan.counts().get(type)).sum() + shearWeight);
        Identifier rolled = null;
        for (Identifier type : fed) {
            pick -= scan.counts().get(type);
            if (pick < 0) {
                rolled = type;
                break;
            }
        }
        if (rolled == null) {
            return Optional.of(shear(context, tools));
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(rolled);
        Optional<Animal> sample = AnimalScan.sample(level, AnimalScan.box(context.poi(), scanRadius(context.jobBlockLevel()), HALF_HEIGHT), type, level.getRandom());
        if (sample.isEmpty()) {
            // An animal that walked off since the scan: the work is done, nothing to collect this time.
            return Optional.of(new Produced(List.of(), context.tool()));
        }
        Animal animal = sample.get();
        // Every roll costs the breeding food of its type; the check above made sure it is there.
        Set<Item> food = Set.copyOf(foodOf(context, rolled).orElseThrow());
        if (!tools.take(stack -> food.contains(stack.getItem()), AnimalFood.PER_ROLL)) {
            return Optional.empty();
        }
        List<JobDrops.Drop> entries = pool().of(type);
        List<ItemStack> drops = new ArrayList<>();
        if (!JobDrops.replaces(entries)) {
            drops.addAll(animal instanceof Chicken && level.getRandom().nextDouble() < EGG_CHANCE ? egg(level, animal) : loot(level, animal, context.settler()));
        }
        drops.addAll(JobDrops.roll(level, entries, context.tool(), context.atMax(), tools, ItemStack.EMPTY));
        return Optional.of(new Produced(drops, context.tool()));
    }

    /**
     * @param context the production context
     * @param scan    the scan
     * @param tools   the collect inventory
     * @return whether the shear entry takes part in the roll: a sheep is counted, the entry is on and its wool is not at Max,
     *         and the collect inventory holds shears
     */
    private boolean shearing(Context context, AnimalScan.Result scan, JobDrops.Tools tools) {
        return wantsShears(context, scan) && tools.find(SHEARS).isPresent();
    }

    /** Whether the shear entry is switched on, a sheep is counted and the wool is not at Max. */
    private boolean wantsShears(Context context, AnimalScan.Result scan) {
        return scan.counts().getOrDefault(SHEEP, 0) > 0 && !leftOut(context, List.of(SHEAR_SHEEP)).contains(SHEAR_SHEEP);
    }

    /**
     * @param context the production context
     * @param tools   the collect inventory
     * @return whether the shear entry would work but the collect inventory holds no shears: the tool supply orders them
     */
    @Override
    public boolean noShears(Context context, JobDrops.Tools tools) {
        return wantsShears(context, animals(context.scan())) && tools.find(SHEARS).isEmpty();
    }

    /** One shearing: 1 to 3 wool of a live adult sheep's colour; the shears wear, the sheep is not changed. */
    private Produced shear(Context context, JobDrops.Tools tools) {
        ServerLevel level = context.level();
        Optional<Animal> sample = AnimalScan.sample(level, AnimalScan.box(context.poi(), scanRadius(context.jobBlockLevel()), HALF_HEIGHT),
                EntityTypes.SHEEP, level.getRandom());
        if (sample.isEmpty() || !(sample.get() instanceof Sheep sheep)) {
            // A sheep that walked off since the scan: the work is done, nothing to collect this time.
            return new Produced(List.of(), ItemStack.EMPTY);
        }
        tools.wear(SHEARS);
        Item wool = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(sheep.getColor().getName() + "_wool"));
        return new Produced(List.of(new ItemStack(wool, SHEAR_MIN + level.getRandom().nextInt(SHEAR_MAX - SHEAR_MIN + 1))), ItemStack.EMPTY);
    }

    /**
     * @param context the production context
     * @param tools   the collect inventory
     * @return whether the scan holds animals that could be rolled but none of their types has food in the collect inventory:
     *         the farmer shows "No food"
     */
    @Override
    public boolean noFood(Context context, JobDrops.Tools tools) {
        List<Identifier> group = rollable(context, animals(context.scan()), tools);
        return !group.isEmpty() && group.stream().noneMatch(type -> foodOf(context, type).filter(food -> hasFood(food, tools)).isPresent())
                && !shearing(context, animals(context.scan()), tools);
    }

    @Override
    public List<List<Item>> unfed(Context context, JobDrops.Tools tools) {
        return rollable(context, animals(context.scan()), tools).stream().map(type -> foodOf(context, type)).flatMap(Optional::stream)
                .filter(food -> !food.isEmpty() && !hasFood(food, tools)).distinct().toList();
    }

    /** The found types that are not excluded, at Max or used up by a replacing entry. */
    private List<Identifier> rollable(Context context, AnimalScan.Result scan, JobDrops.Tools tools) {
        List<Identifier> types = scan.foundBlocks();
        Set<Identifier> leftOut = leftOut(context, types);
        JobDrops.Pool pool = pool();
        return types.stream().filter(type -> !leftOut.contains(type) && !pool.exhausted(type, true, context.atMax(), tools)).toList();
    }

    /** The foods of a type, worked out at a live animal of it in the box of the scan when they are not known yet. */
    private Optional<List<Item>> foodOf(Context context, Identifier type) {
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(type);
        ServerLevel level = context.level();
        return AnimalFood.of(entityType,
                () -> AnimalScan.sample(level, AnimalScan.box(context.poi(), scanRadius(context.jobBlockLevel()), HALF_HEIGHT), entityType, level.getRandom()));
    }

    /** Whether the collect inventory holds the food of one roll of a type. */
    private static boolean hasFood(List<Item> food, JobDrops.Tools tools) {
        Set<Item> items = Set.copyOf(food);
        return tools.count(stack -> items.contains(stack.getItem())) >= AnimalFood.PER_ROLL;
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

    /** The first item the type's loot can give (fixed: read from the loot table, never rolled). */
    @Override
    public Item dropped(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        return droppedItems(level, pos, type, tool).iterator().next();
    }

    /**
     * Every item the type's loot table can give (a cow: leather and beef; a chicken also its eggs) and the results of the
     * Job's drop pool entries for it, so the type is at its Max only when all of them are. The loot items are read once
     * per loot table and kept until a datapack reload replaces the table.
     */
    @Override
    public Set<Item> droppedItems(ServerLevel level, BlockPos pos, Identifier type, ItemStack tool) {
        if (type.equals(SHEAR_SHEEP)) {
            return woolItems();
        }
        return pool().droppedItems(type, true, () -> lootItems(level, type));
    }

    /** Every wool a shearing can give, one per dye color. */
    private static Set<Item> woolItems() {
        Set<Item> wool = new LinkedHashSet<>();
        for (DyeColor color : DyeColor.values()) {
            wool.add(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(color.getName() + "_wool")));
        }
        return Collections.unmodifiableSet(wool);
    }

    private Set<Item> lootItems(ServerLevel level, Identifier type) {
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(type);
        Optional<ResourceKey<LootTable>> key = entityType.getDefaultLootTable();
        if (key.isEmpty()) {
            return Set.of(Items.AIR);
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key.get());
        Set<Item> items = possibleItems.get(table);
        if (items == null) {
            Set<Item> found = new LinkedHashSet<>(LootItems.of(level, key.get()));
            if (entityType == EntityTypes.CHICKEN) {
                found.addAll(LootItems.of(level, BuiltInLootTables.CHICKEN_LAY));
            }
            items = found.isEmpty() ? Set.of(Items.AIR) : Collections.unmodifiableSet(found);
            possibleItems.put(table, items);
        }
        return items;
    }

    /** The counted animal types and, while a sheep is counted, its shear entry. */
    @Override
    public List<Identifier> mainResources(ScanResult scan) {
        List<Identifier> types = animals(scan).foundBlocks();
        if (!types.contains(SHEEP)) {
            return types;
        }
        List<Identifier> main = new ArrayList<>(types);
        main.add(SHEAR_SHEEP);
        return main;
    }

    /** Each animal type as its spawn egg, named with its count; the sheep's shear entry as shears after the types. */
    @Override
    public List<Found> found(ScanResult scan, Optional<Worker> worker) {
        AnimalScan.Result animals = animals(scan);
        List<Found> found = new ArrayList<>(animals.foundBlocks().stream().map(type -> {
            EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(type);
            ItemStack icon = SpawnEggItem.byId(entityType).map(ItemStack::new).orElseGet(() -> new ItemStack(Items.HAY_BLOCK));
            icon.set(DataComponents.ITEM_NAME, Component.translatable("civmod.gui.found.animal", entityType.getDescription(), animals.counts().get(type)));
            return new Found(type, icon, true);
        }).toList());
        if (animals.counts().getOrDefault(SHEEP, 0) > 0) {
            ItemStack icon = new ItemStack(Items.SHEARS);
            icon.set(DataComponents.ITEM_NAME, Component.translatable("civmod.gui.found.shear", animals.counts().get(SHEEP)));
            found.add(new Found(SHEAR_SHEEP, icon, true));
        }
        return found;
    }

    @Override
    public List<Component> foundLines(ScanResult scan, Optional<Worker> worker) {
        return List.of(Component.translatable("civmod.gui.found.animals", animals(scan).total()));
    }
}
