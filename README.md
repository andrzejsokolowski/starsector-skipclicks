# SkipClicks

A Starsector 0.98a mod that cuts the extra clicks out of planet surveys, salvage and battle loot.

## What it skips

- **Planet survey**: unsurveyed planets with no colony get a **Fast survey** option. One click pays the
  survey cost and runs the full survey. The results (survey data, conditions, experience) appear in the
  planet dialog, with Establish a colony and Explore the ruins ready to pick. The survey screen, its
  animation and its Continue are skipped. Vanilla's Perform a survey is still there.
- **Salvage**: debris fields go straight to the salvage cost screen without Assess, and derelicts,
  stations and caches without Explore. Never skipped when automated defenses guard the target. When a
  salvage accident costs crew or heavy machinery, the loot screen opens right away instead of stopping
  on the damage report. The losses go to the message log.
- **Leftover debris**: salvaging a station, probe, cache or other derelict no longer leaves a debris
  field to scavenge afterwards. Its loot and experience join the first loot screen.
- **After battle**: Continue on the ship recovery screen opens the loot screen right away, instead of a
  screen with only Pick through the wreckage. Needs [PatchLib](https://github.com/Lukas22041/PatchLib).

Every skip can be turned off in the LunaLib settings.

## Requirements

- LunaLib
- PatchLib (optional, only for the after-battle skip)

## Compatibility

The survey and salvage parts are rules.csv rules. They add an option or outrank one vanilla rule, and
fall back to the vanilla steps whenever another mod adds its own salvage options. The after-battle part
is a PatchLib patch that runs after the vanilla dialog code and never replaces it.
