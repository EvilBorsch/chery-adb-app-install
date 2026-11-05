//! ADB на компьютере: вшитый `adb` распаковывается в app data dir и запускается процессом.

use super::{adb_shell_prop, CommandOutput, DeviceInfo, InstallerError, PickedApk, Result};
use std::{
    ffi::{OsStr, OsString},
    fs,
    io::Cursor,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};
use tauri::{AppHandle, Manager};
use zip::ZipArchive;

// adb вшит в бинарь: приложение должно работать сразу, без скачивания platform-tools.
// Версия входит в путь распаковки, чтобы обновление приложения не оставляло старый adb.
const ADB_VERSION: &str = "37.0.1";

#[cfg(target_os = "macos")]
const ADB_BUNDLE: &[u8] = include_bytes!("../binaries/adb-macos.zip");
#[cfg(target_os = "windows")]
const ADB_BUNDLE: &[u8] = include_bytes!("../binaries/adb-windows.zip");
#[cfg(target_os = "linux")]
const ADB_BUNDLE: &[u8] = include_bytes!("../binaries/adb-linux.zip");

pub(crate) struct AdbTarget {
    path: PathBuf,
    pub(crate) serial: String,
}

#[derive(Clone, Debug)]
struct ListedDevice {
    serial: String,
    state: String,
    model: Option<String>,
    product: Option<String>,
    usb: bool,
}

pub(crate) fn check_device(app: &AppHandle) -> Result<DeviceInfo> {
    let adb_path = ensure_adb(app)?;
    match resolve_head_unit(&adb_path) {
        Ok(adb) => {
            let model = adb_shell_prop(&adb, "ro.product.model").ok();
            let android = adb_shell_prop(&adb, "ro.build.version.release").ok();
            Ok(DeviceInfo {
                connected: true,
                adb_path: Some(adb.path.display().to_string()),
                serial: Some(adb.serial),
                model,
                android,
                message: None,
            })
        }
        Err(_) => Ok(DeviceInfo {
            connected: false,
            adb_path: Some(adb_path.display().to_string()),
            serial: None,
            model: None,
            android: None,
            message: None,
        }),
    }
}

pub(crate) fn connect(app: &AppHandle) -> Result<AdbTarget> {
    let adb_path = ensure_adb(app)?;
    resolve_head_unit(&adb_path)
}

pub(crate) fn localinstall_helper(app: &AppHandle) -> Result<PathBuf> {
    app.path()
        .resolve(
            "resources/localinstall.apk",
            tauri::path::BaseDirectory::Resource,
        )
        .map_err(|err| InstallerError::Message(format!("Не найден localinstall.apk: {err}")))
}

/// На компьютере APK выбирается диалогом или перетаскиванием прямо из интерфейса.
pub(crate) fn pick_apk(_app: &AppHandle) -> Result<Option<PickedApk>> {
    Err(InstallerError::Message(
        "Выбор APK через Android доступен только в Android-версии".to_string(),
    ))
}

pub(crate) fn run_adb<I, S>(adb: &AdbTarget, args: I) -> Result<CommandOutput>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    let mut full_args: Vec<OsString> = vec!["-s".into(), adb.serial.as_str().into()];
    full_args.extend(args.into_iter().map(|arg| arg.as_ref().to_os_string()));
    run_output(&adb.path, full_args)
}

/// Кладёт вшитый adb на диск: запустить его из константы нельзя, а на Windows рядом с adb.exe
/// должны лежать его DLL, поэтому распаковываем весь архив в app data dir.
fn ensure_adb(app: &AppHandle) -> Result<PathBuf> {
    let base = app_data_dir(app)?;
    let tools_dir = base.join("adb").join(ADB_VERSION);
    let adb_path = tools_dir.join(adb_exe_name());
    if adb_path.is_file() {
        return Ok(adb_path);
    }

    // Версии до 1.2 качали platform-tools целиком, чистим за ними ~50 МБ.
    let downloaded_tools = base.join("android-platform-tools");
    if downloaded_tools.exists() {
        fs::remove_dir_all(&downloaded_tools)?;
    }

    // Распаковываем в отдельный каталог и переносим целиком: прерванная распаковка не должна
    // оставить нерабочий adb, который на следующем запуске сочтут готовым.
    let staging = base.join("adb").join(format!("{ADB_VERSION}.unpacking"));
    if staging.exists() {
        fs::remove_dir_all(&staging)?;
    }
    fs::create_dir_all(&staging)?;
    ZipArchive::new(Cursor::new(ADB_BUNDLE))?.extract(&staging)?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let staged_adb = staging.join(adb_exe_name());
        let mut permissions = fs::metadata(&staged_adb)?.permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&staged_adb, permissions)?;
    }

    if tools_dir.exists() {
        fs::remove_dir_all(&tools_dir)?;
    }
    fs::rename(&staging, &tools_dir)?;

    Ok(adb_path)
}

fn resolve_head_unit(adb_path: &Path) -> Result<AdbTarget> {
    let devices = list_adb_devices(adb_path)?;
    let online: Vec<&ListedDevice> = devices
        .iter()
        .filter(|device| device.state == "device")
        .collect();

    if online.is_empty() {
        return Err(InstallerError::Message(
            "ADB-устройство не подключено или не авторизовано".to_string(),
        ));
    }

    let selected = select_head_unit(&online).ok_or_else(|| {
        InstallerError::Message("ГУ не найдено среди подключенных ADB-устройств".to_string())
    })?;

    Ok(AdbTarget {
        path: adb_path.to_path_buf(),
        serial: selected.serial.clone(),
    })
}

fn list_adb_devices(adb_path: &Path) -> Result<Vec<ListedDevice>> {
    let output = run_output(adb_path, ["devices", "-l"])?;
    if !output.success {
        return Err(InstallerError::Message(format!(
            "Не удалось получить список ADB-устройств: {}{}",
            output.stdout, output.stderr
        )));
    }

    let mut devices = Vec::new();
    for line in output.stdout.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with("List of devices") {
            continue;
        }

        let mut parts = line.split_whitespace();
        let Some(serial) = parts.next() else {
            continue;
        };
        let Some(state) = parts.next() else {
            continue;
        };

        let mut model = None;
        let mut product = None;
        let mut usb = false;
        for part in parts {
            if let Some(value) = part.strip_prefix("model:") {
                model = Some(value.replace('_', " "));
            } else if let Some(value) = part.strip_prefix("product:") {
                product = Some(value.to_string());
            } else if part.starts_with("usb:") {
                usb = true;
            }
        }

        // USB serials are hex ids like bd6bf956; network targets look like host:port.
        if !usb && !serial.contains(':') {
            usb = true;
        }

        devices.push(ListedDevice {
            serial: serial.to_string(),
            state: state.to_string(),
            model,
            product,
            usb,
        });
    }

    Ok(devices)
}

fn select_head_unit<'a>(devices: &[&'a ListedDevice]) -> Option<&'a ListedDevice> {
    // Prefer DesaySV / Chery head units over leftover network/watch ADB sessions.
    devices
        .iter()
        .copied()
        .filter(|device| device.usb || is_truncated_hex_serial(&device.serial))
        .max_by_key(|device| head_unit_score(device))
        .or_else(|| {
            devices
                .iter()
                .copied()
                .filter(|device| is_truncated_hex_serial(&device.serial))
                .max_by_key(|device| head_unit_score(device))
        })
        .or_else(|| {
            devices
                .iter()
                .copied()
                .max_by_key(|device| head_unit_score(device))
        })
}

fn head_unit_score(device: &ListedDevice) -> i32 {
    let mut score = 0;
    let model = device.model.as_deref().unwrap_or("").to_ascii_lowercase();
    let product = device.product.as_deref().unwrap_or("").to_ascii_lowercase();

    if model.contains("desaysv") || product.contains("desay") {
        score += 100;
    }
    if product.contains("g7ph") || product.contains("t22") {
        score += 40;
    }
    if device.usb {
        score += 30;
    }
    if is_truncated_hex_serial(&device.serial) {
        score += 20;
    }
    if device.serial.contains(':') {
        score -= 50;
    }
    score
}

fn is_truncated_hex_serial(serial: &str) -> bool {
    let len = serial.len();
    (6..=16).contains(&len)
        && !serial.contains(':')
        && serial.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn app_data_dir(app: &AppHandle) -> Result<PathBuf> {
    app.path()
        .app_data_dir()
        .map_err(|err| InstallerError::Message(format!("Не удалось получить app data dir: {err}")))
}

fn adb_exe_name() -> &'static str {
    if cfg!(windows) {
        "adb.exe"
    } else {
        "adb"
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
        success: output.status.success(),
        code: output.status.code(),
        stdout: String::from_utf8_lossy(&output.stdout).to_string(),
        stderr: String::from_utf8_lossy(&output.stderr).to_string(),
        command,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Вшитый архив собирается вручную из platform-tools, поэтому проверяем, что в нём лежит
    /// ровно то, что ищет ensure_adb, включая DLL, без которых adb.exe не стартует.
    #[test]
    fn adb_bundle_contains_runtime_files() {
        let archive = ZipArchive::new(Cursor::new(ADB_BUNDLE)).expect("вшитый архив adb читается");
        let names: Vec<&str> = archive.file_names().collect();

        let mut expected = vec![adb_exe_name()];
        if cfg!(windows) {
            expected.extend(["AdbWinApi.dll", "AdbWinUsbApi.dll"]);
        }

        for file in expected {
            assert!(names.contains(&file), "в архиве нет {file}: {names:?}");
        }
    }
}
