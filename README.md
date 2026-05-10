# Chery APK Install

Tauri 2 desktop-приложение для установки APK на Tenet T8 / Chery head unit.


## Использование

1. Подключите ГУ по USB/ADB.
2. Запустите приложение.
3. Нажмите `Установить зависимости`, если `adb` еще не установлен.
4. Нажмите `Проверить ГУ`.
5. Перетащите APK в окно или выберите файл кнопкой.
6. Нажмите `Установить APK и выдать разрешения`.

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
  src-tauri/resources/
    localinstall.apk           helper установки через PackageInstaller.Session
  release/macos/               уже собранные macOS app/dmg
```


Приложение само:

- скачивает Android platform-tools под текущую ОС;
- использует `adb`;
- копирует APK и `localinstall.apk` на ГУ;
- запускает установку через `PackageInstaller.Session`;
- выдает runtime permissions из manifest;
- выставляет appops для файлового доступа и других частых привилегий;
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

## Makefile

```bash
make install-deps  # npm install
make dev           # запуск Tauri dev
make build         # release-сборка приложения
make check         # vite build + cargo check
make clean         # удалить dist/node_modules/src-tauri/target
make tauri-info    # диагностика Tauri окружения
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

Требования:

- Node.js 20+
- Rust stable MSVC
- Microsoft C++ Build Tools / Visual Studio Build Tools
- WebView2 Runtime

PowerShell:

```powershell
cd path\to\cheryapkinstall
npm install
npm run tauri dev
npm run tauri build
```

Если `make` доступен, можно использовать те же команды `make install-deps`, `make dev`,
`make build`.

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
