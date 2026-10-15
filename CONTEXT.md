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
The part of a settler's behaviour shared by every Job Category (schedule, walking, food, sleep, job priorities, EXP, catch-up). Code: `SettlerWork`; it hands control to the `CategoryLoop` of the active Job's category (`ResourceCollectionLoop`). Shared parts the loops use: buffers / delivery and EXP in the shell, tools in `SettlerTools`, `Fatigue`.

**Base Job**:
A Job a Level 1 JobBlock offers; root of a specialization tree.

**Specialization**:
A Job with a parent Base Job, offered by that Base Job's JobBlock from level 2; it may belong to a different Job Category than its parent.
_Avoid_: Advanced job, sub-job

**Laborer**:
The implicit lowest-priority fallback Job of every settler; not a Job Category and never stored.

**JobBlock**:
The block that offers a Base Job (and its Specializations) to one settler. Code: `JobBlock`, `JobBlockEntity`.

**Mission Board**:
The JobBlock of all Mission Jobs; holds several settlers and the list of selected Missions. Code: not yet built.

**Mission**:
A datapack-defined task a Mission Job settler leaves the settlement for (duration, difficulty, loot). Code: not yet built.
_Avoid_: Quest, expedition
