//! ADB на Android-телефоне: телефон подключён к ГУ кабелем через OTG и сам говорит с adbd по
//! USB (Kotlin-плагин `UsbAdbPlugin`). Вшитый adb здесь не годится: без root он не видит USB.
//!
//! Модуль повторяет поведение `adb` на компьютере, чтобы общий код установки и выдачи прав
//! работал на телефоне без изменений.

use super::{adb_shell_prop, CommandOutput, DeviceInfo, InstallerError, PickedApk, Result};
use serde::{Deserialize, Serialize};
use std::{ffi::OsStr, fs, path::PathBuf};
use tauri::{
    plugin::{Builder, PluginHandle, TauriPlugin},
    AppHandle, Manager, Wry,
};

const HELPER_APK: &[u8] = include_bytes!("../resources/localinstall.apk");

struct UsbAdb(PluginHandle<Wry>);

pub(crate) fn plugin() -> TauriPlugin<Wry> {
    Builder::new("usb-adb")
        .setup(|app, api| {
            let handle =
                api.register_android_plugin("local.desaysv.apk.installer", "UsbAdbPlugin")?;
            app.manage(UsbAdb(handle));
            Ok(())
        })
        .build()
}

pub(crate) struct AdbTarget {
    handle: PluginHandle<Wry>,
    pub(crate) serial: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ConnectResponse {
    connected: bool,
    transport: Option<String>,
    serial: Option<String>,
    model: Option<String>,
    message: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ShellRequest<'a> {
    command: &'a str,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ShellResponse {
    exit_code: i32,
    stdout: String,
    stderr: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PushRequest<'a> {
    local_path: &'a str,
    remote_path: &'a str,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PickResponse {
    cancelled: bool,
    path: Option<String>,
    name: Option<String>,
    size: Option<u64>,
}

fn plugin_handle(app: &AppHandle) -> Result<PluginHandle<Wry>> {
    app.try_state::<UsbAdb>()
        .map(|state| state.0.clone())
        .ok_or_else(|| InstallerError::Message("USB-модуль ADB не загружен".to_string()))
}

fn plugin_error(error: impl std::fmt::Display) -> InstallerError {
    InstallerError::Message(error.to_string())
}

fn open_connection(app: &AppHandle) -> Result<(PluginHandle<Wry>, ConnectResponse)> {
    let handle = plugin_handle(app)?;
    let response: ConnectResponse = handle
        .run_mobile_plugin("connect", ())
        .map_err(plugin_error)?;
    Ok((handle, response))
}

pub(crate) fn check_device(app: &AppHandle) -> Result<DeviceInfo> {
    let (handle, response) = open_connection(app)?;
    if !response.connected {
        return Ok(DeviceInfo {
            connected: false,
            adb_path: None,
            serial: None,
            model: None,
            android: None,
            message: response.message,
        });
    }

    let adb = AdbTarget {
        handle,
        serial: response.serial.unwrap_or_default(),
    };
    let model = adb_shell_prop(&adb, "ro.product.model")
        .ok()
        .filter(|model| !model.is_empty())
        .or(response.model);
    let android = adb_shell_prop(&adb, "ro.build.version.release").ok();
    Ok(DeviceInfo {
        connected: true,
        adb_path: response.transport,
        serial: Some(adb.serial),
        model,
        android,
        message: None,
    })
}

pub(crate) fn connect(app: &AppHandle) -> Result<AdbTarget> {
    let (handle, response) = open_connection(app)?;
    if !response.connected {
        return Err(InstallerError::Message(response.message.unwrap_or_else(|| {
            "ГУ не подключено по USB".to_string()
        })));
    }
    Ok(AdbTarget {
        handle,
        serial: response.serial.unwrap_or_default(),
    })
}

/// helper лежит внутри Rust-библиотеки: из ресурсов APK его не прочитать как обычный файл,
/// а отправка на ГУ идёт с пути на диске, как и у ПК-версии.
pub(crate) fn localinstall_helper(app: &AppHandle) -> Result<PathBuf> {
    let directory = app
        .path()
        .app_cache_dir()
        .map_err(|err| InstallerError::Message(format!("Не удалось получить cache dir: {err}")))?;
    fs::create_dir_all(&directory)?;
    let path = directory.join("localinstall.apk");
    if fs::read(&path).ok().as_deref() != Some(HELPER_APK) {
        fs::write(&path, HELPER_APK)?;
    }
    Ok(path)
}

pub(crate) fn pick_apk(app: &AppHandle) -> Result<Option<PickedApk>> {
    let response: PickResponse = plugin_handle(app)?
        .run_mobile_plugin("pickApk", ())
        .map_err(plugin_error)?;
    if response.cancelled {
        return Ok(None);
    }
    let path = response
        .path
        .ok_or_else(|| InstallerError::Message("Android не вернул путь к APK".to_string()))?;
    Ok(Some(PickedApk {
        name: response.name.unwrap_or_else(|| path.clone()),
        size: response.size.unwrap_or_default(),
        path,
    }))
}

/// Аналог запуска `adb -s <serial> <args>` для двух команд, которые использует установщик:
/// `shell` и `push`.
pub(crate) fn run_adb<I, S>(adb: &AdbTarget, args: I) -> Result<CommandOutput>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    let args: Vec<String> = args
        .into_iter()
        .map(|arg| arg.as_ref().to_string_lossy().into_owned())
        .collect();
    let command = format!("adb {}", args.join(" "));

    match args.first().map(String::as_str) {
        Some("shell") => {
            // adb на компьютере склеивает аргументы shell через пробел без экранирования и
            // отдаёт строку sh на ГУ — делаем ровно так же, чтобы команды вели себя одинаково.
            let line = args[1..].join(" ");
            let response: ShellResponse = adb
                .handle
                .run_mobile_plugin("shell", ShellRequest { command: &line })
                .map_err(|error| {
                    InstallerError::Message(format!("Связь с ГУ прервалась: {command}\n{error}"))
                })?;
            Ok(CommandOutput {
                success: response.exit_code == 0,
                code: Some(response.exit_code),
                stdout: response.stdout,
                stderr: response.stderr,
                command,
            })
        }
        Some("push") if args.len() == 3 => {
            let request = PushRequest {
                local_path: &args[1],
                remote_path: &args[2],
            };
            // Как у adb push: ошибка копирования — это неуспешный код возврата и текст в stderr.
            match adb
                .handle
                .run_mobile_plugin::<serde::de::IgnoredAny>("push", request)
            {
                Ok(_) => Ok(CommandOutput {
                    success: true,
                    code: Some(0),
                    stdout: format!("{}: 1 file pushed.", args[1]),
                    stderr: String::new(),
                    command,
                }),
                Err(error) => Ok(CommandOutput {
                    success: false,
                    code: Some(1),
                    stdout: String::new(),
                    stderr: error.to_string(),
                    command,
                }),
            }
        }
        _ => Err(InstallerError::Message(format!(
            "Команда не поддерживается в Android-версии: {command}"
        ))),
    }
}
