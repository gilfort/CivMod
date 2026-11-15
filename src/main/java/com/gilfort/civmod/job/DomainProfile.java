package com.gilfort.civmod.job;

import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ProducingProfile;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/**
 * The Producing specifics of the crafting Specializations that work one domain: their main recipe type at their own
 * station ({@code civmod:stations/<job>}, the station of the day), {@code crafting} as a second type at a crafting
 * table nearby ({@code civmod:stations/craftsperson}), only recipes with an input in the domain
 * ({@code civmod:domains/<domain>}). Their base job factor 0.3 becomes 1.2 with the station extra of their job file;
 * their innate recipes come from {@code civmod:job_crafting} recipes in the datapack.
 * <ul>
 * <li>{@link #fletcher()}: {@code crafting} at a fletching table, fletching domain (string, feathers, flint, arrows;
 * vault {@code Jobs/Fletcher.md});</li>
 * <li>{@link #stonemason()}: {@code stonecutting} at a stonecutter and {@code crafting} at a crafting table nearby,
 * stone domain (vault {@code Jobs/Stonemason.md});</li>
 * <li>{@link #dyer()}: {@code crafting} at a loom, dye domain (dyes and their crafting sources, so every dyed block;
 * vault {@code Jobs/Dyer.md});</li>
 * <li>{@link #baker()}: {@code crafting} at the Craftsperson's crafting table, food domain (everything with a food, crop,
 * egg, milk or sugar input; vault {@code Jobs/Baker.md});</li>
 * <li>{@link #tailor()}: the same at the crafting table with the textile domain (wool, carpets, beds, banners, leather,
 * string); the {@link TailorProfile} adds the colour of the cloth (vault {@code Jobs/Tailor.md});</li>
 * <li>{@link #carpenter()}: the same at the crafting table with the wood domain (logs, planks and everything made of
 * wood; vault {@code Jobs/Carpenter.md}), at job factor 1.2 without a station extra.</li>
 * <li>{@link #printer()}: the same at the crafting table with the print domain (paper, books, bookshelves, feathers, ink;
 * vault {@code Jobs/Printer.md}), at job factor 1.2 from the start, without a station extra.</li>
 * </ul>
 */
public final class DomainProfile implements ProducingProfile {

    private final List<RecipeType<?>> recipeTypes;
    private final TagKey<Block> station;
    private final TagKey<Item> domain;

    private DomainProfile(List<RecipeType<?>> recipeTypes, TagKey<Block> station, String domain) {
        this.recipeTypes = List.copyOf(recipeTypes);
        this.station = station;
        this.domain = TagKey.create(Registries.ITEM, CivMod.id("domains/" + domain));
    }

    private static TagKey<Block> stationOf(String job) {
        return TagKey.create(Registries.BLOCK, CivMod.id("stations/" + job));
    }

    /** @return the Fletcher's profile: {@code crafting} at fletching tables, fletching domain */
    public static DomainProfile fletcher() {
        return new DomainProfile(List.of(RecipeType.CRAFTING), stationOf("fletcher"), "fletching");
    }

    /** @return the Stonemason's profile: {@code stonecutting} at stonecutters, {@code crafting} nearby, stone domain */
    public static DomainProfile stonemason() {
        return new DomainProfile(List.of(RecipeType.STONECUTTING, RecipeType.CRAFTING), stationOf("stonemason"), "stone");
    }

    /** @return the Dyer's profile: {@code crafting} at looms, dye domain */
    public static DomainProfile dyer() {
        return new DomainProfile(List.of(RecipeType.CRAFTING), stationOf("dyer"), "dye");
    }

    /** @return the Baker's profile: {@code crafting} at the Craftsperson's crafting tables, food domain */
    public static DomainProfile baker() {
        return new DomainProfile(List.of(RecipeType.CRAFTING), CraftspersonProfile.STATION, "food");
    }

    /** @return the Printer's profile: {@code crafting} at the Craftsperson's crafting tables, print domain */
    public static DomainProfile printer() {
        return new DomainProfile(List.of(RecipeType.CRAFTING), CraftspersonProfile.STATION, "print");
    }

    /** @return the Tailor's domain, station and recipe types: {@code crafting} at the Craftsperson's crafting tables, textile domain */
    static DomainProfile tailor() {
        return new DomainProfile(List.of(RecipeType.CRAFTING), CraftspersonProfile.STATION, "textile");
    }

    /** @return the Carpenter's profile: {@code crafting} at the Craftsperson's crafting tables, wood domain */
    public static DomainProfile carpenter() {
        return new DomainProfile(List.of(RecipeType.CRAFTING), CraftspersonProfile.STATION, "wood");
    }

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return recipeTypes;
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        // The main type at the Job's own station; crafting as a second type at a crafting table.
        return type == recipeTypes.getFirst() ? station : CraftspersonProfile.STATION;
    }

    @Override
    public Optional<TagKey<Item>> domain() {
        return Optional.of(domain);
    }
}
