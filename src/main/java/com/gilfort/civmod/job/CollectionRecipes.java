package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.JobCraftingRecipe;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.logistics.InnateRecipes;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.npc.Settler;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * The innate recipes of a Resource Collection Job (vault {@code Systems/Resource Collection Jobs.md}, decisions
 * 2026-10-27; {@link JobCraftingRecipe}): which recipes the Job's scan offers, their entries in the Found tab, the
 * inputs the settler fetches onto its belt, and the roll between the found types and the offered recipes. A recipe is
 * offered while its {@code requires} is in the scan; it joins the roll while it is not excluded at the JobBlock, its
 * result is not at its Max at the Home Clerk and its ingredients are on the belt. Its Found tab type is the recipe id.
 */
public final class CollectionRecipes {

    private CollectionRecipes() {
    }

    /**
     * An offered recipe as the Found tab lists it.
     *
     * @param type   the recipe id (the key of its exclusion)
     * @param icon   the result
     * @param result the result item (for the Max check)
     * @param needs  the line of a missing ingredient ("Needs: Bucket"), empty when the settler has or can fetch them all
     */
    public record Listed(Identifier type, ItemStack icon, Item result, Optional<Component> needs) {
    }

    /**
     * @param level   the level
     * @param job     a Resource Collection Job
     * @param profile its profile
     * @param scan    its scan (null while the first scan runs)
     * @return its innate recipes whose {@code requires} the scan found, ordered by recipe id
     */
    public static List<RecipeHolder<JobCraftingRecipe>> offered(ServerLevel level, Job job, ResourceCollectionProfile profile, @Nullable ScanResult scan) {
        List<RecipeHolder<JobCraftingRecipe>> recipes = InnateRecipes.collecting(level, job);
        if (scan == null || recipes.isEmpty()) {
            return List.of();
        }
        return recipes.stream().filter(holder -> holder.value().requires().map(requires -> profile.finds(scan, requires)).orElse(true)).toList();
    }

    /**
     * The Found tab entries of the offered recipes.
     *
     * @param level   the level
     * @param job     a Resource Collection Job
     * @param profile its profile
     * @param scan    its scan
     * @param settler the settler working the JobBlock in that Job, if any (its belt counts)
     * @param collect what is in the JobBlock's collect inventory
     * @return the entries, ordered by recipe id
     */
    public static List<Listed> listed(ServerLevel level, Job job, ResourceCollectionProfile profile, ScanResult scan, @Nullable Settler settler, Stock collect) {
        return offered(level, job, profile, scan).stream().map(holder -> {
            JobCraftingRecipe recipe = holder.value();
            ItemStack icon = recipe.result().create();
            Optional<Component> needs = recipe.ingredients().stream()
                    .filter(input -> (settler == null || belt(settler, input) == 0) && !collect.contains(input::test)).findFirst()
                    .map(InnateRecipes::needsInput);
            return new Listed(type(holder), icon, icon.getItem(), needs);
        }).toList();
    }

    /**
     * @param holder an innate recipe
     * @return its Found tab type: the recipe id
     */
    public static Identifier type(RecipeHolder<JobCraftingRecipe> holder) {
        return holder.id().identifier();
    }

    /** The offered recipes that may join the roll: not excluded and their result not at Max. */
    static List<RecipeHolder<JobCraftingRecipe>> allowed(ServerLevel level, Job job, ResourceCollectionProfile profile, @Nullable ScanResult scan,
            Set<Identifier> excluded, Set<Item> atMax) {
        return offered(level, job, profile, scan).stream()
                .filter(holder -> !excluded.contains(type(holder)) && !atMax.contains(holder.value().result().create().getItem())).toList();
    }

    /**
     * The input the settler should fetch before it works (live play walks to the collect inventory for it): an
     * ingredient of an allowed recipe that is not on the belt but in the collect inventory, while a belt slot is free.
     *
     * @param level    the level
     * @param settler  the settler
     * @param job      the Job
     * @param profile  its profile
     * @param scan     its scan
     * @param excluded the types excluded for the Job at the JobBlock
     * @param atMax    the items at Max at the Home Clerk
     * @param collect  what the settler reaches at its collect inventory
     * @return the missing input, empty if there is nothing to fetch
     */
    static Optional<Predicate<ItemStack>> missingInput(ServerLevel level, Settler settler, Job job, ResourceCollectionProfile profile, @Nullable ScanResult scan,
            Set<Identifier> excluded, Set<Item> atMax, Stock collect) {
        if (freeBeltSlot(settler) < 0) {
            return Optional.empty();
        }
        for (RecipeHolder<JobCraftingRecipe> holder : allowed(level, job, profile, scan, excluded, atMax)) {
            for (Ingredient input : missing(settler, holder.value())) {
                if (SettlerTools.find(collect, input::test).isPresent()) {
                    return Optional.of(input::test);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Takes the missing inputs of the allowed recipes from the collect inventory onto the belt (a stack each).
     *
     * @return whether anything was taken
     */
    static boolean refill(ServerLevel level, Settler settler, Job job, ResourceCollectionProfile profile, @Nullable ScanResult scan, Set<Identifier> excluded,
            Set<Item> atMax, Stock collect) {
        boolean taken = false;
        for (RecipeHolder<JobCraftingRecipe> holder : allowed(level, job, profile, scan, excluded, atMax)) {
            for (Ingredient input : missing(settler, holder.value())) {
                taken |= take(settler, collect, input::test);
            }
        }
        return taken;
    }

    /**
     * The roll between the found types and the recipes ready on the belt (each with its weight): when a recipe wins, the
     * settler uses up its ingredients and gets its result; otherwise the profile rolls ({@link ResourceCollectionProfile#produce}).
     * Without a ready recipe nothing is rolled here.
     *
     * @param settler the settler
     * @param job     the Job
     * @param profile its profile
     * @param context the production context
     * @return the result of the rolled recipe, empty when the found types won (or no recipe is ready)
     */
    static Optional<ItemStack> roll(Settler settler, Job job, ResourceCollectionProfile profile, ResourceCollectionProfile.Context context) {
        ScanResult scan = context.scan();
        if (scan == null) {
            return Optional.empty();
        }
        List<RecipeHolder<JobCraftingRecipe>> ready = allowed(context.level(), job, profile, scan, context.excluded(), context.atMax()).stream()
                .filter(holder -> missing(settler, holder.value()).isEmpty()).toList();
        if (ready.isEmpty()) {
            return Optional.empty();
        }
        List<Identifier> types = scan.foundBlocks();
        Set<Identifier> leftOut = profile.leftOut(context, types);
        int found = types.stream().filter(type -> !leftOut.contains(type)).mapToInt(type -> Math.max(0, profile.weight(scan, type))).sum();
        int pick = context.level().getRandom().nextInt(found + ready.stream().mapToInt(holder -> holder.value().weight()).sum());
        if (pick < found) {
            return Optional.empty();
        }
        pick -= found;
        for (RecipeHolder<JobCraftingRecipe> holder : ready) {
            pick -= holder.value().weight();
            if (pick < 0) {
                consume(settler, holder.value());
                return Optional.of(holder.value().result().create());
            }
        }
        return Optional.empty();
    }

    /** The ingredients of a recipe the belt cannot cover (each belt item covers one ingredient). */
    private static List<Ingredient> missing(Settler settler, JobCraftingRecipe recipe) {
        int[] left = beltCounts(settler);
        List<Ingredient> missing = new ArrayList<>();
        for (Ingredient input : recipe.ingredients()) {
            int slot = coveringSlot(settler, input, left);
            if (slot < 0) {
                missing.add(input);
            } else {
                left[slot]--;
            }
        }
        return missing;
    }

    /** Uses up one belt item per ingredient (the recipe is ready). */
    private static void consume(Settler settler, JobCraftingRecipe recipe) {
        int[] left = beltCounts(settler);
        for (Ingredient input : recipe.ingredients()) {
            int slot = coveringSlot(settler, input, left);
            if (slot >= 0) {
                left[slot]--;
                settler.getInventory().getItem(Settler.BELT_START + slot).shrink(1);
            }
        }
    }

    private static int[] beltCounts(Settler settler) {
        int[] counts = new int[Settler.BELT_SIZE];
        for (int i = 0; i < Settler.BELT_SIZE; i++) {
            counts[i] = settler.getInventory().getItem(Settler.BELT_START + i).getCount();
        }
        return counts;
    }

    private static int coveringSlot(Settler settler, Ingredient input, int[] left) {
        for (int i = 0; i < Settler.BELT_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(Settler.BELT_START + i);
            if (left[i] > 0 && !stack.isEmpty() && input.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private static int belt(Settler settler, Ingredient input) {
        int count = 0;
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            ItemStack stack = settler.getInventory().getItem(i);
            if (!stack.isEmpty() && input.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int freeBeltSlot(Settler settler) {
        for (int i = Settler.BELT_START; i < Settler.BELT_START + Settler.BELT_SIZE; i++) {
            if (settler.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Takes up to a stack of an input from the collect inventory onto a free belt slot. */
    private static boolean take(Settler settler, Stock stock, Predicate<ItemStack> kind) {
        int free = freeBeltSlot(settler);
        Optional<Integer> index = SettlerTools.find(stock, kind);
        if (free < 0 || index.isEmpty()) {
            return false;
        }
        ItemStack sample = stock.contents().get(index.get());
        ItemStack belt = ItemStack.EMPTY;
        for (ItemStack taken : stock.take(sample.getItem(), sample.getMaxStackSize())) {
            if (belt.isEmpty()) {
                belt = taken.copy();
            } else if (ItemStack.isSameItemSameComponents(belt, taken) && belt.getCount() + taken.getCount() <= belt.getMaxStackSize()) {
                belt.grow(taken.getCount());
            } else {
                ItemStack rest = stock.insert(taken);
                if (!rest.isEmpty() && settler.level() instanceof ServerLevel level) {
                    settler.spawnAtLocation(level, rest);
                }
            }
        }
        if (belt.isEmpty()) {
            return false;
        }
        settler.getInventory().setItem(free, belt);
        return true;
    }
}
