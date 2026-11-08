package skipclicks.rulecmd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.campaign.rules.RuleAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.FireBest;
import com.fs.starfarer.api.util.Misc.Token;

import skipclicks.Settings;

/**
 * Lets a SkipClicks rule step in only where vanilla's rule would have run.
 *
 * SKC_Skip check &lt;settingKey|none&gt; &lt;trigger&gt; [option:&lt;id&gt;] &lt;vanillaRuleId&gt;...
 *   A condition. True when the setting is on and the rule FireBest would pick for the trigger without SkipClicks
 *   is one of the vanilla ids. Any other mod's rule that would win (a quest, a story point grant, a custom
 *   outcome) makes it false, so that rule runs exactly as it would without SkipClicks. The SkipClicks rule
 *   carries a large score so it is considered first; while this check asks the game for the best rule,
 *   SkipClicks' own rules answer false, so they never pick themselves.
 *
 * SKC_Skip run &lt;trigger&gt; &lt;option&gt; [maxOptions]
 *   A script. Runs the vanilla rule that would have won (its text and its script), then, when its option is
 *   there and the panel holds at most maxOptions options (1 by default), selects that option the way a click
 *   would, so every mod's DialogOptionSelected rule for it still runs.
 */
public class SKC_Skip extends BaseCommandPlugin {

    private static final Logger log = Global.getLogger(SKC_Skip.class);

    /** True while this class asks the game for the best rule; SkipClicks' own checks then answer false. */
    private static boolean asking = false;

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null) return false;
        List<String> args = new ArrayList<>();
        for (Token token : params) {
            String value = token.getString(memoryMap);
            if (value == null || value.startsWith("score:")) continue;
            args.add(value);
        }
        if (args.isEmpty()) return false;
        try {
            if ("check".equals(args.get(0))) return check(dialog, memoryMap, args);
            if ("run".equals(args.get(0))) {
                run(dialog, memoryMap, args);
                return true;
            }
        } catch (Throwable t) {
            log.error("SkipClicks: " + args + " failed, leaving the dialog to the game", t);
        }
        return false;
    }

    private static boolean check(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, List<String> args) {
        if (asking || args.size() < 4) return false;
        String setting = args.get(1);
        String trigger = args.get(2);
        int first = 3;
        // No '=' or '-' in these tokens: Misc.tokenize reads "=<>!+-" as operators and the line becomes a comparison.
        if (args.get(3).startsWith("option:")) {
            String option = args.get(3).substring("option:".length());
            MemoryAPI local = memoryMap.get(MemKeys.LOCAL);
            if (local == null || !option.equals(local.getString("$option"))) return false;
            first = 4;
        }
        if (!"none".equals(setting) && !Settings.enabled(setting)) return false;

        RuleAPI best = bestWithoutSkipClicks(dialog, memoryMap, trigger);
        if (best == null) return false;
        for (int i = first; i < args.size(); i++) {
            if (args.get(i).equals(best.getId())) return true;
        }
        return false;
    }

    private static void run(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, List<String> args) {
        if (args.size() < 3) return;
        String trigger = args.get(1);
        String option = args.get(2);
        int maxOptions = args.size() > 3 ? Integer.parseInt(args.get(3)) : 1;

        RuleAPI vanilla = bestWithoutSkipClicks(dialog, memoryMap, trigger);
        if (vanilla == null) return;

        // What the dialog plugin does when a rule fires: its text, then its script, then its options.
        String text = vanilla.pickText();
        if (text != null && !text.trim().isEmpty()) {
            SectorEntityToken target = dialog.getInteractionTarget();
            text = Global.getSector().getRules().performTokenReplacement(vanilla.getId(), text, target, memoryMap);
            dialog.getTextPanel().addPara(text);
        }
        vanilla.runScript(dialog, memoryMap);
        if (vanilla.getOptions() != null) {
            for (com.fs.starfarer.api.campaign.rules.Option o : vanilla.getOptions()) {
                if (o == null || o.id == null) continue;
                if (dialog.getOptionPanel().hasOption(o.id)) continue;
                String label = Global.getSector().getRules().performTokenReplacement(vanilla.getId(), o.text, dialog.getInteractionTarget(), memoryMap);
                dialog.getOptionPanel().addOption(label, o.id);
            }
        }

        if (!dialog.getOptionPanel().hasOption(option)) return;
        List<?> all = dialog.getOptionPanel().getSavedOptionList();
        if (all == null || all.size() > maxOptions) return;
        select(dialog, memoryMap, option);
    }

    private static RuleAPI bestWithoutSkipClicks(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, String trigger) {
        boolean was = asking;
        asking = true;
        try {
            return Global.getSector().getRules().getBestMatching(null, trigger, dialog, memoryMap);
        } finally {
            asking = was;
        }
    }

    /** Selects a rules option the way RuleBasedInteractionDialogPluginImpl.optionSelected does, minus the echoed text. */
    static void select(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, String option) {
        MemoryAPI local = memoryMap.get(MemKeys.LOCAL);
        dialog.getOptionPanel().clearOptions();
        local.set("$option", option);
        local.expire("$option", 0);
        FireBest.fire(null, dialog, memoryMap, "DialogOptionSelected");
    }
}
