import { invoke } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import "./styles.css";

type Step = {
  level: "info" | "warn";
  message: string;
};

type DeviceInfo = {
  connected: boolean;
  adb_path: string | null;
  serial: string | null;
  model: string | null;
  android: string | null;
  message: string | null;
};

type PickedApk = {
  path: string;
  name: string;
  size: number;
};

// На телефоне ГУ подключается по USB через OTG, а APK выбирается в памяти телефона.
const isAndroid = /android/i.test(navigator.userAgent);

type InstallResult = {
  package_name: string;
  steps: Step[];
};

const app = document.querySelector<HTMLDivElement>("#app");

if (!app) {
  throw new Error("App root not found");
}

let selectedApk: string | null = null;
let selectedApkLabel: string | null = null;
let selectedPackage: string | null = null;
let uninstallablePackages: string[] = [];
let busy = false;
let steps: Step[] = [];
let device: DeviceInfo | null = null;

app.innerHTML = `
  <section class="shell">
    <header class="topbar">
      <div>
        <h1>DesaySV APK Installer</h1>
        <p>${isAndroid ? "Установка APK на Tenet T8 / Chery DesaySV с телефона по USB" : "Установка APK на Tenet T8 / Chery DesaySV через ADB"}</p>
      </div>
      <div class="status" id="deviceStatus">Проверка ADB...</div>
    </header>

    <section class="notice">
      <strong>При установке приложению сразу выдаются ВСЕ права.</strong>
      Доступ к файлам, работа поверх других окон, установка приложений, работа в фоне без
      ограничений экономии батареи, VPN, уведомления, геолокация и фиктивные местоположения
      (шеринг GPS). В машине выдать их через настройки нельзя, поэтому они выдаются пакетом,
      и Android не отзовёт их со временем.
      <br />
      Если приложение уже установлено и ему не хватает прав — выберите его в списке ниже и
      нажмите «Выдать все права».
    </section>

    ${isAndroid ? `
    <section class="notice">
      <strong>Подключение ГУ к телефону.</strong>
      Кабель USB A–A от ГУ и переходник OTG USB A → USB-C в телефон — тот же кабель, что для
      компьютера. Обычный кабель от зарядки USB-C → USB-A не подойдёт: с ним телефон не станет
      ведущим. При первом подключении разрешите телефону доступ к USB-устройству, а на экране ГУ —
      отладку по USB («Всегда разрешать»).
    </section>` : ""}

    <section class="toolbar">
      <button id="checkDevice" type="button">Проверить ГУ</button>
      <button id="pickApk" type="button">Выбрать APK</button>
    </section>

    <section class="device-panel" id="devicePanel"></section>

    <section class="drop-zone" id="dropZone">
      <div class="drop-icon">APK</div>
      <div>
        <h2>${isAndroid ? "Выберите APK" : "Перетащите APK сюда"}</h2>
        <p id="selectedApkText"></p>
      </div>
    </section>

    <section class="actions">
      <button id="installNormalApk" class="primary" type="button" disabled>Установить приложение и выдать все права</button>
      <button id="installCarApk" class="danger" type="button" disabled>Установить приложение с правом управления авто (вам это скорее всего не надо)</button>
    </section>

    <section class="app-manager">
      <div class="app-manager-header">
        <h2>Управление приложениями</h2>
        <button id="loadApps" type="button">Обновить список</button>
      </div>
      <div class="app-manager-controls">
        <select id="packageSelect" aria-label="Выбор приложения">
          <option value="">Список не загружен</option>
        </select>
        <button id="grantAllApp" class="primary" type="button" disabled>Выдать все права</button>
        <button id="uninstallApp" class="danger" type="button" disabled>Удалить выбранное</button>
      </div>
    </section>

    <section class="log-panel">
      <div class="log-title">Журнал</div>
      <div id="log"></div>
    </section>
  </section>
`;

const checkDeviceButton = document.querySelector<HTMLButtonElement>("#checkDevice")!;
const pickApkButton = document.querySelector<HTMLButtonElement>("#pickApk")!;
const installNormalApkButton = document.querySelector<HTMLButtonElement>("#installNormalApk")!;
const installCarApkButton = document.querySelector<HTMLButtonElement>("#installCarApk")!;
const loadAppsButton = document.querySelector<HTMLButtonElement>("#loadApps")!;
const grantAllAppButton = document.querySelector<HTMLButtonElement>("#grantAllApp")!;
const uninstallAppButton = document.querySelector<HTMLButtonElement>("#uninstallApp")!;
const packageSelect = document.querySelector<HTMLSelectElement>("#packageSelect")!;
const dropZone = document.querySelector<HTMLElement>("#dropZone")!;
const selectedApkText = document.querySelector<HTMLElement>("#selectedApkText")!;
const logEl = document.querySelector<HTMLElement>("#log")!;
const deviceStatus = document.querySelector<HTMLElement>("#deviceStatus")!;
const devicePanel = document.querySelector<HTMLElement>("#devicePanel")!;

function render() {
  checkDeviceButton.disabled = busy;
  pickApkButton.disabled = busy;
  installNormalApkButton.disabled = busy || !selectedApk;
  installCarApkButton.disabled = busy || !selectedApk;
  loadAppsButton.disabled = busy;
  grantAllAppButton.disabled = busy || !selectedPackage;
  uninstallAppButton.disabled = busy || !selectedPackage;
  dropZone.classList.toggle("busy", busy);
  renderPackageOptions();

  selectedApkText.textContent =
    selectedApkLabel ?? (isAndroid ? "Нажмите, чтобы выбрать файл в памяти телефона" : "или нажмите «Выбрать APK»");
  deviceStatus.textContent = device?.connected ? "ГУ подключено" : "ADB не подключен";
  deviceStatus.className = `status ${device?.connected ? "ok" : "warn"}`;

  const adb = device?.adb_path ?? (isAndroid ? "нет" : "не найден");
  const serial = device?.serial ?? "не определен";
  const model = device?.model ?? "не определена";
  const android = device?.android ?? "не определен";
  devicePanel.innerHTML = `
    <div><span>${isAndroid ? "Подключение" : "ADB"}</span><strong>${escapeHtml(adb)}</strong></div>
    <div><span>Серийный номер</span><strong>${escapeHtml(serial)}</strong></div>
    <div><span>Модель</span><strong>${escapeHtml(model)}</strong></div>
    <div><span>Android</span><strong>${escapeHtml(android)}</strong></div>
  `;

  logEl.innerHTML = steps
    .map((step) => `<div class="log-row ${step.level}">${escapeHtml(step.message)}</div>`)
    .join("");
  logEl.scrollTop = logEl.scrollHeight;
}

function addStep(level: Step["level"], message: string) {
  steps.push({ level, message });
  render();
}

function addSteps(nextSteps: Step[]) {
  steps.push(...nextSteps);
  render();
}

async function runBusy<T>(action: () => Promise<T>): Promise<T | null> {
  busy = true;
  render();
  try {
    return await action();
  } catch (error) {
    addStep("warn", String(error));
    return null;
  } finally {
    busy = false;
    render();
  }
}

async function refreshDevice() {
  await runBusy(async () => {
    addStep("info", "Проверяю ADB и подключенное устройство");
    if (isAndroid) {
      addStep("info", "Если на экране ГУ появится запрос отладки по USB — нажмите «Разрешить».");
    }
    device = await invoke<DeviceInfo>("check_device");
    addStep(device.connected ? "info" : "warn", device.connected ? "ГУ найдено" : "ГУ не найдено");
    if (!device.connected && device.message) {
      addStep("warn", device.message);
    }
  });
}

checkDeviceButton.addEventListener("click", refreshDevice);

loadAppsButton.addEventListener("click", () => {
  runBusy(async () => {
    addStep("info", "Загружаю список приложений");
    uninstallablePackages = await invoke<string[]>("list_uninstallable_packages");
    selectedPackage = uninstallablePackages[0] ?? null;
    addStep("info", `Найдено приложений: ${uninstallablePackages.length}`);
  });
});

packageSelect.addEventListener("change", () => {
  selectedPackage = packageSelect.value || null;
  render();
});

grantAllAppButton.addEventListener("click", () => {
  const packageName = selectedPackage;
  if (!packageName) {
    return;
  }

  if (!window.confirm(`Выдать все права приложению ${packageName}?`)) {
    return;
  }

  runBusy(async () => {
    addStep("info", `Выдаю все права: ${packageName}`);
    const result = await invoke<Step[]>("grant_all_permissions", { packageName });
    addSteps(result);
    addStep("info", `Готово: права выданы для ${packageName}`);
  });
});

uninstallAppButton.addEventListener("click", () => {
  const packageName = selectedPackage;
  if (!packageName) {
    return;
  }

  if (!window.confirm(`Удалить приложение ${packageName}?`)) {
    return;
  }

  runBusy(async () => {
    addStep("info", `Удаляю приложение: ${packageName}`);
    const result = await invoke<Step[]>("uninstall_package", { packageName });
    addSteps(result);
    uninstallablePackages = uninstallablePackages.filter((name) => name !== packageName);
    selectedPackage = uninstallablePackages[0] ?? null;
    await refreshDevice();
  });
});

pickApkButton.addEventListener("click", () => {
  runBusy(async () => {
    if (isAndroid) {
      const picked = await invoke<PickedApk | null>("pick_apk_mobile");
      if (picked) {
        selectedApk = picked.path;
        selectedApkLabel = `${picked.name} (${formatSize(picked.size)})`;
        addStep("info", `Выбран APK: ${picked.name}`);
      }
      return;
    }

    const picked = await open({
      multiple: false,
      filters: [{ name: "Android APK", extensions: ["apk"] }]
    });

    if (typeof picked === "string") {
      selectedApk = picked;
      selectedApkLabel = picked;
      addStep("info", `Выбран APK: ${picked}`);
    }
  });
});

installNormalApkButton.addEventListener("click", () => installSelectedApk(false));

installCarApkButton.addEventListener("click", () => {
  const confirmed = window.confirm(
    "Этот режим может сделать приложение Device Owner, очистить данные Bluetooth и выдать расширенные ADB-права. Продолжить?"
  );
  if (confirmed) {
    installSelectedApk(true);
  }
});

function installSelectedApk(carManagement: boolean) {
  if (!selectedApk) {
    return;
  }

  runBusy(async () => {
    const mode = carManagement ? "с правом управления авто" : "обычного приложения";
    addStep("info", `Начинаю установку APK в режиме: ${mode}`);
    addStep("info", "После копирования APK ожидание ответа Android может занять до 120 секунд.");
    const result = await invoke<InstallResult>("install_apk", {
      apkPath: selectedApk,
      carManagement
    });
    addSteps(result.steps);
    addStep("info", `Готово: ${result.package_name}`);
    await refreshDevice();
  });
}

dropZone.addEventListener("click", () => pickApkButton.click());

if (!isAndroid) getCurrentWebview().onDragDropEvent((event) => {
  if (event.payload.type === "over") {
    dropZone.classList.add("over");
  } else if (event.payload.type === "drop") {
    dropZone.classList.remove("over");
    const apk = event.payload.paths.find((path) => path.toLowerCase().endsWith(".apk"));
    if (apk) {
      selectedApk = apk;
      selectedApkLabel = apk;
      addStep("info", `APK принят: ${apk}`);
      render();
    } else {
      addStep("warn", "Перетащенный файл не похож на APK");
    }
  } else {
    dropZone.classList.remove("over");
  }
});

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} КБ`;
}

function escapeHtml(value: string): string {
  return value
    // replace с /g, а не replaceAll: старый Android WebView (Chrome < 85) replaceAll не знает.
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function renderPackageOptions() {
  if (uninstallablePackages.length === 0) {
    packageSelect.innerHTML = `<option value="">Список не загружен</option>`;
    return;
  }

  if (!selectedPackage || !uninstallablePackages.includes(selectedPackage)) {
    selectedPackage = uninstallablePackages[0];
  }

  packageSelect.innerHTML = uninstallablePackages
    .map((packageName) => {
      const selected = packageName === selectedPackage ? " selected" : "";
      return `<option value="${escapeHtml(packageName)}"${selected}>${escapeHtml(packageName)}</option>`;
    })
    .join("");
}

document.body.classList.toggle("mobile", isAndroid);
refreshDevice();
render();
