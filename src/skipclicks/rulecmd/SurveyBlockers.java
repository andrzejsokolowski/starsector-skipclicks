package skipclicks.rulecmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.campaign.rules.RuleAPI;

/**
 * Rules that switch off or remove vanilla's "Perform a survey" option (id surveyPerform). Fast survey must
 * not offer what the normal survey refuses, for example a planet guarded by an Industrial Evolution
 * artillery platform, or a planet a quest keeps unsurveyed for now.
 *
 * The blocking rules are read once from the merged rules.csv of the game and every enabled mod: any rule
 * whose script has "SetEnabled surveyPerform false" or "RemoveOption surveyPerform". A rule on
 * PopulateOptions (fired with FireAll) blocks when it matches; a rule on any other trigger (fired with
 * FireBest) blocks when it is the best match. The option panel has no getter for an option's enabled state,
 * so this is how the survey option's state is known no matter in which order the rules' scripts run.
 */
final class SurveyBlockers {

    private static final Logger log = Global.getLogger(SurveyBlockers.class);

    private static final Pattern BLOCKS = Pattern.compile(
            "(?m)^\\s*(SetEnabled\\s+surveyPerform\\s+false|RemoveOption\\s+surveyPerform)\\b");

    /** Used only when rules.csv cannot be read: the blockers in vanilla and in the mods tested with SkipClicks. */
    private static final List<String[]> KNOWN = Arrays.asList(
            new String[] {"surveyPrintHostilesText", "PrintNearbySurveyHostilesTextIfSo"},
            new String[] {"LOCRblockFirstSurvey", "PopulateOptions"},
            new String[] {"IndEvo_ArtilleryInitialPlanet_CircumventionProtection", "PopulateOptions"},
            new String[] {"cs_giantAlienSpidersRaidOpt", "PopulateOptions"},
            new String[] {"jdp_biofactoryEmbryoBlockFirstSurvey", "PopulateOptions"},
            new String[] {"jdp_soilNanitesBlockFirstSurvey", "PopulateOptions"},
            new String[] {"jdp_mantleBoreBlockFirstSurvey", "PopulateOptions"});

    /** {rule id, trigger} pairs. Not saved; read again after a restart. */
    private static List<String[]> blockers = null;

    private SurveyBlockers() {}

    /** The id of a rule that would switch off or remove the normal survey in this dialog, or null. */
    static String blockingRule(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        List<RuleAPI> populate = null;
        for (String[] blocker : blockers()) {
            String id = blocker[0];
            String trigger = blocker[1];
            if ("PopulateOptions".equals(trigger)) {
                if (populate == null) {
                    populate = Global.getSector().getRules().getAllMatching(null, trigger, dialog, memoryMap);
                }
                for (RuleAPI rule : populate) {
                    if (id.equals(rule.getId())) return id;
                }
            } else {
                RuleAPI best = Global.getSector().getRules().getBestMatching(null, trigger, dialog, memoryMap);
                if (best != null && id.equals(best.getId())) return id;
            }
        }
        return null;
    }

    private static synchronized List<String[]> blockers() {
        if (blockers != null) return blockers;
        List<String[]> found = new ArrayList<>();
        try {
            JSONArray rows = Global.getSettings().getMergedSpreadsheetDataForMod("id", "data/campaign/rules.csv", "starsector-core");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || row.isNull("id") || row.isNull("script") || row.isNull("trigger")) continue;
                String id = row.optString("id").trim();
                if (id.isEmpty() || id.startsWith("#") || id.startsWith("skc_")) continue;
                if (BLOCKS.matcher(row.optString("script")).find()) {
                    found.add(new String[] {id, row.optString("trigger").trim()});
                }
            }
            log.info("SkipClicks: " + found.size() + " rules can block the normal survey; Fast survey follows them");
        } catch (Throwable t) {
            log.warn("SkipClicks: could not read rules.csv for survey blockers, using the known list", t);
            found = new ArrayList<>(KNOWN);
        }
        blockers = Collections.unmodifiableList(found);
        return blockers;
    }
}
