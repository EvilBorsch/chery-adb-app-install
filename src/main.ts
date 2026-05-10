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
};

type InstallResult = {
  package_name: string;
  steps: Step[];
};

const app = document.querySelector<HTMLDivElement>("#app");

if (!app) {
  throw new Error("App root not found");
}

let selectedApk: string | null = null;
let busy = false;
let steps: Step[] = [];
let device: DeviceInfo | null = null;

app.innerHTML = `
  <section class="shell">
    <header class="topbar">
      <div>
        <h1>DesaySV APK Installer</h1>
        <p>Установка APK на Tenet T8 / Chery DesaySV через ADB</p>
      </div>
      <div class="status" id="deviceStatus">Проверка ADB...</div>
    </header>

    <section class="toolbar">
      <button id="installDeps" type="button">Установить зависимости</button>
      <button id="checkDevice" type="button">Проверить ГУ</button>
      <button id="pickApk" type="button">Выбрать APK</button>
    </section>

    <section class="device-panel" id="devicePanel"></section>

    <section class="drop-zone" id="dropZone">
      <div class="drop-icon">APK</div>
      <div>
        <h2>Перетащите APK сюда</h2>
        <p id="selectedApkText">или нажмите «Выбрать APK»</p>
      </div>
    </section>

    <section class="actions">
      <button id="installApk" class="primary" type="button" disabled>Установить APK и выдать разрешения</button>
    </section>

    <section class="log-panel">
      <div class="log-title">Журнал</div>
      <div id="log"></div>
    </section>
  </section>
`;

const installDepsButton = document.querySelector<HTMLButtonElement>("#installDeps")!;
const checkDeviceButton = document.querySelector<HTMLButtonElement>("#checkDevice")!;
const pickApkButton = document.querySelector<HTMLButtonElement>("#pickApk")!;
const installApkButton = document.querySelector<HTMLButtonElement>("#installApk")!;
const dropZone = document.querySelector<HTMLElement>("#dropZone")!;
const selectedApkText = document.querySelector<HTMLElement>("#selectedApkText")!;
const logEl = document.querySelector<HTMLElement>("#log")!;
const deviceStatus = document.querySelector<HTMLElement>("#deviceStatus")!;
const devicePanel = document.querySelector<HTMLElement>("#devicePanel")!;

function render() {
  installDepsButton.disabled = busy;
  checkDeviceButton.disabled = busy;
  pickApkButton.disabled = busy;
  installApkButton.disabled = busy || !selectedApk;
  dropZone.classList.toggle("busy", busy);

  selectedApkText.textContent = selectedApk ?? "или нажмите «Выбрать APK»";
  deviceStatus.textContent = device?.connected ? "ГУ подключено" : "ADB не подключен";
  deviceStatus.className = `status ${device?.connected ? "ok" : "warn"}`;

  const adb = device?.adb_path ?? "не найден";
  const serial = device?.serial ?? "не определен";
  const model = device?.model ?? "не определена";
  const android = device?.android ?? "не определен";
  devicePanel.innerHTML = `
    <div><span>ADB</span><strong>${escapeHtml(adb)}</strong></div>
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
    device = await invoke<DeviceInfo>("check_device");
    addStep(device.connected ? "info" : "warn", device.connected ? "ГУ найдено" : "ГУ не найдено");
  });
}

installDepsButton.addEventListener("click", () => {
  runBusy(async () => {
    addStep("info", "Устанавливаю Android platform-tools");
    const result = await invoke<Step[]>("install_dependencies");
    addSteps(result);
    await refreshDevice();
  });
});

checkDeviceButton.addEventListener("click", refreshDevice);

pickApkButton.addEventListener("click", () => {
  runBusy(async () => {
    const picked = await open({
      multiple: false,
      filters: [{ name: "Android APK", extensions: ["apk"] }]
    });

    if (typeof picked === "string") {
      selectedApk = picked;
      addStep("info", `Выбран APK: ${picked}`);
    }
  });
});

installApkButton.addEventListener("click", () => {
  if (!selectedApk) {
    return;
  }

  runBusy(async () => {
    addStep("info", "Начинаю установку APK");
    const result = await invoke<InstallResult>("install_apk", { apkPath: selectedApk });
    addSteps(result.steps);
    addStep("info", `Готово: ${result.package_name}`);
    await refreshDevice();
  });
});

dropZone.addEventListener("click", () => pickApkButton.click());

getCurrentWebview().onDragDropEvent((event) => {
  if (event.payload.type === "over") {
    dropZone.classList.add("over");
  } else if (event.payload.type === "drop") {
    dropZone.classList.remove("over");
    const apk = event.payload.paths.find((path) => path.toLowerCase().endsWith(".apk"));
    if (apk) {
      selectedApk = apk;
      addStep("info", `APK принят: ${apk}`);
      render();
    } else {
      addStep("warn", "Перетащенный файл не похож на APK");
    }
  } else {
    dropZone.classList.remove("over");
  }
});

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

refreshDevice();
render();
