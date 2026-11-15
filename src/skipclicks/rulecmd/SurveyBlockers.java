package skipclicks.rulecmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.campaign.rules.RuleAPI;

/**
 * Rules that switch off or remove vanilla's "Perform a survey" option (id surveyPerform). Fast survey must
 * not offer what the normal survey refuses, for example a planet guarded by an Industrial Evolution
 * artillery platform, or a planet a quest keeps unsurveyed for now.
 *
 * The blocking rules are read once, while the game loads, from the rules.csv of every enabled mod: any rule
 * whose script has "SetEnabled surveyPerform false" or "RemoveOption surveyPerform". Each mod's file is read
 * on its own. The game's merged read (getMergedSpreadsheetDataForMod) slows down with every row it has
 * already merged: with about 100 mods' rules it froze the game for minutes inside a planet dialog (1.0.3).
 * Only files that mention surveyPerform at all are parsed as CSV. A rule on
 * PopulateOptions (fired with FireAll) blocks when it matches; a rule on any other trigger (fired with
 * FireBest) blocks when it is the best match. The option panel has no getter for an option's enabled state,
 * so this is how the survey option's state is known no matter in which order the rules' scripts run.
 */
public final class SurveyBlockers {

    private static final Logger log = Global.getLogger(SurveyBlockers.class);

    private static final Pattern BLOCKS = Pattern.compile(
            "(?m)^\\s*(SetEnabled\\s+surveyPerform\\s+false|RemoveOption\\s+surveyPerform)\\b");

    private static final String RULES = "data/campaign/rules.csv";

    /**
     * The blockers in the game's own rules.csv (0.98.5a-RC4). Not read from the file: "starsector-core" is not a
     * mod, so the per-mod reads below cannot reach it, and the game's own rules do not change between launches.
     */
    private static final List<String[]> VANILLA = Arrays.asList(
            new String[] {"surveyPrintHostilesText", "PrintNearbySurveyHostilesTextIfSo"},
            new String[] {"surveyPrintHostilesTextEstColony", "PrintNearbySurveyHostilesTextIfSo"},
            new String[] {"surveySystemIsCutOffCanNotColonize", "PrintSystemCutOffText"},
            new String[] {"LOCRblockFirstSurvey", "PopulateOptions"});

    /** Added when a mod's rules.csv cannot be read: the blockers in the mods tested with SkipClicks. */
    private static final List<String[]> KNOWN = Arrays.asList(
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

    /** Reads the blockers now. Called while the game loads, so no planet dialog ever waits for it. */
    public static void load() {
        blockers();
    }

    private static synchronized List<String[]> blockers() {
        if (blockers != null) return blockers;
        long start = System.nanoTime();
        Map<String, String[]> found = new LinkedHashMap<>();
        for (String[] blocker : VANILLA) found.put(key(blocker), blocker);
        int files = 0;
        int parsed = 0;
        boolean failed = false;
        try {
            for (ModSpecAPI mod : Global.getSettings().getModManager().getEnabledModsCopy()) {
                String text;
                try {
                    // Looks only in this mod's own folder; a mod without the file throws.
                    text = Global.getSettings().loadText(RULES, mod.getId());
                } catch (Throwable notThere) {
                    continue;
                }
                files++;
                if (text == null || !text.contains("surveyPerform")) continue;
                parsed++;
                try {
                    addBlockers(Global.getSettings().loadCSV(RULES, mod.getId()), found);
                } catch (Throwable t) {
                    failed = true;
                    log.warn("SkipClicks: could not read " + RULES + " of " + mod.getId() + " for survey blockers", t);
                }
            }
        } catch (Throwable t) {
            failed = true;
            log.warn("SkipClicks: could not list the enabled mods for survey blockers", t);
        }
        if (failed) {
            for (String[] blocker : KNOWN) found.putIfAbsent(key(blocker), blocker);
            log.warn("SkipClicks: added the known list of survey blockers because a rules.csv could not be read");
        }
        blockers = Collections.unmodifiableList(new ArrayList<>(found.values()));
        log.info("SkipClicks: " + blockers.size() + " rules can block the normal survey; Fast survey follows them ("
                + files + " rules.csv files, " + parsed + " read in full, "
                + (System.nanoTime() - start) / 1000000L + " ms)");
        return blockers;
    }

    private static void addBlockers(JSONArray rows, Map<String, String[]> found) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.isNull("id") || row.isNull("script") || row.isNull("trigger")) continue;
            String id = row.optString("id").trim();
            if (id.isEmpty() || id.startsWith("#") || id.startsWith("skc_")) continue;
            if (BLOCKS.matcher(row.optString("script")).find()) {
                String[] blocker = {id, row.optString("trigger").trim()};
                found.put(key(blocker), blocker);
            }
        }
    }

    private static String key(String[] blocker) {
        return blocker[0] + "\n" + blocker[1];
    }
}
