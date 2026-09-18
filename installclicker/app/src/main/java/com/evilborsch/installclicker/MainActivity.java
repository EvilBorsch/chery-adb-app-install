package com.evilborsch.installclicker;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Экран управления: статус сервиса, тумблеры и журнал последних нажатий. */
public class MainActivity extends Activity {

    private TextView statusView;
    private TextView logView;
    private CheckBox enabledBox;
    private CheckBox aggressiveBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Нажимает кнопку «Установить» в системном установщике действием "
                + "ACTION_CLICK, когда обычное касание по экрану ГУ не срабатывает.");
        root.addView(subtitle, marginTop(dp(4)));

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(statusView, marginTop(dp(16)));

        Button settingsButton = new Button(this);
        settingsButton.setText("Открыть настройки Accessibility");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        root.addView(settingsButton, marginTop(dp(8)));

        enabledBox = new CheckBox(this);
        enabledBox.setText("Автоклик кнопки «Установить»");
        enabledBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setEnabled(MainActivity.this, enabledBox.isChecked());
            }
        });
        root.addView(enabledBox, marginTop(dp(12)));

        aggressiveBox = new CheckBox(this);
        aggressiveBox.setText("Нажимать в любом приложении, а не только в установщике");
        aggressiveBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setAggressive(MainActivity.this, aggressiveBox.isChecked());
            }
        });
        root.addView(aggressiveBox);

        TextView logTitle = new TextView(this);
        logTitle.setText("Последние действия");
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(logTitle, marginTop(dp(16)));

        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        root.addView(logView, marginTop(dp(4)));

        Button refreshButton = new Button(this);
        refreshButton.setText("Обновить");
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        root.addView(refreshButton, marginTop(dp(8)));

        TextView help = new TextView(this);
        help.setText("Как пользоваться\n"
                + "1. Включите InstallClicker в настройках специальных возможностей.\n"
                + "2. Оставьте автоклик включённым.\n"
                + "3. Откройте APK — окно установки подтвердится само.\n\n"
                + "Если по экрану ГУ не нажимается даже разрешение Accessibility, включите "
                + "сервис с компьютера:\n"
                + "adb shell settings put secure enabled_accessibility_services "
                + "com.evilborsch.installclicker/com.evilborsch.installclicker.InstallClickerService\n"
                + "adb shell settings put secure accessibility_enabled 1\n\n"
                + "Включить или выключить автоклик из adb:\n"
                + "adb shell am broadcast -a com.evilborsch.installclicker.SET_ENABLED "
                + "--ez value true -n com.evilborsch.installclicker/.ControlReceiver");
        help.setTextIsSelectable(true);
        root.addView(help, marginTop(dp(20)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean serviceOn = isServiceEnabled(this);
        statusView.setText(serviceOn
                ? "Сервис Accessibility: включён"
                : "Сервис Accessibility: выключен — включите его в настройках");
        statusView.setTextColor(serviceOn ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
        statusView.setGravity(Gravity.START);

        enabledBox.setChecked(Prefs.isEnabled(this));
        aggressiveBox.setChecked(Prefs.isAggressive(this));

        String log = InstallClickerService.logSnapshot();
        if (TextUtils.isEmpty(log)) {
            log = Prefs.getLastAction(this);
        }
        logView.setText(TextUtils.isEmpty(log) ? "пока ничего не нажималось" : log);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть настройки специальных возможностей",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Проверяем по системной настройке, а не по флагу внутри сервиса. */
    static boolean isServiceEnabled(Context context) {
        ComponentName component = new ComponentName(context, InstallClickerService.class);
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        for (String part : enabled.split(":")) {
            ComponentName parsed = ComponentName.unflattenFromString(part);
            if (parsed != null && parsed.equals(component)) {
                return true;
            }
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        return params;
    }
}
