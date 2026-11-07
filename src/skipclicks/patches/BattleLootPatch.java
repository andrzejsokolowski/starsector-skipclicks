package skipclicks.patches;

import java.util.List;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl.OptionId;
import com.fs.starfarer.api.util.Misc;

import patchlib.api.context.AfterContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.After;
import patchlib.api.patch.Patch;
import skipclicks.Settings;

/**
 * After a won battle with recoverable ships, the fleet dialog shows "Consider ship recovery" and
 * "Continue". Continue (RECOVERY_CONTINUE) only leads to a screen whose single option is "Pick
 * through the wreckage" (CONTINUE_LOOT). This patch selects that option as soon as Continue has run,
 * so the loot screen opens right away.
 *
 * Only loaded when PatchLib is installed: nothing else in the mod refers to this class. Matches
 * subclasses too (Nexerelin and others extend the vanilla dialog); isMostDerivedCall keeps it to one
 * run per click when a subclass calls super.optionSelected. It only adds a step after the original
 * method, it never skips it.
 */
@Patch(target = @ClassMatch(subtype = FleetInteractionDialogPluginImpl.class))
public class BattleLootPatch {

    private static final Logger log = Global.getLogger(BattleLootPatch.class);

    /** One line in starsector.log the first time the patch runs, so a test can see PatchLib installed it. */
    private static boolean announced = false;

    @After(target = @MethodMatch(methodName = "optionSelected", parameterCount = 2))
    public static void afterOptionSelected(AfterContext context) {
        try {
            if (!announced) {
                announced = true;
                log.info("SkipClicks: battle loot patch is active on " + context.getEffectiveClass().getName());
            }
            if (!context.isMostDerivedCall()) return;
            if (context.getArg(1) != OptionId.RECOVERY_CONTINUE) return;
            if (!Settings.skipBattleContinue()) return;

            FleetInteractionDialogPluginImpl self = context.getInferredSelf();
            InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            if (self == null || dialog == null || dialog.getPlugin() != self) return;

            OptionPanelAPI options = dialog.getOptionPanel();
            if (!options.hasOption(OptionId.CONTINUE_LOOT)) return;
            // Another mod added a choice to this screen: leave it to the player.
            List<?> current = options.getSavedOptionList();
            if (current != null && current.size() != 1) return;

            // The loot screen covers the text that reports the credits found. Keep them in the log.
            if (self.getContext() instanceof FleetEncounterContext) {
                int credits = ((FleetEncounterContext) self.getContext()).getCreditsLooted();
                if (credits > 0) {
                    Global.getSector().getCampaignUI().addMessage(
                            "Recovered " + Misc.getDGSCredits(credits) + " from the wreckage", Misc.getHighlightColor());
                }
            }

            self.optionSelected(null, OptionId.CONTINUE_LOOT);
        } catch (Throwable t) {
            log.error("SkipClicks: could not skip the Continue after ship recovery", t);
        }
    }
}
