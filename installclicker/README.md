# InstallClicker

Android-приложение для ГУ: когда открывается системное окно установки APK, оно находит
кнопку «Установить» и нажимает её **системным действием `ACTION_CLICK`** через сервис
специальных возможностей (Accessibility).

## Зачем это нужно

Частая причина, по которой на ГУ Chery / Tenet кнопка «Установить» выглядит активной, но
не реагирует на палец: поверх окна установщика находится другое окно или системный слой
интерфейса ГУ. Android в такой ситуации отбрасывает касание — это защита от нажатий через
перекрывающие окна (`FLAG_WINDOW_IS_OBSCURED`).

- Пальцем: «касание пришло через перекрытое окно — игнорировать».
- Через сервис: «выполнить действие нажатия у этой кнопки». Фильтр касаний на это
  действие не распространяется.

Если `ACTION_CLICK` не сработал, приложение пробует жест по координатам кнопки
(`dispatchGesture`), но такой жест уже может попасть под тот же фильтр.

## Чего оно НЕ делает

- Не меняет прошивку и не подменяет системный установщик.
- Не снимает запрет установки. Если прошивка действительно запрещает ставить приложения
  или кнопка неактивна (серая), автоклик не поможет.
- Не поможет установить сам себя — его нужно поставить другим способом, см. ниже.

## Установка

Сам InstallClicker ставится через ADB с компьютера — там подтверждать ничего не нужно:

```sh
adb install -r installclicker.apk
```

Дальше включите сервис специальных возможностей — без этого приложение ничего не делает:

**Через экран ГУ:** Настройки → Специальные возможности → InstallClicker → включить.

**Через ADB**, если на ГУ не нажимается даже кнопка подтверждения этого разрешения:

```sh
adb shell settings put secure enabled_accessibility_services \
    com.evilborsch.installclicker/com.evilborsch.installclicker.InstallClickerService
adb shell settings put secure accessibility_enabled 1
```

Проверить, что включилось:

```sh
adb shell settings get secure enabled_accessibility_services
```

Выключить сервис обратно:

```sh
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services ""
```

## Использование

1. Откройте InstallClicker — на экране видно, включён ли сервис Accessibility.
2. Тумблер «Автоклик кнопки „Установить“» включён по умолчанию.
3. Откройте нужный APK, например с флешки. Появится штатное окно установки —
   InstallClicker найдёт кнопку и подтвердит установку сам.
4. Выключите автоклик, когда он больше не нужен.

Включать и выключать автоклик можно и с компьютера:

```sh
adb shell am broadcast -a com.evilborsch.installclicker.SET_ENABLED --ez value true \
    -n com.evilborsch.installclicker/.ControlReceiver
adb shell am broadcast -a com.evilborsch.installclicker.SET_ENABLED --ez value false \
    -n com.evilborsch.installclicker/.ControlReceiver
```

## Что именно нажимается

Только видимые и активные кнопки с текстом из списка: `Установить`, `Установить в любом
случае`, `Обновить`, `Продолжить`, `Далее`, `Встановити`, `Install`, `Install anyway`,
`Update`, `Continue`, `Next`, `Done`, `确定`, `安装`. Кнопки `Отмена`, `Удалить`, `Назад`,
`Закрыть` и их переводы пропускаются всегда.

По умолчанию сервис смотрит только окна установщика — пакеты, в имени которых есть
`packageinstaller` или `installer`. Если на вашем ГУ установщик называется иначе, включите
в приложении «Нажимать в любом приложении». В этом режиме нажатия происходят в любом окне,
поэтому держите его включённым только на время установки.

Что и когда нажималось, видно в блоке «Последние действия» на экране приложения, а также в
логах:

```sh
adb logcat -s InstallClicker
```

## Приватность

Сервис специальных возможностей по своей природе видит содержимое окон. InstallClicker
ничего никуда не отправляет, у него нет разрешения на интернет: он читает текст кнопок,
нажимает подходящую и пишет короткий журнал в память приложения.

## Сборка

Нужны JDK 17+ и Android SDK (`compileSdk 34`, `build-tools 34.0.0`).

```sh
cd installclicker
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Без файла `keystore.properties` релиз подписывается отладочным ключом — APK ставится, но
обновить его можно только APK, подписанным тем же ключом. Свой ключ подключается так:

```properties
# installclicker/keystore.properties
storeFile=installclicker.keystore
storePassword=...
keyAlias=installclicker
keyPassword=...
```

## Статус проверки

Логика собрана под описанный случай с фильтром касаний. На Tenet T8 / Jaecoo J7 работа
ещё не подтверждена — если поставите, напишите в issue, что получилось.
