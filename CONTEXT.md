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
The part of a settler's behaviour shared by every Job Category (acting on the Schedule, walking, food, sleep, job priorities, EXP, catch-up). Code: `SettlerWork`; it hands control to the `CategoryLoop` of the active Job's category (`ResourceCollectionLoop`, `MissionLoop`). Shared parts the loops use: the job's Workplace, delivery and EXP in the shell, the Work rules, tools in `SettlerTools`, `Fatigue`.

**Schedule (intent)**:
The settler's daily rules (sleep and wake, skipped night, morning plan, hunger, evening delivery and meal, rest, bed at 20:00, homeless, foraging) as one pure decision: a snapshot of plain values (time, day, food level, provisions, pantry food, homeless, sleeping, foraging day, night skipped, current activity, per-day marks) goes in, an intent comes out (sleep, wake, eat from pockets, fetch food with a purpose, forage, end foraging, deliver, go to bed, rest, work a job, idle; complaining is a flag). Foraging ends at the end of work (17:30) or on a later day, with the day's food; whether a pantry visit is a meal (not the morning provisions trip unless hungry) is a Schedule rule too (`Schedule.eatsAtPantry`). It never touches the world; the Settler Shell builds the snapshot every decision and acts on the intent. Code: `Schedule` (`wakeUp`, `next`, `Schedule.Snapshot`, `Schedule.Intent`, `Schedule.Decision`, `Schedule.morningPlan`), acted on in `SettlerWork.decide`. Catch-up asks the same Schedule per simulated hour and simulates the intent on the spot (`SettlerWork.decideCatchUp`). The per-day marks (morning handled, evening meal eaten, foraging day) are one set for live play and catch-up, saved with the settler in `SettlerFood`.
_Avoid_: Daily routine, AI tree; putting schedule rules into movement code; separate per-day marks for catch-up

**Workplace**:
Everything a settler keeps for one job at its JobBlock: the buffer, the status shown in the job tab, the blocked-until time, the work place of the day, the scan (requested, scanning, ready, rescanned per day or per centre / radius change) and the fractional production of the Work rules. One per JobBlock the settler works for; forgetting the job drops it whole (the buffer is dropped at the settler). Code: `Workplace`, held by `SettlerWork` (`workplace(jobBlock)`), saved through `SettlerWork.BuffersSnapshot`.
_Avoid_: Work place (that is the block worked at, below); per-job maps beside it

**Work rules**:
How a Resource Collection job turns work time into output: speed × productivity, tool choice (best tool on the belt, refill from the collect inventory, secondary tool), tool wear and EXP; a level-up speeds up the rest of the batch. Advances a job by a number of work ticks and reports rolls, items, tool wear, EXP, a level-up and why the job ended (no tool, output full, no resources). Live play calls it every work tick (output into the buffer, effects in the caller), catch-up per simulated hour (output into the drop-off first, then the buffer). Which tool a settler still has to fetch before it can work (and whether the collect inventory has it) is decided here as well. Code: `WorkRules.work`, `WorkRules.missingTool`, fractional production kept in the job's Workplace.
_Avoid_: A second production loop for catch-up; a "quiet" flag in the rules

**Work place of the day**:
The block a Resource Collection settler works at today (a tree, a stone face, water), chosen each day among the JobBlock's work points or by the job's auto search; part of the Workplace, not the same thing. Code: `Workplace.workPlaceOfDay`, saved as `SettlerWork.WorkPlaceEntry` (`poi`).
_Avoid_: Workplace (the whole per-job state), POI in prose

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
A JobBlock as a settler sees it: its settlement, settlement block, level, skill cap, drop-off and collect inventory. The one way to resolve a JobBlock position outside the settlement storage. Code: `JobBlockSite` (`JobBlockSite.at(level, pos)`, `JobBlockSite.blockAt(level, pos)` for the block alone), backed by `SettlementData`.
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
