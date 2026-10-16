# CivMod

Code-facing glossary: maps the domain terms of the design vault to their names in code. Behaviour and decisions live in the vault, not here.

## Jobs

**Job**:
A kind of work a settler can hold in its job list, e.g. Lumberjack. Code: `Job`, registered in the `civmod:job` registry (`ModJobs`) by namespaced id (`civmod:lumberjack`); per-job datapack overrides in `JobBalancing` (`civmod_jobs/<job>.json`).
_Avoid_: Profession, occupation

**Job Category**:
The behaviour template a Job is built on (Resource Collection, Producing, Mission, Guard, Logistics, Service); it owns the work loop, the Job only fills in its specifics. Code: `JobCategory` (fixed enum), work loop `CategoryLoop`; `JobProfile` is the Resource Collection contract.
_Avoid_: Role, meta type, archetype

**Settler Shell**:
The part of a settler's behaviour shared by every Job Category (schedule, walking, food, sleep, job priorities, EXP, catch-up). Code: `SettlerWork`; it hands control to the `CategoryLoop` of the active Job's category (`ResourceCollectionLoop`, `MissionLoop`). Shared parts the loops use: buffers / delivery and EXP in the shell, tools in `SettlerTools`, `Fatigue`.

**Base Job**:
A Job a Level 1 JobBlock offers; root of a specialization tree.

**Specialization**:
A Job with a parent Base Job, offered by that Base Job's JobBlock from level 2; it may belong to a different Job Category than its parent.
_Avoid_: Advanced job, sub-job

**Laborer**:
The implicit lowest-priority fallback Job of every settler; not a Job Category and never stored.

**JobBlock**:
The block that offers a Base Job (and its Specializations) to one settler. Code: `JobBlock`, `JobBlockEntity`.

**JobBlock site**:
A JobBlock as a settler sees it: its settlement block, level, skill cap, drop-off, collect inventory and the pantries of its settlement. The one way to resolve a JobBlock position outside the settlement storage. Code: `JobBlockSite` (`JobBlockSite.at(level, pos)`), backed by `SettlementData`.
_Avoid_: Looking up `SettlementData.settlementAt(pos)` then `Settlement.block(pos)` by hand

**Mission Board**:
The JobBlock of all Mission Jobs; holds several settlers and the list of selected Missions. Code: `ModBlocks.MISSION_BOARD` (a `JobBlock` offering the Adventurer), capacity in `SettlementRules.capacity`, settlers in `SettlementBlock.assignees()`; a settler's Mission Job is set with `SettlerJobs.withMissionJob`.

**Mission**:
A datapack-defined task a Mission Job settler leaves the settlement for (duration, difficulty, loot). Code: `Mission` in the `civmod:mission` datapack registry (`Missions`, files in `data/<namespace>/civmod/mission/`); arithmetic in `MissionRules`, outcome constants in `OutcomeRules` (`civmod_mission/outcome.json`). The mission a settler reserved or is away on: `MissionTrip`; who takes which mission: `MissionPicks`.
_Avoid_: Quest, expedition

**Mission Offer**:
The missions a Mission Board offers today; the player marks the ones to be done. Code: `MissionOffer` (entries + refill day) in `SettlementBlock.missionOffer()`, refilled and marked through `MissionOffers`.
_Avoid_: Quest list, mission pool (the pool is all missions)

**Mission Assignment**:
A marked mission the player assigned to one settler of the board; it is **locked** for every other settler, and the lock outlives the assignee (death, leaving the board) until the player reassigns or unlocks it. Code: `MissionOffer.Entry.assignee()` / `locked()`, set through `MissionOffers.assign` / `unlock`; a reserved assigned mission: `MissionTrip.assigned()`.
_Avoid_: Reservation (a settler reserves the mission it picked)
