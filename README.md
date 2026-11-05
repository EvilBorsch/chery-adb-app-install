# Chery APK Install

Tauri 2 приложение для установки APK на Tenet T8 / Chery head unit: Windows, macOS, Linux
и Android-телефон (ГУ подключается к телефону кабелем через OTG).


`adb` вшит в приложение — ничего скачивать и устанавливать вручную не нужно.

## Использование

1. Подключите ГУ по USB/ADB.
2. Запустите приложение.
3. Нажмите `Проверить ГУ`.
4. Перетащите APK в окно или выберите файл кнопкой.
5. Нажмите `Установить приложение и выдать все права`.

После копирования APK приложение ждёт ответ Android об установке до **120 секунд**.
При быстром ответе выдача прав начинается сразу. При отказе журнал сохраняет пройденные
шаги, исходный код `INSTALL_FAILED_*` / `INSTALL_PARSE_FAILED_*`, сообщение Android,
статусы PackageInstaller, сведения о ГУ, свободное место и доступные системные логи.
Тайм-аут показывается отдельно: он означает, что Android не вернул результат вовремя,
и установка на ГУ ещё может продолжаться.

### С Android-телефона

Android-версия делает то же самое, что ПК-установщик: тот же helper установки, тот же набор
прав, тот же режим «с правом управления авто». Отличается только способ связи с ГУ: телефон сам
выступает USB-хостом и говорит с ГУ по протоколу ADB, как компьютер.

1. Подключите ГУ к телефону тем же кабелем USB A–A, что и к компьютеру, через переходник
   OTG USB A (мама) → USB-C (папа). Обычный кабель от зарядки USB-C → USB-A не подойдёт:
   с ним телефон считает себя подключённым к компьютеру и не становится хостом.
2. Откройте приложение. На вопрос Android о доступе к USB-устройству ответьте «Разрешить»
   (с галочкой «Всегда» вопрос больше не появится).
3. При первом подключении на экране ГУ появится запрос «Разрешить отладку по USB?» —
   отметьте «Всегда разрешать» и нажмите «Разрешить». Затем снова нажмите `Проверить ГУ`.
4. `Выбрать APK` открывает выбор файла в памяти телефона, дальше всё как на компьютере.

Нужен Android 8.0 или новее.

### Права

При установке приложению сразу выдаются **все** права — на ГУ выдать их через настройки
нельзя, а владелец машины обычно не знает, что именно нужно конкретному приложению:

- runtime-разрешения, объявленные в манифесте;
- доступ к файлам и внешнему хранилищу;
- отрисовка поверх других окон и запись системных настроек;
- установка приложений (нужно Рустору и другим сторонним магазинам);
- работа в фоне, запуск при загрузке, исключение из экономии батареи;
- VPN;
- уведомления и статистика использования;
- геолокация и фиктивные местоположения (шеринг GPS);
- отключение авто-отзыва прав, чтобы Android не снял их у редко используемого приложения.

### Выдать права уже установленному приложению

Если приложение (Рустор, Стрелка, VPN, HUD speed и т.п.) уже установлено, но ему не хватает
прав, нажмите `Обновить список` в блоке «Управление приложениями», выберите приложение и
нажмите `Выдать все права` — выдаётся тот же набор, что и при установке.

## Что находится в папке

```text
.
  README.md                    запуск, сборка и использование приложения
  Makefile                     команды для разработки и сборки
  instuction.md                полный алгоритм установки APK на ГУ для другой LLM
  package.json                 frontend/Tauri scripts
  package-lock.json            lockfile npm-зависимостей
  src/                         web UI
  src-tauri/                   Rust backend Tauri 2
  src-tauri/src/transport_desktop.rs  связь с ГУ через вшитый adb (Windows/macOS/Linux)
  src-tauri/src/transport_android.rs  связь с ГУ по USB OTG (Android)
  src-tauri/gen/android/       Android-проект; ADB-клиент по USB — app/src/main/java/.../adb/
  src-tauri/binaries/
    adb-macos.zip              вшиваемый adb под macOS (universal x86_64 + arm64)
    adb-windows.zip            вшиваемый adb.exe под Windows вместе с его DLL
    adb-linux.zip              вшиваемый adb под Linux x86_64
  src-tauri/resources/
    localinstall.apk           helper установки через PackageInstaller.Session
```


Приложение само:

- распаковывает вшитый `adb` при первом запуске;
- копирует APK и `localinstall.apk` на ГУ;
- запускает установку через `PackageInstaller.Session`;
- выдает runtime permissions из manifest;
- выставляет полный набор appops (файлы, фон, установка, VPN, геолокация и др.);
- перезапускает установленное приложение.

## Разработка и запуск из исходников

```bash
cd cheryapkinstall
make install-deps
make dev
```

## Сборка

```bash
cd cheryapkinstall
make build
```

Артефакты появятся в:

```text
src-tauri/target/release/bundle/
```

## Релиз

Сборку релиза делает GitHub Actions (`.github/workflows/release.yml`) — локальный тулчейн
для этого не нужен. Достаточно поставить тег:

```bash
git tag v1.3.1
git push origin v1.3.1
```

Workflow соберёт Windows (`.exe`/`.msi`), macOS (Apple Silicon и Intel `.dmg`) и, если заданы
секреты подписи, Android (`.apk`) и опубликует их в релизе с этим тегом. Версию перед тегом нужно поднять в трёх местах: `package.json`,
`src-tauri/Cargo.toml` и `src-tauri/tauri.conf.json`.

Каждый push в `main` дополнительно проверяется workflow `ci.yml` (`cargo check` + `cargo test`).

Исходники helper находятся в `helper/`. Для пересборки встроенного
`src-tauri/resources/localinstall.apk` на Windows нужны JDK 17+ и Android SDK:

```powershell
.\helper\build.ps1 -AndroidSdk D:\android-sdk
```

Интерфейсы в `helper/stubs/` используются только при компиляции; в DEX они не включаются.

## Android

Требования: JDK 17, Android SDK с NDK, Rust-таргеты Android:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
```

Сборка (переменные окружения указывают на SDK и NDK):

```powershell
$env:ANDROID_HOME = 'D:\android-sdk'
$env:NDK_HOME = 'D:\android-sdk\ndk\28.2.13676358'
npx tauri android build --apk --target aarch64 --target armv7
```

Готовый APK:

```text
src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release.apk
```

На Windows Tauri кладёт собранную Rust-библиотеку в `jniLibs` символической ссылкой, поэтому
нужен включённый «Режим разработчика» (Параметры → Система → Для разработчиков). Без него сборка
падает на шаге `symlinking lib` — тогда можно скопировать
`src-tauri/target/<target>/release/libdesaysv_apk_installer_lib.so` в
`src-tauri/gen/android/app/src/main/jniLibs/<abi>/` вручную и запустить Gradle:

```powershell
cd src-tauri\gen\android
.\gradlew.bat assembleUniversalRelease -x rustBuildUniversalRelease "-PabiList=arm64-v8a,armeabi-v7a" "-ParchList=arm64,arm" "-PtargetList=aarch64,armv7"
```

Подпись release-сборки берётся из `src-tauri/gen/android/keystore.properties` (в git не
попадает):

```properties
storeFile=C:/путь/к/release.jks
password=пароль
keyAlias=desaysv
```

Ключ нужно хранить: обновление встаёт поверх установленного приложения только с той же
подписью. В GitHub Actions тот же ключ задаётся секретами `ANDROID_KEYSTORE_BASE64`
(`base64` от `.jks`), `ANDROID_KEYSTORE_PASSWORD` и `ANDROID_KEY_ALIAS` — тогда релиз по тегу
дополнительно публикует `*_android.apk`. Без секретов Android-шаг пропускается, а сборки для
Windows и macOS выходят как обычно.

Отладочная сборка (`npx tauri android build --apk --debug`), если ГУ по USB не найдено,
подключается к `127.0.0.1:5555` — так протокол проверяется на эмуляторе после `adb tcpip 5555`.

## Makefile

```bash
make install-deps  # npm install
make dev           # запуск Tauri dev
make build         # release-сборка приложения
make check         # vite build + cargo check + clippy
make test          # cargo test
make clean         # удалить dist/node_modules/src-tauri/target
make tauri-info    # диагностика Tauri окружения
make vendor-adb    # обновить вшитый adb из свежих platform-tools
```

## Вшитый ADB

`adb` лежит в репозитории в виде архивов `src-tauri/binaries/adb-<os>.zip` и попадает в
исполняемый файл через `include_bytes!` — в сборку берется только архив текущей ОС. При
первом обращении к ГУ приложение распаковывает его в app data dir:

```text
macOS    ~/Library/Application Support/local.desaysv.apk.installer/adb/<версия>/
Windows  %APPDATA%\local.desaysv.apk.installer\adb\<версия>\
Linux    ~/.local/share/local.desaysv.apk.installer/adb/<версия>/
```

Версия в пути берется из константы `ADB_VERSION` в `src-tauri/src/lib.rs`, поэтому после
обновления приложения распаковывается новый `adb`, а не остается старый.

Обновление вшитого `adb`:

```bash
make vendor-adb                       # скачает platform-tools и пересоберет архивы
# затем поправьте ADB_VERSION в src-tauri/src/lib.rs на версию из вывода команды
make test
```

## macOS

Требования:

- Node.js 20+
- Rust stable
- Xcode Command Line Tools

Установка зависимостей:

```bash
xcode-select --install
brew install node rust
```

Команды:

```bash
cd cheryapkinstall
make install-deps
make dev
make build
```

## Windows

Собирать Windows-сборку нужно на Windows: кросс-компиляция с macOS требует MSVC-тулчейна и
не поддерживается.

Требования:

- Node.js 20+
- Rust stable MSVC (`rustup toolchain install stable-x86_64-pc-windows-msvc`)
- Microsoft C++ Build Tools / Visual Studio Build Tools (workload «Desktop development with C++»)
- WebView2 Runtime (на Windows 11 уже есть)

PowerShell:

```powershell
git clone https://github.com/EvilBorsch/chery-adb-app-install.git
cd chery-adb-app-install
npm install
npm run tauri build
```

Готовые артефакты:

```text
src-tauri\target\release\bundle\msi\*.msi
src-tauri\target\release\bundle\nsis\*-setup.exe
```

`adb.exe` и его DLL уже лежат в `src-tauri/binaries/adb-windows.zip` и вшиваются в
исполняемый файл автоматически — драйвер USB для ГУ на Windows все равно ставится отдельно.

## Linux

Требования:

- Node.js 20+
- Rust stable
- WebKitGTK и системные зависимости Tauri

Debian/Ubuntu:

```bash
sudo apt update
sudo apt install -y \
  build-essential \
  curl \
  file \
  libayatana-appindicator3-dev \
  librsvg2-dev \
  libssl-dev \
  libwebkit2gtk-4.1-dev

cd cheryapkinstall
npm install
npm run tauri dev
npm run tauri build
```

Fedora:

```bash
sudo dnf install -y \
  cargo \
  nodejs \
  npm \
  openssl-devel \
  webkit2gtk4.1-devel \
  libappindicator-gtk3-devel \
  librsvg2-devel
```

## Проверка результата через терминал

```bash
adb devices -l
adb shell pm list packages <package.name>
adb shell appops get <package.name>
```

## Где описан алгоритм установки APK

Полное техническое описание находится в:

```text
instuction.md
```
