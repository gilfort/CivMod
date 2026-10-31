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
