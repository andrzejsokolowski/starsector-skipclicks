package skipclicks.rulecmd;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.FireAll;
import com.fs.starfarer.api.impl.campaign.rulecmd.UpdateMemory;
import com.fs.starfarer.api.plugins.SurveyPlugin;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Misc.Token;

import skipclicks.Settings;

/**
 * SKC_FastSurvey enabled|prepare|perform
 *
 * One-click planet survey. Does what the game's survey screen does when "Perform survey" and then
 * "Continue" are pressed (PlanetSurveyPanel.actionPerformed and applySurveyResults in 0.98a): take
 * the consumed supplies, mark every condition surveyed, add the survey data item, give the XP and
 * tell the SurveyPlanetListeners. The survey plugin is the one in settings ("surveyPlugin"), so a mod
 * that replaces it changes the cost here too.
 */
public class SKC_FastSurvey extends BaseCommandPlugin {

    public static final String OPTION = "skc_fastSurvey";

    private static final Logger log = Global.getLogger(SKC_FastSurvey.class);

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
        String command = params.isEmpty() ? "" : params.get(0).getString(memoryMap);
        try {
            switch (command) {
                case "enabled":
                    return Settings.fastSurvey() && planetOf(dialog) != null;
                case "prepare":
                    prepare(dialog);
                    return true;
                case "perform":
                    perform(dialog, memoryMap);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            log.error("SkipClicks: fast survey failed (" + command + ")", t);
            if (dialog != null && dialog.getOptionPanel().hasOption(OPTION)) {
                dialog.getOptionPanel().removeOption(OPTION);
            }
            return false;
        }
    }

    private static PlanetAPI planetOf(InteractionDialogAPI dialog) {
        if (dialog == null) return null;
        SectorEntityToken target = dialog.getInteractionTarget();
        if (!(target instanceof PlanetAPI)) return null;
        PlanetAPI planet = (PlanetAPI) target;
        if (planet.isStar() || planet.getMarket() == null) return null;
        return planet;
    }

    private static SurveyPlugin surveyPlugin(PlanetAPI planet) {
        SurveyPlugin plugin = (SurveyPlugin) Global.getSettings().getNewPluginInstance("surveyPlugin");
        plugin.init(Global.getSector().getPlayerFleet(), planet);
        return plugin;
    }

    /** What the fleet lacks, as "40 crew" style strings. Empty when the survey can run. */
    private static List<String> missing(SurveyPlugin plugin, CargoAPI cargo) {
        List<String> result = new ArrayList<>();
        addMissing(result, plugin.getRequired(), cargo);
        addMissing(result, plugin.getConsumed(), cargo);
        return result;
    }

    private static void addMissing(List<String> result, Map<String, Integer> needs, CargoAPI cargo) {
        for (Map.Entry<String, Integer> need : needs.entrySet()) {
            int have = (int) cargo.getCommodityQuantity(need.getKey());
            if (have < need.getValue()) {
                result.add((need.getValue() - have) + " more " + commodityName(need.getKey()));
            }
        }
    }

    private static String commodityName(String id) {
        if (Commodities.CREW.equals(id)) return "crew";
        return Global.getSettings().getCommoditySpec(id).getLowerCaseName();
    }

    private static String describe(Map<String, Integer> amounts) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> amount : amounts.entrySet()) {
            parts.add(amount.getValue() + " " + commodityName(amount.getKey()));
        }
        return Misc.getAndJoined(parts);
    }

    private static void prepare(InteractionDialogAPI dialog) {
        PlanetAPI planet = planetOf(dialog);
        OptionPanelAPI options = dialog.getOptionPanel();
        if (planet == null || !options.hasOption(OPTION)) return;

        SurveyPlugin plugin = surveyPlugin(planet);
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        List<String> missing = missing(plugin, fleet.getCargo());

        options.setOptionText("Fast survey (uses " + describe(plugin.getConsumed()) + ")", OPTION);
        if (missing.isEmpty()) {
            options.setTooltip(OPTION, "Run the full survey right away and skip the survey screen. Uses "
                    + describe(plugin.getConsumed()) + " and needs " + describe(plugin.getRequired()) + ".");
        } else {
            options.setEnabled(OPTION, false);
            options.setTooltip(OPTION, "Your fleet needs " + Misc.getAndJoined(missing) + " to survey this planet.");
        }
    }

    private static void perform(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        PlanetAPI planet = planetOf(dialog);
        if (planet == null) return;
        MarketAPI market = planet.getMarket();
        TextPanelAPI text = dialog.getTextPanel();
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        CargoAPI cargo = fleet.getCargo();

        SurveyPlugin plugin = surveyPlugin(planet);
        if (!missing(plugin, cargo).isEmpty()) {
            // The cargo changed since the option was drawn: rebuild the options with the new numbers.
            FireAll.fire(null, dialog, memoryMap, "PopulateOptions");
            return;
        }

        // The cost, then the results, in the same order and with the same calls as the survey screen.
        for (Map.Entry<String, Integer> consumed : plugin.getConsumed().entrySet()) {
            cargo.removeCommodity(consumed.getKey(), consumed.getValue());
        }
        Global.getSoundPlayer().playUISound("ui_survey_found_1", 1f, 1f);

        for (Map.Entry<String, Integer> consumed : plugin.getConsumed().entrySet()) {
            AddRemoveCommodity.addCommodityLossText(consumed.getKey(), consumed.getValue(), text);
        }

        Misc.setFullySurveyed(market, text, true);
        Misc.addSurveyDataFor(planet, text);
        printConditions(market, text);

        long xp = plugin.getXP();
        Global.getSector().getPlayerPerson().getStats().addXP(xp, text);
        ListenerUtil.reportPlayerSurveyedPlanet(planet);

        // The planet panel and the $market.* memory keys still show the planet as unsurveyed.
        dialog.getVisualPanel().showPlanetInfo(planet);
        new UpdateMemory().execute(null, dialog, new ArrayList<Token>(), memoryMap);

        if (Settings.leaveAfterFastSurvey() && !Misc.hasUnexploredRuins(market)) {
            Global.getSector().getCampaignUI().addMessage("Surveyed " + planet.getName() + ": "
                    + conditionNames(market), Misc.getTextColor());
            dialog.dismiss();
            return;
        }
        // Brings in what a surveyed planet offers: Establish a colony, Explore the ruins, Leave.
        FireAll.fire(null, dialog, memoryMap, "PopulateOptions");
    }

    private static void printConditions(MarketAPI market, TextPanelAPI text) {
        String names = conditionNames(market);
        if (names.isEmpty()) return;
        // The names go in as a highlight argument, never into the format string: a modded condition
        // name with a % in it would break String.format.
        Color highlight = Misc.getHighlightColor();
        text.addPara("Conditions: %s", highlight, names);
    }

    private static String conditionNames(MarketAPI market) {
        List<String> names = new ArrayList<>();
        for (MarketConditionAPI condition : market.getConditions()) {
            if (!condition.isPlanetary()) continue;
            if (condition.getPlugin() != null && !condition.getPlugin().showIcon()) continue;
            names.add(condition.getName());
        }
        return String.join(", ", names);
    }
}
