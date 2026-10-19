# Resource Collection Jobs

A Resource Collection Job scans the area around its work place and produces continuously from what it found, without changing the world (vault `Systems/Resource Collection Jobs.md`). CivMod's loop does everything else: fetching the tool from the collect inventory, walking, fatigue, the per-job buffer and its delivery to the drop-off, food, EXP, catch-up after the chunk was unloaded, the JobBlock's **Found** tab with its exclusions and the **Max** of the Home Clerk's stock rules.

The Job fills in its specifics through a `ResourceCollectionProfile`. There are two levels:

- **`SimpleCollectionProfile.builder()`** – "count the blocks of a tag, produce their drops". A few lines; enough for most Jobs.
- **`ResourceCollectionProfile`** – the full interface for Jobs with their own scan or roll (CivMod's Lumberjack, Miner and Fisherman).

Every Resource Collection Base Job needs a JobBlock (see [getting started](getting-started.md#jobblock-factory)); its window shows the tabs Storage, Info and Found.

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
| `tool(itemTag, icon)` | the items that count as the Job's tool; the icon is shown in the "no tool" bubble (usually the starter tool) | required |
| `radius(level -> r)` | horizontal scan radius per JobBlock level (1–5) | 10 × level |
| `halfHeight(n)` | how far up and down the scan reaches | 10 |
| `minimumCount(n)` | how often a block type must be found to count | 1 |
| `productivity(total -> f)` | speed factor by the total number of counted blocks | 1 |
| `whenNothingFound(reason)` | blocking reason when no block of the tag is in reach | "No work place" / "No resources" |

What the builder Job does:

- **Work place:** the nearest exposed block of the tag (a settler can stand next to or on top of it) within 20 blocks of the JobBlock, or a block of the tag set as work point with the Scroll of Assignment. Lang key of the scroll's work place: `<namespace>.scroll.work_place.<job>`.
- **Scan:** every day, around the first work point (else the JobBlock); each block type of the tag is counted, types below the minimum count are left out. Scans run spread over ticks and skip chunk sections whose palette has none of the blocks. The result is saved with the settler and on the JobBlock (Found tab after a restart, catch-up).
- **Roll:** one found type weighted by its count, then the drops of its loot table with the settler's tool (Silk Touch and Fortune apply). Types the tool cannot harvest are never rolled (greyed out in the Found tab). Types excluded in the Found tab or at their Max at the Home Clerk leave the roll; with all of them left out the roll gives nothing, but still EXP and tool wear, and the job pauses ("All resources excluded" / "Maximum reached" / "Nothing to produce").
- **Speed:** CivMod's shared formula (skill level × tool tier) × `productivity`. 1 durability per roll.

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
| `isTool(stack)`, `toolIcon()` | the Job's tool; the icon for the "no tool" bubble |
| `isWorkPlace(level, pos)` | whether a loaded position is a valid work place |
| `isAssignableWorkPlace(level, pos)` | whether the Scroll of Assignment may set it |
| `autoSearch(level, jobBlock)` | a work place when the JobBlock has no valid work point (keep it cheap: it runs synchronously) |
| `scanCenter(jobBlock, workPoints, workPlace)` | where the scan is centred |
| `rescanDaily()` | rescan every day, or only when centre or radius change |
| `createScan(center, radius)` | a new `SectionScan`: one chunk section per `step`, loaded chunks only |
| `produce(context)` | one roll: the items and the tool to wear, empty for nothing |

Optional hooks (`default` methods): `scanRadius`, `needsResources`, `blockingReason`, `productivity`, `speed`, `canObtain`, `swings`, `reach`, `mainResources`, `dropped`, `leftOut`, `found`, `foundLines`.

Inside `produce`, call `leftOut(context, types)` for the types of the rolled group: it returns the types excluded at the JobBlock and those whose dropped item is at its Max. Keep the group roll as it is and spread the weights over the remaining types; a fully left-out group gives an empty roll (`new Produced(List.of(), context.tool())`), never a roll into another group.

Scan results of your own `ScanResult` types are kept in memory only: after a restart the Found tab waits for the next scan.
