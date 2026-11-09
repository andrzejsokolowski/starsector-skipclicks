# SkipClicks

These instructions apply to this repository, including when it is opened without the parent mod workspace.
The workspace `AGENTS.md` one folder up still governs releases, packaging, Git identity and communication.

## Notion

Tasks, history and test lists: the **SkipClicks board** in Andrzej's Notion, under **Starsector mods**
(`collection://394eac91-d1c3-4dfe-bbb7-b59a13de4571`). Working rules and game API notes: **Claude
Information** (`collection://7bf9b1d6-b9e5-4b62-87f1-757bbaa51919`), starting with "Start here: working
on Starsector mods through Notion". Ticket ids start with SKC. Read the board with
`notion-query-data-sources` mode `view` on `view://41120ca7-5d20-8147-b70f-000c5b7054c8`. Update the
board during the work, not only at release time.

## Build

`./gradlew releaseZip` compiles against the game, LunaLib and PatchLib's API jar (`gradle.properties`
names the game folder), runs the checks and writes `SkipClicks.zip` in this folder.

## Layout

- `data/campaign/rules.csv` - the rules side. `skc_fastSurveyOpt` adds the Fast survey option next to
  vanilla's Perform a survey. `skc_salSkipExplore` (SalvageCheckHostile, score 10) and
  `skc_salCheckAccidents` (DialogOptionSelected salSalvage, score 10) outrank vanilla's
  `sal_noHostileNearby` and `sal_optionCheckAccidents` and fall back to the same vanilla steps.
- `SKC_Skip check <setting|none> <trigger> [option:<id>] <vanilla rule ids>` (condition, score:100000) is
  how every skip rule except Fast survey decides to step in: it asks `getBestMatching` for the trigger
  with SkipClicks' own checks answering false, and is true only when the winner is the named vanilla rule.
  `SKC_Skip run <trigger> <option> [maxOptions]` fires the trigger again through the real `FireBest`
  with the checks answering false, so the vanilla rule is applied exactly as without SkipClicks
  (`applyRule` clears the old options first; leftover buttons from a salvage find otherwise stay
  clickable with no rule behind them), then selects its option like a click. Never put `=<>+-` in an SKC_ command line: `Misc.tokenize` reads them
  as operators (the build checks this). Skips: Explore/Assess, accident report, salvage find Continue
  (`SalvageSpecialFinished`), post-defenders Continue (`BeatDefendersContinue`), cargo pods examine
  (`OpenInteractionDialog`, `pods_start`, unlocked only).
- `src/skipclicks/rulecmd/SKC_FastSurvey` - the survey, the same calls as the game's survey screen
  (`PlanetSurveyPanel.actionPerformed` and `applySurveyResults`): consumed cost, `Misc.setFullySurveyed`,
  `Misc.addSurveyDataFor`, survey XP, `ListenerUtil.reportPlayerSurveyedPlanet`. `SurveyBlockers` reads the
  merged rules.csv once for rules whose script has `SetEnabled surveyPerform false` or `RemoveOption
  surveyPerform` (vanilla hostiles, IndEvo artillery, JDP, Crab Shack, LOCR); Fast survey is removed where
  one would fire (PopulateOptions: any match; other triggers: best match). The option panel has no getter
  for an option's enabled state, so this is the only order-independent way to follow the normal survey.
- `src/skipclicks/rulecmd/SKC_Salvage` - fires PopulateSalvageOptions1 like vanilla (so script-only mod
  rules such as Exotica's and RapeSector's still run), then skips Explore or Assess only when the screen
  offers just salExplore and defaultLeave, the entity has no defenders, no mod rule on that trigger has
  its own text, and the best DialogOptionSelected rule for salExplore is vanilla's `sal_noDefenders`.
  Skips the accident report by selecting salPerform after vanilla's `SalvageEntity checkAccidents`.
- `src/skipclicks/rulecmd/DebrisMerge` - wraps that salSalvage step. Before it, works out one scavenge of
  the PLAYER_SALVAGE field `convertToDebrisField(FIELD_CONTENT_MULTIPLIER_AFTER_SALVAGE)` would spawn (same
  drops, seed, density and value recovery as vanilla; one scavenge takes the whole field because
  FIELD_SALVAGE_FRACTION_PER_ATTEMPT is 1) and adds it to the target's extra salvage. After it, removes the
  new field and grants its XP (`baseSalvageXP * density`, as ScavengeAbility does). No new field means the
  salvage did not run there, so the extra loot is taken back.
- `src/skipclicks/patches/BattleLootPatch` - PatchLib @After on
  `FleetInteractionDialogPluginImpl.optionSelected` (subtype match, most derived call only): after
  RECOVERY_CONTINUE, selects CONTINUE_LOOT when it is the only option.
- `src/skipclicks/Settings` - LunaLib reads with defaults.

PatchLib is optional. Only `skipclicks.patches` may name PatchLib classes (`checkPatchLibIsolated`
fails the build otherwise), so without PatchLib the patch class is never loaded and the rest works.
