# CivMod addon API – Producing Jobs

A Producing Job makes items on request: a Clerk's stock rule (or another source) posts a request, the Job takes the highest-priority one it has a checked pattern for, resolves intermediates with its own patterns, fetches the inputs (or waits for input deliveries on "Delivery: Deliveryman"), works the recipes at its station and delivers the result. CivMod runs this loop for every Producing Job; the Job only fills in a `ProducingProfile`. Read [getting-started.md](getting-started.md) first.

## Recipes come from recipe types

A profile names one or more vanilla or modded `RecipeType`s (`crafting`, `stonecutting`, `smelting`, `campfire_cooking`, a modded type, …). There is no CivMod recipe format: the player makes patterns from those recipes in the JobBlock's Patterns tab (output only: the first matching recipe; exact inputs: the recipe they match), and recipe viewers show the same recipes. Crafting recipes keep their shape in the 3×3 grid; recipes of other types fill the cells in order with their ingredients.

## Register a Producing Job

```java
public static final DeferredHolder<Job, Job> STONECUTTER = JOBS.register("stonecutter", () -> Job.builder(JobCategory.PRODUCING)
        .parent(CivModJobs.CRAFTSPERSON)
        .icon(() -> Items.STONECUTTER)
        .hungerFactor(0.5)
        .baseExp(2)
        .jobFactor(0.5)
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

A Producing Job needs a profile (`build()` fails without one). As a Specialization (`parent`) it works at its parent's JobBlock from level 2 and uses that JobBlock's pattern library; as a Base Job it needs a JobBlock from `CivModApi.jobBlock` (its window gets the Storage, Info and Patterns tabs). The pattern library follows the Job of the settler working the JobBlock: patterns of a recipe type the Job does not follow stay in the library, unchecked.

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
| `filteredLine()` | state line of a pattern the filters block, in the Patterns tab | "Not allowed for this Job" |
| `checkLimit(skill)` | how many patterns can be checked at the settler's effective skill | 6 + 1 per 5 levels |
| `workTime(recipe)` | operations one execution of a recipe takes (an operation lasts as long as the shared speed formula × job factor says) | 1 |
| `execute(level, recipe, result, consumed)` | what one execution puts into the settler's buffer; the inputs are already taken out | the result plus the inputs' remainders (empty buckets, …) |

`ItemFilter.allow(tag)`, `ItemFilter.deny(tag)` and `ItemFilter.ANY` build filters. The Craftsperson is a profile with `crafting`, its crafting-table station and `ItemFilter.deny(civmod:metals)` for inputs and outputs.

## Checkmarks

Only checked patterns are worked. A new pattern is checked automatically if it makes something, the profile can use it and a checkmark is free. At the limit it stays unchecked, and the Patterns tab shows "A Stonecutter masters only 6 recipes at once – choose which to check." until the player's next toggle. A pattern the filters block stays unchecked without that line. Replacing a pattern keeps its checkmark. When the limit drops (a weaker settler), the last checked patterns lose theirs.

## Not part of the profile

Request picking, chain resolution, reservations, input and result deliveries, catch-up and the Patterns tab are CivMod's loop and work the same for every Producing Job. Production modes (always / on demand) and fuel are not supported yet.
