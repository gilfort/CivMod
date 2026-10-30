# CivMod

Code-facing glossary: maps the domain terms of the design vault to their names in code. Behaviour and decisions live in the vault, not here.

## Jobs

**Job**:
A kind of work a settler can hold in its job list, e.g. Lumberjack. Code: `Job` (addon API, `com.gilfort.civmod.api`), registered in the `civmod:job` registry (`CivModApi.JOB_REGISTRY`, CivMod's own Jobs in `ModJobs`) by namespaced id (`civmod:lumberjack`); per-job datapack overrides in `JobBalancing` (`civmod_jobs/<job>.json`).
_Avoid_: Profession, occupation

**Job Category**:
The behaviour template a Job is built on (Resource Collection, Producing, Mission, Guard, Logistics, Service); it owns the work loop, the Job only fills in its specifics through its profile. Code: `JobCategory` (fixed enum, addon API), work loop `CategoryLoop`; the JobBlock window's tabs follow it (`JobBlockTabs`).
_Avoid_: Role, meta type, archetype

**Addon API**:
The public package `com.gilfort.civmod.api` (all `@ApiStatus.Experimental` until the first release) that addons compile against and CivMod uses for its own Jobs: Job definition and registration (`Job`, `CivModApi.jobs`), category profiles, the JobBlock factory (`CivModApi.jobBlock`, `CivModApi.jobBlockItem`) and the built-in Jobs (`CivModJobs`). Category loops stay internal. Guide: `docs/api/getting-started.md`; example: `example-addon/` (mod id `civmod_example`).
_Avoid_: Plugin API, SDK

**Profile**:
The specifics a Job fills into its category's work loop; must belong to the Job's category. Code: `CategoryProfile`, read with `Job.profile(Class)`; `MissionProfile` (public, neutral hooks for success / death chances, `MissionProfile.NEUTRAL` when a Mission Job declares none), `ProducingProfile` (public, required for a Producing Job: recipe types, station tag per recipe type, input / output `ItemFilter`s, checkmark limit, work time per recipe, "execute recipe" hook; guide `docs/api/producing.md`), `ResourceCollectionProfile` (public; CivMod's Lumberjack, Miner, Fisherman, Veggy Farmer, Animal Farmer and Beekeeper implement it, the Gatherer and Plantation Farmer use the builder below, job-specific options such as the Lumberjack's leaves stay in their Job: the internal `SecondaryToolProfile` gives the Lumberjack its shears), `SimpleCollectionProfile` (public builder: block tag, optional tool tag (without one bare-handed), radius rule, minimum count, productivity curve; ripe plants only (`PlantStates`); output = drops of the found blocks weighted by count; guide `docs/api/resource-collection.md`); a farming Job's **farm curve** (`FarmCurve`, `Job.farmCurve()`, `Job.productivity(scan, jobBlockLevel)`) speeds it up with the farm size, capped per JobBlock level.
_Avoid_: Job type, behaviour

**Settler Shell**:
The part of a settler's behaviour shared by every Job Category (acting on the Schedule, walking, food, sleep, job priorities, EXP, catch-up). Code: `SettlerWork`; it hands control to the `CategoryLoop` of the active Job's category (`ResourceCollectionLoop`, `MissionLoop`). Shared parts the loops use: the job's Workplace, delivery and EXP in the shell, the Work rules, tools in `SettlerTools`, `Fatigue`.

**Job step**:
What a category loop tells the Settler Shell its job wants next: walk to a target (and work there), deliver, wait, blocked (a status or a Blocking reason), skip, pause / hold the decision, worked this tick, done. Only the shell changes the settler's activity, walking target, active job and the job's status, after the loop returned; a status outside a step goes through the job's Workplace. Code: `JobStep`, applied in `SettlerWork.apply`.
_Avoid_: Loops calling the shell to steer it (set the active job, start a walk, end a step)

**Sitting**:
A settler state shown by pose: vanilla `Pose.SITTING` (synced like `Pose.SLEEPING`), drawn as the vanilla passenger pose lowered to the seat, hitbox about 1.3 blocks high (eye height, name tag and thought bubble follow), not pushable; the body keeps its facing, the head looks around, a held tool stays in the hand. A settler sits exactly while its job step is a work break (`JobStep.Pause` with `JobStatus.PAUSING`), on the ground at its own position, if it is on the ground and out of fluids when the break starts (decided once; neither the pose nor the decision is saved, the next decision after loading sets it again); any other step, a job step ended outside a decision (job removed, JobBlock broken) and damage stand it up. No seat entity. Code: `Settler.sitDown(seat, facing)` / `Settler.standUp()` (internal hook for later Tavern seats), decided in `SettlerWork.endDecision`, drawn by `SettlerRenderer`.
_Avoid_: Seat entity, riding; a timer or cancel list of its own for the sitting

**Schedule (intent)**:
The settler's daily rules (sleep and wake, skipped night, morning plan, hunger, evening delivery and meal, rest, bed in the going-to-bed window 20:00–20:30, homeless, foraging) as one pure decision: a snapshot of plain values (time, day, food level, provisions, food point food, a Clerk JobBlock to wait at, homeless, sleeping, foraging day, night skipped, current activity, per-day marks, the settler's day times) goes in, an intent comes out (sleep, wake, eat from pockets, fetch food with a purpose, wait for food, forage, end foraging, deliver, go to bed, rest, work a job, idle; complaining is a flag). The day times are the settler's Daily offsets inside the Schedule windows (`Schedule.dayTimes`, `Schedule.DayTimes`, `SettlerWork.dayTimes`): it wakes 06:00–06:30 (awake earlier, it idles until then; a Clerk wakes early, 05:30–06:00), work ends 17:30–18:00 (the Settler Shell replaces it: a Clerk's at 18:30, the end of its shift; a settler back from a Mission ends its work day on its return), it goes to bed 20:00–20:30. An early riser's day begins at its waking time before 06:00: from then on the clock counts as its next day with a negative day time (`Schedule.moment`). Hungry with nothing to eat in the morning, a settler waits at the nearest Clerk JobBlock's queue for 2 hours (`Schedule.FOOD_WAIT_TICKS`; the morning stays open, so food that arrives is fetched as usual; a Clerk waits at its own counter, at work) and only then forages; without a Clerk JobBlock it forages right away. Foraging ends at its end of work or on a later day, with the day's food; whether a food point visit is a meal (not the morning provisions trip unless hungry) is a Schedule rule too (`Schedule.eatsAtFoodPoint`). It never touches the world; the Settler Shell builds the snapshot every decision and acts on the intent. Code: `Schedule` (`wakeUp`, `next`, `Schedule.Snapshot`, `Schedule.Intent`, `Schedule.Decision`, `Schedule.morningPlan`), acted on in `SettlerWork.decide`. Catch-up asks the same Schedule per simulated hour and simulates the intent on the spot (`SettlerWork.decideCatchUp`; every day starts at 06:00 at the earliest, so a Clerk's early start is not simulated and every Clerk is on shift from hour 0; waiting for food idles the hour). The per-day marks (morning handled, evening meal eaten, foraging day, start of waiting for food) are one set for live play and catch-up, saved with the settler in `SettlerFood`.
_Avoid_: Daily routine, AI tree; putting schedule rules into movement code; separate per-day marks for catch-up

**Schedule window**:
The span a point of the daily schedule falls into for every settler: waking 06:00–06:30 (the early waking window 05:30–06:00 for a Clerk: it has a stall to take care of), end of work 17:30–18:00, going to bed 20:00–20:30.
_Avoid_: Time slot, shift (a shift is the Clerk's working time)

**Daily offset**:
Where one settler's point lies inside a Schedule window on one day; computed from the settler and the day, the same in live play and catch-up, never saved. Code: `Schedule.dayTimes`, built on `SettlerDay.pick` (a value from a settler and a day, reusable for other per-night choices).
_Avoid_: Random delay, jitter

**Workplace**:
Everything a settler keeps for one job at its JobBlock: the buffer, the status shown in the job tab, the blocked-until time, the work place of the day, the scan (requested, scanning, ready, rescanned per day or per centre / radius change), the fractional production of the Work rules and, for a Producing Job, the current Round, the requests skipped for a while and a pending trip to the drop-off. One per JobBlock the settler works for; forgetting the job drops it whole (the buffer is dropped at the settler). Code: `Workplace`, held by `SettlerWork` (`workplace(jobBlock)`), saved through `SettlerWork.BuffersSnapshot`.
_Avoid_: Work place (that is the block worked at, below); per-job maps beside it

**Work rules**:
How a Resource Collection job turns work time into output: speed × productivity, tool choice (best tool on the belt, refill from the collect inventory, secondary tool of an internal `SecondaryToolProfile`, the Lumberjack's shears), tool wear and EXP; a level-up speeds up the rest of the batch. Advances a job by a number of work ticks and reports rolls, items, tool wear, EXP, a level-up and why the job ended (no tool, output full, no resources). Live play calls it every work tick (output into the buffer, effects in the caller), catch-up per simulated hour (output into the drop-off first, then the buffer). Which tool a settler still has to fetch before it can work (and whether the collect inventory has it) is decided here as well. Code: `WorkRules.work`, `WorkRules.missingTool`, fractional production kept in the job's Workplace.
_Avoid_: A second production loop for catch-up; a "quiet" flag in the rules

**Work place of the day**:
The block a Resource Collection settler works at today (a tree, a stone face, water), chosen each day among the JobBlock's work points or by the job's auto search; part of the Workplace, not the same thing. Code: `Workplace.workPlaceOfDay`, saved as `SettlerWork.WorkPlaceEntry` (`poi`).
_Avoid_: Workplace (the whole per-job state), POI in prose

**Biome bonus**:
An effect a biome gives the Jobs a datapack file names: `speed`, `rare_chance` (the chance of a roll's **rare group**: the Miner's ores, the Fisherman's treasure) and `double_drop`. Read at the Job's **biome position** (work place / station of the day, JobBlock, or none for Mission Jobs) once per day. Code: files and combining rules in `BiomeBonuses` (`civmod_biome_bonuses/<name>.json`), the resolved effects `BiomeBonus` (addon API, with the source file of each effect), position `BiomePosition` / `CategoryProfile.biomePosition()`, rare group `ResourceCollectionProfile.rareGroup()`, cached per day in the `Workplace`, query `CivModApi.biomeBonus`.
_Avoid_: Building bonus, biome boost

**Found tab**:
The JobBlock tab of a Resource Collection Job listing what its scan found (Lumberjack wood types, Miner stone and ore types, Fisherman the fish of the fishing fish loot table), with "Needs a better tool" and the treasure chance computed for the settler working there, the water info, and "Not scanned yet" before the first scan. It shows the working settler's scan, else the last scan the JobBlock kept. The settler's job tab shows none of it. Code: tab `FoundTab`, data `FoundTabData` (payloads `Sync` / `Toggle`), `FoundTabView`; entries from `ResourceCollectionProfile.found` / `foundLines`, the kept scan in `SettlementBlock.foundScan()` (set through `JobAssignments.rememberScan`); fish list `FishTable`.
_Avoid_: Scan tab, resource list in the settler window

**Excluded resource**:
A found type the player right-clicked off in the Found tab; the job no longer produces it. Stored per JobBlock and Job in the settlement data (survives a settler change, applies in catch-up, kept when a scan no longer finds it); the Found tab shows a section per Job of the JobBlock's settlers (`FoundTabData.Section`). Inside the rolled group (Miner stone / ore, Lumberjack logs vs rare drops, Fisherman fish vs treasure) the weights spread over the rest; a fully excluded group gives an empty roll (EXP and tool wear, no item). Every main resource excluded (`ResourceCollectionProfile.mainResources`), and every innate recipe the scan offers too, pauses the job with "All resources excluded" (`JobStatus.ALL_EXCLUDED`, retried after 30 s, at once when one is included). Code: `SettlementBlock.excluded(job)`, toggled through `SettlementWorld.toggleExcluded` / `JobAssignments.toggleExcluded`; `WorkRules.allExcluded`, `MinerOutput.Roll`.
_Avoid_: Blacklist, filter

**Base Job**:
A Job a Level 1 JobBlock offers; root of a specialization tree.

**Specialization**:
A Job with a parent Base Job, unlocked per JobBlock by its unlock item in a Station Types slot of the parent's JobBlock (Mission Jobs: in a Station Types slot of the Mission Board); it may belong to a different Job Category than its parent. Code: `Job.parent`, `Job.unlockItem` (`unlock_item` in `civmod_jobs/<job>.json`), `StationTypes.specializations`.
_Avoid_: Advanced job, sub-job

**Station Types**:
The 5 slots of a JobBlock whose Base Job has Specializations (the Mission Board too: slot 1 the Adventurer): slot 1 the Base Job's station (locked), one more open slot per level. An unlock item put into an open slot is consumed and unlocks every Specialization naming it at this JobBlock; put into a filled slot it replaces the old one, settlers of a Job no longer unlocked fall back to the Base Job (its library is kept, locked). Not the work place: the Job still needs its station in the world. Code: `StationTypes` (`put`, `unlocked`, `overview`), saved in `SettlementBlock.stationTypes()`; tab `StationTypesTab` (button `StationTypesTab.slotButtonId`, data `StationTypesTabData`), `StationTypesTabView`; offered Jobs `JobPlaces.offered(SettlementBlock)`.
_Avoid_: Upgrade slot, unlock slot

**Gatherer**:
The Resource Collection Base Job of the Gatherer JobBlock: bare-handed, it collects the loot of the ripe wild plants of `#civmod:gatherables` (bushes, grass, ferns, mushrooms, leaf litter) around its work place, never changing them; parent of the farming Specializations below. Code: `FarmingProfiles.gatherer` (a `SimpleCollectionProfile`), `ModJobs.GATHERER`, `ModBlocks.GATHERER_BLOCK`.
_Avoid_: Forager, harvester

**Plantation Farmer**:
A Specialization of the Gatherer (unlocked by bamboo) that collects with an axe from the plants of `#civmod:plantations` (sugar cane, bamboo, cactus, cocoa, vines, melons, pumpkins, kelp, sea pickles), each block of a stacked plant counted. Code: `FarmingProfiles.plantationFarmer`, `ModJobs.PLANTATION_FARMER`.

**Veggy Farmer**:
A Specialization of the Gatherer (unlocked by a composter) that works the crop field around a **Scarecrow**: with a hoe it collects the loot of the mature crops of `#minecraft:crops` and mature nether wart within 5 × JobBlock level of the scarecrow of the day; without a scarecrow "Blocked", "No scarecrow". Code: `VeggyFarmerProfile`, `ModJobs.VEGGY_FARMER`.

**Animal Farmer**:
A Specialization of the Gatherer (unlocked by a hay bale) that collects the loot of the adult animals of `#civmod:farm_animals` around an exposed hay bale (5 × JobBlock level) without killing them, and eggs from chickens; with an axe, whose Looting counts. An animal type is at its Max only when every item its loot can give is. Innate recipes: milk, mushroom stew. Code: `AnimalFarmerProfile`, `AnimalScan`, `LootItems`, `ModJobs.ANIMAL_FARMER`.
_Avoid_: Rancher, butcher (no animal is killed)

**Beekeeper**:
A Specialization of the Gatherer (unlocked by a beehive) that collects honeycomb with shears from the beehives and bee nests holding honey around its work place (5 × JobBlock level), never changing their honey level; a full hive (honey level 5) offers honey bottles. Code: `BeekeeperProfile` (a `BlockCountScan` per block and honey level), `ModJobs.BEEKEEPER`.

**Scarecrow**:
The Veggy Farmer's work place: a two-block-high block that may stand on farmland and keeps it farmland; its lower half is the work point (up to 8 set with the Scroll of Assignment, one a day, else a random one within 20 blocks of the JobBlock). Code: `ScarecrowBlock`, `ModBlocks.SCARECROW`.

**Farm curve**:
How a farming Resource Collection Job speeds up with the size of its farm: productivity `0.2 × (1 + k × count)`, at most 2.0, where the count (plants, animals, hives; `ResourceCollectionProfile.farmCount`) is capped per JobBlock level (plants: `k` 0.005, caps 100 / 350 / 750 / 1350 / 2000; animals: 0.1, 20–100; hives: 0.2, 10–50); a datapack overrides it in `civmod_jobs/<job>.json` under `farm_curve`. Code: `FarmCurve`, `Job.farmCurve()`, `Job.productivity(scan, jobBlockLevel)`, `FarmingProfiles`.
_Avoid_: Growth bonus, farm bonus

**Laborer**:
The implicit lowest-priority fallback Job of every settler; not a Job Category and never stored.

**JobBlock**:
The block that offers a Base Job (and its Specializations) to one settler. Code: `JobBlock`, `JobBlockEntity`, created by the addon API's factory (`CivModApi.jobBlock`); upgraded one level at a time with the JobBlock Upgrade items (see below). Its window (`JobBlockMenu` / `JobBlockScreen`) is a list of tabs chosen by `JobBlockTabs`; each tab is one module: `JobBlockTab` (tab bar entry, menu buttons, synced `TabData`) and its client view `JobBlockTabView` (`StorageTab`, `InfoTab` / `InfoTabView`, `FoundTab`, `MissionsTab`, `StockTab` / `RequestsTab` / `DeliveriesTab`, `PatternsTab`, `StationTypesTab`).

**JobBlock Upgrade item**:
One of four items (JobBlock Upgrade Lv 2 / 3 / 4 / 5, crafted from the Scroll of Assignment – returned – and 8 iron / gold / diamonds / netherite) that raise any JobBlock by one level on right-click: the Lv N item only on a Lv N − 1 block, and only once a loaded settler assigned to that block has reached its skill cap in a Job it holds there. Code: `JobBlockUpgradeItem`, `ModItems.JOB_BLOCK_UPGRADE_2` … `_5`, rule in `JobBlockUpgrade` (called from `JobBlock.useItemOn`), recipes `civmod:job_block_upgrade_<n>`.
_Avoid_: upgrade costs, upgrade button

**Starter tool**:
The tool a new JobBlock puts into its own inventory when it is placed for the first time (Lumberjack stone axe, Miner stone pickaxe, Fisherman fishing rod). A broken JobBlock no longer carries one.
_Avoid_: Free tool, bonus item

**Blocking reason**:
Why a Job cannot work, in its profile's own words ("No sand in reach"): the job pauses with status "Blocked" (`JobStatus.BLOCKED`, retried after 30 s), the reason is its status text in the settler window and its problem bubble shows the reason's icon or the generic "blocked" marker (a barrier). Code: `BlockingReason` (addon API), `ResourceCollectionProfile.blockingReason`, `Workplace.block(BlockingReason, …)`, `ThoughtBubble.BLOCKED`.
_Avoid_: custom status, status registry

**Thought bubble**:
A small icon bubble above a settler's name tag, sent when it starts something new (Job switch, drop-off, fetching, crafting, eating, bed, Mission); a red "!" marks a problem, which the client repeats while it is on. Code: `ThoughtBubble` (types and payload), `SettlerThoughts` (server), `ThoughtBubbleRenderer` (client).

**Bubble icon**:
The typical item of a Job shown in its thought bubble (Lumberjack axe, Fisherman raw cod, …); separate from the Job's GUI icon, which stays the JobBlock.

**Display item**:
An item a settler holds while working a Job without a real tool (Craftsperson hammer, Deliveryman bundle); only a copy in the hand, never in an inventory and not obtainable.

**JobBlock site**:
A JobBlock as a settler sees it: its settlement, settlement block, level, skill cap, drop-off and collect inventory. The one way to resolve a JobBlock position outside the settlement storage. Code: `JobBlockSite` (`JobBlockSite.at(level, pos)`, `JobBlockSite.blockAt(level, pos)` for the block alone), backed by `SettlementData`.
_Avoid_: Looking up `SettlementData.settlementAt(pos)` then `Settlement.block(pos)` by hand

**Mission Board**:
The JobBlock of all Mission Jobs; holds several settlers and the list of selected Missions. Code: `ModBlocks.MISSION_BOARD` (a `JobBlock` offering the Adventurer), capacity in `SettlementRules.capacity` (the `settlers` list of `civmod_job_blocks/adventurer.json`, shared by all Mission Jobs, `JobPlaces`), settlers in `SettlementBlock.assignees()`; a settler's Mission Job is chosen in the Job picker when it joins and changed with the Job switcher. Its Mission Specializations are unlocked by Station Types (tabs: Storage, Info, Missions, Station Types; the Station Types tab makes the window tall).

**Bard**:
A Mission Specialization of the Adventurer, unlocked by a note block in a Station Types slot of the Mission Board; does the Villager missions (sample missions "Invite Villagers (<type>)" and "Village Migration (<type>)" per Villager biome type). Code: `ModJobs.BARD` (base EXP 10, hunger factor 1.0, overridable in `civmod_jobs/bard.json`).

**Mission**:
A datapack-defined task a Mission Job settler leaves the settlement for (duration, difficulty, result: loot or Villagers). Code: `Mission` in the `civmod:mission` datapack registry (`Missions`, files in `data/<namespace>/civmod/mission/`); arithmetic in `MissionRules`, outcome constants in `OutcomeRules` (`civmod_mission/outcome.json`). The mission a settler reserved or is away on: `MissionTrip`; who takes which mission: `MissionPicks`.
_Avoid_: Quest, expedition

**Villager mission**:
A Mission whose result is vanilla Villagers instead of loot: its `villagers` block (`Mission.Villagers`: count, Villager biome type, nitwit chance values) replaces `loot_tables` / `rolls` (a mission with both or neither fails to load). Each Villager is a nitwit with `MissionRules.nitwitChance` (the skill never changes the count). On success they arrive as unemployed adults where the Bard reappears and follow it to the Mission Board (status "Bringing villagers", `JobStatus.BRINGING_VILLAGERS`; the Settler Shell starts the lead right after the return through `MissionLoop.leadingJob`, then it holds the evening; at most `MissionLoop.LEAD_TICKS`; not saved, after loading they stay vanilla Villagers where they are); during catch-up they arrive at the board. Every one keeps the mission's recommended level (`ModAttachments.MISSION_LEVEL`, read with `MissionVillagers.missionLevel`) for its conversion into a Settler. Code: `MissionVillagers` (`arrive`, `follow`, `release`), called from `MissionLoop`.
_Avoid_: Settler (a Villager is always a vanilla Villager entity), recruits

**Mission Offer**:
The missions a Mission Board offers today; the player marks the ones to be done. Each Mission Job of the board has its own share: the Adventurer board level + 1, plus 1 per open Station Types slot without a Specialization; every unlocked Specialization as many as the board level (at most 26 on a level-5 board). Code: `MissionOffer` (entries + refill day) in `SettlementBlock.missionOffer()`, refilled and marked through `MissionOffers`; shares `MissionRules.quotas` (empty slots `StationTypes.emptySlots`), drawn by `MissionRules.refill`.
_Avoid_: Quest list, mission pool (the pool is all missions)

**Mission Assignment**:
A marked mission the player assigned to one settler of the board; it is **locked** for every other settler, and the lock outlives the assignee (death, leaving the board) until the player reassigns or unlocks it. Code: `MissionOffer.Entry.assignee()` / `locked()`, set through `MissionOffers.assign` / `unlock`; a reserved assigned mission: `MissionTrip.assigned()`.
_Avoid_: Reservation (a settler reserves the mission it picked)

## Logistics

**Clerk**:
The first Logistics Job: the settlement's store and food point. Code: `ModJobs.CLERK` on `ModBlocks.CLERK_BLOCK`, work loop `ClerkLoop` (stands on the stand spot of the day during its shift from its own early waking time (05:30–06:00, before any other settler wakes) to 18:30, `ClerkStorage.inShift`, `ClerkStorage.shiftStart`, `ClerkStorage.SHIFT_END`; `SettlerWork.dayTimes`). Hungry settlers without food wait at its queue before they forage (`ClerkStorage.nearestClerk`).

**Clerk storage**:
The Clerk JobBlock's own 27 slots plus the containers linked with the Scroll of Assignment (2 / 4 / 8 / 12 / 16 by level, `SettlementRules.clerkStorage`; extra ones after a downgrade are inactive, `ClerkStorage.activeStorage`). Code: `SettlementBlock.storage()`, linked through `JobAssignments.cycleClerkPoint`.
_Avoid_: Pantry (removed), warehouse (that is a Clerk JobBlock with Deliverymen)

**Access rule**:
Without a working Clerk (no settler assigned, or outside the shift) only the 27 block slots are reachable; the linked storage only through a working Clerk at its counter. Hoppers and pipes see the 27 slots (the block's item capability). Code: `ClerkStorage.working`, `ClerkStorage.stock`.

**Stock**:
What a visitor reaches at one place under the access rule, seen as one list of slots; at a Clerk JobBlock counted by its **stock view** (own slots re-read on change, linked containers at most every 10 s, CivMod's own transactions written through, extraction checks the real container), elsewhere counted on every access. Code: `Stock` (built by `ClerkStorage.stock`), `ClerkStock` (stock view), `StockSummary`; `Stock.counter()` marks the whole storage of a working Clerk, where every take is a hand-out.

**Counter**:
Where a working Clerk serves: within 3 blocks of the stand spot of the day (or next to its JobBlock without spots). One customer at a time, service time by skill (`ClerkStorage.serviceTicks`); a hand-out gives the Clerk EXP. Code: `ClerkStorage.visit` (where a visitor walks), `ClerkCounter` (queue and hand-out), `SettlerWork.reachableVisit` (an unreachable counter offers only the block slots that day; the path is only checked within 16 blocks, further away a search is cut off by the follow range).

**Stand spot**:
A block a Clerk stands on, with a facing; up to 8 per Clerk JobBlock; one is picked each morning and at 12:00, never the same twice in a row. Code: `SettlementBlock.StandSpot`, `ClerkSpots.today`, saved as `SettlementBlock.spotOfDay()`.

**Stock rule**:
A Clerk's rule for one item with the optional fields **Min**, **Max** and **Order** (0 = not set, "—") and a priority (−100…+100, higher first); one rule per item and Clerk, at least one of Min / Max, Min ≤ Max, Order only with Min (Min = Max is "exact", both set a band). 9 per JobBlock level in rows of 9, extra ones after a downgrade kept but inactive. Requests (Min) are active only while a Clerk settler is on shift; checked every 30 s (staggered), right after a delivery into the Clerk and once per simulated hour in catch-up. Shown in the Clerk's "Stock" tab (`StockTab`, `StockTabView`; editor rows Min / Max / Order with − / text field / +, steps 1 / Shift 10 / Ctrl 64). Old saves: a Minimum rule's `amount` loads as Min. Code: `StockRule` (data, `target`, `withMin` / `withMax` clamp against each other), `StockRules` (edit, `check`, `checkSurplus`, `atMax`), saved in `SettlementBlock.stockRules()`.
_Avoid_: Threshold, mode, Minimum rule

**Min**:
A stock rule's lower bound: when the stock (plus results in delivery) falls below it, the rule opens one request; without Min the rule never requests.

**Max**:
A stock rule's cap: collecting jobs whose Home Clerk holds ≥ Max of the item leave it out of their rolls (the dropped item, Silk Touch / Fortune considered; a type whose loot gives several items, like the Animal Farmer's cow, only when all of them are at Max; `ResourceCollectionProfile.dropped` / `droppedItems`, `ResourceCollectionProfile.leftOut`, `StockRules.atMax`) and pause when every main resource is excluded or at Max (`JobStatus.MAX_REACHED` / `NOTHING_TO_PRODUCE` / `ALL_EXCLUDED`, `WorkRules.pause`); the Found tab marks such an entry "Maximum reached at <Clerk>" (`FoundTabData.Entry.maxAt`). Stock above Max is **surplus**.

**Order**:
How much a request orders on top of Min: the **target stock** of a rule is min(Min + Order, Max); without Order it is Max, without Max it is Min (`StockRule.target`). The open request asks for target − stock − amount being crafted − amount in delivery on every check and is withdrawn at the target; the open request is the hysteresis, nothing else is saved.
_Avoid_: Request (that is the open order itself)

**Surplus**:
Stock above a rule's Max at a Clerk. At a Served Clerk it becomes a surplus task for the serving Warehouse's Deliverymen into the Warehouse's store, as far as the Warehouse is below its own Max for the item; a Warehouse itself, a Clerk without Warehouse or a Warehouse at its Max keeps it, the rule shows "Surplus: nowhere to go" (`StockRules.surplusNowhere`, `ClerkTabData.nowhere`). Checked with every rule check and in the Deliveryman's end-of-work sweep (`StockRules.checkSurplus`), no minimum amount. A surplus task is an input delivery whose `jobBlock` is its source Clerk (`DeliveryTask.surplus`, `Deliveries.setSurplus` / `freeSurplus`); Deliveries tab: "Surplus: <Clerk> → <Warehouse>".

**Request**:
"Produce N more of item Y" for the settlement, with a priority and the Clerk to deliver to. A stock rule keeps at most one open (amount = target stock − stock − amount being crafted − amount in delivery); other sources post with priority 0, sub-requests inherit their parent's priority. Producers see them by priority and claim the part they can do in one round (`Request.Claim`); `amount()` is what is still open, `crafting()` what is claimed. Code: `Request` (data), `Requests` (`claimable`, `claim`, `release`, `releaseAll`, `finish`, `setMissing`, `post`, `withdraw`), saved in `Settlement.requests()`; status in the Clerk's Requests tab via `ClerkTabData`, "No Craftsperson can make this" from `Producers.canMake`. A removed Clerk takes its rules' requests with it; other requests that deliver to it lose their target (own drop-off). "Missing" lists every missing base input for the whole open amount (`Request.missing`, a list).
_Avoid_: Order, job, task

**Deliveryman**:
A Logistics Job that carries items between containers for its Warehouse; its places are on the Clerk JobBlock (1 / 2 / 4 / 6 / 8 by level, the `settlers` list of `civmod_job_blocks/deliveryman.json`). A Clerk never delivers itself. Automatic mode so far: it empties the drop-offs of Resource Collection Jobs into their Home Clerk's store and carries result and input deliveries. Code: `ModJobs.DELIVERYMAN`, work loop `DeliverymanLoop` (the Logistics category's second loop beside `ClerkLoop`; picks and batches Delivery tasks to the same store at its decision, walks from source to source – at a Clerk its Warehouse does not serve it picks up at the counter under the access rule, a hand-out for that Clerk – unloads as staff through the shell's delivery into `ClerkStorage.stock(…, true)`, status `JobStatus.COLLECTING` / `UNLOADING` / `STORAGE_FULL` / `NO_PICKUPS`, EXP per delivered stack). Its carry inventory is the job's buffer (`JobBuffer.room`; 9 stacks at skill level 1 to 27 at 100, `DeliverymanLoop.carryStacks`, `SettlerWork.carryStacks`). Catch-up moves at most the carry capacity per trip, trips by distance (`DeliverymanLoop.tripTicks`).
_Avoid_: Courier, porter, Clerk specialization

**Warehouse**:
A Clerk JobBlock with Deliverymen (or one that links Served Clerks); its Deliverymen work for it and for its Served Clerks and are their staff (direct access to the whole store, no counter, no working Clerk needed). A Warehouse without Deliverymen leaves its tasks at "No Deliveryman". Code: `Warehouses` (`hasDeliverymen`, `warehouseOf`, `handled`).
_Avoid_: Depot, storage (that is the Clerk storage)

**Served Clerk**:
A Clerk JobBlock linked to a Warehouse with the Scroll of Assignment (2 / 4 / 6 / 8 / 12 by level, `SettlementRules.servedClerks`; extra ones after a downgrade inactive, `Warehouses.activeServed`); at most one Warehouse per Served Clerk, never one with own Deliverymen, no chains. Code: `SettlementBlock.served()` on the Warehouse, linked through `Warehouses.link` (`Warehouses.LinkResult`), `Warehouses.servedBy`; shown with the scroll (`JobPointsDisplay.JobPoints.served`) and on the Clerk's info tab (`ClerkTabData.served` / `servedBy`).

**Home Clerk**:
The Clerk JobBlock a JobBlock belongs to: the nearest one of its settlement (a Clerk JobBlock is its own), stored and recomputed only when a Clerk JobBlock is placed or removed or a JobBlock is added. The Warehouse serving it handles the JobBlock's transports. Code: `SettlementBlock.homeClerk()`, set in `SettlementData.addBlock` / `removeBlock` through `Warehouses.assignHomeClerks`.

**Delivery task**:
One transport for a Warehouse's Deliverymen: a pickup (empty a drop-off into the Home Clerk, `DeliveryTask.pickup`), a result delivery (producer to requesting Clerk), an input delivery (store to a producer's collect inventory), a surplus task (Served Clerk to its Warehouse, see Surplus). Each task has an id (`DeliveryTask.id`); at most one open pickup per source; taken tasks are locked to one Deliveryman until done, released or the day ends. A pickup is created when a settler delivers into a drop-off that then holds a stack or is half full, by the end-of-work sweep, urgently on "Drop-off full"; none for a Clerk JobBlock drop-off; the job's tools stay in a drop-off that is its collect inventory too. A trip batches tasks to the same store (`Deliveries.destination`). Code: `DeliveryTask` (`DeliveryTask.Taker`, not saved; `carrier`, `carried`), `Deliveries` (`request`, `pick`, `batch`, `score`, `take`, `release`, `releaseAll`, `done`, `destination`, `warehouseOf`, `staff`), saved in `Settlement.deliveries()`; created by `Pickups` (`delivered`, `sweep`, `due`); shown in the Clerk's "Deliveries" tab (`DeliveriesTab`, `ClerkTabData.DeliveryRow`).
_Avoid_: Request (that is "produce more"), order, job

**Result delivery**:
A Delivery task for a producer's result: a Craftsperson on "Delivery: Deliveryman" (`SettlementBlock.byDeliveryman`, toggled in the JobBlock info tab through `JobAssignments.toggleDelivery`) puts the result into its own drop-off and posts one (`Deliveries.postResult`; results of the same kind waiting at that drop-off for that Clerk share one task). Until it arrives it counts as in delivery for the requesting Clerk's stock rules (`Deliveries.inDelivery`), so nothing is produced twice. Routing (`Deliveries.destination`): the producer's Warehouse brings it straight to the requesting Clerk if it serves that Clerk, otherwise into the producer's Home Clerk, where it waits relayed (`DeliveryTask.Result.relayed`) for the requesting Clerk's Warehouse. Picked up it is carried (`Deliveries.carry`, `DeliveryTask.Result.carrier`, saved) until unloaded (`Deliveries.arrived`); nothing found at the source ends it, a dropped load (job removed, death) loses it (`Deliveries.dropCarried`); a removed requesting Clerk or producer takes it with it. Code: `DeliveryTask.Result`.
_Avoid_: Delivery (that is the Craftsperson delivering itself), shipment

**Input delivery**:
A Delivery task for a producer's inputs: a Craftsperson on "Delivery: Deliveryman" plans its round with every Clerk of the settlement as its Home Clerk's Deliverymen find it (staff at the Clerks their Warehouse handles, the counter under the access rule elsewhere) and posts one input delivery per store for what its collect inventory lacks, its Home Clerk first, then the others by distance (`Deliveries.postInput`, `DeliveryTask.Input`: items, collect inventory, carrier). The round waits (`Round.Phase.WAIT`, `Round.inputs`, status `JobStatus.WAITING_FOR_INPUTS`) while the settler works its next priority, and fetches everything at the collect inventory once they arrived. Taking it reserves its inputs at the store (`Deliveries.take`); the Deliveryman picks them up whole (`Deliveries.pickUpInput`; at a foreign Clerk's counter through `ClerkStorage.stock(…, dayTime, atCounter, task)`, a hand-out) and unloads them into the collect inventory, where they are done (`Deliveries.arrived`). A shortfall at pickup frees the task; a store short for an untaken one gives the round up with "Missing" (`ProducingLoop.inputsArrived`). A released round drops the ones not carried yet (`Deliveries.dropInputs`). Deliveries tab: "<store> → <producer>".
_Avoid_: Request (that is "produce more"), fetching (that is the producer taking inputs itself)

**Reservation**:
An amount of an item in a Clerk's storage held for one Delivery task (so far an input delivery, reserved when a Deliveryman takes it) until pickup, cancel or end of day; everybody else sees stock minus reservations. A reservation holds while its task is locked to a Deliveryman on the day it was made, so releasing or finishing the task (`Deliveries.release`, `releaseAll`, `done`) or the end of the day releases it; not saved (like the lock). Customers, producers and other Deliverymen get a Stock without it (counts, slots and takes leave it out, from what the Stock reaches, so the block slots alone may show nothing of a reserved item); the task asking sees all. Picked up, it is consumed; a shortfall at extraction (or no valid reservation) puts back what was taken, releases it and frees the task for another try. Code: `Reservations` (`reserve`, `reserved`, `pickUp` → `Reservations.Pickup`, `release`), `Reservations.Reservation` (item, count, task, day), kept per Clerk JobBlock in the `Settlement`; applied by `ClerkStorage.stock(…, task)` through `Stock.without`.
_Avoid_: Claim (that is a producer's share of a Request)

**Job picker**:
The window that opens when the scroll assigns a settler to a JobBlock: the block's Jobs with taken / places (full ones greyed out); a settler holds at most one Job per JobBlock. Code: `JobPicker` (payloads `Open` / `Choose`), `JobPickerScreen`; Jobs and places per JobBlock in `JobPlaces` (`JobPlaces.Line`; a Base Job and its Specializations share places), checked by `JobAssignments.check` before it opens and assigned through `SettlementWorld.assign` (`JobAssignments.assign(…, job)` plus the settler's job list; shared with automatic assignment); each assignee records its Job (`SettlementBlock.Assignee.job()`, the Clerk of a block: `SettlementBlock.assignee(ModJobs.CLERK.get())`). Places per level: the `settlers` list of `civmod_job_blocks/<job>.json` of the Base Job (`JobBlockSettlers.settlers`, missing → 1, at most `SettlementRules.MAX_SETTLERS`).

**Job switcher**:
The "⇄" of a job in the settler window's Jobs tab: lists the other Jobs unlocked at that job's JobBlock (`JobAssignments.switchOptions`, `SettlerInfo.JobState.switchTo`) and switches to one keeping the JobBlock link and the priority (`SettlerMenu.switchJob`, payload `SettlerMenu.SwitchJob`, `SettlementWorld.switchJob`, `Settler.replaceJob`); the work state at that JobBlock is forgotten like on any job switch. A Job sharing the settler's places always has room; one with own places (Deliveryman) needs a free one.
_Avoid_: re-assigning to change a Specialization

**Automatic assignment**:
A toggle per Job a JobBlock offers (checkbox at the end of its line in the info tab, default off, old saves and the Mission Board included): when it is switched on and every morning at 06:00 (catch-up too, for settlers whose missed time contains that morning) jobless settlers of the settlement (Laborer only; loaded settlers) take that Job's free places, highest skill in the Job first, ties nearest to the JobBlock, until no place or candidate is left; never on other events, so a freed place stays free until the next morning. Players within 32 blocks see "<Name> is now a <Job>". Code: `AutoAssignment` (`setAuto`, `morning`, `catchUpMorning`, the 06:00 check in its server tick), `SettlementBlock.autoAssign()` (saved as `auto_assign`), `JobPlaces.Line.auto()`, menu button `InfoTab.autoButtonId`, refreshed info `JobBlockInfo.Sync`.
_Avoid_: auto-hire, auto-job

**Food point**:
Every Clerk JobBlock; settlers take food from the nearest one whose reachable stock has food (a Clerk from its own storage first). Code: `ClerkStorage.nearestFood` → `ClerkStorage.FoodPoint`.
_Avoid_: Pantry

## Producing

**Craftsperson**:
The first Producing Job: crafts requested items from its patterns at a crafting table. Code: `ModJobs.CRAFTSPERSON` on `ModBlocks.CRAFTSPERSON_BLOCK`, profile `CraftspersonProfile` (`crafting`, no filter: crafts everything), work loop `ProducingLoop` (the Producing category's loop for every `ProducingProfile`); job factor 0.3 and base EXP 2 in `civmod_jobs/craftsperson.json` (`Job.jobFactor`, `JobBalancing`). The example addon's Stonecutter (`civmod_example:stonecutter`, `stonecutting` at a stonecutter, job factor 1.2, unlocked by a stonecutter) is a Producing Specialization of it.

**Mechanic**:
The first built-in crafting Specialization of the Craftsperson: unlocked by a Crafter in a Station Types slot, works at a Crafter (`civmod:stations/mechanic`), `crafting` recipes in the domain `civmod:domains/redstone`, job factor 1.2 (four times the Craftsperson). Code: `ModJobs.MECHANIC`, profile `MechanicProfile`, `civmod_jobs/mechanic.json`.

**Smith**:
The crafting Specialization of the Craftsperson for metal: unlocked by a smithing table in a Station Types slot, works at a smithing table (`civmod:stations/smith`, the station of both its recipe types), `crafting` and `smithing` recipes in the domain `civmod:metals`; of `smithing` only `smithing_transform` (netherite upgrades keep the base item's components, `ProducingProfile.execute`), armor trims are no patterns (`PatternRecipes`). Job factor 0.3, raised to 1.2 by its Station Extra (`#minecraft:anvil` within 4 blocks; anvils do not wear); base EXP 2. Missing ingots stay "Missing: …" (no request to the Smelter). Code: `ModJobs.SMITH`, profile `SmithProfile`, `civmod_jobs/smith.json`.

**Smelter**:
The Producing Base Job of all furnace work: smelts requested items (`smelting`) at furnaces (`civmod:stations/smelter`), simulated in its buffer while the furnace shows its lit state. Works several furnaces in parallel, each with its own charge (`parallel_stations` [1, 2, 3, 4, 5] per JobBlock level in `civmod_jobs/smelter.json`, `ProducingProfile.parallelStations`), each one occupied; a furnace holding items or burning on its own is never used (`Furnaces.isIdle`). Burns fuel from the JobBlock's fuel list (Fuel tab, default coal then charcoal; `ProducingProfile.fuel`, `Fuels`, `SettlementBlock.fuel`) with Remaining Heat; fuel is fetched like inputs, rests stay in the buffer (`CategoryLoop.keeps`); none in stock: job status and request name the one listed fuel ("Missing: 1× Coal"), with several or no listed fuels "Missing: Fuel" (`JobStatus.MISSING_FUEL`, `Request.Missing.ANY_FUEL`). Code: `ModJobs.SMELTER` on `ModBlocks.SMELTER_BLOCK`, profile `SmelterProfile.smelter()` (work time = cooking time ÷ 80 operations: level 20 = vanilla speed), base EXP 1, job factor 1.0. Its JobBlock has Station Types (slot 1: furnace) for the Cook and the Founder.

**Cook** / **Founder**:
The Smelter's Specializations: the Cook works `smoking` recipes at smokers (`civmod:stations/cook`), unlocked by a smoker; the Founder (`civmod:founder`) `blasting` recipes at blast furnaces (`civmod:stations/founder`), unlocked by a blast furnace. Everything else like the Smelter (parallel stations, fuel list of the JobBlock, Remaining Heat, station lock); job factor 1.0, base EXP 1, no station extra. Twice as fast as the Smelter through the station's vanilla cooking speed (2 for a smoker or blast furnace, `Furnaces.cookingSpeed`): work time and heat per item are the recipe's cooking time ÷ that speed, so fuel per item stays vanilla. Code: `ModJobs.COOK`, `ModJobs.FOUNDER`, profiles `SmelterProfile.cook()` / `founder()`, `civmod_jobs/cook.json` / `founder.json`.

**Charge**:
One execution a station of a Producing Job works at a time in the CRAFT phase; a Job with parallel stations runs one charge per station (`Round.charges`, `ProducingLoop.work`). Its inputs leave the buffer when it is done, so a station broken during a charge leaves them in the buffer.
_Avoid_: Batch

**Domain**:
The item tag a crafting Specialization is limited to (`ProducingProfile.domain`): a recipe belongs to it if any input has an alternative in the tag, so mixed recipes (a piston) fit several domains. Patterns outside it are filtered ("Outside this Job's domain", not checkable), and the chain resolution makes no intermediates outside it: they are taken from stock or reported "Missing". Code: `Patterns.filtered`.
_Avoid_: Material set, category

**Pattern**:
One of the 54 slots of a pattern library: a 3×3 grid and an output. Made from the output alone it follows a recipe of the profile's recipe types (tag ingredients flexible, arrows cycle the recipes making the item; crafting recipes keep their shape, others fill the cells in order); made from inputs it uses exactly those items. Every Producing Job unlocked at a JobBlock has its own library and Patterns tab (`SettlementBlock.Library`, `SettlementBlock.patterns(job)`); a settler works only its Job's library. A library whose Job is no longer unlocked is "locked" (`Patterns.locked`): kept, unusable, not editable. A pattern of a recipe type its Job no longer follows stays ("foreign") with its checkmark. A pattern the Job cannot use (locked, foreign or filtered) is "unusable", greyed out in the tab (`Patterns.unusable`, `PatternsTabData.unusable`); not to be confused with the job status Blocked. Code: `Pattern` (data), `Patterns` (edit, `inputs`, `usable`, all per Job), `PatternRecipes` (recipe lookup); GUI via `PatternsTab` (one per Job, `JobBlockMenu.patternJobs`), data `PatternsTabData`.
_Avoid_: Recipe (a pattern follows one), blueprint

**Innate recipe**:
A Job-only recipe of the type `civmod:job_crafting` that players cannot craft: every Producing Job named in its `jobs` knows it, in the first library slots by recipe id, locked (only the checkmark toggles), counting towards the checkmark limit; domain and filters do not apply. May name a tool (fetched onto the belt, worn per execution) and a station; without them only that recipe is skipped and marked in the Patterns tab ("Needs Shears"). Code: `JobCraftingRecipe` (API, `ModRecipes.JOB_CRAFTING`), `InnateRecipes` (per Job, tool / station lines), `Pattern.innate`, `Patterns.syncInnate` (layout on use and datapack reload, `InnateRecipeEvents`), `Patterns.setNeeds` / `PatternsTabData.needs`; recipe viewers: `ClientInnateRecipes`, the "Job Recipes" categories in `compat/jei` and `compat/rei`. For a Resource Collection Job named in `jobs` the recipe instead joins the roll: optional `requires` (an entity type, or a block with optional state, that the scan must have found; `ResourceCollectionProfile.count`) and `weight` (default 1, times the count of `requires` in the scan, against `ResourceCollectionProfile.weight` of the found types; `CollectionRecipes.Offer`); while offered it is an own Found tab entry (type = recipe id, exclusion and Max apply, greyed out "Needs: Bucket" without its input); the settler fetches the inputs from its collect inventory onto the belt like a tool, uses one per ingredient and collects the result (no tool wear, no double drop). Code: `CollectionRecipes`, `JobCraftingRecipe.Requirement`, `InnateRecipes.collecting`; recipes `recipe/animal_farmer/` (milk, mushroom stew), `recipe/beekeeper/` (honey bottle).
_Avoid_: Built-in pattern, Job recipe (in code)

**Fletcher**:
The crafting Specialization of the Craftsperson for bows, arrows and string goods: unlocked by a fletching table, works at a fletching table (`civmod:stations/fletcher`), `crafting` recipes in the domain `civmod:domains/fletching`; innate recipes wool → 2 string (tool `#c:tools/shear`) and gravel → flint. Job factor 0.3, raised to 1.2 by its Station Extra (`minecraft:target` within 4 blocks); base EXP 2. Code: `ModJobs.FLETCHER`, profile `DomainProfile.fletcher()`, `civmod_jobs/fletcher.json`, `recipe/fletcher/`.

**Stonemason**:
The crafting Specialization of the Craftsperson for stone: unlocked by a stonecutter (like the example addon's Stonecutter: one item, both Jobs), `stonecutting` recipes at a stonecutter (`civmod:stations/stonemason`, the main type) and `crafting` recipes at a crafting table nearby (`civmod:stations/craftsperson`), both in the domain `civmod:domains/stone`; no innate recipe. Job factor 0.3, raised to 1.2 by its Station Extra (`minecraft:grindstone` within 4 blocks); base EXP 2. Code: `ModJobs.STONEMASON`, profile `DomainProfile.stonemason()`, `civmod_jobs/stonemason.json`.

**Dyer**:
The crafting Specialization of the Craftsperson for dyes and dyed blocks: unlocked by a loom, works at a loom (`civmod:stations/dyer`), `crafting` recipes in the domain `civmod:domains/dye`; innate recipe dyed wool (`civmod:dyed_wool`) → white wool at a water cauldron (`civmod:stations/water_cauldron`). Job factor 0.3, raised to 1.2 by its Station Extra (`minecraft:water_cauldron` within 4 blocks); base EXP 2. Code: `ModJobs.DYER`, profile `DomainProfile.dyer()`, `civmod_jobs/dyer.json`, `recipe/dyer/`.

**Checkmark**:
A pattern the producer works; 6 + 1 per 5 levels of the settler's effective skill by default (`ProducingProfile.checkLimit`, `Patterns.checkLimit`), per library; with several settlers in the Job at the JobBlock the highest one counts (each reports its own, `Patterns.enforceLimit(…, settler, limit)`, kept per library and saved as `workers`). Only usable checked patterns count against the limit (`Patterns.usable`); foreign ones keep their checkmark outside it. A new pattern is checked automatically while one is free and the profile can use it; at the limit it stays unchecked with the state line "A <Job> masters only <n> recipes at once – choose which to check.". When the limit drops, the last usable checked patterns in list order lose theirs (`Patterns.enforceLimit`) with the state line "<n> checkmarks removed". The state line is one transient notice (`SettlementBlock.Notice`, `PatternsTabData.notice`), ended by the next edit of the library (put, toggle, clear).

**Metal**:
Item tag `civmod:metals` (ingots, nuggets, raw materials, metal blocks), the Smith's domain; the Craftsperson crafts metal too. Code: `Patterns.METALS`, `Patterns.isMetal`. Filters of a profile in general: `Patterns.filtered`, `Patterns.uncheckFiltered` (a filtered pattern loses its checkmark at every decision and tab sync), `PatternsTabData.filtered()`.


**Round**:
One claimed part of one request: claim what fits the buffer, fetch inputs (collect inventory, then the posting Clerk; on "Delivery: Deliveryman" wait for input deliveries into the collect inventory first), craft intermediates and the item, deliver (on "Delivery: Deliveryman" into its own drop-off with a Result delivery). Code: `Round`, kept in the job's Workplace (`Workplace.round`), run by `ProducingLoop`, planned by `CraftPlan` (chain resolution with own patterns; `CraftPlan.most` sizes the claim).

**Station**:
The work place of a Producing Job, named per recipe type by a block tag (`ProducingProfile.station`, by convention `<namespace>:stations/<job>`): the main recipe type's station is set with the scroll (up to 8, one per day) or the nearest one within 16 blocks of the JobBlock inside the border; other recipe types use their nearest station. A Job with parallel stations (`parallel_stations`) also works further stations of the main type, from the scroll points or the nearest ones (`ProducingLoop.stations`).
_Avoid_: Workbench (the JobBlock is none)

**Station Extra**:
A block near a Job's station of the day that replaces its base job factor (the Smith's anvil: 0.3 → 1.2); named per Job in `station_extras` of `civmod_jobs/<job>.json` (`{ blocks (id, list or #tag), radius (cube, 1–8), job_factor }`), the highest matching factor counts, none: the Job's own `job_factor`. Read once when the station of the day is chosen and kept for that day (not saved: read again after loading). Not a bonus multiplier: the biome bonuses act on top. Any Job may name them; they take effect where the job factor counts (Producing Jobs). Code: `StationExtra` (entry and codec, in `JobBalancing.stationExtras`), `StationExtras.jobFactor`, kept per day in `Workplace.jobFactor`, read by `SettlerWork.jobFactor`.
_Avoid_: Work aid, workshop tool (collides with tools)

**Occupied**:
A work place a settler has chosen for the day: no other settler uses it that day; while the settler actually works there, players cannot open its GUI ("In use by <name>"). Breaking it stays allowed; the settler then picks a new free one. Code: `OccupiedPlaces` (kept in the `Settlement`, not saved), chosen in `Workplace.workPlaceOfDay`, status `JobStatus.NO_FREE_WORK_PLACE`, player side `WorkPlaceLock` with `CategoryLoop.usesTarget` / `CategoryLoop.usesPlace` (further parallel stations with a running charge) / `SettlerWork.worksAt`, addon hook `ResourceCollectionProfile.autoSearch(level, jobBlock, free)`.
_Avoid_: Claim (a claim is part of a request, see Round), reserved (stock reservations)

**Remaining Heat**:
The burn time a furnace of the Smelter keeps from fuel already burnt, used by its next charge so no fuel is wasted (one coal = 8 items, also over 8 single-item charges). Kept on the furnace's block entity (lost when the furnace is broken), in ticks of vanilla burn time. Code: attachment `ModAttachments.REMAINING_HEAT`, paid in `Furnaces.pay` (heat per execution: the recipe's cooking time ÷ the station's vanilla cooking speed, `Furnaces.heatOf`), planned in `ProducingLoop.fuelNeed`.
_Avoid_: Batch, leftover fuel

## Population

**Villager conversion**:
Turning a vanilla Villager inside a settlement border into a Settler of that settlement: an item of `civmod:conversion_primer` (default water bottle, which becomes a glass bottle) or a splash water bottle makes it Receptive; an item of `civmod:conversion_food` (default bread) then starts the conversion if the feeding checks pass (in order: baby, outside every border, `maxSettlersPerSettlement` reached counting Settlers and running conversions, no Free bed; a refusal shakes the head, names the reason in the action bar and uses up nothing). Feeding reserves a Free bed (`Settlement.bedReservations()`, through `SettlementData.reserveBed`); the Villager shakes for 5 minutes of world time (`ModAttachments.CONVERSION`, synced; completes on its first tick after loading). At the end the reserved bed (else another Free bed) goes to the new Settler: random skin, empty inventory, name tag or race name, every Job rolled from the mission level (`VillagerConversion.rollLevel`; nitwits and Villagers without one start at level 1). Without a bed or with the settlement dissolved it fails (stays a Villager, items used up); a Villager that dies frees its reservation. Code: `VillagerConversion`.
_Avoid_: Curing (that is the zombie villager), recruiting

**Receptive**:
The 60 s window after the conversion primer in which a Villager takes the conversion food; without it the food is vanilla (trading). Code: `ModAttachments.RECEPTIVE_UNTIL` (not saved), `VillagerConversion.isReceptive`.

**Free bed**:
A bed inside the settlement border that no Settler owns, no Villager conversion has reserved and is no player's respawn bed; a bed a Villager holds counts as free (Settlers before Villagers: the Settler takes it and the Villager forgets its home). Used by Settlers claiming a bed and by the Villager conversion. Code: `SettlerBeds.freeBeds`, claimed with `SettlerBeds.claim`.
