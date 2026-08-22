# Chery APK Install

Tauri 2 desktop-приложение для установки APK на Tenet T8 / Chery head unit.


`adb` вшит в приложение — ничего скачивать и устанавливать вручную не нужно.

## Использование

1. Подключите ГУ по USB/ADB.
2. Запустите приложение.
3. Нажмите `Проверить ГУ`.
4. Перетащите APK в окно или выберите файл кнопкой.
5. Нажмите `Установить приложение и выдать все права`.

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
git tag v1.3.0
git push origin v1.3.0
```

Workflow соберёт Windows (`.exe`/`.msi`) и macOS (Apple Silicon и Intel `.dmg`) и опубликует
их в релизе с этим тегом. Версию перед тегом нужно поднять в трёх местах: `package.json`,
`src-tauri/Cargo.toml` и `src-tauri/tauri.conf.json`.

Каждый push в `main` дополнительно проверяется workflow `ci.yml` (`cargo check` + `cargo test`).

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
