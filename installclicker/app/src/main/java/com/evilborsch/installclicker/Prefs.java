package com.evilborsch.installclicker;

import android.content.Context;
import android.content.SharedPreferences;

/** Общие настройки активити, сервиса и adb-ресивера. */
final class Prefs {

    private static final String NAME = "installclicker";
    private static final String KEY_ENABLED = "auto_click_enabled";
    private static final String KEY_AGGRESSIVE = "aggressive_mode";
    private static final String KEY_LAST_ACTION = "last_action";

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** Автоклик включён по умолчанию: если по экрану ГУ нажать нельзя, тумблер тоже не нажать. */
    static boolean isEnabled(Context context) {
        return get(context).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_ENABLED, value).apply();
    }

    /** Нажимать не только в окнах системного установщика, но и в любом приложении. */
    static boolean isAggressive(Context context) {
        return get(context).getBoolean(KEY_AGGRESSIVE, false);
    }

    static void setAggressive(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_AGGRESSIVE, value).apply();
    }

    static String getLastAction(Context context) {
        return get(context).getString(KEY_LAST_ACTION, "");
    }

    static void setLastAction(Context context, String value) {
        get(context).edit().putString(KEY_LAST_ACTION, value).apply();
    }
}
