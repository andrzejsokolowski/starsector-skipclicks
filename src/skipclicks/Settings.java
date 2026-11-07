package skipclicks;

import lunalib.lunaSettings.LunaSettings;

/** LunaLib values, read live. A missing value falls back to the default in LunaSettings.csv. */
public final class Settings {

    public static final String MOD_ID = "skipclicks";

    private Settings() {}

    public static boolean fastSurvey() {
        return flag("skc_fastSurvey", true);
    }

    public static boolean leaveAfterFastSurvey() {
        return flag("skc_fastSurveyLeave", false);
    }

    public static boolean skipAssessDebris() {
        return flag("skc_skipAssessDebris", true);
    }

    public static boolean skipExplore() {
        return flag("skc_skipExplore", true);
    }

    public static boolean skipAccident() {
        return flag("skc_skipAccident", true);
    }

    public static boolean skipBattleContinue() {
        return flag("skc_skipBattleContinue", true);
    }

    private static boolean flag(String key, boolean fallback) {
        try {
            Boolean value = LunaSettings.getBoolean(MOD_ID, key);
            return value == null ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
