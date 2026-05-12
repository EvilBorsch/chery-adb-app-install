use serde::Serialize;
use std::{
    env,
    ffi::{OsStr, OsString},
    fs,
    io::{self, Cursor, Read},
    path::{Path, PathBuf},
    process::{Command, Stdio},
};
use tauri::{AppHandle, Manager};
use thiserror::Error;
use zip::ZipArchive;

#[derive(Debug, Error)]
enum InstallerError {
    #[error("{0}")]
    Message(String),
    #[error(transparent)]
    Io(#[from] io::Error),
    #[error(transparent)]
    Zip(#[from] zip::result::ZipError),
    #[error(transparent)]
    Http(#[from] ureq::Error),
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

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            check_device,
            install_dependencies,
            install_apk,
            grant_permissions,
            list_uninstallable_packages,
            uninstall_package
        ])
        .run(tauri::generate_context!())
        .expect("failed to run tauri app");
}

#[tauri::command]
fn check_device(app: AppHandle) -> Result<DeviceInfo> {
    let adb = find_adb(&app)?;
    let state = run_output(&adb, ["get-state"])?;
    if !state.status.success() {
        return Ok(DeviceInfo {
            connected: false,
            adb_path: Some(adb.display().to_string()),
            serial: None,
            model: None,
            android: None,
        });
    }

    let serial = run_output(&adb, ["get-serialno"])?
        .stdout
        .trim()
        .to_string();
    let model = adb_shell_prop(&adb, "ro.product.model").ok();
    let android = adb_shell_prop(&adb, "ro.build.version.release").ok();

    Ok(DeviceInfo {
        connected: true,
        adb_path: Some(adb.display().to_string()),
        serial: non_empty(serial),
        model,
        android,
    })
}

#[tauri::command]
fn install_dependencies(app: AppHandle) -> Result<Vec<Step>> {
    let mut log = WorkLog::new();
    let tools_dir = tools_dir(&app)?;
    let adb_path = adb_path_in_tools(&tools_dir);

    if adb_path.exists() {
        log.info(format!("ADB уже установлен: {}", adb_path.display()));
        return Ok(log.steps);
    }

    fs::create_dir_all(&tools_dir)?;
    let url = platform_tools_url()?;
    log.info(format!("Скачиваю Android platform-tools: {url}"));

    let mut response = ureq::get(url).call()?;
    let mut bytes = Vec::new();
    response.body_mut().as_reader().read_to_end(&mut bytes)?;

    log.info("Распаковываю platform-tools");
    let mut archive = ZipArchive::new(Cursor::new(bytes))?;
    archive.extract(&tools_dir)?;

    let adb_path = adb_path_in_tools(&tools_dir);
    if !adb_path.exists() {
        return Err(InstallerError::Message(
            "ADB не найден после распаковки platform-tools".to_string(),
        ));
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = fs::metadata(&adb_path)?.permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&adb_path, permissions)?;
    }

    log.info(format!("ADB готов: {}", adb_path.display()));
    Ok(log.steps)
}

#[tauri::command]
fn install_apk(app: AppHandle, apk_path: String, car_management: bool) -> Result<InstallResult> {
    let apk_path = PathBuf::from(apk_path);
    if !apk_path.is_file() {
        return Err(InstallerError::Message(format!(
            "APK не найден: {}",
            apk_path.display()
        )));
    }

    let mut log = WorkLog::new();
    let adb = find_adb(&app)?;
    ensure_device(&adb)?;
    if car_management {
        apply_vehicle_preinstall(&adb, &mut log);
    }

    let package_before = list_packages(&adb)?;
    let package_hint = detect_package_with_aapt(&apk_path).ok();

    let remote_apk = "/data/local/tmp/desaysv-install-target.apk";
    let remote_helper = "/data/local/tmp/desaysv-localinstall.apk";
    let helper = resource_localinstall(&app)?;

    log.info("Копирую APK на устройство");
    run_checked(&adb, ["push", path_arg(&apk_path).as_str(), remote_apk])?;
    run_checked(&adb, ["shell", "chmod", "644", remote_apk])?;

    log.info("Копирую helper установки");
    run_checked(&adb, ["push", path_arg(&helper).as_str(), remote_helper])?;
    run_checked(&adb, ["shell", "chmod", "644", remote_helper])?;

    log.info("Устанавливаю APK через PackageInstaller.Session");
    let install_cmd = format!(
        "CLASSPATH={remote_helper} /system/bin/app_process /system/bin LocalInstall {remote_apk}"
    );
    let output = run_checked(&adb, ["shell", install_cmd.as_str()])?;
    append_output(&mut log, output);

    std::thread::sleep(std::time::Duration::from_secs(2));

    let package_name = match package_hint {
        Some(package) if is_package_installed(&adb, &package)? => package,
        _ => detect_new_package(&adb, &package_before)?,
    };

    log.info(format!("Пакет установлен: {package_name}"));
    grant_permissions_inner(&adb, &package_name, &mut log)?;
    if car_management {
        apply_vehicle_management_inner(&adb, &package_name, &mut log)?;
        apply_device_owner_inner(&adb, &package_name, &mut log)?;
    }

    run_optional(
        &adb,
        ["shell", "am", "force-stop", package_name.as_str()],
        &mut log,
    );
    run_optional(
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
        &mut log,
    );
    run_optional(
        &adb,
        ["shell", "rm", "-f", remote_apk, remote_helper],
        &mut log,
    );

    Ok(InstallResult {
        package_name,
        steps: log.steps,
    })
}

#[tauri::command]
fn grant_permissions(app: AppHandle, package_name: String) -> Result<Vec<Step>> {
    let adb = find_adb(&app)?;
    ensure_device(&adb)?;
    if !is_package_installed(&adb, &package_name)? {
        return Err(InstallerError::Message(format!(
            "Пакет не установлен: {package_name}"
        )));
    }

    let mut log = WorkLog::new();
    grant_permissions_inner(&adb, &package_name, &mut log)?;
    Ok(log.steps)
}

#[tauri::command]
fn list_uninstallable_packages(app: AppHandle) -> Result<Vec<String>> {
    let adb = find_adb(&app)?;
    ensure_device(&adb)?;

    let output = run_checked(&adb, ["shell", "pm", "list", "packages", "-3"])?;
    let mut packages: Vec<String> = output
        .stdout
        .lines()
        .filter_map(|line| line.trim().strip_prefix("package:").map(str::to_string))
        .filter(|package| is_safe_package_name(package))
        .collect();
    packages.sort();
    Ok(packages)
}

#[tauri::command]
fn uninstall_package(app: AppHandle, package_name: String) -> Result<Vec<Step>> {
    if !is_safe_package_name(&package_name) {
        return Err(InstallerError::Message(format!(
            "Invalid package name: {package_name}"
        )));
    }

    let adb = find_adb(&app)?;
    ensure_device(&adb)?;
    if !is_package_installed(&adb, &package_name)? {
        return Err(InstallerError::Message(format!(
            "Package is not installed: {package_name}"
        )));
    }

    let mut log = WorkLog::new();
    log.info(format!("Uninstalling package: {package_name}"));
    let output = run_checked(&adb, ["shell", "pm", "uninstall", package_name.as_str()])?;
    append_output(&mut log, output);
    log.info(format!("Uninstalled: {package_name}"));
    Ok(log.steps)
}

fn grant_permissions_inner(adb: &Path, package_name: &str, log: &mut WorkLog) -> Result<()> {
    log.info("Выдаю runtime permissions из manifest");
    for permission in requested_permissions(adb, package_name)? {
        let output = run_output(
            adb,
            ["shell", "pm", "grant", package_name, permission.as_str()],
        )?;
        if output.status.success() {
            log.info(format!("permission: {permission}"));
        }
    }

    log.info("Выставляю appops");
    for op in [
        "MANAGE_EXTERNAL_STORAGE",
        "LEGACY_STORAGE",
        "SYSTEM_ALERT_WINDOW",
        "WRITE_SETTINGS",
        "REQUEST_INSTALL_PACKAGES",
        "SCHEDULE_EXACT_ALARM",
        "ACCESS_NOTIFICATIONS",
    ] {
        let output = run_output(adb, ["shell", "appops", "set", package_name, op, "allow"])?;
        if output.status.success() {
            log.info(format!("appop: {op}=allow"));
        }
    }

    Ok(())
}

fn apply_vehicle_preinstall(adb: &Path, log: &mut WorkLog) {
    log.info("Applying vehicle integration pre-install flag");
    run_optional(adb, ["shell", "setprop", "persist.sys.sv.isl", "true"], log);
}

fn apply_vehicle_management_inner(adb: &Path, package_name: &str, log: &mut WorkLog) -> Result<()> {
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
        run_optional(adb, ["shell", "pm", "grant", package_name, permission], log);
    }

    for op in [
        "SYSTEM_ALERT_WINDOW",
        "REQUEST_INSTALL_PACKAGES",
        "MANAGE_EXTERNAL_STORAGE",
        "ACTIVATE_VPN",
        "android:write_settings",
    ] {
        run_optional(
            adb,
            ["shell", "appops", "set", package_name, op, "allow"],
            log,
        );
    }

    for component in notification_listener_candidates(adb, package_name)? {
        run_optional(
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
    run_optional(
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

fn notification_listener_candidates(adb: &Path, package_name: &str) -> Result<Vec<String>> {
    let mut candidates = Vec::new();
    let query = run_output(
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
    if query.status.success() {
        extract_component_candidates(package_name, &query.stdout, &mut candidates);
    }

    let dump = run_output(adb, ["shell", "dumpsys", "package", package_name])?;
    if dump.status.success() {
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

fn apply_device_owner_inner(adb: &Path, package_name: &str, log: &mut WorkLog) -> Result<()> {
    log.info("Applying Device Owner mode");
    log.info("Clearing Bluetooth package before dpm set-device-owner");
    run_optional(adb, ["shell", "pm", "clear", "com.android.bluetooth"], log);

    let candidates = device_admin_candidates(adb, package_name)?;
    if candidates.is_empty() {
        log.warn(format!(
            "No DeviceAdmin receiver found for package: {package_name}"
        ));
        return Ok(());
    }

    for component in candidates {
        log.info(format!("Trying Device Owner receiver: {component}"));
        let output = run_output(
            adb,
            ["shell", "dpm", "set-device-owner", component.as_str()],
        )?;
        append_output(log, output);
        let verify = run_output(adb, ["shell", "dpm", "get-device-owner"])?;
        if verify.status.success() && verify.stdout.contains(package_name) {
            log.info(format!("Device Owner enabled: {component}"));
            return Ok(());
        }
    }

    log.warn(
        "Device Owner was not enabled. Android may already be provisioned or have another owner.",
    );
    Ok(())
}

fn device_admin_candidates(adb: &Path, package_name: &str) -> Result<Vec<String>> {
    let mut candidates = Vec::new();

    let query = run_output(
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
    if query.status.success() {
        extract_component_candidates(package_name, &query.stdout, &mut candidates);
    }

    let dump = run_output(adb, ["shell", "dumpsys", "package", package_name])?;
    if dump.status.success() {
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

fn requested_permissions(adb: &Path, package_name: &str) -> Result<Vec<String>> {
    let output = run_checked(adb, ["shell", "dumpsys", "package", package_name])?;
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

fn find_adb(app: &AppHandle) -> Result<PathBuf> {
    let bundled = adb_path_in_tools(&tools_dir(app)?);
    if bundled.exists() {
        return Ok(bundled);
    }

    find_in_path(adb_exe_name()).ok_or_else(|| {
        InstallerError::Message(
            "ADB не найден. Нажмите «Установить зависимости» или установите Android platform-tools."
                .to_string(),
        )
    })
}

fn ensure_device(adb: &Path) -> Result<()> {
    let output = run_output(adb, ["get-state"])?;
    if output.status.success() && output.stdout.trim() == "device" {
        return Ok(());
    }
    Err(InstallerError::Message(
        "ADB-устройство не подключено или не авторизовано".to_string(),
    ))
}

fn list_packages(adb: &Path) -> Result<Vec<String>> {
    let output = run_checked(adb, ["shell", "pm", "list", "packages"])?;
    let text = output.stdout;
    Ok(text
        .lines()
        .filter_map(|line| line.strip_prefix("package:").map(ToOwned::to_owned))
        .collect())
}

fn is_package_installed(adb: &Path, package_name: &str) -> Result<bool> {
    let output = run_output(adb, ["shell", "pm", "list", "packages", package_name])?;
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

fn detect_new_package(adb: &Path, before: &[String]) -> Result<String> {
    let after = list_packages(adb)?;
    after
        .into_iter()
        .find(|package| !before.contains(package))
        .ok_or_else(|| {
            InstallerError::Message(
                "APK установлен, но имя пакета не удалось определить автоматически".to_string(),
            )
        })
}

fn detect_package_with_aapt(apk_path: &Path) -> Result<String> {
    let aapt = find_in_path("aapt")
        .or_else(|| find_in_path("aapt2"))
        .ok_or_else(|| InstallerError::Message("aapt/aapt2 не найден".to_string()))?;

    let output = if aapt.file_name().and_then(|v| v.to_str()) == Some("aapt2") {
        run_output(&aapt, ["dump", "badging", path_arg(apk_path).as_str()])?
    } else {
        run_output(&aapt, ["dump", "badging", path_arg(apk_path).as_str()])?
    };

    if !output.status.success() {
        return Err(InstallerError::Message(
            "aapt не смог прочитать APK".to_string(),
        ));
    }

    let text = output.stdout;
    let marker = "package: name='";
    let start = text
        .find(marker)
        .ok_or_else(|| InstallerError::Message("package name not found".to_string()))?
        + marker.len();
    let rest = &text[start..];
    let end = rest
        .find('\'')
        .ok_or_else(|| InstallerError::Message("package name not found".to_string()))?;
    Ok(rest[..end].to_string())
}

fn resource_localinstall(app: &AppHandle) -> Result<PathBuf> {
    app.path()
        .resolve(
            "resources/localinstall.apk",
            tauri::path::BaseDirectory::Resource,
        )
        .map_err(|err| InstallerError::Message(format!("Не найден localinstall.apk: {err}")))
}

fn tools_dir(app: &AppHandle) -> Result<PathBuf> {
    let base = app.path().app_data_dir().map_err(|err| {
        InstallerError::Message(format!("Не удалось получить app data dir: {err}"))
    })?;
    Ok(base.join("android-platform-tools"))
}

fn adb_path_in_tools(tools_dir: &Path) -> PathBuf {
    tools_dir.join("platform-tools").join(adb_exe_name())
}

fn adb_exe_name() -> &'static str {
    if cfg!(windows) {
        "adb.exe"
    } else {
        "adb"
    }
}

fn platform_tools_url() -> Result<&'static str> {
    if cfg!(target_os = "macos") {
        Ok("https://dl.google.com/android/repository/platform-tools-latest-darwin.zip")
    } else if cfg!(target_os = "windows") {
        Ok("https://dl.google.com/android/repository/platform-tools-latest-windows.zip")
    } else if cfg!(target_os = "linux") {
        Ok("https://dl.google.com/android/repository/platform-tools-latest-linux.zip")
    } else {
        Err(InstallerError::Message(
            "Эта ОС не поддерживается для auto-install platform-tools".to_string(),
        ))
    }
}

fn find_in_path(binary: &str) -> Option<PathBuf> {
    let paths = env::var_os("PATH")?;
    env::split_paths(&paths)
        .map(|path| path.join(binary))
        .find(|path| path.exists())
}

fn adb_shell_prop(adb: &Path, prop: &str) -> Result<String> {
    let output = run_checked(adb, ["shell", "getprop", prop])?;
    Ok(output.stdout.trim().to_string())
}

struct CommandOutput {
    status: std::process::ExitStatus,
    stdout: String,
    stderr: String,
    command: String,
}

fn run_checked<I, S>(program: &Path, args: I) -> Result<CommandOutput>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    let output = run_output(program, args)?;
    if output.status.success() {
        Ok(output)
    } else {
        Err(InstallerError::Message(format!(
            "Команда завершилась с ошибкой: {}\n{}{}",
            output.command, output.stdout, output.stderr
        )))
    }
}

fn run_output<I, S>(program: &Path, args: I) -> Result<CommandOutput>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    let args: Vec<OsString> = args
        .into_iter()
        .map(|arg| arg.as_ref().to_os_string())
        .collect();
    let command = format!(
        "{} {}",
        program.display(),
        args.iter()
            .map(|arg| arg.to_string_lossy())
            .map(|arg| arg.to_string())
            .collect::<Vec<_>>()
            .join(" ")
    );
    let output = Command::new(program)
        .args(&args)
        .stdin(Stdio::null())
        .output()?;

    Ok(CommandOutput {
        status: output.status,
        stdout: String::from_utf8_lossy(&output.stdout).to_string(),
        stderr: String::from_utf8_lossy(&output.stderr).to_string(),
        command,
    })
}

fn run_optional<I, S>(program: &Path, args: I, log: &mut WorkLog)
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    match run_output(program, args) {
        Ok(output) if output.status.success() => {}
        Ok(output) => log.warn(format!(
            "Необязательная команда завершилась с ошибкой: {}\n{}",
            output.command, output.stderr
        )),
        Err(err) => log.warn(format!("Необязательная команда не выполнена: {err}")),
    }
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

fn non_empty(value: String) -> Option<String> {
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}
