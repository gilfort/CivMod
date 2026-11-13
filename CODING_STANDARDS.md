# Coding Standards

Rules a reviewer checks on every diff. Mechanical problems are caught by the build (Error Prone, `-Xlint`, GameTests in CI); this file holds the judgement calls. Each rule names what to look for in the diff.

## 1. English only

All written output is in English: code, comments, Javadoc, commit messages, GitHub issues, PR descriptions, wiki pages, lang files (`en_us.json` is the primary language) and new design-doc content. Chat with the user may be in German.

*Review:* any non-English identifier, comment, string, lang entry, commit or PR text.

## 2. Consistent GUI design

All GUIs share one look across the whole mod, based on Minecraft defaults: vanilla container textures, widget sprites, fonts, spacing, colors. Reuse the shared GUI helpers and base classes instead of styling screens individually. A custom visual style needs an explicit decision recorded in the vault (`CLAUDE.md`, rule 3).

Tips, tutorials and usage info ("Click a slot with an item to …", "Right-click: delete", "Shift: ±10") never appear as inline text or in slot tooltips. They go into the help icon: a small "?" in the top right corner of the panel that shows all of them as a tooltip on mouse-over (`CivGui.help`, filled by overriding `helpLines` in `CivContainerScreen` / `CivScreen`, per tab). State stays inline: status and error lines ("No crafting recipe matches these items", "3 checkmarks removed …", "No open requests."). Icon-only buttons keep a short tooltip naming what they do ("Delete stock rule").

Each window holds only its own content (vault `Systems/GUI.md`, content guideline): a JobBlock window shows only the state of *this* block; everything about a person goes into the settler window; rules, times and formulas go into the Handbook; details go into tooltips; what every window has goes into the header (a JobBlock window's header line "Miner Block · Lv 2", `JobBlockScreen.extractTitle`).

*Review:* hard-coded colors, textures, paddings or fonts in a screen that a shared helper already provides; a new screen that does not extend the shared base; a hint or usage text drawn inline or added to a slot tooltip instead of `helpLines`; a JobBlock tab showing a settler's details, a rule, time or formula, details inline that belong in a tooltip, or repeating what the header already shows.

## 3. Keyed state lives together

State kept per key (per JobBlock, per settler, per job) sits in one record or one map entry, not spread across parallel maps keyed the same way. The `forget` / remove / reset path then clears one entry and cannot miss a field.

Where parallel maps already exist, every removal path clears every map. Example: #39, where `ResourceCollectionLoop.forget` cleared 2 of 5 per-JobBlock maps and a re-added JobBlock was never scanned again.

*Review:* a new field keyed like an existing one; any `remove`/`forget`/`clear` method checked against every field with the same key. A behaviour-preserving refactor that moves such a path still flags a pre-existing gap.

## 4. Performance

Work that scales per tick, per NPC, per settlement or per chunk is throttled, staggered, cached or event-driven, and its cost was discussed with the user before implementation (`CLAUDE.md`, rule 2).

*Review:* new per-tick loops, area scans, pathfinding calls, chunk loads, network syncs or saved-data growth. When the diff touches a hot path, flag it; `scripts/bench-compare.sh` runs only on the user's request.

## 5. Matches the surrounding code

New code reads like the code next to it: Javadoc density, naming, visibility (package-private unless used outside the package), idiom. Domain terms follow `CONTEXT.md`.

*Review:* `public` without an outside caller; names that differ from the `CONTEXT.md` glossary.

## 6. Small units with one job

A method does one thing at one level of abstraction. A class has one reason to change. Decisions (plain values in, a decision out) stay apart from code that touches the world (blocks, entities, packets, saved data): `Schedule` decides, the Settler Shell acts on it. Several unrelated concerns in one file are a reason to split it.

These numbers are warning signs. Crossing one in new or touched code needs a reason that the PR description or a comment states:

- a method over about 50 lines, or nested deeper than 3 levels
- more than 5 parameters (group them in a record), or a boolean parameter that switches behaviour (use two methods or an enum; a signature fixed by Minecraft or NeoForge is exempt)
- a file over about 600 lines that gains lines in the diff

Existing oversized files are no reason to grow them. Rule 11 says how they get split.

*Review:* a method over 50 lines or nested over 3 levels; more than 5 parameters or a behaviour flag in a signature the mod owns; a file over 600 lines that grows; any of these without a stated reason; a new concern added to a file that already mixes several.

## 7. Names say what and why

Names state intent without a comment: prefer `isReadyForPickup()` over `check()`. No abbreviations beyond the common ones (`pos`, `id`). Booleans read as questions. A bare number or string with a meaning (`17`, `1200`, `"civmod_social"`) is a named constant, a config value or a datapack value, with the unit in the name (`FOOD_WAIT_TICKS`). A comment explains why something is done. Commented-out code is deleted. A `TODO` names an issue number.

*Review:* a magic number in logic; a comment that repeats the code; commented-out code; a `TODO` without an issue number; a name that needs the method body to be understood.

## 8. No duplication, no dead code

The third copy of a block of logic becomes a shared method. Two copies may stay when they change for different reasons; the PR says which reasons. A helper is extracted when the diff already has a second caller for it. Unused imports, config keys and lang keys are deleted in the diff that makes them unused; the build warns about unused methods, parameters and variables, and those warnings get fixed too. A method that only forwards to another one adds nothing and goes, unless it is a seam under rule 9.

*Review:* a pasted block that differs only in a constant; a new helper with a single caller in the diff; leftovers of the code the diff replaces; build warnings about unused code.

## 9. Deep modules, narrow interfaces

A module is any class or package with callers; its interface is everything a caller has to know to use it. A module hides its parts behind few entry points: callers should not need the order of calls. Prefer one method that does a whole job over three that must be called together, and keep the reset and forget paths of keyed state in the owner (rule 3). A seam (an interface, hook or strategy where one implementation can be swapped for another) is added only when two implementations differ across it; a test double counts as the second one. Otherwise use a plain class. Do not reach through objects (`a.b().c().d()`); give the owner a method.

*Review:* a new call sequence several callers must repeat in the same order; a getter chain into another module's internals; an interface or hook with one implementation and no test double.

## 10. Errors are loud, data is checked at the edge

Datapack files, config values, network payloads and saved data are validated where they enter the code; inside the mod, values can be trusted. No empty `catch` and no `catch (Exception)` that hides a bug: log with context or let it propagate. A new method returns an empty collection or an `Optional` when there is nothing to return, instead of `null`; overrides of Minecraft or NeoForge methods that return `null` by contract are exempt. An impossible state throws with a message that names the values.

*Review:* an empty or silent `catch`; `null` returned or passed on purpose in the mod's own methods; a datapack or payload value used without a range check; a `default` branch of a non-enum switch that ignores an unknown value instead of failing.

## 11. Cleanups travel separately

A behaviour-preserving refactor (rename, extract, move) goes in its own commit, never mixed with a behaviour change, so the diff reads as "nothing should change". Touched code is left a little cleaner, within the files the story already changes. Splitting an oversized file (rule 6) is a larger cleanup and gets its own story. A story that needs a small change in such a file makes that change without splitting first. A new file that receives code moved out of a touched file counts as touched.

*Review:* a commit that both moves code and changes what it does; cleanup in files the story does not otherwise touch; a file split inside a feature story without its own commit.

## 12. Producing Jobs take part in the chain

A new Producing Job joins the request chain (vault `Systems/Sub-Requests.md`) through the shared Producing loop and its profile: it posts a child request for an input it cannot make itself and it serves child requests with its checked patterns. Delivery mode (Self or Deliveryman) is transport only. No Job, profile or mode gets its own rule for sub-requests, holds or the skip clock. A new profile is picked up by the generic GameTest over all registered Producing Jobs (`sub_request_every_profile`).

*Review:* a Job, profile or delivery-mode branch inside `ProducingLoop`, `SubRequests` or `Holds`; a Producing Job that posts or serves requests outside the shared loop; a new kind of holder that does not extend `ReservedFor`.
