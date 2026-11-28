# CivMod addon API – Guard Jobs

A Guard Job holds a post. CivMod runs the loop for every Job of the Guard category: its settlers stand at the guard point of the half day, or next to their Guard Post, around the clock. Read [getting-started.md](getting-started.md) first.

CivMod's own Guard is the one Job of this category. The first step of the Guard has no combat; the weapons, the enemies and the gear tiers come with later steps, and so will their hooks.

## The profile

`GuardProfile` has no hook yet. A Guard Job declares it, or any profile of its own that extends it:

```java
public static final DeferredHolder<Job, Job> WATCHMAN = JOBS.register("watchman", () -> Job.builder(JobCategory.GUARD)
        .icon(WATCHMAN_POST_ITEM)
        .bubbleIcon(() -> Items.IRON_SWORD)
        .hungerFactor(1.0)
        .baseExp(1)
        .profile(() -> GuardProfile.DEFAULT)
        .build());
```

A profile of another category fails at start-up with a message that names both categories, like every profile. `GuardProfile` is `@ApiStatus.Experimental` like the rest of the API; a hook added later is a `default` method, so a profile written today keeps working.

## What the loop does for every Guard Job

- **Around the clock.** A settler that holds a Job of the Guard category has no end of work and no bedtime. The Schedule never sends it to the Tavern, to the evening rest or to bed, and a night that is skipped with a bed does not put it to bed. It eats from the food point like every settler, so hunger works as usual.
- **Where it stands.** The JobBlock is its Guard Post. The Guard Post's work points are its guard points (set with the Scroll of Assignment, up to 8, each with a facing). Each Guard picks one at 06:00 and again at 18:30, one that no other Guard of the same post holds if there is one, and stands on it facing the way it was set. A point that is no place to stand, or that a path search finds out of reach (checked once a day per point), is skipped for the day. Without usable points the Guard stands next to the JobBlock.
- **Mode.** The Guard Post window has a mode button: Guard, Patrol or Roam, for all Guards of the block. Only Guard has behaviour in the first step; Patrol and Roam act like Guard until their step is built.
- **Breaks.** The shared fatigue sits the Guard down on the spot for 20 to 45 seconds now and then, only while it stands at its post.
- **Needs.** A Guard claims a bed of its own, which meets the bed need, and never lies in it. Its Entertainment need counts as met. The other needs apply as usual.
- **EXP.** The Job's `baseExp` times the level of the Guard Post for every in-game hour on duty: 24 hours a day. Catch-up pays the whole missed time the same way, nights included, although a catch-up run simulates only the 14 awake hours of each day.

## The JobBlock

A Guard Job needs a JobBlock from `CivModApi.jobBlock`, with the files every JobBlock needs (see [service.md](service.md#register-a-service-job)): blockstate, model, item, recipe, loot table, `civmod_job_blocks/<job>.json` with the Guards per level, and the lang keys `block.<namespace>.<block>`, `job.<namespace>.<job>`, `job.<namespace>.<job>.indefinite` and `<namespace>.scroll.work_place.<job>`. The window has the Workers tab (with the mode button) and the Storage tab.

## Tests

`GuardGameTests` in the repository covers the Guard Post, the window and the mode, the guard points and their numbers, the pick at 06:00 and 18:30, standing at the point, the unreachable point, the missing sleep, the break, the bed and Entertainment needs, the duty EXP live and in catch-up, and the broken Guard Post.
