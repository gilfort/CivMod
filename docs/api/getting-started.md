# CivMod addon API – getting started

CivMod's addon API is the package `com.gilfort.civmod.api`. CivMod registers its own Jobs and JobBlocks through it, so an addon Job behaves exactly like a built-in one. Everything in the package is `@ApiStatus.Experimental` until the first release: breaking changes are allowed and listed in the pull request that makes them.

The example addon in [`example-addon/`](../../example-addon) compiles against this package only (its build fails on any other CivMod class) and is loaded into CivMod's dev runs (`./gradlew runClient`, GameTests).

## Concepts

- **Job** – a kind of work a settler holds, identified by a namespaced id (`examplemod:scout`). Every Job belongs to one **Job Category**, which owns the work loop; the Job fills in its specifics through the category's **profile**.
- **Job Category** – `RESOURCE_COLLECTION`, `PRODUCING`, `MISSION` (public profiles follow per category), `LOGISTICS` (internal to CivMod), `GUARD` and `SERVICE` (not implemented yet).
- **Profiles** – [Resource Collection](resource-collection.md) (`ResourceCollectionProfile`, `SimpleCollectionProfile`), [Producing](producing.md) (`ProducingProfile`), Mission (`MissionProfile`, below).
- **Base Job / Specialization** – a Base Job is offered by its own JobBlock. A Job with a `parent` is a Specialization: its unlock item (`unlockItem`) in a Station Types slot of the parent's JobBlock unlocks it there (see below); Mission Jobs are offered by the Mission Board from level 2.
- **JobBlock** – the block that offers a Base Job to settlers and anchors the settlement. Its window shows the tabs of the Job's category.

## Setup

Declare the dependency in your `neoforge.mods.toml`:

```toml
[[dependencies.examplemod]]
    modId="civmod"
    type="required"
    versionRange="[0.1.0,)"
    ordering="AFTER"
    side="BOTH"
```

## Register a Job

```java
public static final DeferredRegister<Job> JOBS = CivModApi.jobs("examplemod");

public static final DeferredHolder<Job, Job> SCOUT = JOBS.register("scout", () -> Job.builder(JobCategory.MISSION)
        .parent(CivModJobs.ADVENTURER)
        .icon(() -> Items.SPYGLASS)
        .baseExp(8)
        .profile(ScoutProfile::new)
        .build());

public ExampleMod(IEventBus modEventBus) {
    JOBS.register(modEventBus);
}
```

Builder values:

| Method | Meaning | Default |
|---|---|---|
| `icon` | item shown in GUIs (usually the JobBlock) | required |
| `parent` | parent Base Job; makes the Job a Specialization | none |
| `unlockItem` | the item that unlocks the Specialization in a Station Types slot of its parent's JobBlock; for a Base Job its station, shown in the first slot | none (a Specialization without one is never unlocked) |
| `hungerFactor` | how much hungrier work makes than standing around | 1.0 |
| `baseExp` | base EXP per produced resource (per mission for Mission Jobs) | 1 |
| `jobFactor` | factor on the shared speed formula | 1.0 |
| `profile` | the category profile, created on first use; must belong to the Job's category | Mission: `MissionProfile.NEUTRAL` |
| `bubbleIcon` | item in the thought bubble when the settler switches to the Job | the icon |
| `displayItem` | item held while working, for Jobs without a real tool | none |
| `station` | block tag of the Job's work places (Scroll of Assignment) | none |

Producing Jobs: see [producing.md](producing.md) (the profile is required).

## Station Types

Every JobBlock whose Base Job has Specializations (except the Mission Board) gets a **Station Types** tab with 5 slots. Slot 1 shows the Base Job's station (its `unlockItem`, else its icon) and is locked; each JobBlock level above 1 opens one more slot. A player puts an unlock item into an open slot: it is consumed and unlocks **every** Specialization of that Base Job naming the item, at this JobBlock only. Items that unlock nothing (or nothing new) are refused. An item put into a filled slot replaces the old one: settlers working a Job that is no longer unlocked fall back to the Base Job, and its pattern library stays (greyed out) until the Job is unlocked again.

The unlock item is not the work place: the Job still needs its station in the world (its station tag, the Scroll of Assignment's work points). Several addons may name the same item; the tab's "?" lists every unlock item with all Jobs it unlocks, one line each, using the lang key `job.<namespace>.<job>.description` (optional) as the Job's one-line description.

`CivModJobs` holds CivMod's built-in Jobs (`LUMBERJACK`, `MINER`, `FISHERMAN`, `ADVENTURER`, `CLERK`, `DELIVERYMAN`, `CRAFTSPERSON`) for parents and data.

Lang keys (in `assets/<namespace>/lang/en_us.json`):

```json
"job.examplemod.scout": "Scout",
"job.examplemod.scout.indefinite": "a Scout",
"examplemod.scroll.work_place.scout": "no work place (missions are chosen on the board)"
```

## Mission Jobs

Mission Jobs have no JobBlock: they are Specializations of `CivModJobs.ADVENTURER`, offered by the Mission Board from level 2. A mission names the Jobs that may take it in its datapack file `data/<namespace>/civmod/mission/<name>.json`:

```json
{
  "title": { "translate": "mission.examplemod.scouting_trip" },
  "description": { "translate": "mission.examplemod.scouting_trip.description" },
  "icon": "minecraft:spyglass",
  "loot_tables": ["minecraft:chests/village/village_cartographer"],
  "days": 1,
  "recommended_level": 1,
  "rolls": 2,
  "jobs": ["examplemod:scout"]
}
```

Optional fields: `weight` (draw weight in the daily offer, default 1) and `max_per_offer` (default 1).

`MissionProfile` hooks are neutral by default. `successChance` and `deathChance` receive the chance of CivMod's outcome formula, the mission's recommended level and the settler's skill; the results are clamped to 0..1, and death never exceeds 1 − success:

```java
final class ScoutProfile implements MissionProfile {
    @Override
    public double deathChance(double chance, int recommendedLevel, int skill) {
        return chance / 2.0;
    }
}
```

## JobBlock factory

A Base Job other than a Mission Job needs a JobBlock. The factory returns a fully wired block and item: level 1–5, settlement registration on placement, the JobBlock window with the category's tabs, a 27-slot inventory reachable by hoppers and pipes, the starter tool and immunity to pistons.

```java
public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("examplemod");
public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("examplemod");

public static final DeferredBlock<Block> DIGGER_BLOCK = BLOCKS.registerBlock("digger_block",
        properties -> CivModApi.jobBlock(DIGGER, properties),
        properties -> properties.mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD));

public static final DeferredItem<BlockItem> DIGGER_BLOCK_ITEM = ITEMS.registerItem("digger_block",
        properties -> CivModApi.jobBlockItem(DIGGER_BLOCK.get(), () -> Items.STONE_SHOVEL, properties));
```

Use `CivModApi.jobBlockItem(block, properties)` for a JobBlock without a starter tool. Specializations have no block of their own.

The window's tabs follow the category: Resource Collection → Storage, Info, Found; Producing → Storage, Info, Patterns; Mission → Storage, Info, Missions; other categories → Storage, Info.

### What the addon ships

- blockstate, block and item model, texture, recipe, creative tab entry, `block.<namespace>.<name>` lang key;
- loot table `data/<namespace>/loot_table/blocks/<name>.json` that keeps the level and removes the starter tool:

```json
{
  "type": "minecraft:block",
  "pools": [
    {
      "condition": { "type": "minecraft:survives_explosion" },
      "entries": [
        {
          "type": "minecraft:item",
          "modifier": [
            { "type": "minecraft:copy_components", "include": ["civmod:job_level"], "source": "block_entity" },
            { "type": "minecraft:set_components", "components": { "!civmod:starter_tool": {} } }
          ],
          "name": "examplemod:digger_block"
        }
      ],
      "rolls": 1
    }
  ]
}
```

- upgrade costs `data/<namespace>/civmod_upgrades/<job>.json` (per target level 2–5 a list of `{"item": …, "count": n}` or `{"tag": …, "count": n}`). Without the file the JobBlock uses CivMod's default costs (`data/civmod/civmod_upgrades/default.json`) and the server logs one warning per job on every reload.

## Datapack overrides

Packs can override a Job's balancing values per job id in `data/<namespace>/civmod_jobs/<job>.json`:

```json
{ "hunger_factor": 0.8, "base_exp": 10, "job_factor": 1.2, "unlock_item": "minecraft:stonecutter" }
```

`unlock_item` overrides the builder's `unlockItem`. All values are optional; later packs override earlier ones per value. Upgrade costs merge per level the same way.
