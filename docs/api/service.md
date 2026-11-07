# CivMod addon API – Service Jobs

A Service Job stands at its JobBlock and does something for the settlers and players around it. CivMod runs the loop for every Service Job with a `ServiceProfile`; the profile lists **Service acts**, and each act lists **effects**. Read [getting-started.md](getting-started.md) first.

The Priest, the Tavern Host and the Administrator are Service Jobs without a profile. They keep their own handling, and a Service Job without a profile has no work (the settler moves on to its next job). CivMod's Bard is the one built-in Job that uses a profile: its Stage act is a stationary act with an `Entertain` effect.

## Register a Service Job

```java
public static final DeferredHolder<Job, Job> BELL_RINGER = JOBS.register("bell_ringer", () -> Job.builder(JobCategory.SERVICE)
        .icon(BELL_RINGER_BLOCK_ITEM)
        .bubbleIcon(() -> Items.BELL)
        .hungerFactor(0.5)
        .baseExp(5)
        .profile(() -> ServiceProfile.of(ServiceAct.stationary("morning_bell", ServiceAct.clock(7, 0), ServiceAct.clock(12, 0), 12.0,
                new WorkSpeed(10.0, 3.0), new MobEffect(MobEffects.HASTE, 1200, 0))))
        .build());
```

A Service Job needs a JobBlock from `CivModApi.jobBlock` and the files every JobBlock needs (see the Sand Digger in the example addon): blockstate, model, item, recipe, loot table, `civmod_job_blocks/<job>.json` with the settlers per level, and the lang keys `block.<namespace>.<block>`, `job.<namespace>.<job>`, `job.<namespace>.<job>.indefinite` and `<namespace>.scroll.work_place.<job>`. The JobBlock window has the Workers and Storage tabs. The example addon's Bell Ringer is a complete Service Job.

A profile of another category fails at start-up with a message that names both categories, like every profile.

## Service acts

An act has an `id`, a time window, a kind, a radius and a list of effects.

- **Window.** Ticks of the day as in the game's own day time: 0 is 06:00, 6000 noon, a day has 24000 ticks. `ServiceAct.clock(hour, minute)` converts a time on the clock (`clock(11, 0)` is 5000, `clock(20, 0)` is 14000). The window starts at its first tick and ends before its last, and it stays inside one day. A settler works until the end of its work day, 17:30 to 18:00, unless a window of one of its Service Jobs ends later: then its work day ends with the latest such window (the Bard's stage act keeps it until 20:00). A window never makes the settler wake earlier.
- **Kind.** `STATIONARY` acts are run by the Service loop: the settler walks to its JobBlock, stands next to it for the whole window and plays the act once a day. Outside every window the settler works its next job. `ROUND` acts (a settler that walks to working settlers) exist in the profile but do nothing until a Job that uses them is built (the Bard); a round-only Job is not worked.
- **Radius.** Blocks from the center of the act (the bottom center of the JobBlock), at most 64. A listener exactly on the edge counts.
- **Effects.** Applied in order when the act is played.

When an act is played, the loop scans for listeners once (the settlers of the settlement and the players within the radius, never the performer), applies the effects, and gives the settler the Job's base EXP times the JobBlock level. There is no per-tick scan. The first stationary act of the profile whose window is open is the one played, so acts of one Job should not overlap.

## Effects

Each effect names its targets, `SETTLERS`, `PLAYERS` or `BOTH`, and gets only those listeners: the lists in the context it receives are empty for a group it does not name.

| Effect | Targets | Does | Datapack numbers |
|--------|---------|------|------------------|
| `Entertain(points, percentPerLevel, cap)` | settlers | Adds points to their Entertainment (0 to 10), multiplied by 1 plus `percentPerLevel` percent per skill level of the performer. The cap (`NONE`, `ROUND_STOP`, `STAGE_ACT`) lets one settler profit once per day from that kind of act however many performers reach it; `Entertain(points)` has no growth and no cap. | `points`, `percent_per_level` |
| `WorkSpeed(percent, hours)` | settlers | A timed work speed bonus for `hours` in-game hours (1000 ticks each). It adds to the other speed bonuses (the Priest's blessing, Entertainment), never multiplies. A settler has one such boost at a time: a stronger one replaces a weaker one, an equal one extends it, a weaker one is ignored. | `percent`, `hours` |
| `MobEffect(effect, duration, amplifier)` | players | A vanilla mob effect (`duration` in ticks, `amplifier` 0 for level I). Give it a fourth argument, `Targets.SETTLERS` or `Targets.BOTH`, to include settlers. | `duration`, `amplifier` |

### Your own effect

Implement `ServiceEffect` (it uses `com.gilfort.civmod.api` and vanilla classes only):

```java
public record Chime(float volume) implements ServiceEffect {

    @Override
    public Targets targets() {
        return Targets.PLAYERS;
    }

    @Override
    public void apply(ServiceContext context) {
        if (!context.players().isEmpty()) {
            context.level().playSound(null, BlockPos.containing(context.center()), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, volume, 1.0F);
        }
    }
}
```

The `ServiceContext` holds the level, the act, the center, the performer (a vanilla `Mob`), its skill level, the clock value, the day, and the two listener lists. Settlers are passed as vanilla `Mob`s; CivMod's settler classes are not part of the API. An effect keeps no state of its own: one instance serves every settler and every day. To let a datapack change a number of your effect, return it from `numbers()` and read it back in `withNumbers(...)`; effects that skip both keep their numbers.

## Datapack numbers

The datapack changes the numbers of an act, never its effects. In `data/<namespace>/civmod_jobs/<job>.json`, under `service_acts`, keyed by the act id:

```json
{
  "service_acts": {
    "morning_bell": {
      "start": 1000,
      "end": 5000,
      "radius": 16.0,
      "effects": [ { "percent": 15 }, { "duration": 600 } ]
    }
  }
}
```

All values are optional; later files override earlier ones per value. `effects` takes one entry per effect of the act, in the order of the profile (an empty entry `{}` leaves an effect as it is), with the numbers by the names in the table above. A change that cannot hold is ignored for that act and reported in the log when the datapack loads: an unknown act id, a list of another length than the act has effects, or a window that is empty or leaves the day. EXP and hunger (`base_exp`, `hunger_factor`) work as for every Job.

## Tests

`ServiceGameTests` in the repository registers four Service Jobs the way an addon does and covers the window, the radius, the targets of each effect, a custom effect, the wrong-category failure and the numbers; `ExampleAddonGameTests` runs the Bell Ringer.
