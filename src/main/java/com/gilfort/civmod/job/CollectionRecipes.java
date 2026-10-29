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
     * A recipe the scan offers.
     *
     * @param holder the recipe
     * @param weight its weight in the roll: its {@code weight} × the count of its {@code requires} in the scan (decision
     *               2026-10-29; 20 cows: milk weighs 20), its {@code weight} alone without {@code requires}
     */
    public record Offer(RecipeHolder<JobCraftingRecipe> holder, int weight) {
    }

    /**
     * @param level   the level
     * @param job     a Resource Collection Job
     * @param profile its profile
     * @param scan    its scan (null while the first scan runs)
     * @return its innate recipes whose {@code requires} the scan found ({@link ResourceCollectionProfile#count}), ordered
     *         by recipe id
     */
    public static List<Offer> offered(ServerLevel level, Job job, ResourceCollectionProfile profile, @Nullable ScanResult scan) {
        List<RecipeHolder<JobCraftingRecipe>> recipes = InnateRecipes.collecting(level, job);
        if (scan == null || recipes.isEmpty()) {
            return List.of();
        }
        List<Offer> offers = new ArrayList<>();
        for (RecipeHolder<JobCraftingRecipe> holder : recipes) {
            int count = holder.value().requires().map(requires -> profile.count(scan, requires)).orElse(1);
            if (count > 0) {
                offers.add(new Offer(holder, holder.value().weight() * count));
            }
        }
        return offers;
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
        return offered(level, job, profile, scan).stream().map(Offer::holder).map(holder -> {
            JobCraftingRecipe recipe = holder.value();
            ItemStack icon = recipe.result().create();
            Optional<Component> needs = recipe.ingredients().stream()
                    .filter(input -> (settler == null || SettlerTools.beltCount(settler, input::test) == 0) && !collect.contains(input::test)).findFirst()
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
    static List<Offer> allowed(ServerLevel level, Job job, ResourceCollectionProfile profile, @Nullable ScanResult scan,
            Set<Identifier> excluded, Set<Item> atMax) {
        return offered(level, job, profile, scan).stream()
                .filter(offer -> !excluded.contains(type(offer.holder())) && !atMax.contains(offer.holder().value().result().create().getItem())).toList();
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
        if (SettlerTools.freeBeltSlot(settler) < 0) {
            return Optional.empty();
        }
        for (Offer offer : allowed(level, job, profile, scan, excluded, atMax)) {
            for (Ingredient input : missing(settler, offer.holder().value())) {
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
        for (Offer offer : allowed(level, job, profile, scan, excluded, atMax)) {
            for (Ingredient input : missing(settler, offer.holder().value())) {
                taken |= SettlerTools.takeStack(settler, collect, input::test);
            }
        }
        return taken;
    }

    /**
     * The roll between the found types and the recipes ready on the belt (each with its {@link Offer#weight()}): when a recipe wins, the
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
        List<Offer> ready = allowed(context.level(), job, profile, scan, context.excluded(), context.atMax()).stream()
                .filter(offer -> missing(settler, offer.holder().value()).isEmpty()).toList();
        if (ready.isEmpty()) {
            return Optional.empty();
        }
        List<Identifier> types = scan.foundBlocks();
        Set<Identifier> leftOut = profile.leftOut(context, types);
        int found = types.stream().filter(type -> !leftOut.contains(type)).mapToInt(type -> Math.max(0, profile.weight(scan, type))).sum();
        int pick = context.level().getRandom().nextInt(found + ready.stream().mapToInt(Offer::weight).sum());
        if (pick < found) {
            return Optional.empty();
        }
        pick -= found;
        for (Offer offer : ready) {
            pick -= offer.weight();
            if (pick < 0) {
                consume(settler, offer.holder().value());
                return Optional.of(offer.holder().value().result().create());
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
}
