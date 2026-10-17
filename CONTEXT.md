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

**Starter tool**:
The tool a new JobBlock puts into its own inventory when it is placed for the first time (Lumberjack stone axe, Miner stone pickaxe, Fisherman fishing rod). A broken JobBlock no longer carries one.
_Avoid_: Free tool, bonus item

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

## Logistics

**Clerk**:
The first Logistics Job: the settlement's store and food point. Code: `ModJobs.CLERK` on `ModBlocks.CLERK_BLOCK`, work loop `ClerkLoop` (stands on the stand spot of the day during its shift from its own early waking time (05:30–06:00, before any other settler wakes) to 18:30, `ClerkStorage.inShift`, `ClerkStorage.shiftStart`, `ClerkStorage.SHIFT_END`; `SettlerWork.dayTimes`). Hungry settlers without food wait at its queue before they forage (`ClerkStorage.nearestClerk`).

**Clerk storage**:
The Clerk JobBlock's own 27 slots plus the containers linked with the Scroll of Assignment (2 / 4 / 8 / 12 / 16 by level, `SettlementRules.clerkStorage`; extra ones after a downgrade are inactive, `ClerkStorage.activeStorage`). Code: `SettlementBlock.storage()`, linked through `JobAssignments.cycleClerkPoint`.
_Avoid_: Pantry (removed), warehouse (that is a Clerk JobBlock with Deliverymen)

**Access rule**:
Without a working Clerk (no settler assigned, or outside the shift) only the 27 block slots are reachable; the linked storage only through a working Clerk at its counter. Hoppers and pipes see the 27 slots (the block's item capability). Code: `ClerkStorage.working`, `ClerkStorage.stock`.

**Stock**:
What a visitor reaches at one place under the access rule, seen as one list of slots; counted on every access (no snapshot). Code: `Stock` (built by `ClerkStorage.stock`); `Stock.counter()` marks the whole storage of a working Clerk, where every take is a hand-out.

**Counter**:
Where a working Clerk serves: within 3 blocks of the stand spot of the day (or next to its JobBlock without spots). One customer at a time, service time by skill (`ClerkStorage.serviceTicks`); a hand-out gives the Clerk EXP. Code: `ClerkStorage.visit` (where a visitor walks), `ClerkCounter` (queue and hand-out), `SettlerWork.reachableVisit` (an unreachable counter offers only the block slots that day; the path is only checked within 16 blocks, further away a search is cut off by the follow range).

**Stand spot**:
A block a Clerk stands on, with a facing; up to 8 per Clerk JobBlock; one is picked each morning and at 12:00, never the same twice in a row. Code: `SettlementBlock.StandSpot`, `ClerkSpots.today`, saved as `SettlementBlock.spotOfDay()`.

**Stock rule**:
A Clerk's "keep at least X of item Y" with a priority (−100…+100, higher first); 9 per JobBlock level in rows of 9, extra ones after a downgrade kept but inactive. Only the Minimum mode exists. Active only while a Clerk settler is on shift; checked every 30 s (staggered), right after a delivery into the Clerk and once per simulated hour in catch-up. Shown in the Clerk's "Stock" tab (`JobBlockMenu.TAB_STOCK`). Code: `StockRule` (data), `StockRules` (edit, `check`), saved in `SettlementBlock.stockRules()`.
_Avoid_: Threshold, order

**Request**:
"Produce N more of item Y" for the settlement, with a priority and the Clerk to deliver to. A stock rule keeps at most one open (amount = X − stock − amount being crafted); other sources post with priority 0, sub-requests inherit their parent's priority. Producers see them by priority and claim the part they can do in one round (`Request.Claim`); `amount()` is what is still open, `crafting()` what is claimed. Code: `Request` (data), `Requests` (`claimable`, `claim`, `release`, `releaseAll`, `finish`, `setMissing`, `post`, `withdraw`), saved in `Settlement.requests()`; status in the Clerk's Requests tab via `ClerkTab`, "No Craftsperson can make this" from `Producers.canMake`. A removed Clerk takes its rules' requests with it; other requests that deliver to it lose their target (own drop-off). "Missing" lists every missing base input for the whole open amount (`Request.missing`, a list).
_Avoid_: Order, job, task

**Deliveryman**:
A Logistics Job that carries items between containers for its Warehouse; its places are on the Clerk JobBlock (several per block, by level). A Clerk never delivers itself.
_Avoid_: Courier, porter, Clerk specialization

**Warehouse**:
A Clerk JobBlock with Deliveryman places; its Deliverymen work for it and for its Served Clerks and are their staff (direct access, no counter).
_Avoid_: Depot, storage (that is the Clerk storage)

**Served Clerk**:
A Clerk JobBlock linked to a Warehouse with the Scroll of Assignment; at most one Warehouse per Served Clerk, never one with own Deliverymen.

**Home Clerk**:
The Clerk JobBlock a JobBlock belongs to: the nearest one of its settlement, kept until a Clerk JobBlock is placed or removed. The Warehouse serving it handles the JobBlock's transports.

**Delivery task**:
One transport for a Warehouse's Deliverymen: a pickup (empty a drop-off into the Home Clerk), a result delivery (producer to requesting Clerk) or an input delivery (store to a producer's collect inventory). At most one open pickup per source; taken tasks are locked to one Deliveryman.
_Avoid_: Request (that is "produce more"), order, job

**Reservation**:
An amount of an item in a Clerk's storage held for one Delivery task until pickup, cancel or end of day; everybody else sees stock minus reservations.
_Avoid_: Claim (that is a producer's share of a Request)

**Job picker**:
The window that opens when the scroll assigns a settler to a JobBlock: the block's Jobs with taken / places; a settler holds at most one Job per JobBlock.

**Food point**:
Every Clerk JobBlock; settlers take food from the nearest one whose reachable stock has food (a Clerk from its own storage first). Code: `ClerkStorage.nearestFood` → `ClerkStorage.FoodPoint`.
_Avoid_: Pantry

## Producing

**Craftsperson**:
The first Producing Job: crafts requested items from its patterns at a crafting table. Code: `ModJobs.CRAFTSPERSON` on `ModBlocks.CRAFTSPERSON_BLOCK`, work loop `CraftspersonLoop` (the Producing category's loop); job factor 0.3 and base EXP 2 in `civmod_jobs/craftsperson.json` (`Job.jobFactor`, `JobBalancing`).

**Pattern**:
One of the 54 slots of a Craftsperson JobBlock's library: a 3×3 grid and an output. Made from the output alone it follows a crafting recipe (tag ingredients flexible, arrows cycle the recipes making the item); made from inputs it uses exactly those items. Code: `Pattern` (data), `Patterns` (edit, `inputs`), `CraftingRecipes` (recipe lookup), saved in `SettlementBlock.patterns()`; GUI via `PatternTab`.
_Avoid_: Recipe (a pattern follows one), blueprint

**Checkmark**:
A pattern the Craftsperson crafts; 6 + 1 per 5 levels of the settler's effective skill (`Patterns.checkLimit`). When the limit drops, the last checked patterns in list order lose theirs (`Patterns.enforceLimit`, notice `SettlementBlock.Trim`).

**Metal**:
What the Craftsperson never crafts with: item tag `civmod:metals` (ingots, nuggets, raw materials, metal blocks). A pattern whose output or an input (all its alternatives) is metal cannot be checked and is never used; metal alternatives of a tag input are skipped. Code: `Patterns.METALS`, `Patterns.usesMetal`, `Patterns.usable` (the checked patterns a Craftsperson uses), `PatternTab.metal()`.

**Round**:
One claimed part of one request: claim what fits the buffer, fetch inputs (collect inventory, then the posting Clerk), craft intermediates and the item, deliver. Code: `Round`, kept in the job's Workplace (`Workplace.round`), run by `CraftspersonLoop`, planned by `CraftPlan` (chain resolution with own patterns; `CraftPlan.most` sizes the claim).

**Station**:
The work place of a Producing Job, named by the block tag `civmod:stations/<job>` (`Job.station`): set with the scroll (up to 8, one per day) or the nearest one within 16 blocks of the JobBlock inside the border.
_Avoid_: Workbench (the JobBlock is none)
