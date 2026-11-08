package skipclicks.rulecmd;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.campaign.rules.RuleAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.FireAll;
import com.fs.starfarer.api.impl.campaign.rulecmd.FireBest;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageEntity;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Misc.Token;

import skipclicks.Settings;

/**
 * SKC_Salvage skipExplore|checkAccidents
 *
 * skipExplore replaces vanilla sal_noHostileNearby (SalvageCheckHostile) and does what it does, so every
 * mod's PopulateSalvageOptions1 rules still run. Then, when the screen offers only Explore (or Assess) and
 * Leave, the entity has no defenders and Explore leads where vanilla's does, it selects salExplore at once,
 * which leads to the salvage cost screen.
 *
 * checkAccidents replaces vanilla sal_optionCheckAccidents (DialogOptionSelected salSalvage). It runs
 * the vanilla accident check, and when that stops on the damage report ("Continue" to salPerform),
 * it selects salPerform at once and puts the losses in the message log. Around it, DebrisMerge adds the
 * loot of the debris field the salvage leaves behind and removes that field.
 */
public class SKC_Salvage extends BaseCommandPlugin {

    private static final Logger log = Global.getLogger(SKC_Salvage.class);

    /** Rules that vanilla 0.98a fires on PopulateSalvageOptions1. */
    private static final Set<String> VANILLA_EXPLORE_RULES =
            new HashSet<>(Arrays.asList("sal_defaultLeave1", "sal_explore", "sal_assess"));

    /** Vanilla's rule for Explore on a target without defenders: on to the salvage cost screen. */
    private static final String VANILLA_EXPLORE_OUTCOME = "sal_noDefenders";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || params.isEmpty()) return false;
        String command = params.get(0).getString(memoryMap);
        if ("skipExplore".equals(command)) {
            skipExplore(dialog, memoryMap);
            return true;
        }
        if ("checkAccidents".equals(command)) {
            checkAccidents(ruleId, dialog, memoryMap);
            return true;
        }
        return false;
    }

    private static void skipExplore(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        MemoryAPI local = memoryMap.get(MemKeys.LOCAL);
        // The vanilla rule, line for line: every mod's PopulateSalvageOptions1 rules run as usual.
        local.set("$salvageLeaveText", "Leave", 0);

        boolean wanted = false;
        boolean textFromMods = false;
        try {
            wanted = skipWanted(dialog);
            if (wanted) textFromMods = modRuleAddsText(dialog, memoryMap);
        } catch (Throwable t) {
            log.error("SkipClicks: could not check the salvage rules, showing the options as usual", t);
            wanted = false;
        }

        FireAll.fire(null, dialog, memoryMap, "PopulateSalvageOptions1");
        if (!wanted || textFromMods) return;

        boolean skip = false;
        try {
            skip = onlyVanillaChoice(dialog, memoryMap, local);
        } catch (Throwable t) {
            log.error("SkipClicks: could not check the salvage options, showing them as usual", t);
        }
        if (skip) select(dialog, memoryMap, "salExplore");
    }

    private static boolean skipWanted(InteractionDialogAPI dialog) {
        SectorEntityToken entity = dialog.getInteractionTarget();
        boolean debris = entity != null && Entities.DEBRIS_FIELD_SHARED.equals(entity.getCustomEntityType());
        return debris ? Settings.skipAssessDebris() : Settings.skipExplore();
    }

    /** A mod rule with its own text on this screen is a message for the player: keep the screen. */
    private static boolean modRuleAddsText(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        List<RuleAPI> matches = Global.getSector().getRules().getAllMatching(null, "PopulateSalvageOptions1", dialog, memoryMap);
        for (RuleAPI rule : matches) {
            if (VANILLA_EXPLORE_RULES.contains(rule.getId())) continue;
            List<String> text = rule.getText();
            if (text == null) continue;
            for (String line : text) {
                if (line != null && !line.trim().isEmpty()) return true;
            }
        }
        return false;
    }

    /**
     * True when the screen, after every mod's rules ran, offers only Explore (or Assess) and Leave, the target
     * has no defenders, and Explore would do what vanilla's sal_noDefenders does. Rules that only run scripts
     * (Exotica Technologies, RapeSector and others hook this screen) do not stop the skip.
     */
    private static boolean onlyVanillaChoice(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, MemoryAPI local) {
        // Explore starts the fight with the defenders: keep the chance to leave first.
        if (local.getBoolean("$hasDefenders")) return false;

        OptionPanelAPI options = dialog.getOptionPanel();
        if (!options.hasOption("salExplore") || !options.hasOption("defaultLeave")) return false;
        List<?> all = options.getSavedOptionList();
        if (all == null || all.size() != 2) return false;

        // Another mod can give Explore its own outcome (Nexerelin's scavenger ambush, quest targets): keep the screen.
        Object previous = local.get("$option");
        local.set("$option", "salExplore", 0);
        RuleAPI best;
        try {
            best = Global.getSector().getRules().getBestMatching(null, "DialogOptionSelected", dialog, memoryMap);
        } finally {
            if (previous != null) local.set("$option", previous, 0);
            else local.unset("$option");
        }
        return best != null && VANILLA_EXPLORE_OUTCOME.equals(best.getId());
    }

    private static void checkAccidents(String ruleId, InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        // The leftover debris field goes into this loot. Its targets (stations, probes, caches) are never
        // debris fields, so vanilla's checkAccidents fires salPerform right away and the salvage runs inside
        // this call; finish() then finds the spawned field.
        DebrisMerge merge = Settings.mergeDebris() ? DebrisMerge.prepare(dialog) : null;
        try {
            checkAccidentsAndSkip(ruleId, dialog, memoryMap);
        } finally {
            if (merge != null) merge.finish();
        }
    }

    private static void checkAccidentsAndSkip(String ruleId, InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        SectorEntityToken entity = dialog.getInteractionTarget();
        CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
        int crewBefore = cargo.getCrew();
        int machineryBefore = (int) cargo.getCommodityQuantity(Commodities.HEAVY_MACHINERY);
        long seedBefore = entity.getMemoryWithoutUpdate().getLong(MemFlags.SALVAGE_SEED);

        new SalvageEntity().execute(ruleId, dialog, Misc.tokenize("checkAccidents"), memoryMap);

        if (!Settings.skipAccident()) return;
        // Vanilla stopped on the damage report only when "Continue" (salPerform) is the option now.
        // The salvage seed changes when the salvage has already run: never run it twice.
        if (!dialog.getOptionPanel().hasOption("salPerform")) return;
        if (entity.getMemoryWithoutUpdate().getLong(MemFlags.SALVAGE_SEED) != seedBefore) return;

        int crewLost = Math.max(0, crewBefore - cargo.getCrew());
        int machineryLost = Math.max(0, machineryBefore - (int) cargo.getCommodityQuantity(Commodities.HEAVY_MACHINERY));
        if (crewLost > 0 || machineryLost > 0) {
            String what;
            if (crewLost > 0 && machineryLost > 0) what = crewLost + " crew and " + machineryLost + " heavy machinery";
            else if (crewLost > 0) what = crewLost + " crew";
            else what = machineryLost + " heavy machinery";
            Global.getSector().getCampaignUI().addMessage("Salvage accident: lost " + what, Misc.getNegativeHighlightColor());
        }
        select(dialog, memoryMap, "salPerform");
    }

    /** Selects a rules option the way RuleBasedInteractionDialogPluginImpl.optionSelected does, minus the echoed text. */
    private static void select(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, String option) {
        MemoryAPI local = memoryMap.get(MemKeys.LOCAL);
        dialog.getOptionPanel().clearOptions();
        local.set("$option", option);
        local.expire("$option", 0);
        FireBest.fire(null, dialog, memoryMap, "DialogOptionSelected");
    }
}
