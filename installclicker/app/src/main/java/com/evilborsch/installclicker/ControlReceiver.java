package com.evilborsch.installclicker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Включение и выключение автоклика с компьютера, когда по экрану ГУ нажать не получается:
 *
 * <pre>
 * adb shell am broadcast -a com.evilborsch.installclicker.SET_ENABLED --ez value true \
 *     -n com.evilborsch.installclicker/.ControlReceiver
 * </pre>
 */
public class ControlReceiver extends BroadcastReceiver {

    static final String ACTION_SET_ENABLED = "com.evilborsch.installclicker.SET_ENABLED";
    static final String ACTION_SET_AGGRESSIVE = "com.evilborsch.installclicker.SET_AGGRESSIVE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        boolean value = intent.getBooleanExtra("value", true);
        String what;
        switch (intent.getAction()) {
            case ACTION_SET_ENABLED:
                Prefs.setEnabled(context, value);
                what = "автоклик";
                break;
            case ACTION_SET_AGGRESSIVE:
                Prefs.setAggressive(context, value);
                what = "режим «в любом приложении»";
                break;
            default:
                return;
        }
        String message = what + ": " + (value ? "включён" : "выключен");
        Log.i(InstallClickerService.TAG, message);
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
