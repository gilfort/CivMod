# Resource Collection Jobs

A Resource Collection Job scans the area around its work place and produces continuously from what it found, without changing the world (vault `Systems/Resource Collection Jobs.md`). CivMod's loop does everything else: fetching the tool from the collect inventory, walking, fatigue, the per-job buffer and its delivery to the drop-off, food, EXP, catch-up after the chunk was unloaded, the JobBlock's **Found** tab with its exclusions and the **Max** of the Home Clerk's stock rules.

The Job fills in its specifics through a `ResourceCollectionProfile`. There are two levels:

- **`SimpleCollectionProfile.builder()`** – "count the blocks of a tag, produce their drops". A few lines; enough for most Jobs.
- **`ResourceCollectionProfile`** – the full interface for Jobs with their own scan or roll (CivMod's Lumberjack, Miner, Fisherman, Veggy Farmer, Animal Farmer and Beekeeper; the Gatherer and Plantation Farmer use the builder).

Every Resource Collection Base Job needs a JobBlock (see [getting started](getting-started.md#jobblock-factory)); its window shows the tabs Storage, Info and Found, and Station Types when the Base Job has Specializations (CivMod's Gatherer). The Found tab shows one section per Job of the JobBlock's settlers; the JobBlock keeps the scan and the exclusions per Job.

## The builder

The example addon's Sand Digger (`example-addon/`):

```java
public static final DeferredHolder<Job, Job> SAND_DIGGER = JOBS.register("sand_digger", () -> Job.builder(JobCategory.RESOURCE_COLLECTION)
        .icon(SAND_DIGGER_BLOCK_ITEM)
        .bubbleIcon(() -> Items.STONE_SHOVEL)
        .profile(() -> SimpleCollectionProfile.builder(BlockTags.SAND)
                .tool(ItemTags.SHOVELS, () -> Items.STONE_SHOVEL)
                .whenNothingFound(BlockingReason.of("civmod_example.blocked.no_sand").withIcon(Items.SAND))
                .build())
        .build());
```

| Method | Meaning | Default |
|---|---|---|
| `builder(blockTag)` | the blocks the Job scans and produces the drops of | required |
| `tool(itemTag, icon)` | the items that count as the Job's tool; the icon is shown in the "no tool" bubble (usually the starter tool) | none: the Job works bare-handed (no tool wear, never "No tool") |
| `radius(level -> r)` | horizontal scan radius per JobBlock level (1–5) | 10 × level |
| `minimumCount(n)` | how often a block type must be found to count | 1 |
| `productivity(total -> f)` | speed factor by the total number of counted blocks | 1 |
| `whenNothingFound(reason)` | blocking reason when no block of the tag is in reach | "No work place" / "No resources" |

What the builder Job does:

- **Work place:** the nearest exposed block of the tag (a settler can stand next to or on top of it) within 20 blocks of the JobBlock, or a block of the tag set as work point with the Scroll of Assignment. Lang key of the scroll's work place: `<namespace>.scroll.work_place.<job>`.
- **Scan:** every day, around the work place of the day, 10 blocks up and down; each block type of the tag is counted, types below the minimum count are left out. Plants count only when ripe (crops at full age, sweet berry bushes with berries, ripe cocoa and nether wart, cave vines with glow berries) and a two-block plant (tall grass) once. Scans run spread over ticks and skip chunk sections whose palette has none of the blocks. The result is saved with the settler and on the JobBlock (Found tab after a restart, catch-up).
- **Roll:** one found type weighted by its count, then the drops of its loot table in the state it was counted in (an age-2 berry bush gives what an age-2 bush drops) with the settler's tool (none for a bare-handed Job; Silk Touch and Fortune apply). Types the tool cannot harvest are never rolled (greyed out in the Found tab). Types excluded in the Found tab or at their Max at the Home Clerk leave the roll; with all of them left out the roll gives nothing, but still EXP and tool wear, and the job pauses ("All resources excluded" / "Maximum reached" / "Nothing to produce").
- **Speed:** CivMod's shared formula (skill level × tool tier) × `productivity` × the Job's farm curve, if it declares one (below). 1 durability per roll.

## Farm curve

A farming Job declares `Job.builder(...).farmCurve(new FarmCurve(k, caps))`: its speed factor is `0.2 × (1 + k × count)`, at most 2.0, where the count (`ResourceCollectionProfile.farmCount(scan)`; the builder: every counted block) is capped per JobBlock level by `caps` (one value per level, levels beyond the list use the last). One plant is slow; a farm pays off up to the cap of its JobBlock level. Datapacks override it in `data/<namespace>/civmod_jobs/<job>.json`:

```json
{ "farm_curve": { "k": 0.005, "caps": [100, 350, 750, 1350, 2000] } }
```

CivMod's Gatherer and Plantation Farmer use these values.

## Blocking reasons

A profile can say why its Job cannot work. The job then pauses with status **Blocked**: the settler works its next priority and checks again after 30 s. The settler window shows the reason as the job's status; the settler's problem bubble shows the reason's icon, or a barrier (the generic "blocked" marker) without one.

```java
BlockingReason.of("examplemod.blocked.no_sand").withIcon(Items.SAND)
BlockingReason.of(Component.translatable("examplemod.blocked.too_dark"))
```

The builder reports `whenNothingFound` when no work place is found or the scan found nothing. A full profile overrides `blockingReason(scan)`: it is asked at every work decision with the finished scan, or with `null` when no work place was found.

## The full profile

Implement `ResourceCollectionProfile` for a Job with its own scan or roll. Required methods:

| Method | Meaning |
|---|---|
| `isTool(stack)`, `toolIcon()` | the Job's tool; the icon for the "no tool" bubble (a bare-handed Job overrides `needsTool()` with `false`) |
| `isWorkPlace(level, pos)` | whether a loaded position is a valid work place |
| `isAssignableWorkPlace(level, pos)` | whether the Scroll of Assignment may set it |
| `autoSearch(level, jobBlock)` | a work place when the JobBlock has no valid work point (keep it cheap: it runs synchronously) |
| `rescanDaily()` | rescan every day, or only when centre or radius change |
| `createScan(center, radius)` | a new `SectionScan`: one chunk section per `step`, loaded chunks only |
| `produce(context)` | one roll: the items and the tool to wear, empty for nothing |

Optional hooks (`default` methods): `autoSearch(level, jobBlock, free)` (the search the loop calls: it must skip places that fail `free`, i.e. places occupied by another settler today; the default takes `autoSearch(level, jobBlock)` and gives up when that place is occupied, so override it to find the next free one), `scanCenter` (first work point, else the JobBlock; the builder uses the work place of the day), `scanRadius`, `needsResources`, `blockingReason`, `productivity`, `farmCount`, `needsTool`, `speed`, `canObtain`, `swings`, `reach`, `mainResources`, `dropped`, `droppedItems`, `leftOut`, `found`, `foundLines`, `count`, `weight` (both for innate recipes, see below).

**Occupied work places:** the work place of the day is occupied by its settler for the day; no other settler uses it. The loop skips occupied scroll work points and passes `free` to the auto-search; none free → "No free work place". Your Job needs no code for this. Players are never kept from a Resource Collection work place.

Inside `produce`, call `leftOut(context, types)` for the types of the rolled group: it returns the types excluded at the JobBlock and those at their Max: every item `droppedItems(level, pos, type, tool)` names is at its Max (by default the one `dropped` item; override `droppedItems` when a type's loot gives several items, like a cow's beef and leather, so the type keeps rolling while one of them is still wanted). Keep the group roll as it is and spread the weights over the remaining types; a fully left-out group gives an empty roll (`new Produced(List.of(), context.tool())`), never a roll into another group.

Scan results of your own `ScanResult` types are kept in memory only: after a restart the Found tab waits for the next scan.

## Innate recipes

A collecting Job can turn a consumed input into a product while it works (CivMod: milk buckets, mushroom stew, honey bottles). These are `civmod:job_crafting` recipes (`JobCraftingRecipe`, see [Producing Jobs](producing.md#innate-recipes)) that name the Job in `jobs`, with two optional fields only Resource Collection uses:

```json
{
  "type": "civmod:job_crafting",
  "jobs": ["civmod:beekeeper"],
  "ingredients": ["minecraft:glass_bottle"],
  "result": { "id": "minecraft:honey_bottle" },
  "requires": { "block": ["minecraft:beehive", "minecraft:bee_nest"], "state": { "honey_level": "5" } },
  "weight": 1
}
```

- `requires` (optional): what the Job's scan must have found. Either `{"entity": <entity type id, #tag or list>}` or `{"block": <block id, #tag or list>, "state": {<property>: <value or {"min", "max"}>}}` (`state` optional). Without `requires` the recipe is offered whenever the Job has a scan.
- `weight` (optional, default 1, at least 1): the recipe's weight in the roll against the found types, times the count of its `requires` in the scan (20 cows: milk weighs 20, about half of the rolls next to cow loot); without `requires` the weight alone.
- `tool` and `station` do not apply to Resource Collection.

While the scan found what `requires` names, the recipe is an own entry of the Found tab (after the found resources) and joins the roll like a found type: each roll picks between the found types (not left out) with `weight(scan, type)` each and the ready recipes with their `weight` × `count(scan, requires)`; only when a found type wins does the loop call `produce`. A rolled recipe uses up one belt item per ingredient and collects the result; no tool wear, no double drop. The settler fetches the ingredients from its collect inventory onto its belt like a tool (a stack each, while a belt slot is free). Without them the entry is greyed out ("Needs: Bucket") and leaves the roll; the Job keeps working. Exclusion in the Found tab and the Home Clerk's Max of the result apply to the entry. For the pause checks an offered recipe counts like a found type: the Job pauses ("All resources excluded" / "Maximum reached" / "Nothing to produce") only when its found types and its offered recipes are all excluded or at Max, so milk is still made when every cow drop is excluded.

Your profile decides how much of it the scan found with `count(scan, requires)` (0: the recipe is not offered): by default the number of found block types whose default state matches a block requirement; the builder counts the blocks counted in a matching state (an age-3 berry bush); override it for a scan of entities or block states (CivMod's Animal Farmer counts adult animals, the Beekeeper hives with honey or full hives). `weight(scan, type)` is 1 per found type by default (the Animal Farmer weighs an animal type by its count).

## NPC drop pools

Modpacks give CivMod's farming Jobs (Gatherer, Plantation Farmer, Veggy Farmer, Animal Farmer, Beekeeper) extra drops only settlers get, without code: one JSON per entry in `data/<namespace>/civmod_job_drops/` (subfolders are fine). An entry can also add a new plant or animal: the Job's scan then counts it (Found tab, farm curve) and the settler "harvests" it without changing the world.

```json
{
  "job": "civmod:gatherer",
  "from": "minecraft:bush",
  "state": { "age": "3" },
  "result": { "id": "minecraft:melon_seeds", "count": { "min": 1, "max": 2 } },
  "chance": 0.05,
  "mode": "add",
  "tool": "#c:tools/shear"
}
```

- `job`: a Job id or a list. `[]` turns the entry off, so a pack disables a default by shipping a file at the same path. Entries for other Jobs (Lumberjack, Miner, Fisherman, addon Jobs) are ignored with a log warning.
- `from`: a block id, `#block tag` or a list; for the Animal Farmer entity type ids or tags (adult animals around the hay bale).
- `state` (optional, blocks only): block-state properties the counted block must have, exact values or `{"min", "max"}` (unripe blocks are not counted and do not roll the entry).
- `result`: an item id, or `{"id", "count"}` with a number or `{"min", "max"}` (1–64).
- `chance` (optional, 0–1, default 1): each entry rolls on its own every time its type is rolled.
- `mode` (optional): `add` (default) keeps the type's normal loot; `replace` skips it for that Job (one `replace` entry is enough; the other entries still roll).
- `tool` (optional): an item id or `#item tag`. The settler uses such a tool straight from its collect inventory for that drop (1 durability when the entry drops, no visible tool switch); without one the entry is skipped and its Found tab line says "Needs: Shears". Without `tool` the Job's own tool counts (or bare hands).

Enchantments of the tool used apply: Fortune (blocks) or Looting (animals) add 0–1 items per level to the count; Silk Touch on a block gives the block itself once per output instead of the entries' results. An unknown Job, block, entity type or item skips the file with a log warning; files reload with `/reload`.

An output still picks one found type weighted by count, rolls its normal loot (mode `add`), then every entry of that Job and type. Nothing dropped is an empty output that still counts as work. Excluding a type in the Found tab excludes all its drops; the Home Clerk's Max works per result item: an entry whose result is at Max is skipped, a type pauses only when everything it can give is at Max, and a type whose loot is replaced and whose entries are all at Max or miss their tool leaves the roll. The type's tooltip lists its extra drops ("Also: Melon Seeds 5 %"). JEI and REI show every entry in the display-only "Settler Gathering" category (the normal loot is not shown).

CivMod's defaults: the Plantation Farmer gets a vine from vines (100 %), the Gatherer melon, pumpkin and beetroot seeds from bushes (5 % each).

## Biome bonuses

The loop applies a biome bonus's `speed` to `speed × productivity` and its `double_drop` to the stacks `produce` returns. `rare_chance` needs the profile: a roll with a rare group (the Miner's ores, the Fisherman's treasure) overrides `rareGroup()` with the group's name and multiplies the group's chance with `context.rareChance(baseChance)` (capped at 1.0). Profiles without a rare group ignore `rare_chance`. A profile whose Found tab lists the rare group's types overrides `inRareGroup(scan, type)` too (the Miner: its ores), so the tab marks them with a bonus star. See the getting-started guide for the file format.
