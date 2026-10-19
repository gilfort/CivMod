# CivMod addon API – Producing Jobs

A Producing Job makes items on request: a Clerk's stock rule (or another source) posts a request, the Job takes the highest-priority one it has a checked pattern for, resolves intermediates with its own patterns, fetches the inputs (or waits for input deliveries on "Delivery: Deliveryman"), works the recipes at its station and delivers the result. CivMod runs this loop for every Producing Job; the Job only fills in a `ProducingProfile`. Read [getting-started.md](getting-started.md) first.

## Recipes come from recipe types

A profile names one or more vanilla or modded `RecipeType`s (`crafting`, `stonecutting`, `smelting`, `campfire_cooking`, a modded type, …). There is no CivMod recipe format: the player makes patterns from those recipes in the JobBlock's Patterns tab (output only: the first matching recipe; exact inputs: the recipe they match), and recipe viewers show the same recipes. Crafting recipes keep their shape in the 3×3 grid; recipes of other types fill the cells in order with their ingredients.

### Recipe viewer transfer (JEI / REI)

The transfer button of JEI and REI fills a pattern (the open one, else the first empty library slot) from a recipe of every recipe type a registered Producing profile names, vanilla or modded; nothing to register in the addon. The transfer fills the library of the Patterns tab shown (or looked at last). A recipe of a type that library's Job does not follow gets a disabled button with "Not a recipe this Job follows" (the server refuses it as well). Checkmark, filter and limit rules are the same as for any new pattern.

The viewers have to recognise the category of the recipe type:

- **JEI:** the category's recipe type must be `IRecipeType.create(yourRecipeType)` (an `IRecipeHolderType`, uid = the recipe type's id), as JEI's own vanilla categories are.
- **REI:** the display's category id must be the recipe type's id or `<namespace>:plugins/<path>` (REI's vanilla convention; `stone_cutting` and `campfire` are mapped too). A display naming its recipe id (`getDisplayLocation()`) makes a pattern following that recipe; without one the pattern uses exactly the shown items.

Recipes in other categories (custom recipe classes, other category ids) get no button; the player drags the output from the viewer onto a pattern slot instead.

## Register a Producing Job

```java
public static final DeferredHolder<Job, Job> STONECUTTER = JOBS.register("stonecutter", () -> Job.builder(JobCategory.PRODUCING)
        .parent(CivModJobs.CRAFTSPERSON)
        .unlockItem(() -> Items.STONECUTTER)
        .icon(() -> Items.STONECUTTER)
        .hungerFactor(0.5)
        .baseExp(2)
        .jobFactor(1.2)
        .profile(StonecutterProfile::new)
        .build());

final class StonecutterProfile implements ProducingProfile {

    static final TagKey<Block> STATION = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("examplemod", "stations/stonecutter"));

    @Override
    public List<RecipeType<?>> recipeTypes() {
        return List.of(RecipeType.STONECUTTING);
    }

    @Override
    public TagKey<Block> station(RecipeType<?> type) {
        return STATION;
    }
}
```

A Producing Job needs a profile (`build()` fails without one). As a Specialization (`parent`) it works at its parent's JobBlock once its `unlockItem` is in a Station Types slot there ([getting-started.md](getting-started.md#station-types)); as a Base Job it needs a JobBlock from `CivModApi.jobBlock` (its window gets the Storage, Info, Patterns and, with Specializations, Station Types tabs).

Every Producing Job unlocked at a JobBlock has its **own pattern library** and Patterns tab (the Base Job's on top, one per Specialization below the window); a settler works only its Job's library. The library of a Job that is no longer unlocked (its Station Type was replaced) is kept, greyed out and not editable, and works again once the Job is unlocked again.

Ship the station tag, e.g. `data/examplemod/tags/block/stations/stonecutter.json`:

```json
{ "values": ["minecraft:stonecutter"] }
```

and the lang keys `job.<namespace>.<job>`, `job.<namespace>.<job>.indefinite` and `<namespace>.scroll.work_place.<job>` (the station's name, shown by the Scroll of Assignment).

## Profile hooks

| Method | Meaning | Default |
|---|---|---|
| `recipeTypes()` | the recipe types the patterns follow; the first is the main type | required |
| `station(type)` | block tag of the station for a recipe type. The main type's station is the work place of the day (Scroll of Assignment, up to 8; else the nearest within 16 blocks of the JobBlock); other types use the nearest station of their tag | required |
| `inputFilter()` | `ItemFilter` for inputs: an input whose alternatives it all rejects blocks the pattern, rejected alternatives of a tag input are skipped | every item |
| `outputFilter()` | `ItemFilter` for outputs: a rejected output blocks the pattern | every item |
| `domain()` | item tag of a crafting Specialization's domain (see [Domains](#domains)) | none: every recipe |
| `filteredLine()` | state line of a pattern the filters or the domain block, in the Patterns tab | "Outside this Job's domain" with a domain, else "Not allowed for this Job" |
| `checkLimit(skill)` | how many patterns can be checked at the settler's effective skill | 6 + 1 per 5 levels |
| `workTime(recipe)` | operations one execution of a recipe takes (an operation lasts as long as the shared speed formula × job factor says) | 1 |
| `execute(level, recipe, result, consumed)` | what one execution puts into the settler's buffer; the inputs are already taken out | the result plus the inputs' remainders (empty buckets, …) |

`ItemFilter.allow(tag)`, `ItemFilter.deny(tag)` and `ItemFilter.ANY` build filters. The Craftsperson is a profile with `crafting` and its crafting-table station, without filters: it crafts everything, slowly (job factor 0.3).

## Domains

A crafting Specialization trades the Craftsperson's "everything, slowly" for "its domain, fast": its profile names a domain item tag, and its Job uses job factor **1.2** (four times the Craftsperson's 0.3; set it with `.jobFactor(1.2)` and let datapacks override it through `job_factor` in `civmod_jobs/<job>.json`).

```java
@Override
public Optional<TagKey<Item>> domain() {
    return Optional.of(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("examplemod", "domains/wood")));
}
```

- A recipe belongs to the domain if **any input** has an alternative in the tag, so a mixed recipe fits several domains (a piston – planks, cobblestone, iron, redstone – is in a wood, a stone, a metal and a redstone domain).
- A pattern outside the domain is greyed out with "Outside this Job's domain" and cannot be checked (like a filtered pattern; it loses a checkmark it had, e.g. after the tag changed).
- Intermediates follow the same rule: the specialist crafts only intermediates inside its domain. One outside it must be in stock, otherwise the request shows "Missing: …" (a Mechanic making repeaters needs sticks from a Craftsperson or the Clerk's storage).
- Domains are item tags, so datapacks change them. CivMod's built-in Mechanic (unlocked by and working at a Crafter, `crafting`) uses `civmod:domains/redstone`.

## Checkmarks

Only checked patterns are worked. Each library has its own checkmark limit; only checked patterns the Job can use count against it. A new pattern is checked automatically if it makes something, the profile can use it and a checkmark is free. At the limit it stays unchecked, and the Patterns tab shows "A Stonecutter masters only 6 recipes at once – choose which to check." until the player's next edit of the library. A pattern the filters reject stays unchecked without that line, and loses a checkmark it had. Replacing a pattern keeps its checkmark. Several settlers working the same Job at one JobBlock share its library; its limit is the one of the highest effective skill among them (`checkLimit` of the best settler). When the limit drops (the best settler leaves or switches Job), the last checked patterns the Job can use lose theirs.


## Not part of the profile

Request picking, chain resolution, reservations, input and result deliveries, catch-up and the Patterns tab are CivMod's loop and work the same for every Producing Job. Production modes (always / on demand) and fuel are not supported yet.
