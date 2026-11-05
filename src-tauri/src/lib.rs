use serde::Serialize;
use std::{
    ffi::OsStr,
    fs,
    io::{self, Read},
    path::{Path, PathBuf},
};
use tauri::AppHandle;
use thiserror::Error;
use zip::ZipArchive;

mod install_diagnostics;
mod install_wait;

// Транспорт до ГУ: на компьютере — вшитый adb, на телефоне — ADB по USB через OTG.
// Вся логика установки и выдачи прав ниже общая для всех платформ.
#[cfg(desktop)]
#[path = "transport_desktop.rs"]
mod transport;
#[cfg(target_os = "android")]
#[path = "transport_android.rs"]
mod transport;

use transport::AdbTarget;

use install_diagnostics::{failure_summary, helper_package, helper_succeeded};
use install_wait::wait_for_installation;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[derive(Debug, Error)]
enum InstallerError {
    #[error("{0}")]
    Message(String),
    #[error(transparent)]
    Io(#[from] io::Error),
    #[error(transparent)]
    Zip(#[from] zip::result::ZipError),
}

impl serde::Serialize for InstallerError {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::ser::Serializer,
    {
        serializer.serialize_str(&self.to_string())
    }
}

type Result<T> = std::result::Result<T, InstallerError>;

const AXML_FILE: usize = 0x0003;
const AXML_STRING_POOL: usize = 0x0001;
const AXML_START_ELEMENT: usize = 0x0102;

const INSTALL_TIMEOUT: Duration = Duration::from_secs(120);
const INSTALL_POLL_INTERVAL: Duration = Duration::from_secs(2);

#[derive(Clone, Serialize)]
struct Step {
    level: String,
    message: String,
}

#[derive(Serialize)]
struct DeviceInfo {
    connected: bool,
    adb_path: Option<String>,
    serial: Option<String>,
    model: Option<String>,
    android: Option<String>,
    /// Почему ГУ не найдено — на телефоне есть что подсказать (OTG, запрос отладки на ГУ).
    message: Option<String>,
}

#[derive(Serialize)]
struct PickedApk {
    path: String,
    name: String,
    size: u64,
}

#[derive(Serialize)]
struct InstallResult {
    package_name: String,
    steps: Vec<Step>,
}

struct WorkLog {
    steps: Vec<Step>,
}

impl WorkLog {
    fn new() -> Self {
        Self { steps: Vec::new() }
    }

    fn info(&mut self, message: impl Into<String>) {
        self.steps.push(Step {
            level: "info".to_string(),
            message: message.into(),
        });
    }

    fn warn(&mut self, message: impl Into<String>) {
        self.steps.push(Step {
            level: "warn".to_string(),
            message: message.into(),
        });
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init());
    #[cfg(target_os = "android")]
    let builder = builder.plugin(transport::plugin());

    builder
        .invoke_handler(tauri::generate_handler![
            check_device,
            install_apk,
            grant_all_permissions,
            list_uninstallable_packages,
            uninstall_package,
            pick_apk_mobile
        ])
        .run(tauri::generate_context!())
        .expect("failed to run tauri app");
}

// Все команды работают вне главного потока: на Android вызов USB-плагина из главного потока
// заблокировал бы его самого, а на компьютере окно не замирает, пока идёт adb.
#[tauri::command(async)]
fn check_device(app: AppHandle) -> Result<DeviceInfo> {
    transport::check_device(&app)
}

/// Выбор APK в памяти телефона. На компьютере APK выбирается диалогом в интерфейсе.
#[tauri::command(async)]
fn pick_apk_mobile(app: AppHandle) -> Result<Option<PickedApk>> {
    transport::pick_apk(&app)
}

#[tauri::command(async)]
fn install_apk(app: AppHandle, apk_path: String, car_management: bool) -> Result<InstallResult> {
    let mut log = WorkLog::new();
    match install_apk_inner(&app, apk_path, car_management, &mut log) {
        Ok(package_name) => Ok(InstallResult {
            package_name,
            steps: log.steps,
        }),
        Err(error) => {
            let steps = log
                .steps
                .iter()
                .map(|step| step.message.as_str())
                .collect::<Vec<_>>()
                .join("\n");
            Err(InstallerError::Message(format!("{steps}\nОшибка: {error}")))
        }
    }
}

fn install_apk_inner(
    app: &AppHandle,
    apk_path: String,
    car_management: bool,
    log: &mut WorkLog,
) -> Result<String> {
    let apk_path = PathBuf::from(apk_path);
    if !apk_path.is_file() {
        return Err(InstallerError::Message(format!(
            "APK не найден: {}",
            apk_path.display()
        )));
    }

    let adb = transport::connect(app)?;
    if car_management {
        apply_vehicle_preinstall(&adb, log);
    }

    let package_before = list_packages(&adb)?;
    let package_hint = apk_package_name(&apk_path).ok();

    let remote_apk = "/data/local/tmp/desaysv-install-target.apk";
    let remote_helper = "/data/local/tmp/desaysv-localinstall.apk";
    let helper = transport::localinstall_helper(app)?;

    log.info("Копирую APK на устройство");
    run_adb_checked(&adb, ["push", path_arg(&apk_path).as_str(), remote_apk])?;
    run_adb_checked(&adb, ["shell", "chmod", "644", remote_apk])?;

    log.info("Копирую helper установки");
    run_adb_checked(&adb, ["push", path_arg(&helper).as_str(), remote_helper])?;
    run_adb_checked(&adb, ["shell", "chmod", "644", remote_helper])?;

    // Сессионный установщик не умеет ставить поверх («Attempt to re-install without first
    // uninstalling»), а данные прошлой версии не дадут поставить APK с другой подписью.
    if let Some(package) = package_hint.as_deref() {
        if is_package_installed(&adb, package)? {
            log.info(format!("Снимаю установленную версию {package}"));
            let output = run_adb_checked(&adb, ["shell", "pm", "uninstall", package])?;
            if !output.stdout.lines().any(|line| line.trim() == "Success") {
                return Err(InstallerError::Message(format!(
                    "Не удалось удалить установленную версию {package}.\n{}{}",
                    output.stdout, output.stderr
                )));
            }
            append_output(log, output);
        }
    }

    log.info("Устанавливаю APK через PackageInstaller.Session");
    let marker = format!(
        "desaysv-install-{}",
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    );
    run_adb_optional(
        &adb,
        ["shell", "log", "-t", "DesaySVInstaller", marker.as_str()],
        log,
    );
    let install_cmd = format!(
        "CLASSPATH={remote_helper} /system/bin/app_process /system/bin LocalInstall {remote_apk} {}",
        INSTALL_TIMEOUT.as_secs()
    );
    let started = Instant::now();
    let output = run_adb(&adb, ["shell", install_cmd.as_str()])?;
    let failure = (!output.success || !helper_succeeded(&output.stdout)).then(|| {
        failure_summary(
            &output.stdout,
            output.code,
            INSTALL_TIMEOUT.as_secs(),
        )
    });
    let package_hint = helper_package(&output.stdout)
        .filter(|package| is_safe_package_name(package))
        .map(ToOwned::to_owned)
        .or(package_hint);
    append_output(log, output);
    if let Some(reason) = failure {
        let diagnostics = collect_install_diagnostics(&adb, &marker);
        return Err(InstallerError::Message(format!("{reason}\n{diagnostics}")));
    }

    // Даже после успешного callback пакет должен появиться в списке PackageManager.
    let package_name = wait_for_installation(
        INSTALL_TIMEOUT.saturating_sub(started.elapsed()),
        INSTALL_POLL_INTERVAL,
        || -> Result<Option<String>> {
            match package_hint.as_deref() {
                Some(package) => {
                    Ok(is_package_installed(&adb, package)?.then(|| package.to_string()))
                }
                None => Ok(list_packages(&adb)?
                    .into_iter()
                    .find(|package| !package_before.contains(package))),
            }
        },
    )?;
    let package_name = package_name.ok_or_else(|| {
        InstallerError::Message(format!(
            "Android сообщил об успешной установке {}, но пакет не появился в списке за {} секунд.\n{}",
            package_hint.as_deref().unwrap_or("APK"),
            INSTALL_TIMEOUT.as_secs(),
            collect_install_diagnostics(&adb, &marker)
        ))
    })?;

    log.info(format!("Пакет установлен: {package_name}"));
    // Пользователи ГУ не знают, что нужно конкретному приложению, а выдать право руками в
    // машине негде — поэтому при установке сразу выдаём весь набор, а не только манифестный.
    grant_all_permissions_inner(&adb, &package_name, log)?;
    if car_management {
        apply_vehicle_management_inner(&adb, &package_name, log)?;
        apply_device_owner_inner(&adb, &package_name, log)?;
    }

    run_adb_optional(
        &adb,
        ["shell", "am", "force-stop", package_name.as_str()],
        log,
    );
    run_adb_optional(
        &adb,
        [
            "shell",
            "monkey",
            "-p",
            package_name.as_str(),
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
        ],
        log,
    );
    run_adb_optional(&adb, ["shell", "rm", "-f", remote_apk, remote_helper], log);

    Ok(package_name)
}

#[tauri::command(async)]
fn grant_all_permissions(app: AppHandle, package_name: String) -> Result<Vec<Step>> {
    if !is_safe_package_name(&package_name) {
        return Err(InstallerError::Message(format!(
            "Некорректное имя пакета: {package_name}"
        )));
    }

    let adb = transport::connect(&app)?;
    if !is_package_installed(&adb, &package_name)? {
        return Err(InstallerError::Message(format!(
            "Пакет не установлен: {package_name}"
        )));
    }

    let mut log = WorkLog::new();
    grant_all_permissions_inner(&adb, &package_name, &mut log)?;
    Ok(log.steps)
}

#[tauri::command(async)]
fn list_uninstallable_packages(app: AppHandle) -> Result<Vec<String>> {
    let adb = transport::connect(&app)?;

    let output = run_adb_checked(&adb, ["shell", "pm", "list", "packages", "-3"])?;
    let mut packages: Vec<String> = output
        .stdout
        .lines()
        .filter_map(|line| line.trim().strip_prefix("package:").map(str::to_string))
        .filter(|package| is_safe_package_name(package))
        .collect();
    packages.sort();
    Ok(packages)
}

#[tauri::command(async)]
fn uninstall_package(app: AppHandle, package_name: String) -> Result<Vec<Step>> {
    if !is_safe_package_name(&package_name) {
        return Err(InstallerError::Message(format!(
            "Invalid package name: {package_name}"
        )));
    }

    let adb = transport::connect(&app)?;
    if !is_package_installed(&adb, &package_name)? {
        return Err(InstallerError::Message(format!(
            "Package is not installed: {package_name}"
        )));
    }

    let mut log = WorkLog::new();
    log.info(format!("Uninstalling package: {package_name}"));
    let output = run_adb_checked(&adb, ["shell", "pm", "uninstall", package_name.as_str()])?;
    append_output(&mut log, output);
    log.info(format!("Uninstalled: {package_name}"));
    Ok(log.steps)
}

// Сколько раз перевыдаём право на фиктивные местоположения, пока установка доезжает
const MOCK_LOCATION_ATTEMPTS: usize = 4;

fn grant_manifest_permissions(
    adb: &AdbTarget,
    package_name: &str,
    log: &mut WorkLog,
) -> Result<()> {
    log.info("Выдаю runtime permissions из manifest");
    for permission in requested_permissions(adb, package_name)? {
        let output = run_adb(
            adb,
            ["shell", "pm", "grant", package_name, permission.as_str()],
        )?;
        if output.success {
            log.info(format!("permission: {permission}"));
        }
    }

    Ok(())
}

/// Отказ appops — это норма: часть операций не существует на конкретной версии Android,
/// часть неприменима к приложению. Поэтому в журнал пишем только то, что реально выдалось.
fn set_appops(adb: &AdbTarget, package_name: &str, ops: &[&str], log: &mut WorkLog) -> Result<()> {
    for &op in ops {
        let output = run_adb(adb, ["shell", "appops", "set", package_name, op, "allow"])?;
        if output.success {
            log.info(format!("appop: {op}=allow"));
        }
    }

    Ok(())
}

/// Право на фиктивные местоположения проверяем отдельно: без него шеринг GPS молча не
/// работает, а на ГУ выдать его руками негде — в прошивке нет пункта developer options.
/// Раньше отказ appops не был виден вообще: код возврата adb shell об этом не говорит,
/// и «нет права» всплывало уже в машине.
/// Установка большого APK доезжает асинхронно, и appop, выданный на недоустановленный
/// пакет, теряется молча: adb shell об этом не сообщает никак. Поэтому здесь читаем
/// фактическое состояние и повторяем выдачу, пока оно не станет allow.
fn verify_mock_location(adb: &AdbTarget, package_name: &str, log: &mut WorkLog) -> Result<()> {
    let mut mock_state = String::new();
    for attempt in 0..MOCK_LOCATION_ATTEMPTS {
        if attempt > 0 {
            std::thread::sleep(std::time::Duration::from_secs(2));
            run_adb(
                adb,
                [
                    "shell",
                    "appops",
                    "set",
                    package_name,
                    "android:mock_location",
                    "allow",
                ],
            )?;
        }
        mock_state = run_adb(
            adb,
            [
                "shell",
                "cmd",
                "appops",
                "get",
                package_name,
                "android:mock_location",
            ],
        )?
        .stdout;
        if mock_state.contains("allow") {
            log.info("Фиктивные местоположения разрешены");
            return Ok(());
        }
    }

    log.warn(format!(
        "Фиктивные местоположения НЕ разрешены ({}) — шеринг GPS работать не будет. \
         Выдайте вручную: adb shell appops set {package_name} android:mock_location allow",
        mock_state.trim()
    ));

    Ok(())
}

// Полный набор appops для кнопки «Выдать все права»: людям проще выдать сразу всё, чем
// разбираться, что именно нужно конкретному приложению (Рустор, Стрелка, VPN, HUD speed),
// а на ГУ соответствующих пунктов в настройках нет.
const ALL_APPOPS: &[&str] = &[
    // Хранилище
    "MANAGE_EXTERNAL_STORAGE",
    "LEGACY_STORAGE",
    "READ_EXTERNAL_STORAGE",
    "WRITE_EXTERNAL_STORAGE",
    // Поверх других приложений / системные настройки
    "SYSTEM_ALERT_WINDOW",
    "WRITE_SETTINGS",
    // Установка приложений (нужно Рустору и др. сторонним магазинам)
    "REQUEST_INSTALL_PACKAGES",
    // Работа в фоне без ограничений
    "RUN_IN_BACKGROUND",
    "RUN_ANY_IN_BACKGROUND",
    "START_FOREGROUND",
    "INSTANT_APP_START_FOREGROUND",
    "WAKE_LOCK",
    // Уведомления / статистика использования
    "POST_NOTIFICATION",
    "ACCESS_NOTIFICATIONS",
    "GET_USAGE_STATS",
    // Будильники и точное время
    "SCHEDULE_EXACT_ALARM",
    "USE_EXACT_ALARM",
    // Экран
    "TURN_SCREEN_ON",
    "PICTURE_IN_PICTURE",
    // VPN
    "ACTIVATE_VPN",
    "ACTIVATE_PLATFORM_VPN",
    // Геолокация и фиктивные местоположения (шеринг GPS)
    "COARSE_LOCATION",
    "FINE_LOCATION",
    "android:mock_location",
];

fn grant_all_permissions_inner(
    adb: &AdbTarget,
    package_name: &str,
    log: &mut WorkLog,
) -> Result<()> {
    log.info(format!("Выдаю все права: {package_name}"));
    grant_manifest_permissions(adb, package_name, log)?;

    log.info("Выставляю полный набор appops (фон, установка, VPN, экран, геолокация)");
    set_appops(adb, package_name, ALL_APPOPS, log)?;

    // Android сам отзывает права у приложений, которыми давно не пользовались. На ГУ это
    // выглядит так, будто через пару недель всё «сломалось» само — выключаем авто-отзыв.
    let auto_revoke = run_adb(
        adb,
        [
            "shell",
            "cmd",
            "appops",
            "set",
            package_name,
            "AUTO_REVOKE_PERMISSIONS_IF_UNUSED",
            "ignore",
        ],
    )?;
    if auto_revoke.success {
        log.info("Авто-отзыв неиспользуемых прав отключён");
    }

    // Без этого система душит фоновую работу — рвутся VPN и фоновая навигация.
    log.info("Исключаю из ограничений экономии батареи");
    let whitelist_package = format!("+{package_name}");
    run_adb_optional(
        adb,
        [
            "shell",
            "dumpsys",
            "deviceidle",
            "whitelist",
            whitelist_package.as_str(),
        ],
        log,
    );

    // Доступ к уведомлениям выдаётся не appops, а отдельным списком слушателей, и только
    // если приложение объявляет NotificationListenerService.
    let listeners = notification_listener_candidates(adb, package_name)?;
    if !listeners.is_empty() {
        log.info("Разрешаю доступ к уведомлениям");
        for component in listeners {
            run_adb_optional(
                adb,
                [
                    "shell",
                    "cmd",
                    "notification",
                    "allow_listener",
                    component.as_str(),
                ],
                log,
            );
        }
    }

    verify_mock_location(adb, package_name, log)?;
    log.info(format!("Все права выданы: {package_name}"));
    Ok(())
}

fn apply_vehicle_preinstall(adb: &AdbTarget, log: &mut WorkLog) {
    log.info("Applying vehicle integration pre-install flag");
    run_adb_optional(adb, ["shell", "setprop", "persist.sys.sv.isl", "true"], log);
}

fn apply_vehicle_management_inner(
    adb: &AdbTarget,
    package_name: &str,
    log: &mut WorkLog,
) -> Result<()> {
    log.info("Applying vehicle-management compatibility commands");
    apply_vehicle_preinstall(adb, log);

    for permission in [
        "android.permission.READ_LOGS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.RECORD_AUDIO",
        "android.permission.BLUETOOTH_CONNECT",
    ] {
        run_adb_optional(adb, ["shell", "pm", "grant", package_name, permission], log);
    }

    for op in [
        "SYSTEM_ALERT_WINDOW",
        "REQUEST_INSTALL_PACKAGES",
        "MANAGE_EXTERNAL_STORAGE",
        "ACTIVATE_VPN",
        "android:write_settings",
        "android:mock_location",
    ] {
        run_adb_optional(
            adb,
            ["shell", "appops", "set", package_name, op, "allow"],
            log,
        );
    }

    for component in notification_listener_candidates(adb, package_name)? {
        run_adb_optional(
            adb,
            [
                "shell",
                "cmd",
                "notification",
                "allow_listener",
                component.as_str(),
            ],
            log,
        );
    }
    let whitelist_package = format!("+{package_name}");
    run_adb_optional(
        adb,
        [
            "shell",
            "dumpsys",
            "deviceidle",
            "whitelist",
            whitelist_package.as_str(),
        ],
        log,
    );

    Ok(())
}

fn notification_listener_candidates(adb: &AdbTarget, package_name: &str) -> Result<Vec<String>> {
    let mut candidates = Vec::new();
    let query = run_adb(
        adb,
        [
            "shell",
            "cmd",
            "package",
            "query-services",
            "-a",
            "android.service.notification.NotificationListenerService",
            package_name,
        ],
    )?;
    if query.success {
        extract_component_candidates(package_name, &query.stdout, &mut candidates);
    }

    let dump = run_adb(adb, ["shell", "dumpsys", "package", package_name])?;
    if dump.success {
        extract_notification_listener_candidates(package_name, &dump.stdout, &mut candidates);
    }

    Ok(candidates)
}

fn extract_notification_listener_candidates(
    package_name: &str,
    text: &str,
    candidates: &mut Vec<String>,
) {
    let lines: Vec<&str> = text.lines().collect();
    for (index, line) in lines.iter().enumerate() {
        if line.contains("android.service.notification.NotificationListenerService")
            || line.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
        {
            let start = index.saturating_sub(6);
            let end = (index + 7).min(lines.len());
            extract_component_candidates(package_name, &lines[start..end].join("\n"), candidates);
        }
    }
}

fn apply_device_owner_inner(adb: &AdbTarget, package_name: &str, log: &mut WorkLog) -> Result<()> {
    log.info("Applying Device Owner mode");

    let candidates = device_admin_candidates(adb, package_name)?;
    if candidates.is_empty() {
        log.warn(format!(
            "No DeviceAdmin receiver found for package: {package_name}"
        ));
        return Ok(());
    }

    for component in candidates {
        log.info(format!("Trying Device Owner receiver: {component}"));
        let output = run_adb(
            adb,
            ["shell", "dpm", "set-device-owner", component.as_str()],
        )?;
        append_output(log, output);
        let verify = run_adb(adb, ["shell", "dpm", "get-device-owner"])?;
        if verify.success && verify.stdout.contains(package_name) {
            log.info(format!("Device Owner enabled: {component}"));
            return Ok(());
        }
    }

    log.warn(
        "Device Owner was not enabled. Android may already be provisioned or have another owner.",
    );
    Ok(())
}

fn device_admin_candidates(adb: &AdbTarget, package_name: &str) -> Result<Vec<String>> {
    let mut candidates = Vec::new();

    let query = run_adb(
        adb,
        [
            "shell",
            "cmd",
            "package",
            "query-receivers",
            "-a",
            "android.app.action.DEVICE_ADMIN_ENABLED",
            package_name,
        ],
    )?;
    if query.success {
        extract_component_candidates(package_name, &query.stdout, &mut candidates);
    }

    let dump = run_adb(adb, ["shell", "dumpsys", "package", package_name])?;
    if dump.success {
        extract_device_admin_candidates(package_name, &dump.stdout, &mut candidates);
    }

    Ok(candidates)
}

fn extract_device_admin_candidates(package_name: &str, text: &str, candidates: &mut Vec<String>) {
    let lines: Vec<&str> = text.lines().collect();
    for (index, line) in lines.iter().enumerate() {
        if line.contains("android.app.action.DEVICE_ADMIN_ENABLED")
            || line.contains("android.permission.BIND_DEVICE_ADMIN")
            || line.contains("android.app.device_admin")
        {
            let start = index.saturating_sub(6);
            let end = (index + 7).min(lines.len());
            extract_component_candidates(package_name, &lines[start..end].join("\n"), candidates);
        }
    }
}

fn extract_component_candidates(package_name: &str, text: &str, candidates: &mut Vec<String>) {
    for raw_token in text.split_whitespace() {
        let token = raw_token.trim_matches(|ch: char| {
            matches!(
                ch,
                '{' | '}' | '[' | ']' | '(' | ')' | ',' | ';' | ':' | '"' | '\''
            )
        });

        if let Some(component) = normalize_component_candidate(package_name, token) {
            push_unique(candidates, component);
        }
    }
}

fn normalize_component_candidate(package_name: &str, token: &str) -> Option<String> {
    if let Some(receiver_name) = token.strip_prefix(&format!("{package_name}/")) {
        if is_safe_component_class_name(receiver_name) {
            return Some(format!("{package_name}/{receiver_name}"));
        }
    }

    if token.starts_with(&format!("{package_name}."))
        && token.len() > package_name.len() + 1
        && is_safe_component_class_name(token)
    {
        return Some(format!("{package_name}/{token}"));
    }

    None
}

fn is_safe_component_class_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'$'))
}

fn push_unique(values: &mut Vec<String>, value: String) {
    if !values.iter().any(|existing| existing == &value) {
        values.push(value);
    }
}

fn requested_permissions(adb: &AdbTarget, package_name: &str) -> Result<Vec<String>> {
    let output = run_adb_checked(adb, ["shell", "dumpsys", "package", package_name])?;
    let text = output.stdout;
    let mut in_requested = false;
    let mut permissions = Vec::new();

    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed == "requested permissions:" {
            in_requested = true;
            continue;
        }
        if in_requested && trimmed == "install permissions:" {
            break;
        }
        if in_requested {
            let permission = trimmed.strip_suffix(": restricted=true").unwrap_or(trimmed);
            if permission.contains(".permission.") && !permissions.iter().any(|p| p == permission) {
                permissions.push(permission.to_string());
            }
        }
    }

    Ok(permissions)
}

fn run_adb<I, S>(adb: &AdbTarget, args: I) -> Result<CommandOutput>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    transport::run_adb(adb, args)
}

fn run_adb_checked<I, S>(adb: &AdbTarget, args: I) -> Result<CommandOutput>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    let output = run_adb(adb, args)?;
    if output.success {
        Ok(output)
    } else {
        Err(InstallerError::Message(format!(
            "Команда завершилась с ошибкой: {}\n{}{}",
            output.command, output.stdout, output.stderr
        )))
    }
}

fn run_adb_optional<I, S>(adb: &AdbTarget, args: I, log: &mut WorkLog)
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    match run_adb(adb, args) {
        Ok(output) if output.success => {}
        Ok(output) => log.warn(format!(
            "Необязательная команда завершилась с ошибкой: {}\n{}",
            output.command, output.stderr
        )),
        Err(err) => log.warn(format!("Необязательная команда не выполнена: {err}")),
    }
}

fn list_packages(adb: &AdbTarget) -> Result<Vec<String>> {
    let output = run_adb_checked(adb, ["shell", "pm", "list", "packages"])?;
    let text = output.stdout;
    Ok(text
        .lines()
        .filter_map(|line| line.strip_prefix("package:").map(ToOwned::to_owned))
        .collect())
}

fn is_package_installed(adb: &AdbTarget, package_name: &str) -> Result<bool> {
    let output = run_adb_checked(adb, ["shell", "pm", "list", "packages", package_name])?;
    Ok(output
        .stdout
        .lines()
        .any(|line| line.trim() == format!("package:{package_name}")))
}

fn is_safe_package_name(package_name: &str) -> bool {
    !package_name.is_empty()
        && package_name.contains('.')
        && package_name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_'))
}

fn collect_install_diagnostics(adb: &AdbTarget, marker: &str) -> String {
    let mut sections = Vec::new();
    for (label, args) in [
        ("Модель ГУ", vec!["shell", "getprop", "ro.product.model"]),
        (
            "Android",
            vec!["shell", "getprop", "ro.build.version.release"],
        ),
        ("SDK", vec!["shell", "getprop", "ro.build.version.sdk"]),
        (
            "Архитектуры",
            vec!["shell", "getprop", "ro.product.cpu.abilist"],
        ),
        ("Свободное место /data", vec!["shell", "df", "-k", "/data"]),
    ] {
        let text = match run_adb(adb, args) {
            Ok(output) => format!("{}{}", output.stdout, output.stderr),
            Err(error) => format!("Не удалось прочитать: {error}"),
        };
        sections.push(format!("{label}: {}", text.trim()));
    }
    match run_adb(
        adb,
        [
            "shell",
            "logcat",
            "-b",
            "all",
            "-d",
            "-t",
            "2000",
            "-v",
            "threadtime",
        ],
    ) {
        Ok(output) => {
            let current = output
                .stdout
                .rsplit_once(marker)
                .map(|(_, current)| current);
            let source = current.unwrap_or(&output.stdout);
            let relevant: Vec<&str> = source
                .lines()
                .filter(|line| {
                    [
                        "PackageManager",
                        "PackageInstaller",
                        "LocalInstall",
                        "installd",
                        "dex2oat",
                        "INSTALL_FAILED",
                        "Exception",
                        "avc:",
                    ]
                    .iter()
                    .any(|tag| line.contains(tag))
                })
                .collect();
            let start = relevant.len().saturating_sub(80);
            sections.push(format!(
                "Системный журнал ({}):\n{}\n{}",
                if current.is_some() { "текущая попытка" } else { "маркер попытки недоступен; последние сообщения могут относиться к прошлым операциям" },
                if relevant.is_empty() { "Android не записал подходящих сообщений в доступный журнал.".to_string() } else { relevant[start..].join("\n") },
                output.stderr.trim()
            ));
        }
        Err(error) => sections.push(format!("Не удалось прочитать logcat: {error}")),
    }
    format!("Диагностика ГУ:\n{}", sections.join("\n"))
}

/// Читает package из бинарного AndroidManifest.xml внутри APK: aapt в системе может
/// не быть, а у собранного приложения PATH урезан до системного.
fn apk_package_name(apk_path: &Path) -> Result<String> {
    let mut archive = ZipArchive::new(fs::File::open(apk_path)?)?;
    let mut data = Vec::new();
    archive
        .by_name("AndroidManifest.xml")?
        .read_to_end(&mut data)?;

    if le_u16(&data, 0) != AXML_FILE {
        return Err(InstallerError::Message(
            "AndroidManifest.xml не в бинарном формате".to_string(),
        ));
    }

    let mut strings: Vec<String> = Vec::new();
    let mut offset = le_u16(&data, 2);
    while offset + 8 <= data.len() {
        let chunk_type = le_u16(&data, offset);
        let chunk_size = le_u32(&data, offset + 4);
        if chunk_size < 8 || offset + chunk_size > data.len() {
            break;
        }

        if chunk_type == AXML_STRING_POOL {
            strings = axml_strings(&data, offset);
        } else if chunk_type == AXML_START_ELEMENT
            && strings.get(le_u32(&data, offset + 20)).map(String::as_str) == Some("manifest")
        {
            let attributes = offset + 16 + le_u16(&data, offset + 24);
            let attribute_size = le_u16(&data, offset + 26);
            for index in 0..le_u16(&data, offset + 28) {
                let field = attributes + index * attribute_size;
                if strings.get(le_u32(&data, field + 4)).map(String::as_str) == Some("package") {
                    // Значение атрибута лежит либо в raw-строке, либо в типизированном поле.
                    let raw = le_u32(&data, field + 8);
                    let value = if raw == 0xFFFF_FFFF {
                        le_u32(&data, field + 16)
                    } else {
                        raw
                    };
                    return strings.get(value).cloned().ok_or_else(|| {
                        InstallerError::Message("package в манифесте пуст".to_string())
                    });
                }
            }
            break;
        }

        offset += chunk_size;
    }

    Err(InstallerError::Message(
        "не нашёл package в AndroidManifest.xml".to_string(),
    ))
}

/// Разбирает пул строк AXML: строки лежат в UTF-8 или UTF-16 с длиной переменного размера.
fn axml_strings(data: &[u8], offset: usize) -> Vec<String> {
    let count = le_u32(data, offset + 8);
    let utf8 = le_u32(data, offset + 16) & 0x100 != 0;
    let strings_start = offset + le_u32(data, offset + 20);
    let offsets = offset + le_u16(data, offset + 2);

    (0..count)
        .map(|index| {
            let mut entry = strings_start + le_u32(data, offsets + index * 4);
            if utf8 {
                entry += if data[entry] & 0x80 != 0 { 2 } else { 1 };
                let length = if data[entry] & 0x80 != 0 {
                    let long = ((data[entry] as usize & 0x7F) << 8) | data[entry + 1] as usize;
                    entry += 2;
                    long
                } else {
                    let short = data[entry] as usize;
                    entry += 1;
                    short
                };
                String::from_utf8_lossy(&data[entry..entry + length]).into_owned()
            } else {
                let length = le_u16(data, entry) & 0x7FFF;
                entry += 2;
                let units: Vec<u16> = (0..length)
                    .map(|unit| le_u16(data, entry + unit * 2) as u16)
                    .collect();
                String::from_utf16_lossy(&units)
            }
        })
        .collect()
}

fn le_u16(data: &[u8], offset: usize) -> usize {
    u16::from_le_bytes([data[offset], data[offset + 1]]) as usize
}

fn le_u32(data: &[u8], offset: usize) -> usize {
    u32::from_le_bytes([
        data[offset],
        data[offset + 1],
        data[offset + 2],
        data[offset + 3],
    ]) as usize
}

pub(crate) fn adb_shell_prop(adb: &AdbTarget, prop: &str) -> Result<String> {
    let output = run_adb_checked(adb, ["shell", "getprop", prop])?;
    Ok(output.stdout.trim().to_string())
}

/// Результат команды adb: на компьютере — процесса, на телефоне — сервиса ADB по USB.
struct CommandOutput {
    success: bool,
    code: Option<i32>,
    stdout: String,
    stderr: String,
    command: String,
}

fn append_output(log: &mut WorkLog, output: CommandOutput) {
    for line in output.stdout.lines().filter(|line| !line.trim().is_empty()) {
        log.info(line.to_string());
    }
    for line in output.stderr.lines().filter(|line| !line.trim().is_empty()) {
        log.warn(line.to_string());
    }
}

fn path_arg(path: &Path) -> String {
    path.display().to_string()
}
