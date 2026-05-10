# Алгоритм установки APK на Tenet T8 / Chery DesaySV

Этот документ можно дать другой LLM как самостоятельную инструкцию. Он описывает рабочий способ
установки APK на ГУ Tenet T8 / Chery DesaySV, где обычная установка через shell-команду `pm install`
завершается ошибкой прошивки.

## Суть метода

APK устанавливается не shell-командой установки, а через Android API `PackageInstaller.Session`.
На устройстве запускается маленький helper `localinstall.apk` через `app_process`. Helper:

1. Получает Android system context.
2. Берет `PackageInstaller`.
3. Создает full install session.
4. Записывает APK в session как `base.apk`.
5. Делает `fsync`.
6. Коммитит session через собственный `IntentSender` на базе `IIntentSender.Stub`.

Именно такой тип установки сработал на ГУ.

## Необходимые файлы

В этом проекте helper уже готов:

```text
src-tauri/resources/localinstall.apk
```

Его можно использовать из Tauri-приложения или напрямую через `adb`.

## Прямая установка APK через adb

Пусть APK лежит на компьютере:

```text
app.apk
```

Пусть helper лежит:

```text
src-tauri/resources/localinstall.apk
```

Установка:

```bash
adb push app.apk /data/local/tmp/desaysv-install-target.apk
adb shell chmod 644 /data/local/tmp/desaysv-install-target.apk

adb push src-tauri/resources/localinstall.apk /data/local/tmp/desaysv-localinstall.apk
adb shell chmod 644 /data/local/tmp/desaysv-localinstall.apk

adb shell 'CLASSPATH=/data/local/tmp/desaysv-localinstall.apk /system/bin/app_process /system/bin LocalInstall /data/local/tmp/desaysv-install-target.apk'
```

Проверка:

```bash
adb shell pm list packages | grep '<package.name>'
```

Если имя пакета неизвестно, его можно определить до установки через `aapt`/`aapt2`:

```bash
aapt dump badging app.apk | sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p"
```

Или после установки сравнить список пакетов до и после:

```bash
adb shell pm list packages | sort > before.txt
# выполнить установку
adb shell pm list packages | sort > after.txt
comm -13 before.txt after.txt
```

## Выдача разрешений после установки

После установки нужно сразу выдать runtime permissions, которые приложение запросило в manifest.

```bash
pkg='<package.name>'

requested_permissions="$(
  adb shell dumpsys package "$pkg" \
    | sed -n '/requested permissions:/,/install permissions:/p' \
    | grep -E '^[[:space:]]+[a-zA-Z0-9_.]+\.[A-Z0-9_]+(: restricted=true)?$' \
    | sed -E 's/^[[:space:]]+//; s/: restricted=true$//' \
    | sort -u
)"

while IFS= read -r permission; do
  [ -n "$permission" ] && adb shell pm grant "$pkg" "$permission" >/dev/null 2>&1 || true
done <<EOF
$requested_permissions
EOF
```

Затем выставить appops:

```bash
adb shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
adb shell appops set "$pkg" LEGACY_STORAGE allow >/dev/null 2>&1 || true
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
adb shell appops set "$pkg" WRITE_SETTINGS allow >/dev/null 2>&1 || true
adb shell appops set "$pkg" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
adb shell appops set "$pkg" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1 || true
adb shell appops set "$pkg" ACCESS_NOTIFICATIONS allow >/dev/null 2>&1 || true
```

Для файловых менеджеров особенно важно:

```bash
adb shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow
adb shell appops set "$pkg" LEGACY_STORAGE allow
```

Выставлять `MANAGE_EXTERNAL_STORAGE` нужно именно на package-level, без `--uid`. На этом ГУ
uid-level `allow` может сосуществовать с package-level `default/rejectTime`, и тогда экран запроса
доступа в файловом менеджере продолжит показывать ошибку.

После выдачи разрешений перезапустить приложение:

```bash
adb shell am force-stop "$pkg" >/dev/null 2>&1 || true
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1
```

Удалить временные файлы:

```bash
adb shell rm -f /data/local/tmp/desaysv-install-target.apk /data/local/tmp/desaysv-localinstall.apk
```

## Проверка результата

```bash
pkg='<package.name>'

adb shell pm list packages "$pkg"
adb shell cmd package resolve-activity --brief --user 0 \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER "$pkg"
adb shell dumpsys package "$pkg" | grep -E 'versionName=|versionCode=|installerPackageName|User 0:'
adb shell appops get "$pkg" | grep -E 'MANAGE_EXTERNAL_STORAGE|LEGACY_STORAGE|SYSTEM_ALERT_WINDOW|WRITE_SETTINGS'
```

## Что делает `localinstall.apk`

Внутренняя логика helper:

```java
Looper.prepareMainLooper();
Context context = ActivityThread.systemMain().getSystemContext();
PackageInstaller installer = context.getPackageManager().getPackageInstaller();
PackageInstaller.SessionParams params =
    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
params.setSize(apk.length());
int sessionId = installer.createSession(params);
PackageInstaller.Session session = installer.openSession(sessionId);
OutputStream out = session.openWrite("base.apk", 0, apk.length());
// copy apk bytes into out
session.fsync(out);
session.commit(new IntentSender(new IIntentSender.Stub() { ... }));
session.close();
```

`PendingIntent` не использовать: shell-процесс получает system context с package `android`, но uid
остается shell, и Android запрещает создавать PendingIntent от имени `android`. Поэтому используется
собственный `IntentSender` через `IIntentSender.Stub`.
