package skipclicks;

import com.fs.starfarer.api.BaseModPlugin;

import skipclicks.rulecmd.SurveyBlockers;

/** Reads the rules that block the normal survey while the game loads, not in the first planet dialog. */
public final class SkipClicksModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() {
        SurveyBlockers.load();
    }
}
