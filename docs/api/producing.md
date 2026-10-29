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
| `execute(level, recipe, result, consumed)` | what one execution puts into the settler's buffer; the inputs are already taken out | the result plus the inputs' remainders (empty buckets, …); a `smithing_transform` result keeps the base item's components |

`ItemFilter.allow(tag)`, `ItemFilter.deny(tag)` and `ItemFilter.ANY` build filters. The Craftsperson is a profile with `crafting` and its crafting-table station, without filters: it crafts everything, slowly (job factor 0.3).

**Occupied stations:** the station of the day and every station of another type a settler crafts at are occupied by it for the day; other settlers of the same or another JobBlock take the next free one ("No free work place" if none). While the settler crafts at a station, players cannot open its GUI (action bar "In use by <name>"). Your Job needs no code for this.

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
- Domains are item tags, so datapacks change them. CivMod's built-in Mechanic (unlocked by and working at a Crafter, `crafting`) uses `civmod:domains/redstone`, its Smith (unlocked by and working at a smithing table, `crafting` and `smithing`) uses `civmod:metals`, its Fletcher (fletching table, `crafting`) `civmod:domains/fletching`, its Stonemason (stonecutter; `stonecutting` at the stonecutter, `crafting` at a crafting table nearby) `civmod:domains/stone` and its Dyer (loom, `crafting`) `civmod:domains/dye`.

## Station extras

Blocks near a Job's station of the day can **replace** its base job factor: the Smith works at 0.3 without and at 1.2 with an anvil near its smithing table. Station extras are datapack data in the Job's `civmod_jobs/<job>.json`, so they work for every Job, CivMod's and yours, without code:

```json
{
  "station_extras": [
    { "blocks": "#minecraft:anvil", "radius": 4, "job_factor": 1.2 },
    { "blocks": ["create:mechanical_press", "minecraft:grindstone"], "radius": 2, "job_factor": 1.5 }
  ]
}
```

- `blocks`: a block id, a `#tag` or a list of both. `radius`: 1–8 blocks around the station in every direction (a cube). `job_factor`: the base job factor while a matching block stands there.
- Several entries match: the **highest** `job_factor` counts (no adding). None matches: the Job's own `job_factor`.
- Read once when the station of the day is chosen and kept for that day; a block placed or removed later counts from the next day (or when the settler has to choose a new station, e.g. its station was broken). After loading a world it is read again.
- Not a bonus multiplier: biome bonuses (and later other speed multipliers) act on the result.
- A later pack's file replaces the whole list. Station extras take effect where the job factor counts: the station of the day of a Producing Job (Resource Collection and Mission Jobs have no job factor in their speed).
- Ask for the factor acting on a settler today with `SettlerWork.jobFactor(level, jobBlock)`.

## Smithing recipes

A profile with `smithing` works `smithing_transform` recipes (netherite upgrades): the result keeps the base item's components like at a vanilla smithing table (enchantments, damage, name). Armor trims (`smithing_trim`) keep their base item and are no patterns.

## Parallel stations and fuel

A furnace Job (CivMod's Smelter, its Cook and Founder, or a modded smeltery) works several stations at once and burns fuel. Two optional hooks turn this on; the loop does the rest.

| Method | Meaning | Default |
|---|---|---|
| `parallelStations(jobBlockLevel)` | how many stations of the main recipe type a settler works at once, each with its own charge | 1 |
| `fuel()` | the default fuel list in priority order (at most 6 items); present = the Job burns fuel | empty: no fuel |

```java
@Override
public int parallelStations(int jobBlockLevel) {
    return jobBlockLevel; // 1 furnace at level 1 … 5 at level 5
}

@Override
public Optional<List<Item>> fuel() {
    return Optional.of(List.of(Items.COAL, Items.CHARCOAL));
}
```

**Parallel stations.** Datapacks override the number per JobBlock level with `parallel_stations` in `civmod_jobs/<job>.json` (a list from level 1; levels beyond the list use its last value; at most 8):

```json
{ "parallel_stations": [1, 2, 3, 4, 5] }
```

- The station of the day comes first; the further ones come from the JobBlock's scroll work points if it has valid ones, else from the stations of the main type nearest to the JobBlock (within 16 blocks, inside the border). Each one is occupied for the day, so several settlers of one JobBlock never share a station. While a settler is short of stations it looks for more at most every 30 s.
- Every station smelts its own charge (one execution at a time) at the full speed of the speed formula; the biome bonus of the station of the day applies to all of them. Steps of another recipe type run only at their own station.
- A station that holds items or burns on its own (a furnace a player uses) is never chosen: it is free only while its inventory is empty and it is not lit by vanilla. Hoppers are not blocked.
- While a charge runs, the station shows its lit state (if its block has one) and players cannot open it.
- A station broken during a charge loses that charge: the inputs never left the buffer and are worked at the next station.

**Fuel.** With `fuel()` present the JobBlock gets a Fuel tab: up to 6 ghost slots shared by all settlers of the block, the order is the priority, the profile's list is the default until the player changes it. Only items with a vanilla burn time (`minecraft:cooking_fuel` component) are taken.

- Every execution needs heat: the recipe's cooking time for a cooking recipe (`AbstractCookingRecipe.cookingTime()`) divided by the station's vanilla cooking speed (2 for a smoker or blast furnace, any block of the `minecraft:block/fast_cooking` predicate), 200 ticks otherwise. Fuel per item therefore stays vanilla, whatever the settler's speed.
- A cooking recipe's work time (`workTime`) is divided by the same cooking speed: CivMod's Cook and Founder are twice as fast as the Smelter on the same 200-tick recipes.
- Each station keeps the *Remaining Heat* of the fuel it burnt, on its block entity, for its next charge: one coal (1600 ticks) smelts 8 items, also over 8 single-item rounds. A broken station loses it.
- A round fetches its fuel with its inputs (from the collect inventory and the requesting Clerk, or as input deliveries on "Delivery: Deliveryman"): the heat its charges need minus the stations' Remaining Heat, burning the highest-priority listed fuel in the buffer or in stock first. It claims only as much as the fuel covers.
- No listed fuel in stock: with exactly one fuel listed, job status and request name it ("Missing: 1× Coal"); with several (any will do) or none listed, both say "Missing: Fuel" (`Request.Missing.ANY_FUEL`). A listed item that does not burn is skipped for the next listed fuel.
- Fuel rests stay in the settler's buffer for the next rounds; they never go to the drop-off. A burnt lava bucket leaves its bucket.

## Innate recipes

Job-only recipes the player cannot craft anywhere: CivMod's recipe type `civmod:job_crafting` (`JobCraftingRecipe`). Every Producing Job named in `jobs` knows them, CivMod's and yours, without code: a profile does not list the type in `recipeTypes()`. Put them in `data/<namespace>/recipe/` like any recipe:

```json
{
  "type": "civmod:job_crafting",
  "jobs": ["civmod:fletcher"],
  "ingredients": ["#minecraft:wool"],
  "result": { "id": "minecraft:string", "count": 2 },
  "tool": "#c:tools/shear",
  "station": "#civmod:stations/fletcher"
}
```

- `jobs`: Job ids. A Job that is neither Producing nor Resource Collection (or not registered) is ignored with a log warning. Resource Collection Jobs use innate recipes differently (`requires`, `weight`): see [Resource Collection Jobs](resource-collection.md#innate-recipes).
- `ingredients`: 1–9 ingredients, shapeless (item ids, `#tags` or lists, like vanilla recipes).
- `result`: the item stack one execution makes.
- `tool` (optional): an item id or `#tag`. The settler takes the tool from its collect inventory onto its belt at the start of a round (like a Resource Collection tool) and wears it by 1 durability per execution; Unbreaking works. Without the tool on the belt or in the collect inventory only this recipe is skipped, and the Patterns tab marks it ("Needs Shears"); the Job keeps working its other recipes.
- `station` (optional): a block `#tag`. The settler works the recipe at the nearest such station within 16 blocks of the JobBlock (like a step of another recipe type); without `station` the station of the Job's main recipe type counts. A missing station skips and marks the recipe ("Needs a Water Cauldron nearby"); the search runs at most every 30 s per settler and tag.

In the library innate recipes sit in the first slots, ordered by recipe id, with a lock (tooltip "Innate recipe of the Fletcher"). They count towards the checkmark limit and are checked automatically like every new pattern while the limit has room; the player can only check and uncheck them (no edit, clear, overwrite or recipe viewer transfer). Your profile's `domain()` and filters do not apply to them: naming the Job in `jobs` is the opt-in. When a datapack adds an innate recipe later, a player pattern on a slot it needs moves to the next free slot (lost if all 54 are used: patterns are ghost items); a removed innate recipe frees its slot; the check state is kept per recipe id. JEI and REI show innate recipes in a display-only "Job Recipes" category (inputs, result, tool, station, the Jobs' icons as catalysts). The example addon's Stonecutter has one (`data/civmod_example/recipe/stonecutter/mossy_cobblestone.json`).

## Checkmarks

Only checked patterns are worked. Each library has its own checkmark limit; only checked patterns the Job can use count against it. A new pattern is checked automatically if it makes something, the profile can use it and a checkmark is free. At the limit it stays unchecked, and the Patterns tab shows "A Stonecutter masters only 6 recipes at once – choose which to check." until the player's next edit of the library. A pattern the filters reject stays unchecked without that line, and loses a checkmark it had. Replacing a pattern keeps its checkmark. Several settlers working the same Job at one JobBlock share its library; its limit is the one of the highest effective skill among them (`checkLimit` of the best settler). When the limit drops (the best settler leaves or switches Job), the last checked patterns the Job can use lose theirs.


## Not part of the profile

Request picking, chain resolution, reservations, input and result deliveries, catch-up, the Patterns tab and the Fuel tab are CivMod's loop and work the same for every Producing Job. Production modes (always / on demand) are not supported yet.
