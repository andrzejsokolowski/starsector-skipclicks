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
- **Salvage finds and defenses**: after a find that asks nothing (survey data, a blueprint, a hint about
  another wreck, cargo) and after beating automated defenses, the dialog goes straight on instead of
  stopping on a screen with only Continue.
- **Cargo pods**: unlocked pods show their contents at once, without the Examine pod contents step.
- **Leftover debris**: salvaging a station, probe, cache or other derelict no longer leaves a debris
  field to scavenge afterwards. Its loot and experience join the first loot screen.
- **After battle**: Continue on the ship recovery screen opens the loot screen right away, instead of a
  screen with only Pick through the wreckage. Needs [PatchLib](https://github.com/Lukas22041/PatchLib).

Every skip can be turned off in the LunaLib settings.

## Requirements

- LunaLib
- PatchLib (optional, only for the after-battle skip)

## Compatibility

The survey, salvage and cargo pod parts are rules.csv rules. Each one first asks the game which rule
would run without SkipClicks and only steps in when that is vanilla's own rule, so another mod's quest
or reward on the same screen runs untouched. The after-battle part
is a PatchLib patch that runs after the vanilla dialog code and never replaces it.
