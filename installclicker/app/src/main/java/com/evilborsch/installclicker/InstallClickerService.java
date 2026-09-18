package com.evilborsch.installclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Сервис специальных возможностей: ищет в окне системного установщика кнопку подтверждения
 * и нажимает её действием ACTION_CLICK. Такое нажатие идёт мимо фильтра касаний, из-за
 * которого на ГУ палец по кнопке «Установить» может не срабатывать.
 */
public class InstallClickerService extends AccessibilityService {

    static final String TAG = "InstallClicker";

    /** Кнопки, которые подтверждают установку. Сравнение по нормализованному тексту. */
    private static final String[] CLICK_LABELS = {
            "установить", "установить всё равно", "установить все равно",
            "установить в любом случае", "обновить", "продолжить", "далее",
            "встановити", "оновити", "продовжити",
            "install", "install anyway", "update", "continue", "next", "done",
            "确定", "安装",
    };

    /** Кнопки, которые трогать нельзя, даже если они попали под совпадение. */
    private static final String[] BLOCKED_LABELS = {
            "отмена", "отменить", "не устанавливать", "закрыть", "назад",
            "удалить", "cancel", "back", "close", "uninstall", "delete",
            "скасувати", "видалити",
    };

    private static final long CLICK_COOLDOWN_MS = 1200L;
    private static final int MAX_LOG_LINES = 15;

    private static final Deque<String> LOG = new ArrayDeque<>();

    private long lastClickAt = 0L;
    private String lastClickedSignature = "";

    static String logSnapshot() {
        synchronized (LOG) {
            if (LOG.isEmpty()) {
                return "";
            }
            return TextUtils.join("\n", LOG);
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        addLog("сервис подключён");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !Prefs.isEnabled(this)) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        if (!Prefs.isAggressive(this) && !isInstallerPackage(event.getPackageName())) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            AccessibilityNodeInfo button = findConfirmButton(root);
            if (button == null) {
                return;
            }
            try {
                clickIfNotRepeated(button, event.getPackageName());
            } finally {
                button.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "не удалось обработать окно", t);
        } finally {
            root.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        // Нечего прерывать: сервис ничего не удерживает между событиями.
    }

    private void clickIfNotRepeated(AccessibilityNodeInfo button, CharSequence packageName) {
        String label = String.valueOf(textOf(button));
        String signature = packageName + "/" + label;
        long now = SystemClock.uptimeMillis();
        if (signature.equals(lastClickedSignature) && now - lastClickAt < CLICK_COOLDOWN_MS) {
            return;
        }
        lastClickedSignature = signature;
        lastClickAt = now;

        // Основной путь: системное действие нажатия. Фильтр касаний на него не влияет.
        AccessibilityNodeInfo clickable = clickableSelfOrParent(button);
        boolean clicked = false;
        if (clickable != null) {
            clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (clickable != button) {
                clickable.recycle();
            }
        }
        if (clicked) {
            addLog("нажата кнопка «" + label + "» (" + packageName + ")");
            return;
        }

        // Запасной путь: жест по координатам кнопки. Он уже может попасть под тот же фильтр.
        if (dispatchTapOn(button)) {
            addLog("жест по кнопке «" + label + "» (" + packageName + ")");
        } else {
            addLog("не удалось нажать «" + label + "» (" + packageName + ")");
        }
    }

    private boolean dispatchTapOn(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 60L))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private static boolean isInstallerPackage(CharSequence packageName) {
        if (packageName == null) {
            return false;
        }
        String name = packageName.toString().toLowerCase(Locale.ROOT);
        return name.contains("packageinstaller")
                || name.contains("packageinstall")
                || name.contains(".installer")
                || name.endsWith("installer");
    }

    /** Обход дерева окна в ширину: берём первую видимую включённую кнопку подтверждения. */
    private static AccessibilityNodeInfo findConfirmButton(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo found = null;
        int visited = 0;

        while (!queue.isEmpty() && visited < 600) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            if (node == null) {
                continue;
            }
            if (found == null && isConfirmButton(node)) {
                found = AccessibilityNodeInfo.obtain(node);
            }
            if (found == null) {
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
            node.recycle();
        }
        for (AccessibilityNodeInfo rest : queue) {
            if (rest != null) {
                rest.recycle();
            }
        }
        return found;
    }

    private static boolean isConfirmButton(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        String label = normalize(textOf(node));
        if (label.isEmpty() || label.length() > 40) {
            return false;
        }
        for (String blocked : BLOCKED_LABELS) {
            if (label.equals(blocked)) {
                return false;
            }
        }
        boolean matches = false;
        for (String candidate : CLICK_LABELS) {
            if (label.equals(candidate)) {
                matches = true;
                break;
            }
        }
        if (!matches) {
            return false;
        }
        // Заголовок диалога тоже может содержать слово «Установить», поэтому берём только то,
        // что ведёт себя как кнопка.
        return node.isClickable() || clickableParentExists(node);
    }

    private static boolean clickableParentExists(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo clickable = clickableSelfOrParent(node);
        if (clickable == null) {
            return false;
        }
        if (clickable != node) {
            clickable.recycle();
        }
        return true;
    }

    /** Текст кнопки часто лежит не на кликабельном узле, а на его ребёнке — идём вверх. */
    private static AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo node) {
        if (node.isClickable()) {
            return node;
        }
        AccessibilityNodeInfo current = node.getParent();
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.isClickable() && current.isEnabled()) {
                return current;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
            depth++;
        }
        if (current != null) {
            current.recycle();
        }
        return null;
    }

    private static CharSequence textOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (TextUtils.isEmpty(text)) {
            text = node.getContentDescription();
        }
        return text == null ? "" : text;
    }

    private static String normalize(CharSequence text) {
        return text.toString().trim().replace("…", "").replace(".", "").toLowerCase(Locale.ROOT);
    }

    private void addLog(String message) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        String line = stamp + "  " + message;
        synchronized (LOG) {
            LOG.addFirst(line);
            while (LOG.size() > MAX_LOG_LINES) {
                LOG.removeLast();
            }
        }
        Prefs.setLastAction(this, line);
        Log.i(TAG, message);
    }
}
